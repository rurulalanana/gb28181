package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.dahua.ext.DahuaExtAccess;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.gb28181.sip.zlm.EsMediaPublisher;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import io.github.rurulalanana.gb28181.sip.zlm.ZlmMediaServer;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.config.DahuaProperties;
import io.github.rurulalanana.starter.entity.GbDevice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 大华 SDK 取流：SDK 拿裸 H264/H265 → 直接喂 ZLM 建流。
 *
 * <p>为什么不用 RTSP：那条路要平台<b>反向</b>连设备的 554 端口，设备在 NAT 后面时
 * 根本不可达。SDK 取流走已建立的登录会话，方向与主动注册一致，天然穿透 NAT。
 *
 * <p>为什么要裸流而不是 PS：主动注册会话上 SDK 只给私有 DHAV、拿不到 PS。
 * 裸流交给 {@code mk_media_input_h264/265} 建流，下游输出与国标设备完全一致。
 *
 * <p>只在设备当前有 SDK 会话时启用；没有会话的部署仍走 RTSP 代理，行为不变。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DahuaSdkStreamService {

    /** 首帧等待上限：局域网通常 200ms 内到，NAT 后稍慢。 */
    private static final long FIRST_FRAME_WAIT_MS = 3_000L;

    /** 空闲回收宽限：播放器重连的间隙不能被误判成「没人看」。 */
    private static final long IDLE_GRACE_MS = 60_000L;

    private final DahuaCapabilityService capability;
    private final DeviceService deviceService;
    private final Gb28181MediaPlatform platform;
    private final DahuaProperties props;
    /** 常开判定，与国标链路的空闲巡检共用同一份策略。 */
    private final StreamKeepAlive streamKeepAlive;

    /** 一路 SDK 取流：SDK 句柄 + ZLM 发布器。 */
    public record LiveStream(String key, String app, String stream,
                             DahuaExtAccess.RealPlay realPlay, EsMediaPublisher publisher,
                             /** 本次取的是哪一路码流（0 主码流 / 1 子码流），用于复用判断。 */
                             int streamNumber) {
    }

    /** 正在取的流：{@code deviceId/channel} → 句柄。 */
    private final Map<String, LiveStream> running = new ConcurrentHashMap<>();

    /** 各流「最近一次确认有观众」的时刻（毫秒），用于空闲回收。 */
    private final Map<String, Long> lastViewerAt = new ConcurrentHashMap<>();

    /**
     * 设备此刻是否能用 SDK 取流。
     *
     * <p>判据是「有活跃 SDK 会话」而不是「设备是主动注册模式」：
     * 局域网直连的设备同样有会话，走 SDK 一样能出图。
     */
    public boolean available(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return false;
        }
        DahuaSdk.Session session = DahuaSdk.get().sessionBySerial(deviceId);
        return session != null && session.valid();
    }

    /**
     * 启动一路 SDK 取流。
     *
     * @param deviceId     设备编号（大华即序列号）
     * @param channel      通道编码（{@code <deviceId>-<n>}）
     * @param streamNumber 码流编号：0 主码流 / 1 子码流；{@code null} 或负数按主码流处理
     * @return 取流句柄；失败抛异常
     */
    public LiveStream start(String deviceId, String channel, Integer streamNumber) {
        GbDevice device = deviceService.findDevice(deviceId);
        if (device == null) {
            throw BizException.notFound("设备不存在：" + deviceId);
        }
        DahuaSdk.Session session = DahuaSdk.get().sessionBySerial(deviceId);
        if (session == null || !session.valid()) {
            throw BizException.badRequest("大华设备未登录，无法 SDK 取流：" + deviceId);
        }
        ZlmMediaServer media = platform.mediaServer();
        if (media == null) {
            throw BizException.badRequest("媒体服务未启动");
        }

        String key = deviceId + "/" + channel;
        int wantStream = resolveStreamNumber(device, streamNumber);
        /*
         * 已在取流就直接复用，不要「先 stop 再 start」。
         *
         * 多画面 / 前端并发请求（/stream/start 与 /webrtc 协商几乎同时到）时，
         * 后到的请求会把先建好的流拆掉重建，而设备侧还没释放上一路，
         * CLIENT_RealPlayByDataType 就会报 0x8000040A「创建套接字失败」——
         * 表现为「点播成功了一瞬间又失败」。
         */
        LiveStream existing = running.get(key);
        if (existing != null && existing.realPlay().active()) {
            if (existing.streamNumber() == wantStream) {
                log.info("大华 SDK 取流已在运行，复用: {} 码流={} codec={} 累计帧={}",
                        key, wantStream == 0 ? "主" : "子",
                        existing.publisher().codecName() == 0 ? "H264" : "H265",
                        existing.publisher().frames());
                return existing;
            }
            /*
             * 码流不同：必须拆重建。
             *
             * <p>不复用也不能「先改 rType」—— 码流类型是
             * {@code CLIENT_RealPlayByDataType} 入参，只在开始取流时生效，
             * 已经在跑的句柄改不了。所以先停旧句柄与旧发布器，再重新取流。
             *
             * <p>旧发布器必须一并关掉：ZLM 的媒体源路径仍是 {@code deviceId/channel}，
             * 不关的话两路流会往同一个媒体源里写，画面会花。
             */
            log.info("大华 SDK 码流切换，重建取流: {} {} -> {}",
                    key, existing.streamNumber() == 0 ? "主码流" : "子码流",
                    wantStream == 0 ? "主码流" : "子码流");
            stop(key);
        } else {
            // 句柄已失效（设备重连过）：清掉残留再重建
            stop(key);
        }

        int sdkChannel = sdkChannelOf(session, deviceId, channel);

        /*
         * app = 设备编码、stream = 通道编码，与国标收流的口径一致：
         * 播放地址、ZLM 媒体源路径、前端播放器都不用区分流的来源。
         */
        EsMediaPublisher publisher;
        try {
            publisher = media.createEsPublisher(deviceId, channel,
                    audioCodec(), props.getStream().getAudioSampleRate(),
                    props.getStream().getAudioChannels());
        } catch (RuntimeException e) {
            throw BizException.badRequest("创建媒体源失败：" + e.getMessage());
        }

        DahuaExtAccess.RealPlay play;
        try {
            // dataType 只作诊断用（H265 也可能报 1004），编码由发布器从 NAL 头判定
            play = capability.ext().startRealPlay(session, sdkChannel, key,
                    (es, dataType) -> publisher.push(es),
                    audioCodec() == 0 ? null : (audio, dataType) -> publisher.pushAudio(audio),
                    wantStream == 0 ? DahuaExtAccess.MAIN_STREAM : DahuaExtAccess.SUB_STREAM);
        } catch (RuntimeException e) {
            publisher.close();
            throw BizException.badRequest("大华 SDK 取流失败：" + e.getMessage());
        }

        /*
         * 等首帧：ZLM 在收到媒体数据前不会真正注册流，
         * 提前返回的播放地址前端打开是 404（表现为「有地址但播不了」）。
         *
         * 判据用 ready() 而不是 hasFirstFrame()：开了音频时发布器要把
         * mk_media_init_complete 推迟到音频轨就绪（否则音频轨加不进去），
         * 只推了首帧视频的那几百毫秒里 ZLM 还没注册这路流。
         */
        long deadline = System.currentTimeMillis() + FIRST_FRAME_WAIT_MS;
        while (System.currentTimeMillis() < deadline && !publisher.ready()) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!publisher.ready()) {
            // 不留死流：SDK 句柄与媒体源一起收掉，接口明确报错
            try {
                play.close();
            } catch (Throwable ignored) {
                // 已失败，清理异常不再向上抛
            }
            publisher.close();
            // 区分「一帧都没有」与「有帧但轨道没收尾」：两者的排查方向完全不同
            String why = publisher.hasFirstFrame()
                    ? "已有视频帧但轨道未就绪（音频轨初始化异常）"
                    : "没有拿到视频帧。请检查该通道是否真的有画面"
                    + "（通道号：IPC 自身是 0，NVR 下挂是 1~N）";
            throw BizException.badRequest(
                    "大华 SDK 取流超时：已登录设备但 " + FIRST_FRAME_WAIT_MS + "ms 内" + why);
        }

        LiveStream live = new LiveStream(key, deviceId, channel, play, publisher, wantStream);
        running.put(key, live);
        lastViewerAt.remove(key);
        log.info("大华 SDK 取流已启动: {} 通道={} 码流={} codec={} 视频帧={} 音频帧={}",
                key, sdkChannel, wantStream == 0 ? "主" : "子",
                publisher.codecName() == 0 ? "H264" : "H265",
                publisher.frames(), publisher.audioFrames());
        return live;
    }

    /**
     * 决定本次取哪一路码流。
     *
     * <p>口径与国标链路一致（{@code StreamService#resolveStreamNumber}）：
     * 请求显式值 &gt; 设备配置的码流索引 &gt; 主码流。
     * 大华没有「域内自增序号」那种自动行为，所以「自动」等同于主码流。
     */
    private int resolveStreamNumber(GbDevice device, Integer requested) {
        if (requested != null && requested >= 0) {
            return requested;
        }
        if (device == null || device.getStreamIndex() == null) {
            return 0;
        }
        int n = io.github.rurulalanana.starter.common.StreamIndex
                .of(device.getStreamIndex()).getStreamNumber();
        // StreamIndex 的 AUTO 返回 -1，大华没有自动取流，按主码流兜底
        return n < 0 ? 0 : n;
    }

    /**
     * SDK 通道号。
     *
     * <p>与云台那条链路同一套口径：<b>IPC 自身是通道 0</b>，NVR 下挂摄像机是 1~N。
     * 用 1 取 IPC 的码流会报 {@code 0x80000007}（参数不合法）——
     * 实测踩过，改成 0 立刻成功。
     */
    private int sdkChannelOf(DahuaSdk.Session session, String deviceId, String channel) {
        int index = io.github.rurulalanana.dahua.DahuaDeviceAccessProvider
                .channelIndexOf(deviceId, channel);
        return session.ipc() ? Math.max(0, index - 1) : index;
    }

    /**
     * 音频编码 → ZLM CodecId。
     *
     * <p>{@code auto} 取大华出厂默认值 G.711A。不做自动探测的理由见
     * {@link DahuaProperties.Stream#getAudioCodec()}。
     *
     * @return ZLM 的音频 CodecId；{@code 0} 表示这路流不要音频轨
     */
    private int audioCodec() {
        String v = props.getStream() == null ? null : props.getStream().getAudioCodec();
        if (v == null || v.isBlank()) {
            return EsMediaPublisher.AUDIO_G711A;
        }
        return switch (v.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "off", "none", "false", "0" -> 0;
            case "g711u", "g711mu", "mulaw", "ulaw", "pcmu" -> EsMediaPublisher.AUDIO_G711U;
            case "aac" -> EsMediaPublisher.AUDIO_AAC;
            // g711a / auto 以及任何拼错的值：按大华出厂默认处理。
            // 拼错时按默认走而不是报错，是因为音频是附加能力，不该拦住出图。
            default -> EsMediaPublisher.AUDIO_G711A;
        };
    }

    /** 停止一路 SDK 取流（幂等）。 */
    public void stop(String key) {
        LiveStream live = running.remove(key);
        lastViewerAt.remove(key);
        if (live == null) {
            return;
        }
        try {
            live.realPlay().close();
        } catch (Throwable t) {
            log.debug("停止 SDK 取流失败: {} {}", key, t.toString());
        }
        try {
            live.publisher().close();
        } catch (Throwable t) {
            log.debug("关闭发布器失败: {} {}", key, t.toString());
        }
        /*
         * 清通道的 stream_id —— 它是通道列表判断「有流 / 显示停止按钮」的唯一依据。
         *
         * 不清的后果（实测）：用户关掉页面 → 60 秒后这里静默回收了 SDK 流，
         * 但通道列表仍然显示「停止」按钮、numOutputs=1，看起来像一直在推流。
         * gb_stream 那条记录由 ZLM 的 onStreamOffline 事件置 CLOSED（见 StreamEventBridge），
         * 这里只负责通道侧。
         */
        try {
            deviceService.setChannelStream(live.app(), live.stream(), null);
        } catch (Exception e) {
            log.debug("清除通道 stream_id 失败: {} {}", key, e.toString());
        }
        log.info("大华 SDK 取流已释放: {}", key);
    }

    /** 某设备通道当前是否已在取流。 */
    public boolean isRunning(String deviceId, String channel) {
        return running.containsKey(deviceId + "/" + channel);
    }

    /** 当前正在取的流数量（诊断用）。 */
    public int runningCount() {
        return running.size();
    }

    /**
     * 空闲回收：没人看就停掉 SDK 取流。
     *
     * <p>SDK 流没有 RtpSession，{@code StreamService.reapIdleStreams} 扫不到它，
     * 不单独回收的话用户关掉页面后设备会一直被取流。
     *
     * <p>判据用 ZLM 的读者数（含 MP4 录制器），所以「只录不看」不会被误关。
     * 通道设了常开（{@code on_demand=false}）的不回收。
     *
     * <p><b>不带 {@code @Scheduled}</b>：由 {@code PlatformScheduler} 通过
     * {@code DeviceMediaProvider.reapIdle()} 统一驱动，厂商不必各自声明定时任务。
     */
    public void reapIdleStreams() {
        reapIdleStreams(0);
    }

    /**
     * 带指定宽限期的空闲回收。
     *
     * @param graceMs 宽限期（毫秒），与国标链路共用运行时配置；
     *                {@code <= 0} 时用本类默认值
     */
    public void reapIdleStreams(long graceMs) {
        if (running.isEmpty()) {
            return;
        }
        ZlmMediaServer media = platform.mediaServer();
        if (media == null) {
            return;
        }
        // 下限 20 秒：浏览器从点播到真正建立拉流要 1~3 秒，再快会误杀刚打开的页面
        long grace = graceMs > 0 ? Math.max(20_000L, graceMs) : IDLE_GRACE_MS;
        /*
         * 常开判定必须与国标链路共用同一份策略（录像中 / on_demand=false / 正在视觉检测）。
         * 早先这里只看 on_demand，结果正在录或正在做 AI 检测的 SDK 流被当成无人观看回收，
         * 视觉侧发现流没了又立刻重拉 —— 日志里表现为「回收后十几秒又被启动」的反复起停。
         */
        java.util.Set<String> keepAlive = streamKeepAlive.channelsRequiringStream();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, LiveStream> e : running.entrySet()) {
            String key = e.getKey();
            LiveStream live = e.getValue();
            try {
                /*
                 * 先看读者数再查开关：常开通道的流由 ZLM 的录制定时器维持读者数，
                 * 但「只开常开、不录像」的通道没有读者也要一直在线。
                 * 查库有成本，所以先用内存里的 readers 判，只有 0 读者才去查开关。
                 */
                int readers = media.readerCount(live.app(), live.stream());
                if (readers > 0) {
                    lastViewerAt.put(key, now);
                    continue;
                }
                if (keepAlive.contains(live.stream())) {
                    // 常开/录制中/检测中：不回收，但要持续刷新时间戳，避免条件解除后立刻被判超时
                    lastViewerAt.put(key, now);
                    continue;
                }
                Long seen = lastViewerAt.get(key);
                if (seen == null) {
                    // 首次采样到 0：先记时间，给播放器重连的间隙留出宽限
                    lastViewerAt.put(key, now);
                    continue;
                }
                // 设备断线（SDK 回调停了）也要回收：地址还在但永远没新帧
                boolean stalled = now - live.publisher().lastFrameAt() > grace;
                if (now - seen >= grace || stalled) {
                    log.info("回收空闲的大华 SDK 流: {}（{} 秒无观众{}）", key,
                            (now - seen) / 1000, stalled ? "，且已无新帧" : "");
                    stop(key);
                }
            } catch (Exception ex) {
                log.debug("回收 SDK 流失败: {} {}", key, ex.toString());
            }
        }
    }
}
