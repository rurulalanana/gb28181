package io.github.rurulalanana.vision.pipeline;

import io.github.rurulalanana.vision.Frame;

import java.util.function.Consumer;

/**
 * 帧来源（流水线的输入端）。
 *
 * <p><b>为什么接口化</b>：取帧方式会变（ffmpeg 拉 FLV、RTSP 直拉、文件、SDK 抓图），
 * 流水线不该认识它们。业务层实现这个接口，把任意来源变成 {@link Frame} 流。
 *
 * <p><b>为什么用回调而不是返回值</b>：取帧是持续的（流），不是一次性的（请求）。
 * 实现方在有新帧时调用 {@code onFrame}；流水线负责节流与背压。
 */
public interface FrameSource {

    /** 来源标识（透传给 {@link Frame#sourceId()}）。 */
    String sourceId();

    /**
     * 开始产帧。
     *
     * @param onFrame 新帧回调（流水线保证快速返回，实现方不得假设回调线程）
     * @return 停止句柄，调用 {@code close()} 停止产帧
     */
    AutoCloseable start(Consumer<Frame> onFrame);
}
