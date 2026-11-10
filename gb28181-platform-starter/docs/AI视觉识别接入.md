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

### 5.1 端到端延迟（fps=4，1280×720）

| 指标 | 修复前 | 修复后 | 怎么测的 |
|---|---|---|---|
| 开启检测 → 首个框 | **3691 ms** | **866 ms** | `POST /vision/start` 后轮询 `at` 非 0 |
| 结果陈旧度（中位） | **1553 ms** | **215 ms** | 服务器当前时间 − `detections.at` |
| 结果陈旧度（最坏） | 3465 ms | 604 ms | 同上 |
| 有效推理帧率 | 0.3~0.7 fps | 2.9 fps | 20 秒内 `at` 变化次数 |
| 每帧管道数据量 | 288 KB | 25 KB | 解码后的 JPEG 字节数 |

三段延迟各修一处，详见 §5.7 / §5.8 / §5.9。**别再改回去**，每一处都有实测数字。

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

## 5.7 首帧要等好几秒：ffmpeg 默认攒够 5MB 才出图（已修复）

**症状**：点「AI 检测」后要等 3~4 秒画面里才出现第一个框。

**根因**：ffmpeg 从管道读 TS 时，默认 `probesize=5000000`、`analyzeduration=5000000`
—— 它要**先攒够 5MB 数据才肯开始输出第一帧**。同一路流实测：

| 参数 | 首帧 |
|---|---|
| 默认 | 4001~4498 ms |
| `probesize=100000` | 284~2840 ms（多数 < 500ms） |
| `probesize=32` | **0 帧**（太小，解不出流参数） |

**修复**（`ZlmFrameSource`，都在 `-i` 之前）：
```
-fflags nobuffer -flags low_delay -probesize 100000 -analyzeduration 0
```
`analyzeduration=0` 让它别再做「猜编码」的采样等待 —— TS 的 `stream_type`
已经写明是 H.264/H.265，不需要推断。

**别把 `probesize` 调得更小**：32 字节会直接解不出流参数，表现为 0 帧。

## 5.8 框比画面慢好几秒：队列积压（已修复，最容易误判的一处）

**症状**：框能出来，但比画面慢 1~3 秒，人走过去了框才跟上来。
第一直觉会怀疑模型太慢 —— **不是**，单帧推理只要 60~90 ms。

**根因**：`VisionPipeline.offer()` 原来是「满了 poll 掉一条再 offer」，
看起来像「保留最新帧」，实际上：

> 推理只要比取帧**略慢一点**，队列就长期处于**满**的状态。
> 消费端严格 FIFO，于是永远在处理 `queueCapacity`（4）帧**之前**的画面。
> 积压量固定在那里，**不会自愈**。

实测两路 1280×720、fps=4、queueCapacity=4：第二路结果中位陈旧 **1553 ms**、
最坏 **3465 ms**，而单帧推理只要 60~90 ms —— 延迟几乎全部来自排队。

⚠️ **这解释了一个反直觉现象**：把 `fps` 调高反而更慢。
帧进得越快，积压的绝对量越大。所以「提高帧率」不能解决延迟，必须先修积压。

**修复**：消费端取出帧后**排空积压、只保留最新一帧**，队列深度恒在 0~1，
陈旧度降到 ≈ 单帧推理耗时。

**顺带修的节流 bug**：`-vf fps=N` 的实际出帧间隔会在 990~1010 ms 抖动，
用严格 `now - last < 1000` 判定会把 999 ms 的合法帧当成「太早」丢掉，
下一帧又要再等一整个周期 —— 实测 fps=1 时有效帧率只有 **0.3~0.7**，
丢帧率 **30~68%**。留 10% 容差（`THROTTLE_TOLERANCE`）后能到满速。

## 5.9 每帧 288KB 是纯浪费：在 ffmpeg 里就缩到模型输入尺寸

模型输入只有 416×416，但原来管道里传的是 1280×720 的 JPEG：

| 做法 | 每帧 | 4fps 管道流量 |
|---|---|---|
| 不缩放（`-q:v 3`） | 288 KB | 1.1 MB/s |
| `scale=416:-2 -q:v 5` | **25 KB** | 0.1 MB/s |

Java 侧还要 `ImageIO.read` 解一遍 1280×720、再用 `Graphics2D` 缩到 416 ——
这些全在**推理线程**里做，直接吃掉帧率。缩放在 ffmpeg 里几乎是免费的
（它本来就要解码整帧，顺手 swscale 一次比跨进程搬像素便宜得多）。

