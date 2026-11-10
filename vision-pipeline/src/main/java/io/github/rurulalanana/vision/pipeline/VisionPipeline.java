package io.github.rurulalanana.vision.pipeline;

import io.github.rurulalanana.vision.Detection;
import io.github.rurulalanana.vision.DetectionSink;
import io.github.rurulalanana.vision.Frame;
import io.github.rurulalanana.vision.VisionDetector;
import io.github.rurulalanana.vision.VisionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 视觉识别流水线：帧泵 → 推理 → 事件聚合 → 结果出口。
 *
 * <p><b>只依赖 vision-api</b>：不认识具体模型（通过 {@link VisionDetector} 接口）、
 * 不认识取帧来源（通过 {@link FrameSource} 接口）、不认识结果去向
 * （通过 {@link DetectionSink} 接口）。业务层实现后两个接口即可接入。
 *
 * <p><b>背压策略</b>：每路一个有界队列，满了丢最旧的帧 —— 实时识别宁可丢帧，
 * 也不能让延迟无限累积。丢帧计数可通过 {@link #droppedFrames()} 观测。
 *
 * <p><b>节流</b>：按 {@link PipelineConfig#fps()} 限制每路推理频率，
 * 无 GPU 时建议 1 fps。
 */
public final class VisionPipeline implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(VisionPipeline.class);

    private final VisionDetector detector;
    private final PipelineConfig config;
    private final DetectionSink sink;
    private final EventAggregator aggregator;
    private final ExecutorService workers;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong droppedFrames = new AtomicLong();

    /**
     * 因「排空积压」丢掉的帧数（背压）。
     *
     * <h3>为什么要和节流丢帧分开计数</h3>
     * 两者含义完全不同，合并成一个数字时根本判断不出问题出在哪：
     * <ul>
     *   <li><b>背压丢弃</b>：推理追不上取帧，队列里积了好几帧，
     *       只留最新一帧。这是<b>健康且必要</b>的 —— 实时叠框只需要最新画面。</li>
     *   <li><b>节流丢弃</b>：帧到得比目标间隔还快，按 fps 主动限速。
     *       这是<b>配置意图</b>，丢得多说明上游出帧比 fps 快。</li>
     * </ul>
     * 实测：两路都稳定在 3.0 fps（配置是 4.0），而推理线程只占 13.8% CPU ——
     * 算力根本不是瓶颈。把两类计数分开后才能确认到底是哪边在丢。
     */
    private final AtomicLong backpressureDropped = new AtomicLong();

    /** 因「未到目标间隔」丢掉的帧数（节流）。 */
    private final AtomicLong throttleDropped = new AtomicLong();

    private final AtomicLong inferredFrames = new AtomicLong();

    /**
     * 令牌桶容量（单位：帧）。
     *
     * <p>2 而不是 1：留 1 帧的抖动余量。容量 1 会退化成「固定最小间隔」，
     * 而那种判定在抖动下会系统性少算（详见 {@code consume()} 里的实测数据）。
     *
     * <p>也不要放大到 4 以上：桶越大，ffmpeg 重启一次性吐多帧时
     * 能被连续推下去的帧就越多，CPU 会出现尖刺。
     */
    private static final double TOKEN_BUCKET_CAPACITY = 2.0;

    /** 单路的队列与节流状态。 */
    private static final class Lane {
        final ArrayBlockingQueue<Frame> queue;
        final AtomicLong lastInferMs = new AtomicLong(0);
        /**
         * 令牌桶余额（单位：帧）。只由该 lane 的唯一消费线程读写，无需同步。
         *
         * <p>容量 2 而不是 1：允许「1 帧」的抖动余量。
         * 用 1 的话就退化成固定最小间隔，抖动下会系统性少算（见 consume 注释）。
         */
        double tokens = 2.0;

        Lane(int capacity) {
            this.queue = new ArrayBlockingQueue<>(Math.max(1, capacity));
        }
    }

    public VisionPipeline(VisionDetector detector, PipelineConfig config, DetectionSink sink, Roi roi) {
        if (detector == null || sink == null) {
            throw new IllegalArgumentException("detector 与 sink 不能为空");
        }
        this.detector = detector;
        this.config = config == null ? PipelineConfig.defaults() : config;
        this.sink = sink;
        this.aggregator = new EventAggregator(this.config, roi);
        this.workers = Executors.newFixedThreadPool(Math.max(1, this.config.workerThreads()),
                r -> {
                    Thread t = new Thread(r, "vision-infer");
                    t.setDaemon(true);
                    return t;
                });
    }

    /**
     * 接入一路帧来源。
     *
     * @param source 帧来源
     * @return 停止句柄（关闭后停止该路）
     */
    public AutoCloseable attach(FrameSource source) {
        if (source == null) {
            throw new IllegalArgumentException("source 不能为空");
        }
        Lane lane = new Lane(config.queueCapacity());
        running.set(true);
        AutoCloseable handle = source.start(frame -> offer(lane, frame));
        // 消费循环：从队列取帧 → 节流 → 推理 → 聚合 → 出口
        workers.submit(() -> consume(source.sourceId(), lane));
        return () -> {
            try {
                handle.close();
            } catch (Exception e) {
                log.debug("帧来源关闭异常: {}", e.getMessage());
            }
            aggregator.clear(source.sourceId());
        };
    }

    /** 入队（满了丢最旧的）。 */
    private void offer(Lane lane, Frame frame) {
        if (frame == null || !frame.usable()) {
            return;
        }
        if (!lane.queue.offer(frame)) {
            lane.queue.poll();
            droppedFrames.incrementAndGet();
            backpressureDropped.incrementAndGet();
            lane.queue.offer(frame);
        }
    }

    /** 消费循环。 */
    private void consume(String sourceId, Lane lane) {
        while (running.get()) {
            Frame frame;
            try {
                frame = lane.queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (frame == null) {
                continue;
            }
            /*
             * 排空积压，只保留最新一帧。
             *
             * <h3>这是「框比画面慢好几秒」的根因修复</h3>
             * 队列里装的是<b>同一个画面在时间上的一串快照</b>，对实时叠框来说
             * 只有最新那帧有意义。早先的消费方式是严格 FIFO：推理只要比取帧慢
             * 一点点，队列就长期处于满的状态，消费端于是永远在处理
             * 「queueCapacity 帧之前」的画面 —— 积压量固定在那里，不会自愈。
             *
             * <p>实测（fps=4、queueCapacity=4、1280×720 H.265 两路）：
             * 第二路结果的中位陈旧度 <b>1553ms</b>、最坏 <b>3465ms</b>，
             * 而单帧推理只要 60~90ms。也就是说延迟几乎全部来自排队，
             * 不是模型慢。这也解释了为什么提高 fps 反而更糟：帧进得越快，
             * 积压的绝对量越大。
             *
             * <p>排空后队列深度恒在 0~1，陈旧度 ≈ 单帧推理耗时。
             * 被丢掉的帧计入 droppedFrames，如实反映「为了实时性牺牲了多少帧」。
             */
            Frame newer;
            while ((newer = lane.queue.poll()) != null) {
                droppedFrames.incrementAndGet();
                backpressureDropped.incrementAndGet();
                frame = newer;
            }
            /*
             * 节流：用<b>令牌桶</b>而不是「固定最小间隔」。
             *
             * <h3>为什么固定最小间隔是错的（实测数据）</h3>
             * 早先用 {@code now - last < minGap * 0.9} 判定。fps=1 时能工作
             * （间隔 1000ms，容差 100ms 远大于抖动），但 fps=4 时失效：
             * 目标间隔 250ms、容差只剩 25ms，而 {@code -vf fps=4.0} 的输出
             * 抖动就有几十毫秒。更关键的是<b>丢帧后基准时间不前移</b> ——
             * 下一帧要等间隔<b>变大</b>才通过，于是形成「过 1 丢 1」的稳定节奏，
             * 系统性少算。
             *
             * <p>实测（两路，60 秒窗口，8 核机器）：
             * <pre>
             *   通道 1  推理 2.85 fps（目标 4.0）  背压丢 0    节流丢 70
             *   通道 2  推理 1.52 fps（目标 4.0）  背压丢 51   节流丢 98
             * </pre>
             * 而推理线程只占 <b>13.8% CPU</b> —— 算力根本不是瓶颈，
             * 丢掉的帧全是节流误杀的。
             *
             * <h3>令牌桶为什么对</h3>
             * 每秒补充 {@code fps} 个令牌，桶容量 2：
             * <ul>
             *   <li>帧到得正好（250ms）时余额恒 ≥ 1，<b>全部通过</b></li>
             *   <li>帧到得比目标快（如 25fps）时，长期平均仍被锁在 fps</li>
             *   <li>短暂突发（ffmpeg 重启吐多帧）最多补推 2 帧，不会雪崩</li>
             * </ul>
             * 关键在于它限制的是<b>长期平均速率</b>，而不是每一帧的间隔 ——
             * 后者在抖动下无法区分「抖动」和「真的太快」。
             */
            long now = System.currentTimeMillis();
            long last = lane.lastInferMs.get();
            long elapsedMs = last <= 0 ? 0 : Math.max(0, now - last);
            // 补充令牌：按流逝时间线性补，上限 = 桶容量
            double rate = config.fps() > 0 ? config.fps() : 1.0;
            lane.tokens = Math.min(TOKEN_BUCKET_CAPACITY,
                    lane.tokens + elapsedMs * rate / 1000.0);
            if (lane.tokens < 1.0) {
                droppedFrames.incrementAndGet();
                throttleDropped.incrementAndGet();
                /*
                 * 不能更新 lastInferMs：令牌是按「距上次推理」补的，
                 * 这里更新会让被丢掉的这段时间白白清零，又退回少算。
                 */
                continue;
            }
            lane.tokens -= 1.0;
            lane.lastInferMs.set(now);

            List<Detection> detections;
            try {
                detections = detector.detect(frame);
            } catch (RuntimeException e) {
                log.warn("推理失败: source={} err={}", sourceId, e.getMessage());
                continue;
            }
            inferredFrames.incrementAndGet();
            /*
             * 阈值 + 标签双重过滤。
             *
             * 标签过滤是「只检测人形」的实现方式：COCO 80 类里还有 car / truck /
             * bench，不过滤的话画面里的车、椅子都会触发事件，告警中心会被淹没。
             *
             * 注意 config.acceptsLabel() 在 labels 为空时返回 true ——
             * 未配置过滤就是「什么都保留」，不会静默变成「什么都检测不到」。
             */
            List<Detection> filtered = detections.stream()
                    .filter(d -> d.above(config.confidenceThreshold()))
                    .filter(d -> config.acceptsLabel(d.label()))
                    .toList();
            /*
             * 先聚合出事件，再递逐帧结果。
             *
             * 顺序不能反：事件里的 frame 就是这一帧，业务层可能拿它去存截图；
             * 而 onDetections 只用于实时叠框（覆盖最新一帧）。
             *
             * 早先这里把 aggregator.accept() 的返回值直接丢弃了 ——
             * 去抖算了半天没人用，业务层只能按帧落库，等于去抖没生效。
             */
            List<VisionEvent> events = aggregator.accept(sourceId, frame, filtered);
            for (VisionEvent event : events) {
                try {
                    sink.onEvent(event);
                } catch (RuntimeException e) {
                    log.warn("事件出口异常: source={} label={} err={}",
                            sourceId, event.label(), e.getMessage());
                }
            }
            // 结果出口（业务层落库 / 推前端 / 发告警）
            try {
                sink.onDetections(sourceId, frame, filtered);
            } catch (RuntimeException e) {
                log.warn("结果出口异常: source={} err={}", sourceId, e.getMessage());
            }
        }
    }

    /** 累计丢帧数（背压 + 节流）。 */
    public long droppedFrames() {
        return droppedFrames.get();
    }

    /** 其中因背压（排空积压）丢掉的帧数。 */
    public long backpressureDropped() {
        return backpressureDropped.get();
    }

    /** 其中因节流（未到目标间隔）丢掉的帧数。 */
    public long throttleDropped() {
        return throttleDropped.get();
    }

    /** 累计推理帧数。 */
    public long inferredFrames() {
        return inferredFrames.get();
    }

    @Override
    public void close() {
        running.set(false);
        workers.shutdownNow();
    }
}
