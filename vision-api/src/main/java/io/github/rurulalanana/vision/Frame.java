package io.github.rurulalanana.vision;

/**
 * 一帧待识别的图像（模型层的唯一输入）。
 *
 * <p><b>为什么用 JPEG 字节而不是解码后的像素</b>：取帧侧（ffmpeg / RTSP / 文件）
 * 产出的天然形态就是 JPEG；解码成像素是模型预处理的事，放在实现层做，
 * 契约层不背这个包袱。这样换取帧方式（RTSP、文件、SDK 抓图）都不用改契约。
 *
 * <p><b>为什么带 {@code sourceId}</b>：流水线要把结果路由回「哪一路画面」，
 * 但模型层不该认识设备/通道编码 —— 用一个不透明字符串透传即可，
 * 业务层自己决定它是什么（设备编码、流地址、文件名都行）。
 *
 * @param jpeg         JPEG 字节（实现层负责解码与缩放）
 * @param width        图像宽（像素）
 * @param height       图像高（像素）
 * @param timestampMs  帧的时间戳（毫秒，取帧时刻；用于事件聚合与去抖）
 * @param sourceId     来源标识（不透明，业务层定义）
 */
public record Frame(byte[] jpeg, int width, int height, long timestampMs, String sourceId) {

    /** 便捷构造：时间戳取当前时刻。 */
    public Frame(byte[] jpeg, int width, int height, String sourceId) {
        this(jpeg, width, height, System.currentTimeMillis(), sourceId);
    }

    /** 图像是否可用（实现层据此快速失败，而不是抛异常）。 */
    public boolean usable() {
        return jpeg != null && jpeg.length > 0 && width > 0 && height > 0;
    }
}
