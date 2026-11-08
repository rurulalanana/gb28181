package io.github.rurulalanana.deviceaccess;

/**
 * 设备直接抓图（SPI，可选能力）。
 *
 * <p>走 SDK 让设备自己编码一张 JPEG 回来，<b>不拉流、不解码</b> ——
 * 一张图几十 KB，是「低流量抓图」的唯一落点。
 *
 * <p>做不到这条的厂商不要实现本接口：平台查不到就回落到「拉流截帧」。
 */
public interface DeviceSnapshot extends DeviceExtension {

    /**
     * 该设备此刻能否直接抓图。
     *
     * <p>通常等于「SDK 会话是否有效」。返回 {@code false} 时平台回落到拉流截帧。
     */
    boolean available(String deviceId);

    /**
     * 抓一张 JPEG。
     *
     * @return JPEG 字节；设备返回空时返回 {@code null}
     * @throws IllegalStateException 设备拒绝（会话失效、通道无画面等）
     */
    byte[] jpeg(String deviceId, String channelId);
}
