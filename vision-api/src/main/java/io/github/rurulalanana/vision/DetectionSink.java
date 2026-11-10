package io.github.rurulalanana.vision;

import java.util.List;

/**
 * 检测结果出口（业务层实现）。
 *
 * <p><b>模型层不关心结果去哪</b>：落库、推前端、发告警、存截图，都是业务层的事。
 * 流水线把两类结果递到这里，<b>用途完全不同，不能混用</b>：
 * <ul>
 *   <li>{@link #onDetections} —— <b>每帧</b>都调，用于实时叠框（前端预览）。
 *       频率高，必须极快返回，一般只做「覆盖最新一帧的框」。</li>
 *   <li>{@link #onEvent} —— 只在<b>去抖后触发</b>时调一次，用于落库 / 告警 / 存截图。
 *       频率低，可以慢一点，但也不应阻塞（耗时操作请自己异步化）。</li>
 * </ul>
 *
 * <p><b>为什么必须有 onEvent</b>：早先只有 {@code onDetections}，业务层只能按帧落库
 * —— 1 fps × 8 路 = 每秒 8 条记录，告警刷屏；而流水线里 {@code EventAggregator}
 * 算出的「连续 N 帧 / 持续 T 秒」结果<b>没人接收</b>，去抖等于没生效。
 * 事件回调就是那条缺失的出口。
 *
 * <p><b>实现要求</b>：两个方法都不得阻塞 —— 流水线在工作线程上调用它们，
 * 阻塞会直接拖慢推理。
 */
public interface DetectionSink {

    /**
     * 收到一批检测结果（<b>每帧</b>都调，实时叠框用）。
     *
     * @param sourceId    来源标识（与 {@link Frame#sourceId()} 一致）
     * @param frame       产生这批结果的帧
     * @param detections  检测结果（可能为空列表）
     */
    void onDetections(String sourceId, Frame frame, List<Detection> detections);

    /**
     * 去抖后触发的<b>事件</b>（落库 / 告警 / 存截图用）。
     *
     * <p>同一 {@code sourceId + label} 在一次「出现」期间<b>只触发一次</b>，
     * 目标消失后重新计数。默认空实现：只做实时预览的业务层不必覆写。
     *
     * @param event 事件（含触发时的检测框、帧、持续时长）
     */
    default void onEvent(VisionEvent event) {
        // 默认不处理：只做实时叠框的业务层不需要事件
    }
}
