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

# 【加模型的方法】
# 1) 把 .onnx 放进本目录
# 2) 在这里加一条元数据
# 3) 把同样的内容抄进 application.yml 的 vision.models
# 4) 重启平台，看日志「视觉模型已加载: id=xxx」
#
# 【模型选型建议】
#   yolox-nano    416  最快，精度一般，适合人形/车辆这种大目标
#   yolox-tiny    416  速度/精度平衡
#   yolov8n       640  精度更好，但要 /255 + RGB 预处理（format: YOLO_CHANNEL_FIRST）
#
# 【许可】
#   YOLOX 是 Apache-2.0，商用 OK。
#   选模型时必须确认许可 —— 部分 YOLO 权重是 AGPL，商用要买授权。
