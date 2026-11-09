# AI 视觉识别（视觉识别集成）

在 GB28181 视频平台上集成视觉识别：人形画框、火源/烟雾识别、车辆识别、
区域入侵、人员聚集等，并把识别结果落成可检索的 AI 事件。

> **设计原则**：模型只定义「输入输出」，不依赖任何业务。推理层不认识 GB28181、
> 不认识 ZLM、不认识数据库；业务层只依赖接口。换模型、换引擎、换项目都不用改业务代码。

---

## 1. 现状与前提（已核实）

| 前提 | 现状 | 结论 |
|---|---|---|
| 取帧 | 已有 `FfmpegSnapService`：拉 ZLM 的 FLV 喂给 ffmpeg 出 JPEG，H264/H265 均验证过，约 1 秒 | 直接复用，不用另造 |
| ZLM 快照 API | ZLM4J 1.12 内嵌的 ZLM 不含 Web API，`/index/api/getSnap` 返回 404 | 只能走 ffmpeg 或 PS 解码器 |
| 裸流入口 | 已有 `PsMediaDecoder`（`mk_ps_decoder_*` frame 回调） | 想要更低延迟可走这条 |
| 叠框 | 前端已有 `TrackBox.vue`（框选、坐标换算、叠加层） | 可参考或抽公共组件 |
| 事件与告警 | 已有 `gb_alarm`、告警中心、`SnapService` 存图 | 复用 |
| 服务器算力 | 8 核 / 39 GB（可用 20 GB）/ ffmpeg 7.0.2 / JDK 21，无 GPU | YOLO-n 级 1 fps 可跑 6~8 路 |

---

## 2. 总体架构

```
设备 RTP → ZLM 媒体源 → HTTP-FLV
                          ↓
                  帧泵（ffmpeg 管道，按 fps 出帧）
                          ↓
                  推理（ONNX Runtime CPU，模型随包分发）
                          ↓
        ┌─────────────────┼──────────────────┐
    检测框 JSON        AI 事件入库          事件截图
   WebSocket 推送     gb_ai_event          SnapService
        ↓                                    ↓
   前端 canvas 叠框                    告警中心 / 按事件检索录像
```

> ⚠️ **两个关键选型**
> 1. 推理引擎用 **onnxruntime**（Java），不选 DJL —— DJL 默认运行期联网拉模型，
>    与本项目「离线 `-o` 构建、服务器不出网」的纪律冲突。
> 2. **模型许可**：Ultralytics 的 YOLOv8/v11 是 **AGPL-3.0**，商用需另行授权；
>    优先 **YOLOX / RT-DETR / NanoDet（Apache-2.0）**。

---

## 3. 模块化架构（核心）

### 3.1 分层与依赖方向

| 层 | 职责 | 依赖 |
|---|---|---|
| `vision-api`（独立 jar） | 只放接口与数据模型：`VisionDetector`、`Detection`、`Frame`、`DetectorConfig`、`DetectionSink` | 零依赖（纯 JDK） |
| `vision-onnx`（独立 jar） | ONNX Runtime 实现：`OnnxVisionDetector`、预处理、后处理（NMS） | 只依赖 vision-api + onnxruntime |
| `vision-pipeline`（独立 jar） | 帧泵、节流、背压、调度、事件聚合（去抖、ROI 判定） | 只依赖 vision-api |
| `starter`（业务层） | 把 ZLM 的流接进来、把结果落库、推前端、发告警 | 依赖上面三个 jar |

依赖方向单向：`starter → vision-pipeline → vision-api`，`starter → vision-onnx → vision-api`。
`vision-api` 不依赖任何人，所以任何项目（不只是 GB28181）都能直接拿去用。

### 3.2 核心接口（只定义输入输出）

模型层只认三种东西：`Frame`（输入）、`Detection`（输出）、`DetectorConfig`（配置）。
它不知道帧从哪来、结果去哪。

| 接口 / 类型 | 签名 | 说明 |
|---|---|---|
| `VisionDetector` | `List<Detection> detect(Frame frame)` | 唯一入口。同步、纯函数式：给一帧，还一批框 |
| `Frame` | `record Frame(byte[] jpeg, int width, int height, long timestampMs, String sourceId)` | 输入。JPEG 字节 + 尺寸 + 时间戳 + 来源标识（不透明字符串） |
| `Detection` | `record Detection(String label, float confidence, Box box, Map<String,String> attributes)` | 输出。label 是稳定字符串（`person` / `vehicle` / `fire`…），不是模型内部的数字 id |
| `Box` | `record Box(float x, float y, float w, float h)`（归一化 0~1） | 与分辨率解耦，任何尺寸的画面都能直接叠框 |
| `DetectorConfig` | `record DetectorConfig(String modelId, float confidenceThreshold, int inputSize, List<String> labels)` | 阈值、输入尺寸、标签表都在配置里，不写死在代码里 |
| `DetectionSink` | `void onDetections(String sourceId, Frame frame, List<Detection> detections)` | 结果出口。业务层实现它：落库 / 推前端 / 发告警 |

### 3.3 模型注册表（换模型不改代码）

每个模型是一个独立的 `VisionDetector` 实现，通过 `modelId` 注册（`DetectorRegistry`）。
业务层只按 `modelId` 取，不 import 具体类。

| modelId | 能力 | 许可 |
|---|---|---|
| `yolox-nano` | person / vehicle / bicycle / motorcycle | Apache-2.0 |
| `rt-detr-lite` | person / vehicle（精度更高） | Apache-2.0 |
| `fire-smoke` | fire / smoke（专用模型） | 按模型来源确认 |
| `helmet` | helmet / no-helmet | 按模型来源确认 |

