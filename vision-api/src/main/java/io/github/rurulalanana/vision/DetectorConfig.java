package io.github.rurulalanana.vision;

import java.util.List;

/**
 * 检测器配置（全部可调项都在这里，不写死在代码里）。
 *
 * <p><b>为什么阈值、输入尺寸、标签表都要进配置</b>：换模型时这三样一定变；
 * 写死在实现里就意味着换模型要改代码。配置化之后，换模型只改配置 + 模型文件。
 *
 * @param modelId               模型标识（注册表键，如 {@code yolox-nano}）
 * @param modelPath             模型文件路径（ONNX；随包分发，禁止运行期联网下载）
 * @param confidenceThreshold   置信度阈值（0~1），低于此值的检测直接丢弃
 * @param inputSize             模型输入边长（正方形，如 640）
 * @param labels                类别表：下标 = 模型输出的类别 id，值 = 稳定标签
 */
public record DetectorConfig(
        String modelId,
        String modelPath,
        float confidenceThreshold,
        int inputSize,
        List<String> labels) {

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
