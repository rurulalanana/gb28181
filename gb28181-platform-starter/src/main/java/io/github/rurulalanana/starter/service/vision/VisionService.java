package io.github.rurulalanana.starter.service.vision;

import io.github.rurulalanana.starter.config.VisionProperties;
import io.github.rurulalanana.starter.entity.GbStream;
import io.github.rurulalanana.starter.service.FfmpegSnapService;
import io.github.rurulalanana.starter.service.StreamService;
import io.github.rurulalanana.vision.VisionDetector;
import io.github.rurulalanana.vision.pipeline.PipelineConfig;
import io.github.rurulalanana.vision.pipeline.Roi;
import io.github.rurulalanana.vision.pipeline.VisionPipeline;
import io.github.rurulalanana.gb28181.sip.zlm.RtpSession;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 视觉识别的编排：决定「哪几路在跑」，并为每一路装配 取帧 → 流水线。
 *
 * <h3>为什么单独一层，而不是塞进 StreamService</h3>
 * 取流会话是<b>国标</b>的（设备 INVITE / ZLM 收流），检测是<b>视觉</b>的。
 * 混在一起会让「只是想看视频」的场景也把模型和 ffmpeg 拖起来。
 * 这里只在<b>明确配置/明确调用</b>时才 attach，不侵入播放链路。
 *
 * <h3>与播放的关系</h3>
 * 检测需要一路活流。所以 attach 时会调 {@code streamService.startLive()} ——
 * 与用户点播是同一套机制，ZLM 会复用同一路收流，不会重复向设备要流。
 * detach 时<b>不停流</b>：可能还有人在看，停流由播放侧自己的空闲回收管。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VisionService {

    private final VisionProperties props;
    private final VisionModelRegistry registry;
    private final StreamService streamService;
    private final FfmpegSnapService ffmpegSnapService;
    private final DbDetectionSink sink;

    /** 正在检测的通道：通道编码 → 运行中的一路。 */
    private final Map<String, Running> running = new ConcurrentHashMap<>();
    /** 每路 attach 的时刻（毫秒）：用于判断「多久没出帧才算卡住」。 */
    private final Map<String, Long> startedAt = new ConcurrentHashMap<>();

    /** 已就「零出帧」告警过的通道（每条只报一次，避免每 30 秒刷屏）。 */
    private final java.util.Set<String> stallWarned = ConcurrentHashMap.newKeySet();

    /**
     * 多久没产出推理帧就告警（毫秒）。
     *
     * <p>正常通道：ffmpeg 启动 + 拉流 + 等关键帧 + 首次推理不超过 10 秒。
     * 给 120 秒是为了容忍慢设备（级联下级实测有 45 秒才推第一个包的）。
     */
    private static final long STALL_WARN_AFTER_MS = 120_000;
    /** 一路检测的运行态。 */
    private static final class Running {
        final VisionPipeline pipeline;
        final AutoCloseable detach;
        final String modelId;

        Running(VisionPipeline pipeline, AutoCloseable detach, String modelId) {
            this.pipeline = pipeline;
            this.detach = detach;
            this.modelId = modelId;
        }
    }

    /** 启动时按配置自动开启通道。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!props.isEnabled()) {
            log.info("视觉识别未启用（vision.enabled=false）");
            return;
        }
        if (!registry.available()) {
            log.warn("视觉识别已启用但没有可用模型，跳过自动开启");
            return;
        }
        List<String> channels = props.getChannels();
        if (channels == null || channels.isEmpty()) {
            log.info("视觉识别已启用但未配置通道（vision.channels），等待接口手动开启");
            return;
        }
        // 先试一轮，失败的下一次 tick 再试（设备可能还没注册上来）
        retryPending();
    }

    /**
     * 周期性把「配置了但没跑起来」的通道补上。
     *
     * <h3>为什么必须有这个重试</h3>
     * 实测：服务重启后 {@code ApplicationReadyEvent} 触发时，设备往往还没完成
     * SIP REGISTER / 大华 SDK 登录，此时 {@code startLive()} 会报
     * 「通道不在线」。只看启动那一瞬间的日志，会以为「视觉识别配了但没生效」——
     * 而实际上是设备几十秒后才上线。
     *
     * <p>这也让「设备断电重启」「网络抖动导致流断」能自愈，不需要人工介入。
     *
     * <p>间隔 30 秒：比 SIP 心跳（通常 60 秒）短一半，设备一上线就能被发现；
     * 又不至于每秒去打扰设备要流。
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void retryPending() {
        if (!props.isEnabled() || !registry.available()) {
            return;
        }
        List<String> channels = props.getChannels();
        if (channels == null || channels.isEmpty()) {
            return;
        }
        int started = 0;
        int pending = 0;
        for (String channel : channels) {
            if (channel == null || channel.isBlank()) {
                continue;
            }
            String key = channel.trim();
            if (running.containsKey(key)) {
                continue;
            }
            pending++;
            if (start(key)) {
                started++;
            }
        }
        if (pending > 0) {
            if (started > 0) {
                log.info("视觉识别补开: {}/{} 路（共 {} 路配置，{} 路在跑）",
                        started, pending, channels.size(), running.size());
            } else {
                // 全都没起来时降到 debug：设备长时间离线时不该每 30 秒刷一条 WARN
                log.debug("视觉识别等待通道上线: {}/{} 路仍不可用", pending, channels.size());
            }
        }
        warnIfStalled();
    }

    /**
     * 报「已 attach 但从未产出推理帧」的通道。
     *
     * <h3>为什么需要这个检查</h3>
     * 这个失效方式<b>完全静默</b>：ffmpeg 进程在跑、attach 成功、
     * {@code runningCount} 正确，唯独 {@code inferredFrames} 永远是 0。
     * 不看这个指标会以为「检测正常工作」。
     *
     * <p>给 120 秒宽限：ffmpeg 启动 + 拉流 + 等关键帧 + 首次推理
     * 在正常通道上不超过 10 秒，120 秒还没帧就是真有问题。
     *
     * <p>每条通道只告警一次（{@link #stallWarned}），避免每 30 秒刷屏。
     */
    private void warnIfStalled() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Running> e : running.entrySet()) {
            String channel = e.getKey();
            if (e.getValue().pipeline.inferredFrames() > 0) {
                stallWarned.remove(channel);
                continue;
            }
            Long since = startedAt.get(channel);
            if (since == null || now - since < STALL_WARN_AFTER_MS) {
                continue;
            }
            if (stallWarned.add(channel)) {
                log.warn("视觉检测已 attach 但 {} 秒内没有产出任何推理帧: channel={} —— "
                                + "请检查 ① TS 地址是否可达（日志里搜 ffmpeg[）② 设备是否在推流 "
                                + "③ 流是否为 H.265 以外的异常编码。用 GET /api/v1/vision/status 看 inferredFrames。",
                        STALL_WARN_AFTER_MS / 1000, channel);
            }
        }
    }

    /**
     * 开启一路检测。
     *
     * @param channelKey 通道键，格式 {@code 设备编码/通道编码} 或 {@code 通道编码}
     * @return 是否成功开启
     */
    public boolean start(String channelKey) {
        if (!props.isEnabled()) {
            log.warn("视觉识别未启用，拒绝开启: {}", channelKey);
            return false;
        }
        if (channelKey == null || channelKey.isBlank()) {
            return false;
        }
        if (running.containsKey(channelKey)) {
            log.debug("通道已在检测中，忽略重复开启: {}", channelKey);
            return true;
        }
        VisionDetector detector = registry.detector(props.getDefaultModel());
        if (detector == null) {
            log.warn("没有可用视觉模型，无法开启检测: {}", channelKey);
            return false;
        }
        String[] ids = splitKey(channelKey);
        String flvUrl = resolveFlvUrl(ids[0], ids[1]);
        if (flvUrl == null) {
            log.warn("取不到通道的 FLV 地址，无法开启检测: {}", channelKey);
            return false;
        }
        /*
         * 交给 ffmpeg 的必须是 HTTP-TS，不能是 HTTP-FLV。
         * ZLM 的 H.265 FLV 用传统 codecId=12（rtmpEnhanced=false，为了 JS 播放器），
         * 而 ffmpeg 7.0.2 的 flv demuxer 不支持它，只会刷
         * "Video codec (c) is not implemented" 并且一帧不出。
         * TS 原生支持 HEVC，同一个流实测 1 秒出图。详见 ZlmFrameSource 类注释。
         */
        String tsUrl = ZlmFrameSource.toTsUrl(flvUrl);

        VisionPipeline pipeline = new VisionPipeline(detector, pipelineConfig(), sink,
                new Roi(List.of()));
        ZlmFrameSource source = new ZlmFrameSource(channelKey, tsUrl, props.getFps());
        AutoCloseable detach = pipeline.attach(source);
        running.put(channelKey, new Running(pipeline, detach, props.getDefaultModel()));
        startedAt.put(channelKey, System.currentTimeMillis());
        stallWarned.remove(channelKey);
        log.info("视觉检测已开启: channel={} model={} url={}", channelKey, props.getDefaultModel(), tsUrl);
        return true;
    }

    /**
     * 关闭一路检测。
     *
     * @return 是否确实停了一路（本来没在跑返回 {@code false}）
     */
    public boolean stop(String channelKey) {
        Running r = running.remove(channelKey);
        if (r == null) {
            return false;
        }
        startedAt.remove(channelKey);
        stallWarned.remove(channelKey);
        try {
            r.detach.close();
        } catch (Exception e) {
            log.debug("关闭取帧失败: {} {}", channelKey, e.getMessage());
        }
        r.pipeline.close();
        sink.forget(channelKey);
        log.info("视觉检测已关闭: channel={}", channelKey);
        return true;
    }

    /** 当前在检测的通道。 */
    public List<Map<String, Object>> status() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, Running> e : running.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("channel", e.getKey());
            row.put("model", e.getValue().modelId);
            row.put("inferredFrames", e.getValue().pipeline.inferredFrames());
            row.put("droppedFrames", e.getValue().pipeline.droppedFrames());
            DbDetectionSink.Latest latest = sink.latestOf(e.getKey());
            row.put("lastFrameAt", latest == null ? 0 : latest.at());
            row.put("detections", latest == null ? 0 : latest.detections().size());
            row.put("counts", latest == null ? Map.of() : latest.counts());
            /*
             * 健康判定：只报「在跑」是不够的。
             *
             * 实测踩过的坑：ffmpeg 进程起来了、attach 成功了、runningCount=1，
             * 但一帧都不出 —— 从「在跑」这个字段完全看不出来。
             * 所以显式给出 healthy 与原因，让运维一眼看到问题。
             */
            long inferred = e.getValue().pipeline.inferredFrames();
            row.put("healthy", inferred > 0);
            if (inferred == 0) {
                row.put("hint", "已 attach 但从未产出推理帧。排查顺序："
                        + "① 日志里搜 'ffmpeg[' 看 ffmpeg 报什么；"
                        + "② TS 地址是否可达（curl 一下 /<app>/<stream>.live.ts）；"
                        + "③ 设备是否在推流。");
            }
            list.add(row);
        }
        return list;
    }

    /** 是否在检测。 */
    public boolean isRunning(String channelKey) {
        return channelKey != null && running.containsKey(channelKey);
    }

    /** 正在检测的通道数。 */
    public int runningCount() {
        return running.size();
    }

    /**
     * 正在检测的<b>通道编码</b>集合（不带设备编码）。
     *
     * <p>给 {@code StreamService.reapIdleStreams} 用：视觉检测是靠 ffmpeg 拉 TS 流工作的，
     * 但那条连接不算 ZLM 的「读者」，所以空闲流巡检会把正在检测的流当无人观看关掉，
     * 表现为「检测开了几十秒后自己停了」。把这些通道编码并进常开集合即可。
     *
     * <p>只返回通道编码是因为常开集合本来就是按通道编码匹配的。
     */
    public java.util.Set<String> runningChannelIds() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String key : running.keySet()) {
            if (key == null || key.isBlank()) {
                continue;
            }
            int slash = key.indexOf('/');
            out.add(slash < 0 ? key : key.substring(slash + 1));
        }
        return out;
    }

    @PreDestroy
    public void shutdown() {
        for (String key : new ArrayList<>(running.keySet())) {
            stop(key);
        }
        registry.closeAll();
    }

    // ==================== 内部 ====================

    private PipelineConfig pipelineConfig() {
        return new PipelineConfig(props.getFps(), props.getQueueCapacity(),
                props.getWorkerThreads(), props.getConfidenceThreshold(),
                props.getMinEventFrames(), props.getMinEventMillis(),
                props.getLabels());
    }

    /** 拆 {@code 设备编码/通道编码}；只有一段时两段相同（设备直连）。 */
    private static String[] splitKey(String key) {
        int slash = key.indexOf('/');
        if (slash < 0) {
            return new String[]{key, key};
        }
        return new String[]{key.substring(0, slash), key.substring(slash + 1)};
    }

    /**
     * 拿到通道的 HTTP-FLV 地址。
     *
     * <p>会话不存在时<b>先点播</b>：检测是长期行为，需要一路活流；
     * 与抓拍不同，这里不主动停流（可能有人在看，停流交给播放侧的空闲回收）。
     */
    /**
     * 拿到通道的 HTTP-FLV 地址。
     *
     * <h3>为什么要分两条路</h3>
     * 平台有两套「流」的概念，视觉检测必须都能处理：
     * <ul>
     *   <li><b>国标设备</b>（GB28181）—— 点播后会话登记在 {@code RtpSessionManager}，
     *       用 {@link StreamService#session} 取 {@code RtpSession}，再用
     *       {@code localFlvUrl} 拼本机地址。</li>
     *   <li><b>SDK 直连设备</b>（大华 / 海康）—— 它们不接 INVITE，点播走
     *       {@code DeviceMediaProvider}，结果只写 {@code GbStream}（数据库），
     *       <b>不进 {@code RtpSessionManager}</b>。所以对这类设备
     *       {@code streamService.session()} 永远返回 null，
     *       只认 RtpSession 会让「大华通道配了检测但一直起不来」。</li>
     * </ul>
     *
     * <p>回退方案直接用 {@code GbStream.httpFlv} —— 那是 {@code media.playUrls()}
     * 拼出来的地址，与前端播放器用的是同一个，一定能拉。
     * 注意它通常是公网/内网地址而非 127.0.0.1，同机访问也没问题。
     */
    private String resolveFlvUrl(String deviceId, String channelId) {
        try {
            // ① 国标设备：优先走 RtpSession（能拿本机 127.0.0.1 地址，省一次外网回环）
            RtpSession session = streamService.session(deviceId, channelId);
            if (session != null && RtpSession.ONLINE.equals(session.getState())) {
                String url = ffmpegSnapService.localFlvUrl(session);
                log.info("视觉检测：复用国标会话 channel={} url={}", channelId, url);
                return url;
            }

            // ② 点播一次（对 SDK 直连设备，这里会创建 GbStream 而不创建 RtpSession）
            log.info("视觉检测：通道无活流，先点播: device={} channel={}", deviceId, channelId);
            GbStream stream = streamService.startLive(deviceId, channelId);

            // ③ 点播后再查一次 RtpSession（国标设备这时应该有会话了）
            session = streamService.session(deviceId, channelId);
            if (session != null && RtpSession.ONLINE.equals(session.getState())) {
                String url = ffmpegSnapService.localFlvUrl(session);
                log.info("视觉检测：点播后取得国标会话 channel={} url={}", channelId, url);
                return url;
            }

            // ④ 回退：SDK 直连设备，用 GbStream 里已拼好的 FLV 地址
            if (stream != null && stream.getHttpFlv() != null
                    && !stream.getHttpFlv().isBlank()) {
                log.info("视觉检测：使用 SDK 直连设备的 FLV 地址 channel={} url={}",
                        channelId, stream.getHttpFlv());
                return stream.getHttpFlv();
            }

            log.warn("视觉检测：点播成功但拿不到 FLV 地址 channel={}（GbStream 无 httpFlv）", channelId);
            return null;
        } catch (RuntimeException e) {
            log.warn("视觉检测：取流失败 device={} channel={} {}", deviceId, channelId, e.getMessage());
            return null;
        }
    }
}