### 3.4 流水线（帧泵 → 推理 → 事件）

| 组件 | 职责 | 关键设计 |
|---|---|---|
| `FrameSource` | 把任意来源变成 `Frame` 流（本项目：ffmpeg 拉 ZLM FLV） | 接口化：换成 RTSP / 文件 / 摄像头 SDK 都不用改流水线 |
| `FramePump` | 按 fps 出帧 | 复用 `FfmpegSnapService` 的管道喂 ffmpeg 方案；不用 `-i URL`（活流实测挂死） |
| `InferenceWorker` | 线程池执行 `detect()` | 每路节流 + 背压丢旧帧（队列满了丢最旧的，不堆积） |
| `EventAggregator` | 把逐帧检测聚合成事件 | 连续 N 帧 / 持续 T 秒才触发；ROI 判定（框中心在多边形内）；同一目标只报一次 |
| `DetectionSink`（业务实现） | 落库、推前端、发告警、存截图 | starter 侧实现，模型层完全不感知 |

### 3.5 复用方式（给其他项目）

- 只想要检测能力：依赖 `vision-api` + `vision-onnx`，`new OnnxVisionDetector(config)` 即可，零 Spring、零数据库。
- 想要完整流水线：再加 `vision-pipeline`，自己实现 `FrameSource` 和 `DetectionSink`。
- 换推理引擎：实现一个新的 `VisionDetector`（比如 TensorRT / 云端 API），业务代码一行不用改。
- 换模型：改 `DetectorConfig.modelId` 和模型文件，代码不动。
- 模型文件随包分发（`models/`），禁止运行期联网下载，保证离线部署可用。

---

## 4. 任务清单

### P0 地基（1~2 天）

- [x] 新建 `vision-api` 模块：`VisionDetector` / `Frame` / `Detection` / `Box` / `DetectorConfig` / `DetectionSink`（零依赖）
- [x] 新建 `vision-onnx` 模块：`OnnxVisionDetector` + 预处理 + NMS 后处理 + 模型注册表
- [x] 新建 `vision-pipeline` 模块：`FrameSource` / `FramePump` / `InferenceWorker` / `EventAggregator`
- [ ] starter 侧适配：`ZlmFrameSource`（ffmpeg 拉 FLV）+ `DbDetectionSink`（落库 + WebSocket + 告警）
- [ ] 单元测试：用固定图片断言检测结果（不依赖网络、不依赖设备）
- [ ] 性能基线：单路 1 fps 的 CPU 占用与延迟，输出 6~8 路上限的依据

### P1 人 / 车（现成 COCO 模型即可，2~3 天）

- [ ] 人形检测 + 前端 canvas 叠框（实时预览页）
- [ ] 车辆检测（car / truck / bus / motorcycle 归并为「车辆」）
- [ ] 区域入侵：画 ROI 多边形，框中心在内 + 持续 N 帧 → 告警
- [ ] `gb_ai_event` 表 + 告警中心联动 + 事件截图
- [ ] 按 AI 事件检索录像（回放页加「仅看有人的片段」）

### P2 火源 / 烟雾（需要专用模型，3~5 天）

- [ ] 火焰检测（先做「颜色 + 高亮面积 + 持续帧」快速版，再换专用模型）
- [ ] 烟雾检测 —— 只出「疑似」，与火焰合并为烟火告警
- [ ] 告警去抖调参（连续 N 帧 / 持续 T 秒，压误报）

### P3 其它识别（按现场价值排）

- [ ] 安全帽 / 反光衣（工地）
- [ ] 人员聚集 / 滞留（人形框数量 + 轨迹）
- [ ] 区域人数统计
- [ ] 车辆违停（框 + 停留时长）
- [ ] 电瓶车进电梯
- [ ] 船只 / 犬只 / 牲畜（水库场景其实比「车辆」更常用）
- [ ] 水位标尺读数（OCR，水利刚需）
- [ ] 落水 / 抛物（水面运动检测）

### P4 工程化

- [ ] 每路 ROI / 灵敏度 / 开关 配置页
- [ ] 模型热切换与版本管理
- [ ] 性能降级策略（CPU 超阈值自动降 fps / 停路）
- [ ] GPU 可选（onnxruntime-gpu，需 CUDA）
- [ ] 本文档（模型来源、许可、部署）

---

## 5. 风险与注意事项

- **模型许可**：商用必须避开 AGPL（Ultralytics YOLOv8/v11），优先 Apache-2.0 的 YOLOX / RT-DETR / NanoDet，或购买商业授权。
- **误报控制**：所有告警必须经过「连续 N 帧 / 持续 T 秒」去抖，否则现场会报到麻木。
- **性能**：无 GPU 时按 1 fps / 路估算，6~8 路为上限；CPU 超阈值要自动降 fps 或停路，不能拖垮信令。
- **取帧方式**：ffmpeg 必须走管道喂 FLV，不能用 `-i URL`（活流上实测挂死）。
- **模型分发**：模型文件随包分发（`models/`），禁止运行期联网下载，保证离线部署可用。

---

## 6. 进度记录

| 日期 | 完成 | 备注 |
|---|---|---|
| 2026-11-10 | `vision-api` / `vision-onnx` / `vision-pipeline` 三个模块落地，单测通过 | 契约层零依赖；ONNX 1.18.0 已拉到本地仓库 |
| 2026-11-10 | 腾讯文档《GB28181 视频平台 · 视觉识别（AI）集成方案与任务清单》已建 | https://docs.qq.com/aio/DVnJCaHJkbnJUQktQ（架构章节待补，末尾插入不生效） |
