package io.github.rurulalanana.vision.pipeline;

import io.github.rurulalanana.vision.DetectorConfig;
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
 * 流水线配置。
 *
 * @param fps                 每路目标帧率（推理节流用；无 GPU 时建议 1）
 * @param queueCapacity       每路队列容量（满了丢最旧的帧，不堆积）
 * @param workerThreads       推理线程数（CPU 推理建议 ≤ 核数的一半）
 * @param confidenceThreshold 置信度阈值（低于此值的检测直接丢弃）
 * @param minEventFrames      事件触发所需的最少连续帧数（去抖）
 * @param minEventMillis      事件触发所需的最短持续时长（毫秒，去抖）
 */
public record PipelineConfig(
        double fps,
        int queueCapacity,
        int workerThreads,
        float confidenceThreshold,
        int minEventFrames,
        long minEventMillis) {

    /** 合理默认值：1 fps、队列 4、线程 2、阈值 0.5、连续 3 帧 / 持续 2 秒。 */
    public static PipelineConfig defaults() {
        return new PipelineConfig(1.0, 4, 2, 0.5f, 3, 2000L);
    }

    /** 每帧之间的最小间隔（毫秒）。 */
    public long minIntervalMs() {
        if (fps <= 0) {
            return 0L;
        }
        return (long) (1000.0 / fps);
    }
}
