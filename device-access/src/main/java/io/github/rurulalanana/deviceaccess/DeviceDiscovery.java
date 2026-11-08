package io.github.rurulalanana.deviceaccess;

import java.util.List;

/**
 * 局域网设备发现（SPI，可选能力）。
 *
 * <p>国标设备靠主动注册/心跳进入平台，不需要搜索；厂商设备靠广播包探测。
 * 平台只关心「搜到了谁」，不关心用的是哪种广播协议。
 *
 * <p>能力只对同广播域有效，跨网段搜不到是协议决定的，实现方不必补偿。
 */
public interface DeviceDiscovery extends DeviceExtension {

    /**
     * 搜一轮。
     *
     * @param localIp   本机网卡地址；{@code null} 表示由实现方自选
     * @param timeoutMs 收集时长
     * @return 搜到的设备；没搜到返回空列表
     */
    List<Found> discover(String localIp, long timeoutMs);

    /**
     * 搜到的一台设备。
     *
     * @param serial   序列号（平台纳管后作为设备编号）
     * @param ip       设备地址
     * @param sdkPort  私有协议端口
     * @param httpPort Web 端口；未知时为 0
     * @param model    型号；未知时为 {@code null}
     * @param firmware 固件版本；未知时为 {@code null}
     * @param channels 通道数；未知时为 0
     */
    record Found(String serial, String ip, int sdkPort, int httpPort,
                 String model, String firmware, int channels) {
    }
}
