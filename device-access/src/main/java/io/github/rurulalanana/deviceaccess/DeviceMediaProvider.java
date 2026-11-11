package io.github.rurulalanana.deviceaccess;

/**
 * 厂商取流实现（SPI）。
 *
 * <p>解决的是「同一个接入方式可能有多种取流路径」：大华内网走 RTSP 代理、
 * NAT 后走 SDK 裸流；海康可能是 ISUP 推流或 SDK 取流。
 * 平台只认「给我一路流」，具体怎么拿由实现决定。
 *
 * <h3>与 {@link DeviceAccessProvider} 的分工</h3>
 * <ul>
 *   <li>{@link DeviceAccessProvider#liveStreamUri} —— 只给<b>地址</b>，由平台去拉（RTSP 代理）</li>
 *   <li>本接口 —— 自己<b>建流</b>（SDK 裸流、ISUP 推流等），平台拿到的仍是一个 ZLM 的 app/stream</li>
 * </ul>
 * 后者能力更强（能穿透 NAT），所以优先用本接口；实现内部可以自行回退到前者。
 *
 * <h3>实现约定</h3>
 * <ul>
 *   <li>不碰数据库、不决定业务规则（与大华 provider 同样的边界）</li>
 *   <li>{@link #start} 返回时流必须<b>已经能在 ZLM 里播</b>，否则前端拿到地址打不开</li>
 *   <li>{@link #stop} 与 {@link #running} 必须幂等，会被定时巡检反复调用</li>
 * </ul>
 */
public interface DeviceMediaProvider {

    /** 本实现对应的接入方式。 */
    DeviceKind kind();

    /**
     * 此刻能否由本实现取流。
     *
     * <p>典型判据是「设备会话是否有效」：没有会话时平台应回退到其他路径或直接报错，
     * 而不是在这里干等超时。
     */
    boolean available(String deviceId);

    /**
     * 开一路实时流。
     *
     * @param deviceId     设备编码
     * @param channelId    通道编码
     * @param streamNumber 码流编号：0 主码流 / 1 子码流；{@code null} 取默认
     * @return 流句柄（ZLM 的 app/stream 与传输方式）
     * @throws IllegalStateException 设备不可用或取流失败（调用方转成业务错误）
     */
    StreamHandle start(String deviceId, String channelId, Integer streamNumber);

    /** 停止一路流（幂等）。 */
    void stop(String deviceId, String channelId);

    /** 该通道当前是否已在本实现里取流。 */
    boolean running(String deviceId, String channelId);

    /**
     * 空闲回收。由平台定时调用，实现自行判断「没人看就停」。
     *
     * <p>默认空实现：不是所有取流方式都需要回收（例如设备推流由设备自己控制）。
     */
    default void reapIdle() {
    }

    /**
     * 带宽限期的空闲回收。
     *
     * <p><b>为什么要传参</b>：宽限期是运行时配置（界面可热改）。
     * 厂商实现自己读配置只能拿到启动快照，改了不生效 ——
     * 表现为「国标流按新值释放、厂商流还是旧值」，看起来像厂商流没被回收。
     *
     * <p>默认实现转调 {@link #reapIdle()}，不关心宽限期的实现不用改。
     *
     * @param graceMs 宽限期（毫秒）；{@code <= 0} 表示由实现自选默认值
     */
    default void reapIdle(long graceMs) {
        reapIdle();
    }

    /**
     * 一路已建立流的坐标。
     *
     * @param app       ZLM 应用名（通常等于设备编码）
     * @param stream    ZLM 流名（通常等于通道编码）
     * @param transport 传输方式，落库到 {@code gb_stream.transport}
     *                  （如 {@code SDK} / {@code TCP} / {@code ISUP}）
     */
    record StreamHandle(String app, String stream, String transport) {
    }
}
