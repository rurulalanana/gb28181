package io.github.rurulalanana.deviceaccess;

import java.util.List;

/**
 * 设备接入提供者（SPI）。
 *
 * <h3>边界：只管「协议差异」，不管「业务」</h3>
 * 实现类只负责把统一语义翻译成厂商调用，<b>不得</b>：
 * <ul>
 *   <li>读写数据库（设备/通道的持久化由平台 Service 负责）</li>
 *   <li>决定业务规则（能不能点播、是否超限由 Service 层判断）</li>
 *   <li>依赖 Spring 容器（保证可用桩对象单测）</li>
 * </ul>
 *
 * <h3>为什么不把国标也做成 provider</h3>
 * GB28181 已经是平台的主链路，把它再包一层 SPI 只会多一次跳转、
 * 且要重写已验证过的实现。因此本 SPI 只描述「<b>国标做不到、需要额外协议栈</b>」
 * 的那部分能力。点播/回放/下载这类两边都能做的能力，统一走
 * 「provider 给出拉流地址，由媒体服务拉流」。
 */
public interface DeviceAccessProvider {

    /** 本实现对应的接入方式。 */
    DeviceKind kind();

    /**
     * 本实现是否支持「平台监听、设备主动注册」。
     *
     * <p>平台据此决定是否开放监听端口、以及在该模式下是否还要求填设备地址。
     */
    default boolean supportsServerMode() {
        return false;
    }

    /**
     * 探测设备是否可达（通常是一次登录/登出）。
     *
     * <p>SERVER 模式下实现方应先查「设备注册表」里有没有该设备；
     * 没有就返回 {@code false}，而不是拿配置里的空地址去连。
     */
    boolean probe(DeviceContext ctx);

    /**
     * 拉取设备的通道目录。
     *
     * <p>返回值只描述「设备上有什么」，由调用方决定如何合并进通道表。
     * <b>不要</b>在这里直接写库。
     *
     * @return 通道列表；设备不可达时返回空列表而不是抛异常
     */
    List<ChannelInfo> listChannels(DeviceContext ctx);

    /**
     * 实时流的拉流地址，交由媒体服务去拉。
     *
     * <p><b>为什么返回地址而不是直接推流</b>：拉流地址这条路复用了现有的
     * FLV/WS-FLV/HLS/WebRTC 输出与前端播放器，不需要在平台侧解析 PS 流；
     * 而 SDK 取码流那条路要自己做解复用与时间戳重排，风险高得多。
     *
     * @param channelCode  通道编码（本平台内的通道编号）
     * @param streamNumber 码流编号：0 主码流 / 1 子码流；{@code null} 取默认
     * @return {@code rtsp://...} 形式的地址
     */
    String liveStreamUri(DeviceContext ctx, String channelCode, Integer streamNumber);

    /**
     * 历史回放的拉流地址。
     *
     * @param startTime 起始时间，格式 {@code yyyy-MM-dd HH:mm:ss}
     * @param endTime   结束时间，同上
     */
    String playbackStreamUri(DeviceContext ctx, String channelCode,
                             String startTime, String endTime);

    /**
     * 云台方向控制。
     *
     * @param command 统一命令词：{@code up}/{@code down}/{@code left}/{@code right}/
     *                {@code upleft}/{@code upright}/{@code downleft}/{@code downright}/
     *                {@code zoomin}/{@code zoomout}/{@code stop}
     * @param speed   0~255（与国标入参口径一致，内部再折算成厂商范围）
     */
    void ptz(DeviceContext ctx, String channelCode, String command, int speed);

    /** 通道信息（协议无关的最小集）。 */
    record ChannelInfo(
            String channelCode,
            String name,
            boolean online,
            int ptzType,
            String manufacturer,
            String model) {
    }
}
