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

    /** RTSP 代理流的空闲宽限：与 SDK 取流一致，给播放器重连留够间隙。 */
    private static final long PROXY_IDLE_GRACE_MS = 60_000L;

    private final DeviceService deviceService;
    private final DahuaSdkStreamService sdkStream;
    private final DeviceAccessRegistry accessRegistry;
    private final Gb28181MediaPlatform platform;
    /** 常开判定，与国标链路的空闲巡检共用同一份策略。 */
    private final StreamKeepAlive streamKeepAlive;

    /**
     * 正在跑的 RTSP 代理流：{@code deviceId/channelId} → 最近一次确认有观众的时刻。
     *
     * <p><b>为什么单独记</b>：代理流由 ZLM 自己拉设备，平台没有 {@code RtpSession}，
     * 三条既有回收路径都覆盖不到 —— {@code StreamService} 的巡检遍历会话、
     * ZLM 的无人观看回调要求能 resolve 到会话、{@code DahuaSdkStreamService}
     * 只遍历 SDK 句柄。不记下来就没人回收，代理流会一直拉到进程重启。
     */
    private final java.util.Map<String, Long> proxyStreams =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public DeviceKind kind() {
        return DeviceKind.DAHUA;
    }

    /**
     * 有活跃 SDK 会话才算可用。
     *
     * <p>返回 {@code false} 时调用方（{@code StreamService.startSdkLive}）会直接报
     * 「设备当前不可取流」而不是退回 RTSP 代理：走到这里的设备都是
     * {@code source=dahua}（靠主动注册上线），没有会话就意味着设备离线，
     * 此时反向连它的 554 也必然失败。
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
        /*
         * SDK 会话恢复后可能还留着上次的 RTSP 代理玩家（同一个 app/stream）。
         * 两个来源同时往一个媒体源推会互相打架，所以先解掉代理。
         */
        if (proxyStreams.remove(deviceId + "/" + channelId) != null) {
            ZlmMediaServer media = platform.mediaServer();
            if (media != null) {
                media.stopProxy(deviceId, channelId);
            }
        }
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
        // 记下开始时刻：此后由 reapIdle 按读者数与常开策略决定何时释放
        proxyStreams.put(deviceId + "/" + channelId, System.currentTimeMillis());
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
        proxyStreams.remove(deviceId + "/" + channelId);
        sdkStream.stop(deviceId + "/" + channelId);
        ZlmMediaServer media = platform.mediaServer();
        if (media != null) {
            media.stopProxy(deviceId, channelId);
            media.closeMediaSource(deviceId, channelId);
        }
    }

    @Override
    public boolean running(String deviceId, String channelId) {
        return sdkStream.isRunning(deviceId, channelId)
                || proxyStreams.containsKey(deviceId + "/" + channelId);
    }

    @Override
    public void reapIdle() {
        reapIdle(0);
    }

    @Override
    public void reapIdle(long graceMs) {
        sdkStream.reapIdleStreams(graceMs);
        reapIdleProxies(graceMs);
    }

    /**
     * 回收无人观看的 RTSP 代理流。
     *
     * <p>判据与 SDK 取流保持一致：ZLM 读者数为 0、通道不常开、且连续宽限期都没见过观众。
     * 用读者数而不是「有没有人在看页面」：ZLM 把 MP4 录制器也算作读者，
     * 「只录不看」的流不会被误关。
     *
     * @param graceMs 宽限期（毫秒）；{@code <= 0} 时用本类默认值
     */
    private void reapIdleProxies(long graceMs) {
        if (proxyStreams.isEmpty()) {
            return;
        }
        ZlmMediaServer media = platform.mediaServer();
        if (media == null) {
            return;
        }
        long grace = graceMs > 0 ? Math.max(20_000L, graceMs) : PROXY_IDLE_GRACE_MS;
        long now = System.currentTimeMillis();
        java.util.Set<String> keepAlive = streamKeepAlive.channelsRequiringStream();
        for (java.util.Map.Entry<String, Long> e : proxyStreams.entrySet()) {
            String key = e.getKey();
            int slash = key.indexOf('/');
            String deviceId = slash < 0 ? key : key.substring(0, slash);
            String channelId = slash < 0 ? key : key.substring(slash + 1);
            try {
                if (media.readerCount(deviceId, channelId) > 0
                        || keepAlive.contains(channelId)) {
                    proxyStreams.put(key, now);
                    continue;
                }
                long idleMs = now - e.getValue();
                if (idleMs >= grace) {
                    log.info("回收空闲的大华 RTSP 代理流: {}（{} 秒无观众）", key, idleMs / 1000);
                    stop(deviceId, channelId);
                }
            } catch (Exception ex) {
                log.debug("回收 RTSP 代理流失败: {} {}", key, ex.toString());
            }
        }
    }
}
