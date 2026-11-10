package io.github.rurulalanana.vision;

import java.util.List;

/**
 * 检测器配置（全部可调项都在这里，不写死在代码里）。
 *
 * <p><b>为什么阈值、输入尺寸、标签表、输出格式都要进配置</b>：换模型时这些一定变；
 * 写死在实现里就意味着换模型要改代码。配置化之后，换模型只改配置 + 模型文件。
 *
 * <h3>输出格式为什么必须可配置（实测差异）</h3>
 * 同样是「YOLO 系」的 ONNX 模型，导出格式差别很大：
 * <pre>
 *   YOLOv8n : input [1,3,640,640]   output [1, 84, 8400]   框是 cx,cy,w,h（归一化 0~1）
 *   YOLOX-nano: input [1,3,416,416] output [1,3549, 85]    框是 cx,cy,w,h（<b>像素</b>），
 *                                                          且第 5 个通道是 obj 置信度，
 *                                                          真实分数 = obj × classScore
 * </pre>
 * 把这两者写死成一套，换模型就必须改 {@code OnnxVisionDetector} 的代码 ——
 * 那就违背了「模型层只认契约」的初衷。
 *
 * @param modelId               模型标识（注册表键，如 {@code yolox-nano}）
 * @param modelPath             模型文件路径（ONNX；随包分发，禁止运行期联网下载）
 * @param confidenceThreshold   置信度阈值（0~1），低于此值的检测直接丢弃
 * @param inputSize             模型输入边长（正方形，如 416 / 640）
 * @param labels                类别表：下标 = 模型输出的类别 id，值 = 稳定标签
 * @param format                输出张量格式（决定怎么解析）
 * @param preprocess            预处理方式（决定怎么喂模型）
 */
