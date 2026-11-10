package io.github.rurulalanana.vision;

import java.util.List;

/**
 * 一个<b>已去抖</b>的视觉事件。
 *
 * <p>与逐帧的 {@link Detection} 的区别：{@code Detection} 是「这一帧有个框」，
 * 本类是「这个东西持续存在了一段时间，可以报警了」。
 *
 * <p><b>为什么事件要带 triggeredAt 与 sustainedMs 两个时间</b>：
 * <ul>
 *   <li>{@code triggeredAt} —— 事件<b>首次出现</b>的时间（用于落库排序、检索录像起点）</li>
 *   <li>{@code firedAt} —— 达到去抖阈值、<b>真正触发</b>的时间（用于告警时间戳）</li>
 * </ul>
 * 两者相差约等于 {@code minEventMillis}。只有后者会让「检索录像」从报警那一刻开始，
 * 丢掉前面那段目标已经出现的画面。
 *
 * @param sourceId     来源标识（通道编码）
 * @param label        事件标签（{@code person} / {@code vehicle} / {@code fire} …）
 * @param triggeredAt  首次出现时间（毫秒时间戳）
 * @param firedAt      触发时间（毫秒时间戳）
 * @param sustainedMs  从首次出现到触发持续了多久
 * @param detections   触发那一刻的检测框（可能多个）
 * @param frame        触发那一刻的帧（业务层可用它存截图）
 */
public record VisionEvent(
        String sourceId,
        String label,
        long triggeredAt,
        long firedAt,
        long sustainedMs,
        List<Detection> detections,
        Frame frame) {

    /** 本事件涉及的目标数量。 */
    public int count() {
        return detections == null ? 0 : detections.size();
    }

    /** 最高置信度（没有框时返回 0）。 */
    public float maxConfidence() {
        if (detections == null || detections.isEmpty()) {
            return 0f;
        }
        float max = 0f;
        for (Detection d : detections) {
            if (d.confidence() > max) {
                max = d.confidence();
            }
        }
        return max;
    }
}
