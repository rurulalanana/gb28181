package io.github.rurulalanana.vision;

import java.util.Map;

/**
 * 一条检测结果（模型层的唯一输出）。
 *
 * <p><b>{@code label} 必须是稳定字符串</b>（{@code person} / {@code vehicle} /
 * {@code fire} …），不是模型内部的数字类别 id。数字 id 随模型版本变化，
 * 业务层（落库、告警规则、前端图例）按字符串匹配才不会被模型升级打断。
 * 模型内部的类别映射放在实现层（见 {@link DetectorConfig#labels()}）。
 *
 * @param label        稳定标签（小写蛇形，如 {@code person}）
 * @param confidence   置信度（0~1）
 * @param box          检测框（归一化坐标）
 * @param attributes   附加属性（实现层按需填充，如 {@code trackId}、{@code plate}；可为空）
 */
public record Detection(String label, float confidence, Box box, Map<String, String> attributes) {

    /** 无附加属性的便捷构造。 */
    public Detection(String label, float confidence, Box box) {
        this(label, confidence, box, Map.of());
    }

    /** 是否达到置信度阈值（流水线过滤用）。 */
    public boolean above(float threshold) {
        return confidence >= threshold;
    }
}