public record DetectorConfig(
        String modelId,
        String modelPath,
        float confidenceThreshold,
        int inputSize,
        List<String> labels,
        OutputFormat format,
        Preprocess preprocess) {

    /**
     * 预处理方式。
     *
     * <h3>为什么这个也必须可配置（踩过的坑）</h3>
     * 同一张图、同一个「YOLO 系」模型，预处理差一点结果就完全不同。实测两种模型的
     * 官方预处理<b>不一样</b>：
     * <table border="1">
     *   <tr><th></th><th>YOLOv8/v5</th><th>YOLOX</th></tr>
     *   <tr><td>像素值</td><td>{@code /255}（0~1）</td><td><b>原样 0~255</b>，不归一化</td></tr>
     *   <tr><td>通道序</td><td>RGB</td><td><b>BGR</b>（它用 cv2 读图）</td></tr>
     *   <tr><td>缩放</td><td>letterbox 或直接拉伸</td><td>letterbox，<b>填充值 114</b></td></tr>
     * </table>
     *
     * <p><b>症状对照</b>：把 YOLOX 当成 YOLOv8 那样预处理（/255 + RGB + 拉伸），
     * 结果是「框的位置大体在合理范围，但分数全部挤在 0.31~0.34」——
     * 因为输入分布完全错位，模型在乱猜。这种错误很难从日志看出来，
     * 只有对比官方实现才能定位。
     *
     * @param valueScale 像素值缩放：{@code 1/255f}（YOLOv8）或 {@code 1f}（YOLOX）
     * @param channels   通道顺序
     * @param letterbox  {@code true} 保持长宽比并填充；{@code false} 直接拉伸成正方形
     * @param padValue   letterbox 的填充值（YOLOX 用 114，YOLOv8 用 114 或 0）
     */
    public record Preprocess(
            float valueScale,
            ChannelOrder channels,
            boolean letterbox,
            int padValue) {

        /** 通道顺序。 */
        public enum ChannelOrder {
            /** 红绿蓝（大多数推理框架的约定）。 */
            RGB,
            /** 蓝绿红（OpenCV / YOLOX 的约定）。 */
            BGR
        }

        /** YOLOv8/v5 风格：/255、RGB、直接拉伸。 */
        public static Preprocess yolo() {
            return new Preprocess(1f / 255f, ChannelOrder.RGB, false, 114);
        }

        /** YOLOX 风格：0~255、BGR、letterbox 填充 114。 */
        public static Preprocess yolox() {
            return new Preprocess(1f, ChannelOrder.BGR, true, 114);
        }
    }

    /** 默认预处理：YOLOv8 风格。 */
    public static final Preprocess DEFAULT_PREPROCESS = Preprocess.yolo();

    /**
     * 输出张量格式。
     *
     * <p>三种常见导出形态，{@code OnnxVisionDetector} 按它分派解析：
     * <table border="1">
     *   <tr><th>值</th><th>形状</th><th>通道含义</th><th>框坐标</th><th>需 sigmoid</th></tr>
     *   <tr><td>{@link #YOLO_CHANNEL_FIRST}</td><td>{@code [1, 4+C, N]}</td>
     *       <td>4 框 + C 类（无 obj）</td><td>归一化 0~1</td><td>否（导出已内置）</td></tr>
     *   <tr><td>{@link #YOLO_ANCHOR_FIRST}</td><td>{@code [1, N, 4+C]}</td>
     *       <td>4 框 + C 类（无 obj）</td><td>归一化 0~1</td><td>否（导出已内置）</td></tr>
     *   <tr><td>{@link #YOLOX}</td><td>{@code [1, N, 5+C]}</td>
     *       <td>4 框 + obj + C 类</td><td><b>未解码的网格偏移</b></td><td><b>是</b></td></tr>
     * </table>
     *
     * <p><b>YOLOX 的框必须解码</b>：官方 {@code yolox_nano.onnx} 输出的是未解码的
     * head 张量，要按 {@code cx=(raw+grid)*stride, w=exp(raw)*stride} 还原。
     * 直接当坐标用的表现是「所有框塌到左上角、只有几像素」+「置信度 7.879」，
     * 这两个现象同时出现就是没做 postprocess 的指纹。
     */
    public enum OutputFormat {
        /** YOLOv8/v11 默认导出：{@code [1, 4+C, N]}，通道在前，无 obj，分数已是概率。 */
        YOLO_CHANNEL_FIRST,
        /** YOLOv5/v7 默认导出：{@code [1, N, 4+C]}，候选在前，无 obj，坐标已解码。 */
        YOLO_ANCHOR_FIRST,
        /** YOLOX 官方导出：{@code [1, N, 5+C]}，含 obj，坐标<b>未解码</b>，分数是 logits。 */
        YOLOX
    }

    /** 默认格式：YOLOv8 风格（{@code [1, 84, 8400]}）。 */
    public static final OutputFormat DEFAULT_FORMAT = OutputFormat.YOLO_CHANNEL_FIRST;

    /**
     * 兼容旧调用点的构造器（不传 format / preprocess 时按 YOLOv8 风格）。
     *
     * <p>保留它是因为已有单测与外部调用都按 5 参数构造；新增字段不破坏它们。
     */
    public DetectorConfig(String modelId, String modelPath, float confidenceThreshold,
                          int inputSize, List<String> labels) {
        this(modelId, modelPath, confidenceThreshold, inputSize, labels,
                DEFAULT_FORMAT, DEFAULT_PREPROCESS);
    }

    /** 输出格式；{@code null} 时按默认（YOLOv8）处理。 */
    public OutputFormat outputFormat() {
        return format == null ? DEFAULT_FORMAT : format;
    }

    /** 预处理；{@code null} 时按默认（YOLOv8 风格）处理。 */
    public Preprocess preprocessSpec() {
        return preprocess == null ? DEFAULT_PREPROCESS : preprocess;
    }

    /**
     * 按 {@code format} 推导出配套的预处理。
     *
     * <p>之所以提供这个捷径：{@code format} 与 {@code preprocess} 必须配对 ——
     * 声明成 {@link OutputFormat#YOLOX} 却用 YOLOv8 的预处理，模型会全在乱猜。
     * 单独构造时容易漏配，用这个方法可以保证两者一致。
     */
    public static DetectorConfig forYolox(String modelId, String modelPath,
                                          float threshold, int inputSize, List<String> labels) {
        return new DetectorConfig(modelId, modelPath, threshold, inputSize, labels,
                OutputFormat.YOLOX, Preprocess.yolox());
    }

    /** 模型输出的类别 id → 稳定标签；越界返回 {@code null}（实现层据此丢弃）。 */
    public String labelOf(int classId) {
        if (labels == null || classId < 0 || classId >= labels.size()) {
            return null;
        }
        return labels.get(classId);
    }

    /** 配置是否可用（实现层据此快速失败）。 */
    public boolean usable() {
        return modelId != null && !modelId.isBlank()
                && modelPath != null && !modelPath.isBlank()
                && inputSize > 0 && labels != null && !labels.isEmpty();
    }
}
