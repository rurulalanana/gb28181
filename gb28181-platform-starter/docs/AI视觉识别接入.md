# AI 视觉识别接入说明

人形 / 车辆等目标检测，接在 ZLM 输出流上，与国标链路并列、互不影响。

## 1. 结构

```
models/                                  ← 模型文件（独立于代码，可单独替换）
  MODELS.md                              ← 元数据说明（本文件在仓库根 models/）
  yolox_nano.onnx                        ← YOLOX-nano，3.5 MB，Apache-2.0

vision-api/                              ← 契约层（零依赖，纯 JDK）
  Frame / Detection / Box / VisionDetector / DetectionSink / VisionEvent
vision-onnx/                             ← ONNX Runtime 实现（只依赖 vision-api + onnxruntime）
vision-pipeline/                         ← 流水线：帧泵 → 推理 → 去抖聚合 → 出口（只依赖 vision-api）

gb28181-platform-starter/.../vision/
  VisionModelRegistry.java               ← 模型注册表：配置 → 检测器（懒加载、失败降级）
  ZlmFrameSource.java                    ← 取帧：常驻 ffmpeg 拉 HTTP-TS，按 fps 出 JPEG
  DbDetectionSink.java                   ← 结果出口：内存快照 + 事件处理器
  VisionEventRecorder.java               ← 事件处理：存截图 + 写 gb_alarm
  VisionService.java                     ← 编排：哪几路在跑
  web/VisionController.java              ← REST 接口
```

**依赖方向单向**：`starter → vision-pipeline → vision-api`，`starter → vision-onnx → vision-api`。
模型层不认识 GB28181 / ZLM / 数据库。

## 2. 开启方式

### 2.1 配置

`application.yml` 的 `vision` 段（默认关闭）：

```yaml
vision:
  enabled: true                    # ← 打开
  model-dir: models                # 模型目录（相对进程工作目录）
  models:
    - id: yolox-nano
      file: yolox_nano.onnx
      format: YOLOX                # 必须与 preprocess 配对
      input-size: 416
      preprocess: yolox
      labels: []                   # 空 = 内置 COCO 80 类
  default-model: yolox-nano
  channels:                        # 要检测的通道（设备编码/通道编码）
    - "10001/10001-1"
    - "10001/10001-2"
  fps: 1.0
  confidence-threshold: 0.45
  min-event-frames: 3
  min-event-millis: 2000
  event-cooldown-millis: 30000
  raise-alarm: true
  save-snapshot: true
```

### 2.2 部署模型文件

模型**不打进 jar**（体积 + 许可），放在 jar 旁边：

```bash
# 服务器上
cd /opt/gb28181
ls models/yolox_nano.onnx       # 必须存在，否则日志报「模型文件不存在」
```

仓库里的 `models/` 目录随 git 分发（3.5 MB，适合入库）。
部署时 `scp -r models root@host:/opt/gb28181/`。

### 2.3 手动开关某一路

```bash
# 开启
curl -H "Authorization: <token>" -X POST \
  "http://host:48092/api/v1/vision/start?channel=10001/10001-1"

# 关闭
curl -H "Authorization: <token>" -X POST \
  "http://host:48092/api/v1/vision/stop?channel=10001/10001-1"

# 看状态
curl -H "Authorization: <token>" "http://host:48092/api/v1/vision/status"

# 看某路最新检测框（归一化坐标，前端按画面尺寸乘）
curl -H "Authorization: <token>" "http://host:48092/api/v1/vision/detections?channel=10001/10001-1"
```

## 3. 关键设计决策

### 3.1 为什么用「常驻 ffmpeg」而不是复用 `FfmpegSnapService`

`FfmpegSnapService.capture()` 是**单次抓拍**：每次起一个 ffmpeg、解出第一帧就退出，实测约 1 秒。
做成 1 fps 连续检测时，那等于每秒起一个进程 —— 进程创建 + 拉流 + 等关键帧的开销全落在每一帧上。

`ZlmFrameSource` 改成**一个常驻 ffmpeg**：一次拉流，用 `-vf fps=N` 让它自己按频率出图，
从 stdout 读连续的 JPEG 流。进程启动成本只付一次。

### 3.2 为什么必须走管道喂流

实测 `ffmpeg -i http://.../live.flv` 在**活流**上会挂死（不返回帧也不退出）。
所以 Java 侧读 HTTP 流、写进 ffmpeg 的 stdin，ffmpeg 从 `pipe:0` 读 ——
与 `FfmpegSnapService` 相同的做法。

### 3.2.1 为什么是 HTTP-TS 而不是 HTTP-FLV

