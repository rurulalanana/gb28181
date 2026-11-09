package io.github.rurulalanana.vision;

import java.util.List;

/**
 * 检测结果出口（业务层实现）。
 *
 * <p><b>模型层不关心结果去哪</b>：落库、推前端、发告警、存截图，都是业务层的事。
 * 流水线只负责把 {@code (sourceId, frame, detections)} 递到这里。
 *
 * <p><b>实现要求</b>：必须快速返回、不得阻塞 —— 流水线在工作线程上调用它，
 * 阻塞会直接拖慢推理。需要耗时操作（写库、发通知）请自己异步化。
 */
public interface DetectionSink {

    /**
     * 收到一批检测结果。
     *
     * @param sourceId    来源标识（与 {@link Frame#sourceId()} 一致）
     * @param frame       产生这批结果的帧
     * @param detections  检测结果（可能为空列表）
     */
    void onDetections(String sourceId, Frame frame, List<Detection> detections);
}