**两个必须注意的点**：

1. **必须 `scale=416:-2`，不能 `416:416`**。压成正方形会把 16:9 里的行人
   压成细长竖条（水平压 3.08 倍、垂直只压 1.73 倍），检出率断崖下降 ——
   这正是 `OnnxVisionDetector.preprocess` 注释里警告过的坑。
   `-2` 表示「高度按长宽比算并取偶数」，1280×720 → **416×234**。
2. **先 `fps` 后 `scale`**，顺序不能反。反了会让 ffmpeg 把上游每一帧都缩一遍
   （25fps → 每秒缩 25 次），而正确顺序只缩 4 次。画质等价，CPU 差好几倍。

框坐标是**相对这一帧**归一化的，而缩放是逐轴线性的，所以百分比位置原样
映射回原始画面 —— 前端叠框不需要任何换算。

## 5.10 有些通道的延迟降不下来：上游是突发式推流

**不是所有通道都能降到 200 ms。** 实测第二路（国标球机）：

- 帧**到达**节奏是突发的：14 帧瞬间到达，然后**停顿 2.5~3.85 秒**，再来一批
- TS 里**持续**有 H.264 解码错误：`error while decoding MB 41 32, bytestream -7`
- **落盘后解码仍报错**，所以与「管道丢数据」无关，是设备/传输侧的问题

判定方法（区分「代码慢」和「上游差」）：
```bash
# 把流落盘 15 秒，再离线解码。仍报错 = 上游数据本身就坏了
timeout 17 curl -s --max-time 15 "http://127.0.0.1:48089/rtp/<dev>_<ch>.live.ts" > /tmp/raw.ts
/opt/gb28181/bin/ffmpeg -hide_banner -loglevel error -err_detect ignore_err -i /tmp/raw.ts -an -f null -
```
这类通道的陈旧度会停在 1.5~2 秒，**代码侧无法消除**。要改善只能查设备侧：
码流参数、网络丢包、或改用子码流。

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

### 7.1 实时推送：WebSocket（首选），REST 仅作降级

**首选 `ws://<host>/api/v1/vision/ws?token=xxx`**，不要再轮询 `detections`。

轮询有三个绕不过去的问题，WebSocket 一次解决：

| 问题 | 轮询 | WebSocket |
|---|---|---|
| 延迟下限 | 轮询周期（与帧率无关，4fps 时平均空等 125ms） | 帧产生即推 |
| 周期匹配 | 必须 ≥ 帧间隔，否则同一帧反复取回 | 无需匹配 |
| 16 分屏开销 | 每秒 16 个 HTTP 请求，每个过鉴权+操作日志拦截器 | 一条连接 |

实测（生产，1280×720 一路）：

| 指标 | 轮询 | WebSocket |
|---|---|---|
| DOM 更新间隔 | 1000 ms | **336 ms** |
| 首帧到达 | 250 ms+ | **147 ms** |
| 9 秒内 REST 请求数 | ~9 次 | **0 次** |

#### 协议

连接后客户端发一条消息声明要看哪些通道：

```json
{"action":"watch","channels":["10001/10001-1"]}
{"action":"ping"}
```

服务端推送：

```json
{"type":"detections","data":{"10001/10001-1":{ ...同 REST 结构... }}}
{"type":"hello"}      // 连接可用
{"type":"watching"}   // 订阅已生效
{"type":"pong"}
```

**字段与 REST 完全一致**，所以前端两套传输方式共用同一份渲染逻辑 ——
换传输不该换数据结构。

#### 推送器做了两级合并（不是每帧都发）

`VisionBroadcaster` 每 **200 ms** 一拍：

1. 同一通道在一个周期内只保留**最后一帧**（覆盖写）
2. 与该订阅者**上次收到的完全一致就跳过**（画面静止时不发）

不合并的话 16 路会变成每秒 64 次回调，浏览器主线程扛不住。

#### 鉴权：token 只能放 query

浏览器 `new WebSocket()` **带不了自定义请求头**，而平台登录态在
`Authorization` 头里。所以：

- `SaTokenConfiguration` 把 `/api/v1/vision/ws` 加进 `excludePathPatterns`
  （否则握手就被 MVC 拦截器挡掉）
- handler 里用 `StpUtil.getLoginIdByToken(token)` **再校验一次**，
  无效直接 `close(1008)`

与大华对讲端点（`/api/v1/dahua/talk/audio`）完全相同的做法。

#### ⚠️ 踩过的坑：`lastSent` 不能是全局的