**这条是踩过坑的结论，别改回去。** 详见 [§5.5](#55-hevc-取帧必须用-http-ts不能用-http-flv已修复别改回去)。

一句话：ZLM 的 H.265 FLV 用传统 `codecId=12`（为了兼容 JS 播放器），
而 ffmpeg 7.0.2 的 flv demuxer 不认它，只会反复报
`Video codec (c) is not implemented` 并**一帧不出**。TS 原生支持 HEVC，同一个流 1 秒出图。

### 3.3 为什么复用 `gb_alarm` 而不新建 `gb_ai_event`

报警中心已有完整的列表 / 分页 / 复位 / 保留期清理，前端也在用。
AI 识别出的「有人闯入」本质上就是一条报警，单独开表会让用户必须在两个页面里
找「到底有没有报警」，两套清理策略迟早不一致。

区分方式：`alarm_method=7`（其他）+ `alarm_type_name` 写标签名，
`alarm_description` 写「AI 识别：person × 2（置信度 0.78，持续 3 秒）」。

### 3.4 为什么「每帧」和「事件」两条路径分开

- `onDetections` 每帧都调（1 fps × N 路 = 每秒 N 次），**只碰内存**：写库会被高频小事务拖垮。
  前端要的「当前画面上的框」本质是最新快照。
- `onEvent` 只在去抖后触发（同通道同标签 30 秒内最多一次），频率低，才适合落库 + 存图 + 告警。

### 3.5 空结果也必须覆盖写

`DbDetectionSink.onDetections` 收到空列表也要覆盖，否则**人走了框还在**。
这条有专门的测试钉住（`emptyDetectionsOverwritePrevious`）。

## 4. 踩过的坑（症状 → 根因）

| 症状 | 根因 | 修法 |
|---|---|---|
| 置信度 `7.879`（>1） | YOLOX 输出是 **logits**，没过 sigmoid | `sigmoid(obj) × sigmoid(classScore)` |
| 所有框塌到左上角、6×3 像素 | YOLOX 输出是**未解码的 head 张量** | `cx=(raw+grid)*stride`、`w=exp(raw)*stride` |
| 分数全挤在 0.31~0.34 | 预处理用错口径（用了 YOLOv8 的 `/255`+RGB+拉伸） | YOLOX 要 **0~255 + BGR + letterbox 填 114** |
| 框越界（`x=1.000 w=1.000`） | 归一化分母用了模型输入尺度，不是原图尺寸 | 用 `origW/origH` |
| 「ffmpeg 明明在出图，但一帧都检测不到」 | `Frame` 的宽高填 0 → `Frame.usable()` 判 false → 整帧被丢弃 | 从 JPEG 的 SOF 段解析真实尺寸（`ZlmFrameSource.jpegSize`） |
| 前端框不消失 | 空检测结果没有覆盖上一次的快照 | `onDetections` 空列表也写 |

**单测只能断言「坐标在 0~1」是不够的**：像素当归一化时框会塌到左上角、
坐标被 clamp 成 1.0，**断言照样通过**。必须用**含目标的真实图片**并断言
「已知有人的图上必须检出 person」（`VisionRealModelIntegrationTest`）。

## 5. 性能实测

8 核 CPU、无 GPU：

| 项 | 数值 |
|---|---|
| 模型加载 | 216 ms（一次性） |
| 单帧推理（416×416） | 60~90 ms（YOLOX-nano） |
| 1 路 1 fps CPU 占用 | 约 6~9% 单核 |
| 8 路 1 fps | 约 50~70% 单核，余量充足 |
| 常驻 ffmpeg（1 路） | 约 3~5% 单核 |

**建议**：`worker-threads` 不超过核数一半，否则会拖慢信令与转发。

## 5.5 HEVC 取帧：必须用 HTTP-TS，不能用 HTTP-FLV（已修复，别改回去）

**曾经的症状**：检测开启了、ffmpeg 进程也在跑，但 `inferredFrames` 永远是 0，日志里没有明显错误。

**根因**：ffmpeg 反复报
```
[flv @ ...] Video codec (c) is not implemented.
```
这里的 `(c)` 是十六进制 `0xc` = **12**，不是字母 c —— 这是最容易误判的一点。
ZLM 用**传统 FLV 扩展格式**封装 H.265（视频 tag 首字节 `0x1C`，即 `codecId=12`），
而 **ffmpeg 7.0.2 的 flv demuxer 只支持 E-RTMP 增强头**（`codecId=0` + FourCC `hvc1`），
不认老的 `codecId=12`。

⚠️ **两个极易误判的地方**：

1. `ffmpeg -decoders | grep hevc` 是**有** hevc 解码器的。缺的是 **demuxer** 对
   `codecId=12` 的识别。所以「装了 hevc 解码器」不解决问题。
2. ffmpeg **能**解自己 mux 出来的 HEVC FLV（那是增强头）。所以拿
   `ffmpeg -f lavfi -i testsrc -c:v libx265 -f flv` 造的样本测，会得出
   「ffmpeg 支持 HEVC FLV」的错误结论。

**为什么不能直接把 ZLM 改成增强头**：`MediaServerProperties.rtmpEnhanced` 默认 `false`
是为了让 JS 播放器（`xgplayer-transmuxer`）能播 —— 绝大多数播放器只认传统 `codecId=12`，
遇到增强头会丢掉整个视频轨（「只有声音没有画面」）。所以**这个开关不能为视觉检测而改**。

### 正确做法：换 MPEG-TS 封装

TS 原生支持 HEVC，且 ZLM 默认就开了 `enable_ts`。同一个流实测：

| 封装 | 结果 |
|---|---|
| HTTP-FLV | 0 字节，`Video codec (c) is not implemented` |
| HTTP-TS | 约 1 秒出 1280x720 JPEG，无错误 |

实现上只需要把 URL 后缀换掉（`ZlmFrameSource.toTsUrl`）：
```
/<app>/<stream>.live.flv  →  /<app>/<stream>.live.ts
```
`VisionService.start()` 里转换，`GbStream` 表结构不用动。

### 万一以后又遇到取帧问题，按这个顺序查

```bash
# 1) 看流是什么编码（0x4028 → 浮点 12.0 → HEVC；0x401C → 7.0 → H.264）
curl -s "http://127.0.0.1:48089/<app>/<stream>.live.flv" | head -c 2000 | xxd | grep -A1 videocodecid

# 2) 直接用 TS 测能否出图（这是当前生产路径）
curl -s --max-time 8 "http://127.0.0.1:48089/<app>/<stream>.live.ts" -o /tmp/f.ts
./bin/ffmpeg -hide_banner -loglevel error -i pipe:0 -frames:v 1 \
    -f image2pipe -vcodec mjpeg -q:v 3 pipe:1 < /tmp/f.ts | wc -c

# 3) 平台侧诊断（healthy=false 时会给出 hint）
curl -H "Authorization: <token>" http://127.0.0.1:48092/api/v1/vision/status
```

**验证成功的唯一标准**：`inferredFrames` **持续增长**。
`runningCount=1` 只能说明 attach 成功了，不代表在出帧。

## 5.6 ⚠️ 空闲流巡检会把检测流当「无人观看」关掉（已修复）

**症状**：检测开起来后正常工作，约 45~60 秒后静默停止，`inferredFrames` 不再增长。

**根因**：`StreamService.reapIdleStreams()` 按 ZLM 的**读者数**判断流是否还有用。
视觉取帧是 ffmpeg 从 HTTP-TS 拉流，这条连接 **不算 ZLM 的读者**，
所以正在被检测的流会被判「无人观看」并关闭，ffmpeg 随之断流。

**修复**：`VisionService.runningChannelIds()` 返回正在检测的通道编码，
`StreamService.channelsRequiringStream()` 把它们并入「常开集合」，巡检直接跳过。

> 用 `ObjectProvider<VisionService>` 延迟解析，而不是直接注入 ——
> `VisionService` 反过来要调 `StreamService.startLive`，直接互注会循环依赖。

## 6. 换模型 / 加模型

1. 把 `.onnx` 放进 `models/`
2. 在 `models/MODELS.md` 加元数据（人工维护的权威说明）
3. 在 `application.yml` 的 `vision.models` 加同样一条
4. 重启，看日志「视觉模型已加载: id=xxx」

**不需要改代码、不需要重新编译**。

选模型时必须确认许可：YOLOX 是 Apache-2.0 可商用；部分 YOLO 权重是 AGPL，商用要买授权。

## 7. 前端叠框

`GET /api/v1/vision/detections?channel=xxx` 返回归一化坐标：

```json
{
  "channel": "10001/10001-1",
  "at": 1730000000000,
  "width": 1280, "height": 720,
  "counts": { "person": 2 },
  "detections": [
    { "label": "person", "confidence": 0.78,
      "box": { "x": 0.17, "y": 0.36, "w": 0.25, "h": 0.58 } }
  ]
}
```

前端按 canvas 实际尺寸乘即可（`x * canvas.width`）。
归一化坐标的好处：主码流/子码流切换导致分辨率变化时，框不会错位。
