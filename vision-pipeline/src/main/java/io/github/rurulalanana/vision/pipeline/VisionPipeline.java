package io.github.rurulalanana.vision.pipeline;

import io.github.rurulalanana.vision.Detection;
import io.github.rurulalanana.vision.DetectionSink;
import io.github.rurulalanana.vision.Frame;
import io.github.rurulalanana.vision.VisionDetector;
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
    private final AtomicLong inferredFrames = new AtomicLong();

    /** 单路的队列与节流状态。 */
    private static final class Lane {
        final ArrayBlockingQueue<Frame> queue;
        final AtomicLong lastInferMs = new AtomicLong(0);

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
            // 节流：未到间隔的帧直接丢弃（实时性优先于完整性）
            long now = System.currentTimeMillis();
            long last = lane.lastInferMs.get();
            if (now - last < config.minIntervalMs()) {
                droppedFrames.incrementAndGet();
                continue;
            }
            lane.lastInferMs.set(now);

            List<Detection> detections;
            try {
                detections = detector.detect(frame);
            } catch (RuntimeException e) {
                log.warn("推理失败: source={} err={}", sourceId, e.getMessage());
                continue;
            }
            inferredFrames.incrementAndGet();
            // 阈值过滤
            List<Detection> filtered = detections.stream()
                    .filter(d -> d.above(config.confidenceThreshold()))
                    .toList();
            // 事件聚合（内部再做 ROI 过滤）
            aggregator.accept(sourceId, frame, filtered);
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
