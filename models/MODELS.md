# 视觉识别模型清单
#
# 【为什么这个文件在仓库根目录，而不是在 vision-onnx 模块里】
# 模型是会换的（YOLOX → RT-DETR → 专用烟火/安全帽模型），
# 而换模型不应该要求改代码、改 pom、重新打包 jar。
# 把模型与「模型注册表」放在模块之外：
#   - 换模型 = 放一个新 .onnx + 改这里的元数据，重启即可
#   - 模型文件可以不进 jar（体积、许可），由部署脚本单独放
#   - 多个项目可共享同一份模型目录
#
# 【如何被加载】
# 1) 平台启动时读 application.yml 的 vision.* 配置（这是生效的那份）
# 2) 本文件是「元数据的权威来源」，用于人工维护与部署拷贝
# 3) 二者不一致时以 application.yml 为准（Spring 配置优先）
#
# 【格式说明】见 vision-api/DetectorConfig.OutputFormat 的注释：
#   YOLO_CHANNEL_FIRST  [1, 4+C, N]  YOLOv8/v11，分数已是概率，坐标归一化
#   YOLO_ANCHOR_FIRST   [1, N, 4+C]  YOLOv5/v7，坐标已解码
#   YOLOX               [1, N, 5+C]  含 obj，坐标未解码，分数是 logits
#
# 【预处理必须与 format 配对】配错的症状：
#   YOLOX 用错 yolo 口径  → 分数全挤在 0.31~0.34，框位置偏
#   yolo 用错 yolox 口径  → 置信度 > 1，框全塌到左上角

models:
  - id: yolox-nano
    file: yolox_nano.onnx
    format: YOLOX
    inputSize: 416
    preprocess: yolox
    labels: []            # 空 = 用内置 COCO 80 类
    license: Apache-2.0
    source: https://github.com/Megvii-BaseDetection/YOLOX/releases/tag/0.1.1rc0
    note: |
      通用目标检测，COCO 80 类。CPU 单帧推理约 60~90ms（8 核服务器实测）。
      用于「人形 / 车辆 / 常见物体」这类通用场景足够。
      3.5 MB，适合随包分发。

  - id: yolox-tiny
    file: yolox_tiny.onnx
    format: YOLOX
    inputSize: 416
    preprocess: yolox
    labels: []
    license: Apache-2.0
    source: https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_tiny.onnx
    note: |
      同系列的中等模型，同样 COCO 80 类。CPU 单帧约 150ms，是 nano 的两倍。
      换来的收益：远处/小目标的检出率明显更高（实测 20 米外的人，nano 漏、tiny 能检出）。
      20.2 MB。**按需懒加载** —— 没配到 tiny 的场景就不会加载，不占内存。

  - id: yunet-face
    file: face_detection_yunet.onnx
    format: YUNET
    inputSize: 640
    preprocess: yunet
    labels: [face]
    license: MIT
    source: https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet
    note: |
      人脸检测（只框人脸，不是整个人体）。232 KB，CPU 单帧约 15~25ms，比 nano 还快。
      **COCO 模型里没有「人脸」这个类别** —— yolox 的 person 检出的是整个人，
      数人头、判断是否正面朝向镜头都做不到，所以必须单独一个模型。

      YuNet 与 YOLO 系有三处结构性差异，都已实测确认：
        1) **12 个输出**而不是 1 个：cls/obj/bbox/kps × 3 个尺度（8/16/32），
           必须按名字取。按索引只会拿到 cls_8，解析时静默返回空列表
        2) **不做 letterbox**：官方就是 cv2.resize 直接拉成 640×640。
           这个拉伸让 X/Y 两方向缩放比不同（16:9 画面下差近 2 倍），
           用一个统一 ratio 还原会让框整体缩小一半并挤向左上角
        3) **不需要 sigmoid**：cls/obj 导出时已是概率。
           加了之后最高分从 0.892 掉到 0.710
      分数是 sqrt(cls × obj) 几何平均，不是相乘。

      ⚠️ 这是「检测到人脸」，**不是「识别是谁」**。人脸识别需要另配
      embedding 模型（如 ArcFace）+ 人脸库 + 比对逻辑，不在当前范围。

# 【加模型的方法】
# 1) 把 .onnx 放进本目录
# 2) 在这里加一条元数据
# 3) 把同样的内容抄进 application-vision.yml 的 vision.models
# 4) 重启平台，看日志「视觉模型已加载: id=xxx」
#
# 【模型选型建议】
#   yolox-nano    416  最快，精度一般，适合人形/车辆这种大目标
#   yolox-tiny    416  速度/精度平衡，小目标更准
#   yunet-face    640  人脸专用，极快极准，但只能检人脸
#   yolov8n       640  精度更好，但要 /255 + RGB 预处理（format: YOLO_CHANNEL_FIRST）
#                     注意：ultralytics 官方 assets 里**没有** .onnx，
#                     需要自己从 .pt 导出（yolo export format=onnx），
#                     所以没进这个仓库
#
# 【为什么一个模型能配出多种能力】
# 同一份 COCO 权重换个标签过滤就是另一种能力，不需要多份模型文件：
#   yolox-nano + [person]                     → 人形检测
#   yolox-nano + [car,truck,bus,motorcycle]   → 车辆检测
#   yolox-nano + []                           → 全目标检测
# 这层封装叫「场景（scenario）」，配在 application-vision.yml 的 vision.scenarios，
# 界面上「检测能力」下拉里的每一项就是一条场景。
#
# 【许可】
#   YOLOX 是 Apache-2.0，YuNet 是 MIT，都可商用。
#   选模型时必须确认许可 —— 部分 YOLO 权重是 AGPL，商用要买授权。
#   ultralytics 的 YOLOv8 权重是 AGPL-3.0，商用需购买授权，这也是没选它的原因之一。
