package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.deviceaccess.DeviceContext;
import io.github.rurulalanana.deviceaccess.DeviceKind;
import io.github.rurulalanana.deviceaccess.DeviceMediaProvider;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import io.github.rurulalanana.gb28181.sip.zlm.ZlmMediaServer;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.entity.GbDevice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 大华取流实现：有 SDK 会话走裸流（穿透 NAT），否则退回 RTSP 代理。
 *
 * <p>这是「平台通用取流逻辑」与「大华具体实现」的分界线：
 * {@code StreamService} 只认 {@link DeviceMediaProvider}，不再出现大华类型。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DahuaMediaProvider implements DeviceMediaProvider {

    private final DeviceService deviceService;
    private final DahuaSdkStreamService sdkStream;
    private final DeviceAccessRegistry accessRegistry;
    private final Gb28181MediaPlatform platform;

    @Override
    public DeviceKind kind() {
        return DeviceKind.DAHUA;
    }

    /**
     * 有活跃 SDK 会话才算可用。
     *
     * <p>没有会话（纯内网、SDK 不可用）时返回 {@code false}，让平台走 RTSP 代理 ——
     * 与改造前「{@code dahua.available()} 不成立就退回代理」的行为一致。
     */
    @Override
    public boolean available(String deviceId) {
        return sdkStream.available(deviceId);
    }

    @Override
    public StreamHandle start(String deviceId, String channelId, Integer streamNumber) {
        if (sdkStream.available(deviceId)) {
            return startSdk(deviceId, channelId);
        }
        return startRtspProxy(deviceId, channelId, streamNumber);
    }

    /** SDK 取流：首帧已就绪，可直接返回。 */
    private StreamHandle startSdk(String deviceId, String channelId) {
        DahuaSdkStreamService.LiveStream live = sdkStream.start(deviceId, channelId);
        return new StreamHandle(live.app(), live.stream(), StreamService.TRANSPORT_SDK);
    }

    /**
     * RTSP 代理：由 ZLM 反向拉设备的 {@code rtsp://...}。
     *
     * <p>状态先记 {@code NEGOTIATING}，等 ZLM 的流上线事件改成 ONLINE
     * （代理拉流没有 RTP 握手，ZLM 立刻去连设备）。
     */
    private StreamHandle startRtspProxy(String deviceId, String channelId, Integer streamNumber) {
        GbDevice device = deviceService.findDevice(deviceId);
        if (device == null) {
            throw BizException.notFound("设备不存在：" + deviceId);
        }
        DeviceContext ctx = new DeviceContext(
                device.getDeviceId(), device.getRemoteIp(),
                device.getRemotePort() == null ? 0 : device.getRemotePort(),
                device.getUsername(), device.getPassword(),
                device.getChannelCount() == null ? 0 : device.getChannelCount());
        if (!ctx.connectable()) {
            throw BizException.badRequest("设备连接信息不完整（host/port/账号）：" + deviceId);
        }
        int sdkStreamNumber = resolveStreamNumber(device, streamNumber);
        String url = accessRegistry.require(kind())
                .liveStreamUri(ctx, channelId, sdkStreamNumber);
        if (url == null || url.isBlank()) {
            throw BizException.badRequest(
                    "设备未提供拉流地址（检查 dahua.rtsp-port 与通道号）：" + channelId);
        }
        ZlmMediaServer media = platform.mediaServer();
        if (media == null) {
            throw BizException.badRequest("媒体服务未启动");
        }
        if (!media.startProxy(deviceId, channelId, url, false)) {
            throw BizException.badRequest("媒体服务拉流失败（检查设备 RTSP 是否开启）：" + url);
        }
        log.info("大华 RTSP 代理已发起: {}/{} <- {}", deviceId, channelId,
                url.replaceAll("//[^@]*@", "//***@"));
        // transport 用 TCP：与改造前一致，前端与监控按这个值区分是否走代理
        return new StreamHandle(deviceId, channelId, "TCP");
    }

    /** 0 = 主码流；未指定时看设备配置的码流索引（与国标路径口径一致）。 */
    private static int resolveStreamNumber(GbDevice device, Integer requested) {
        if (requested != null && requested >= 0) {
            return requested;
        }
        String idx = device.getStreamIndex();
        if (idx != null && !idx.isBlank()) {
            try {
                return Math.max(0, Integer.parseInt(idx.trim()));
            } catch (NumberFormatException ignored) {
                // 配置值非法就按主码流
            }
        }
        return 0;
    }

    @Override
    public void stop(String deviceId, String channelId) {
        sdkStream.stop(deviceId + "/" + channelId);
        ZlmMediaServer media = platform.mediaServer();
        if (media != null) {
            media.stopProxy(deviceId, channelId);
            media.closeMediaSource(deviceId, channelId);
        }
    }

    @Override
    public boolean running(String deviceId, String channelId) {
        return sdkStream.isRunning(deviceId, channelId);
    }

    @Override
    public void reapIdle() {
        sdkStream.reapIdleStreams();
    }
}