「这个通道推过没有」最初做成了**全局 Map**，导致**第二个连接永远收不到数据**：

> 第一个连接推过 → 全局签名被写入 → 第二个连接的更新被判成「没变化」
> 而跳过。表现为「打开第二个页面看不到框」，而第一个页面完全正常。

必须**每个订阅者各自一份** `lastSent`。实测两条连接并发，各收 5 条，
首帧 166 ms / 147 ms。

#### 降级与重连

WebSocket 不可用时**自动退回轮询**（反代没配 `Upgrade` 头、
企业网络拦非 HTTP 流量、服务端推送器异常），用户不需要知道走的是哪条。
重连用指数退避 1s → 2s → 4s → 8s → **10s 封顶**，避免服务端重启时被打爆。

心跳：前端每 20 秒发 `{"action":"ping"}`。浏览器的 WebSocket 不会自动发
ping 帧，而 nginx / 云负载均衡通常 60 秒无数据就断开。
服务端 90 秒没收到任何消息就主动回收（浏览器直接关标签页时不发 close 帧）。

## 7.5 「AI 检测」配置页（多选通道）

菜单：**运维 → AI 检测**（`/vision`）。

### 配置存在哪

表 `gb_vision_channel`（一行 = 一个要长期检测的通道，停用就删行）。

`application.yml` 的 `vision.channels` 降级为**初始种子**：

- 表为空时才导入一次
- 之后**一律以库为准**

**为什么不做「yml ∪ 表」的并集**：那样界面上**取消**一个 yml 里的通道
永远取消不掉 —— 每次重启它又回来了，比「少一个通道」更难排查。

**为什么没有 `enabled` 列**：多一个布尔列就多一个可能忘记同步的状态，
而它没有收益 —— 本表只存「要跑的通道」，不需要保留历史。

### 状态为什么分四种

| 显示 | 含义 | 该查什么 |
|---|---|---|
| 检测中（绿，呼吸灯） | 在跑且有推理帧 | 正常 |
| 启动中/无帧（红） | 在跑但 `inferredFrames=0` | 查 ffmpeg 日志、TS 可达性 |
| 未运行（灰） | 配置了但没跑起来 | 设备多半离线 |
| 已配置 | 表里有行 | — |

「配置了没跑起来」和「跑起来了没出帧」是**完全不同**的故障，
合并成一个「异常」会让人从错误的方向排查。

### 移除顺序：先删库，再停实例

顺序不能反。先停再删的话，如果删除失败，配置里还留着这一路，
30 秒后 `retryPending()` 又把它启动起来 —— 用户会看到
「点了删除，过一会儿又自己开了」。

### 添加通道：可搜索多选表格，不用穿梭框

通道多时穿梭框左右两栏都要滚，且看不到「已选哪些」的全貌。
表格 + 复选 + 顶部搜索能一次看清状态并批量勾选。

「只看在线」默认开启（离线通道加上去也不会立刻出框，容易以为没生效），
但保留开关 —— 设备暂时离线而运维想先把配置做好是真实场景。

## 7.6 播放弹窗里「关闭」的两种意图

在「通道管理 → 播放」或「实时预览」的弹窗里点 AI 按钮，若该路**正在检测**，
会弹三按钮选择：

- **暂时停止** —— 只停这一次，配置保留（`retryPending` 30 秒后会再开）
- **不再检测** —— 从 `gb_vision_channel` 移除，重启也不再开

**为什么不能直接停**：配置了长期检测的通道停掉后 30 秒会被自动恢复，
用户会看到「点了停止又自己开了」，以为坏了。

**为什么用三按钮对话框而不是确认框**：确认框只能问「是/否」，
问不出这两个选项。

## 7.7 前端渲染的一个坑：`v-if` 分支必须加 key

状态标签（未启用 / 无可用模型 / 模型名）用 `v-if` / `v-else-if` / `v-else` 时
**必须给每个分支不同的 `key`**。

不加 key 时 Vue 会把三个分支当成同一个元素来**复用**，而 `el-tag` 内部有
transition —— 切分支时旧标签进入离场动画，却因为节点被复用而**永远播不完**。

症状：「未启用」和「yolox-nano」**同时挂在页面上**，两个标签的 class
一直停在 `leave-active`。接口返回 `enabled=true`，`alertRendered=false`，
逻辑完全正确，纯粹是渲染层的问题 —— 极易误判成「后端返回错了」。

另外加一个 `ready` 标志：首次加载完成前不显示状态标签，
否则会先闪一下「未启用」（`enabled` 的初始值），用户以为真没启用。
