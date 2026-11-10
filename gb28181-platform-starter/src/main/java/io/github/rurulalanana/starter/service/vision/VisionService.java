package io.github.rurulalanana.starter.service.vision;

import io.github.rurulalanana.starter.config.VisionProperties;
import io.github.rurulalanana.starter.entity.GbStream;
import io.github.rurulalanana.starter.entity.GbVisionChannel;
import io.github.rurulalanana.starter.mapper.GbVisionChannelMapper;
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
    private final GbVisionChannelMapper visionChannelMapper;

    /** 正在检测的通道：通道编码 → 运行中的一路。 */
    private final Map<String, Running> running = new ConcurrentHashMap<>();
    /** 每路 attach 的时刻（毫秒）：用于判断「多久没出帧才算卡住」。 */
    private final Map<String, Long> startedAt = new ConcurrentHashMap<>();

    /** 已就「零出帧」告警过的通道（每条只报一次，避免每 30 秒刷屏）。 */
    private final java.util.Set<String> stallWarned = ConcurrentHashMap.newKeySet();

    /**
     * 多久没新帧就判定「卡住」并主动重启这一路（毫秒）。
     *
     * <h3>为什么需要主动重启（真实故障）</h3>
     * 实测踩过：某一路检测「卡住」了一小时 —— ffmpeg 进程还在、
     * {@code runningCount} 正确、{@code inferredFrames} 是个大正数，
     * 界面上 {@code healthy=true}，但画面里的框早就不动了。
     * 那一小时里 {@code gb_alarm} 一条记录都没有（正常时几分钟一条）。
     *
     * <p>根因是两处判断都用了<b>累计值</b>：
     * <ul>
     *   <li>{@code healthy} 判 {@code inferredFrames > 0} —— 出过一帧就永远健康</li>
     *   <li>{@code warnIfStalled} 同样只看 {@code inferredFrames > 0}</li>
     * </ul>
     * 累计值只能回答「历史上有没有出过帧」，回答不了「现在还在不在出帧」。
     * 所以「卡住」这个失效方式<b>完全静默</b>，没有任何告警。
     *
     * <p>阈值取 45 秒：比已知的最长上游突发停顿（实测 3.85 秒）大一个量级，
     * 不会误判；而 45 秒不出帧对监控场景已经是「必须自愈」的量级。
     */
    private static final long RESTART_AFTER_SILENT_MS = 45_000;
    /** 一路检测的运行态。 */
    private static final class Running {
        final VisionPipeline pipeline;
        final AutoCloseable detach;
        final String modelId;
        /**
         * 取帧源：用来判「现在还在不在出帧」。
         *
         * <p>不能只靠 {@code pipeline.inferredFrames()}（累计值）：
         * 卡了一小时它照样是个大正数，看不出任何异常。
         */
        final ZlmFrameSource source;

        Running(VisionPipeline pipeline, AutoCloseable detach, String modelId,
                ZlmFrameSource source) {
            this.pipeline = pipeline;
            this.detach = detach;
            this.modelId = modelId;
            this.source = source;
        }

        /** 距上一帧过去了多久（毫秒）；取不到时返回 0（当作健康）。 */
        long silentMillis() {
            return source == null ? 0 : source.silentMillis();
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
        List<String> channels = configuredChannels();
        if (channels.isEmpty()) {
            log.info("视觉识别已启用但还没有配置任何通道，请在「AI 检测」页面添加");
            return;
        }
        // 先试一轮，失败的下一次 tick 再试（设备可能还没注册上来）
        retryPending();
    }

    /**
     * 要跑检测的通道清单。
     *
     * <h3>唯一来源是数据库</h3>
     * 通道配置<b>完全在平台界面维护</b>（{@code gb_vision_channel}），
     * 配置文件里不再有 {@code vision.channels}。
     *
     * <p>这是刻意的：曾经有过「yml 当种子、表为空时导入」的兼容逻辑，
     * 结果留下一个反复出问题的边界 —— 运维在界面上删掉通道，重启后
     * 因为表刚好空过又种回来；或者 yml 里写了一份、界面上是另一份，
     * 排查时根本不知道哪份在生效。现在只有一个来源，没有歧义。
     */
    private List<String> configuredChannels() {
        List<GbVisionChannel> rows = visionChannelMapper.selectList(null);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(rows.size());
        for (GbVisionChannel row : rows) {
            String key = row.channelKey();
            if (key != null && !key.isBlank()) {
                out.add(key);
            }
        }
        return out;
    }

    /** 当前生效的通道清单（给接口用，与 {@link #configuredChannels()} 同源）。 */
    public List<String> activeChannels() {
        return configuredChannels();
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
        List<String> channels = configuredChannels();
        if (channels.isEmpty()) {
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
     * 检查「卡住」的通道，并主动重启这一路。
     *
     * <h3>这个检查要抓的是哪种失效</h3>
     * 两种完全不同的失效，早先被混成了一个判断：
     * <ol>
     *   <li><b>从未出帧</b>：ffmpeg 起来了但一帧没出（编码不支持、TS 不可达）。
     *       {@code inferredFrames == 0}，能被累计值判断抓到。</li>
     *   <li><b>出过帧后卡住</b>：跑了很久突然不再出帧（ffmpeg 退出、
     *       上游断流、管道阻塞）。{@code inferredFrames} 是个大正数，
     *       <b>累计值判断完全看不出来</b> —— 这就是实测那次「卡了一小时」。</li>
     * </ol>
     *
     * <p>早先的实现只判 {@code inferredFrames() > 0}，所以第 2 种情况
     * 永远判成健康，日志里一条 WARN 都没有。
     *
     * <p>现在两种都按「距最后一帧多久」统一判断，并<b>主动重启</b>：
     * 视觉识别是无人值守的，发现了却只记日志，等于还是卡着。
     *
     * <p>重启用「先 stop 再 start」而不是原地重连：ffmpeg 进程可能已经
     * 处于异常状态（僵死、stdout 被阻塞），只重启拉流线程修不好。
     */
    private void warnIfStalled() {
        for (Map.Entry<String, Running> e : running.entrySet()) {
            String channel = e.getKey();
            Running r = e.getValue();
            long silent = r.silentMillis();
            /*
             * 阈值只有一个：RESTART_AFTER_SILENT_MS。
             *
             * 早先这里写成两段（120 秒告警 / 45 秒重启），结果 45~120 秒之间
             * 的停滞既不算「该重启」也不算「该清标记」—— 状态机卡在中间。
             * 只有一个阈值就不会有这种空洞。
             */
            if (silent <= RESTART_AFTER_SILENT_MS) {
                // 还在出帧，清掉告警标记（下次卡住能再报一次）
                stallWarned.remove(channel);
                continue;
            }
            /*
             * 超过阈值没新帧 = 卡住。先告警（每条通道只报一次，避免刷屏），
             * 然后重启这一路。
             */
            if (stallWarned.add(channel)) {
                log.warn("视觉检测已 {} 秒没有新帧，判定为卡住: channel={} "
                                + "累计推理={} 累计出帧={} —— 自动重启这一路",
                        silent / 1000, channel,
                        r.pipeline.inferredFrames(),
                        r.source == null ? -1 : r.source.producedFrames());
            }
            try {
                stop(channel);
                // 立刻重开；失败也不要紧，retryPending() 30 秒内会再试
                if (start(channel)) {
                    log.info("视觉检测已自愈重启: channel={}", channel);
                    stallWarned.remove(channel);
                } else {
                    log.warn("视觉检测自愈重启失败，交给 retryPending 重试: channel={}", channel);
                }
            } catch (RuntimeException ex) {
                log.warn("视觉检测自愈重启异常: channel={} {}", channel, ex.getMessage());
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
        /*
         * 用这一路绑定的场景，而不是全局默认模型。
         *
         * 一个通道可以只跑人形、另一个跑车辆：场景 id 存在
         * gb_vision_channel.scenario_id 里，由界面选择。
         * 没绑定（老数据 / 直接调 start 接口）时退回默认场景。
         */
        VisionProperties.Scenario scenario = scenarioOf(channelKey);
        String modelId = scenario == null ? props.getDefaultModel() : scenario.modelId();
        VisionDetector detector = registry.detector(modelId);
        if (detector == null) {
            log.warn("没有可用视觉模型，无法开启检测: channel={} model={}", channelKey, modelId);
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

        /*
         * 流水线的标签过滤按「场景」来，不是全局。
         *
         * 这样同一份 COCO 权重能当两种能力用：
         * 场景「人形」配 [person]、场景「车辆」配 [car,truck,bus,motorcycle]。
         * 传空列表表示场景没限制标签（全目标检测）。
         */
        List<String> scenarioLabels = scenario == null
                ? List.of() : registry.scenarioLabels(scenario.id());
        VisionPipeline pipeline = new VisionPipeline(detector,
                pipelineConfig(scenarioLabels), sink, new Roi(List.of()));
        ZlmFrameSource source = new ZlmFrameSource(channelKey, tsUrl, props.getFps());
        AutoCloseable detach = pipeline.attach(source);
        running.put(channelKey, new Running(pipeline, detach, modelId, source));
        startedAt.put(channelKey, System.currentTimeMillis());
        stallWarned.remove(channelKey);
        log.info("视觉检测已开启: channel={} model={} scenario={} labels={} url={}",
                channelKey, modelId,
                scenario == null ? "-" : scenario.id(),
                scenarioLabels.isEmpty() ? "全部" : scenarioLabels,
                tsUrl);
        return true;
    }

    /**
     * 某通道绑定的场景；没绑定或场景已不存在时返回 {@code null}。
     *
     * <p>每次 start 都查库而不是缓存：通道数量是个位数，
     * 而缓存会引入「界面上改了场景但运行的还是旧的」这类问题 ——
     * 改完必须重启才生效，运维很难意识到。
     */
    private VisionProperties.Scenario scenarioOf(String channelKey) {
        try {
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<GbVisionChannel> q =
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            String[] ids = splitKey(channelKey);
            q.eq("channel_id", ids[1]);
            List<GbVisionChannel> rows = visionChannelMapper.selectList(q);
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            String scenarioId = rows.get(0).getScenarioId();
            if (scenarioId == null || scenarioId.isBlank()) {
                return null;
            }
            VisionProperties.Scenario s = registry.scenario(scenarioId);
            if (s == null) {
                log.warn("通道绑定的场景不存在，退回默认: channel={} scenario={}", channelKey, scenarioId);
            }
            return s;
        } catch (RuntimeException e) {
            log.debug("读取通道场景失败，退回默认: {} {}", channelKey, e.getMessage());
            return null;
        }
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
            /*
             * 两类丢帧分开暴露：合并成一个数字时判断不出问题出在哪。
             * 背压丢帧多 = 推理追不上（或队列太小），是健康的；
             * 节流丢帧多 = 上游出帧比 fps 快，是配置意图。
             */
            row.put("backpressureDropped", e.getValue().pipeline.backpressureDropped());
            row.put("throttleDropped", e.getValue().pipeline.throttleDropped());
            DbDetectionSink.Latest latest = sink.latestOf(e.getKey());
            row.put("lastFrameAt", latest == null ? 0 : latest.at());
            row.put("detections", latest == null ? 0 : latest.detections().size());
            row.put("counts", latest == null ? Map.of() : latest.counts());
            /*
             * 健康判定：只看累计帧数是错的（踩过）。
             *
             * <p>实测故障：某一路卡了一小时，ffmpeg 进程在、attach 成功、
             * {@code inferredFrames} 是个大正数 —— 按累计值判就永远是 healthy=true，
             * 而画面里的框早就不动了。那一小时 {@code gb_alarm} 一条都没增加。
             *
             * <p>所以改成判「距最后一帧多久」：超过 {@link #RESTART_AFTER_SILENT_MS}
             * 就是不健康。这样卡住能被界面看到，而不是靠人盯画面。
             */
            long silent = e.getValue().silentMillis();
            boolean healthy = e.getValue().pipeline.inferredFrames() > 0
                    && silent <= RESTART_AFTER_SILENT_MS;
            row.put("healthy", healthy);
            row.put("silentMs", silent);
            if (!healthy) {
                row.put("hint", e.getValue().pipeline.inferredFrames() == 0
                        ? "已 attach 但从未产出推理帧。排查顺序："
                        + "① 日志里搜 'ffmpeg[' 看 ffmpeg 报什么；"
                        + "② TS 地址是否可达（curl 一下 /<app>/<stream>.live.ts）；"
                        + "③ 设备是否在推流。"
                        : "已 " + silent / 1000 + " 秒没有新帧（卡住）。"
                        + "系统会每 30 秒检查并自动重启这一路；持续反复请查设备推流与网络。");
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

    // ==================== 通道配置（界面用） ====================

    /**
     * 列出已配置的检测通道，并附带运行态。
     *
     * <p>把「配置」和「实际在跑」放在同一行返回，是因为这两者会不一致：
     * 设备离线、模型不可用、刚启动还没 attach 完成时，配置里有这一路
     * 但 {@code running=false}。分开两个接口查，界面要自己对齐，
     * 而这个对齐逻辑每个调用方都会写错一次。
     *
     * @return 每行含 channel / deviceId / channelId / remark / running / healthy 等
     */
    public List<Map<String, Object>> listConfigured() {
        List<GbVisionChannel> rows = visionChannelMapper.selectList(null);
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (GbVisionChannel row : rows) {
            String key = row.channelKey();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("deviceId", row.getDeviceId());
            item.put("channelId", row.getChannelId());
            item.put("channel", key);
            item.put("scenarioId", row.getScenarioId());
            /*
             * 把场景的展示名一起返回：界面上显示「人形检测」而不是
             * 「person-coco」这种 id，运维才看得懂自己在配什么。
             */
            VisionProperties.Scenario sc = registry.scenario(row.getScenarioId());
            item.put("scenarioName", sc == null ? null : sc.name());
            item.put("scenarioCategory", sc == null ? null : sc.category());
            item.put("remark", row.getRemark());
            item.put("createTime", row.getCreateTime() == null ? null
                    : row.getCreateTime().toString());

            Running r = key == null ? null : running.get(key);
            item.put("running", r != null);
            item.put("model", r == null ? null : r.modelId);
            item.put("inferredFrames", r == null ? 0 : r.pipeline.inferredFrames());
            item.put("droppedFrames", r == null ? 0 : r.pipeline.droppedFrames());
            item.put("backpressureDropped", r == null ? 0 : r.pipeline.backpressureDropped());
            item.put("throttleDropped", r == null ? 0 : r.pipeline.throttleDropped());
            DbDetectionSink.Latest latest = key == null ? null : sink.latestOf(key);
            item.put("lastFrameAt", latest == null ? 0 : latest.at());
            item.put("counts", latest == null ? Map.of() : latest.counts());
            /*
             * healthy 只对「在跑」的通道有意义。
             * 没在跑时给 false 而不是 null：前端少一个分支，
             * 且「配置了但没跑起来」本来就该显示成不健康。
             *
             * <p>与 {@link #status()} 同口径：判「现在还在不在出帧」，
             * 不是「历史上有没有出过帧」—— 后者卡住了也永远是 true。
             */
            long silent = r == null ? Long.MAX_VALUE : r.silentMillis();
            boolean healthy = r != null && r.pipeline.inferredFrames() > 0
                    && silent <= RESTART_AFTER_SILENT_MS;
            item.put("healthy", healthy);
            item.put("silentMs", r == null ? 0 : silent);
            if (r != null && !healthy) {
                item.put("hint", r.pipeline.inferredFrames() == 0
                        ? "已启动但还没有产出推理帧，请确认设备在线且在推流"
                        : "已 " + silent / 1000 + " 秒没有新帧（卡住），系统会自动重启这一路");
            }
            out.add(item);
        }
        return out;
    }

    /**
     * 新增一路检测通道（幂等：已存在直接返回成功）。
     *
     * <p>写入库后<b>立即尝试启动</b>，但启动失败不回滚配置 ——
     * 设备可能只是暂时离线，{@code retryPending()} 会在 30 秒内自动补上。
     * 如果这里因为「设备不在线」拒绝保存，运维就得等设备上线再来配一次。
     *
     * @param scenarioId 这一路跑哪个场景；为空时用默认场景
     * @return 是否为新插入（{@code false} 表示已存在）
     */
    public boolean addChannel(String deviceId, String channelId, String scenarioId, String remark) {
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException("通道编码不能为空");
        }
        String cid = channelId.trim();
        String did = deviceId == null || deviceId.isBlank() ? null : deviceId.trim();
        String sid = scenarioId == null || scenarioId.isBlank()
                ? registry.defaultScenarioId() : scenarioId.trim();

        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<GbVisionChannel> q =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        q.eq("channel_id", cid);
        Long exists = visionChannelMapper.selectCount(q);
        boolean inserted = exists == null || exists == 0;
        if (inserted) {
            GbVisionChannel row = new GbVisionChannel();
            row.setDeviceId(did);
            row.setChannelId(cid);
            row.setScenarioId(sid);
            row.setRemark(remark);
            visionChannelMapper.insert(row);
        }
        // 立即试一次；失败也无所谓，定时任务会补
        String key = did == null ? cid : did + "/" + cid;
        if (!running.containsKey(key)) {
            start(key);
        }
        return inserted;
    }

    /**
     * 修改某通道的场景。
     *
     * <p>先停再启：场景决定用哪个模型和哪套标签过滤，而这两者都是在
     * {@code start()} 里装配进流水线的，改配置不会自动生效。
     * 不重启的话，界面上显示已改成「车辆检测」，实际还在跑人形 ——
     * 这种「显示和实际不一致」最难排查，所以宁可断开重连一次。
     *
     * @return 是否确实改了（通道不存在返回 false）
     */
    public boolean updateChannelScenario(String channelId, String scenarioId) {
        if (channelId == null || channelId.isBlank()) {
            return false;
        }
        String cid = channelId.trim();
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<GbVisionChannel> q =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        q.eq("channel_id", cid);
        List<GbVisionChannel> rows = visionChannelMapper.selectList(q);
        if (rows == null || rows.isEmpty()) {
            return false;
        }
        GbVisionChannel row = rows.get(0);
        row.setScenarioId(scenarioId);
        visionChannelMapper.updateById(row);

        // 停掉再起，让新场景生效
        for (String key : new ArrayList<>(running.keySet())) {
            if (key.equals(cid) || key.endsWith("/" + cid)) {
                stop(key);
                start(key);
            }
        }
        return true;
    }

    /**
     * 删除一路检测通道：先从配置移除，再停掉正在跑的那一路。
     *
     * <p>顺序不能反。先停再删的话，如果删除失败，配置里还留着这一路，
     * 30 秒后 {@code retryPending()} 又把它启动起来 —— 用户会看到
     * 「点了删除，过一会儿又自己开了」。先删库，再停：即使停失败，
     * 配置已经没了，重试不会再启动它。
     *
     * @return 是否确实删掉了配置行
     */
    public boolean removeChannel(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return false;
        }
        String cid = channelId.trim();
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<GbVisionChannel> q =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        q.eq("channel_id", cid);
        List<GbVisionChannel> rows = visionChannelMapper.selectList(q);
        if (rows == null || rows.isEmpty()) {
            return false;
        }
        visionChannelMapper.delete(q);
        /*
         * 停掉所有指向这个通道编码的运行实例。
         *
         * 键是「设备编码/通道编码」，而删除是按通道编码删的，
         * 所以要把所有以它结尾的键都找出来 —— 不能只拼一个 key，
         * 否则设备编码猜错就停不掉，表现为「配置删了但还在跑」。
         */
        for (String key : new ArrayList<>(running.keySet())) {
            if (key.equals(cid) || key.endsWith("/" + cid)) {
                stop(key);
            }
        }
        return true;
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

    /**
     * 某个<b>通道编码</b>是否正在检测（不带设备编码）。
     *
     * <p>给 {@code Gb28181MediaService.onNoViewer} 用：ZLM 的无人观看回调只看
     * 它自己的读者数，而视觉取帧的 ffmpeg 拉流不算读者，于是正在检测的流会被
     * 当「无人观看」发 BYE 关掉，表现为检测「卡住」。
     *
     * <p>这里只按通道编码匹配（不带设备编码）：ZLM 回调给的是 app/stream
     * 解析出的会话，而 {@code running} 的键可能是 {@code 设备/通道} 也可能是
     * 裸 {@code 通道}（SDK 直连设备没有国标设备编码）。两种都要能命中。
     */
    public boolean isRunningChannel(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return false;
        }
        return runningChannelIds().contains(channelId);
    }

    @PreDestroy
    public void shutdown() {
        for (String key : new ArrayList<>(running.keySet())) {
            stop(key);
        }
        registry.closeAll();
    }

    // ==================== 内部 ====================

    /**
     * 流水线配置。
     *
     * @param scenarioLabels 场景的标签过滤（空 = 全部）。
     *                       由<b>场景</b>决定而不是全局配置：同一份 COCO 权重，
     *                       配 {@code [person]} 就是人形检测，配
     *                       {@code [car,truck,bus]} 就是车辆检测。
     *                       全局 {@code vision.labels} 已经移除 ——
     *                       多模型场景下「一个全局标签表」会把所有通道
     *                       锁成同一种能力，那样加模型就没意义了。
     */
    private PipelineConfig pipelineConfig(List<String> scenarioLabels) {
        return new PipelineConfig(props.getFps(), props.getQueueCapacity(),
                props.getWorkerThreads(), props.getConfidenceThreshold(),
                props.getMinEventFrames(), props.getMinEventMillis(),
                scenarioLabels == null ? List.of() : scenarioLabels);
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
