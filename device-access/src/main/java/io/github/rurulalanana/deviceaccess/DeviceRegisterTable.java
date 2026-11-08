package io.github.rurulalanana.deviceaccess;

import java.util.List;

/**
 * 「设备主动注册」会话表（SPI）。
 *
 * <p>公网部署时设备在 NAT 后面，平台无法主动连过去；唯一可行的方向是
 * 平台开监听端口，设备主动连上来并上报序列号与实际地址。
 *
 * <p>这份状态<b>不能落库</b>：随进程消失（设备重连会重新上报）、
 * 更新极频繁（心跳几十秒一次）、地址易失（存旧地址反而误导连接）。
 *
 * <p>每个厂商一份实现（大华 CLIENT_ListenServer、海康 ISUP），
 * 由 {@code DeviceRegisterRegistry} 按 {@link #kind()} 分发。
 */
public interface DeviceRegisterTable {

    /** 本实现对应的接入方式。 */
    DeviceKind kind();

    /**
     * 设备注册/心跳。
     *
     * @param serial 设备序列号（厂商侧唯一 ID，登录时还要用它）
     * @param ip     设备实际来源地址（由监听服务捕获，不是配置值）
     * @param port   设备实际来源端口
     * @param extra  附加信息（可选，厂商自定义），可为 {@code null}
     */
    void register(String serial, String ip, int port, String extra);

    /**
     * 取某序列号的注册信息；{@code null} 表示从未注册或已过期。
     */
    RegisteredDevice lookup(String serial);

    /** 全部当前注册记录（诊断/列表用）。 */
    List<RegisteredDevice> list();

    /** 显式注销（设备主动断开，或平台判定失效）。 */
    void unregister(String serial);

    /**
     * 清理超时未心跳的记录。
     *
     * @return 被清理的条数
     */
    int pruneExpired();

    /**
     * 一条注册记录。
     *
     * @param serial       设备序列号
     * @param ip           实际来源 IP
     * @param port         实际来源端口
     * @param registeredAt 首次注册时间（毫秒）
     * @param lastSeenAt   最后一次心跳时间（毫秒）
     * @param extra        附加信息
     */
    record RegisteredDevice(
            String serial,
            String ip,
            int port,
            long registeredAt,
            long lastSeenAt,
            String extra) {
    }
}
