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
 * <h3>{@code ended}：两个阶段，别把「确认耗时」当成「持续时长」</h3>
 * 早先只有「开始」一种事件，而 {@code sustainedMs} 在那一帧<b>恒等于</b>
 * {@code minEventMillis} —— 因为触发条件就是「持续 ≥ 阈值」，它必然在刚跨过阈值
 * 的那一帧被置位。拿它当「目标持续了多久」显示，界面上就全是「持续 2 秒」，
 * 看起来像数据、实际是<b>配置值的回声</b>。
 *
 * <p>现在区分两个阶段：
 * <ul>
 *   <li>{@code ended=false}（开始）：{@code sustainedMs} = <b>确认耗时</b>，
 *       约等于去抖阈值，只用于排障</li>
 *   <li>{@code ended=true}（结束）：{@code sustainedMs} = <b>真实持续时长</b>
 *       （首次出现 → 消失），这才是用户想看的「持续了多久」</li>
 * </ul>
 *
 * @param sourceId     来源标识（通道编码）
 * @param label        事件标签（{@code person} / {@code vehicle} / {@code fire} …）
 * @param triggeredAt  首次出现时间（毫秒时间戳）
 * @param firedAt      触发时间（毫秒时间戳）；结束事件里是<b>消失</b>的时间
 * @param sustainedMs  开始阶段：确认耗时；结束阶段：真实持续时长
 * @param detections   触发那一刻的检测框（可能多个）；结束事件为空
 * @param frame        触发那一刻的帧（业务层可用它存截图）；结束事件为 {@code null}
 * @param ended        {@code true} 表示这是「目标消失」事件
 */
public record VisionEvent(
        String sourceId,
        String label,
        long triggeredAt,
        long firedAt,
        long sustainedMs,
        List<Detection> detections,
        Frame frame,
        boolean ended) {

    /**
     * 兼容旧调用（7 参数 = 开始事件）。
     *
     * <p>保留它是因为现有单测与外部调用都按 7 参数构造；
     * 新增字段不该把那些调用全改一遍。
     */
    public VisionEvent(String sourceId, String label, long triggeredAt, long firedAt,
                       long sustainedMs, List<Detection> detections, Frame frame) {
        this(sourceId, label, triggeredAt, firedAt, sustainedMs, detections, frame, false);
    }

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
