package io.github.rurulalanana.vision.pipeline;

import java.util.List;

/**
 * 流水线配置。
 *
 * @param fps                 每路目标帧率（推理节流用；无 GPU 时建议 1）
 * @param queueCapacity       每路队列容量（满了丢最旧的帧，不堆积）
 * @param workerThreads       推理线程数（CPU 推理建议 ≤ 核数的一半）
 * @param confidenceThreshold 置信度阈值（低于此值的检测直接丢弃）
 * @param minEventFrames      事件触发所需的最少连续帧数（去抖）
 * @param minEventMillis      事件触发所需的最短持续时长（毫秒，去抖）
 * @param labels              只保留这些标签（空 = 全部）。用于「只检测人形」
 */
public record PipelineConfig(
        double fps,
        int queueCapacity,
        int workerThreads,
        float confidenceThreshold,
        int minEventFrames,
        long minEventMillis,
        List<String> labels) {

    /** 6 参数兼容构造器：不过滤标签（保留全部）。 */
    public PipelineConfig(double fps, int queueCapacity, int workerThreads,
                          float confidenceThreshold, int minEventFrames, long minEventMillis) {
        this(fps, queueCapacity, workerThreads, confidenceThreshold,
                minEventFrames, minEventMillis, List.of());
    }

    /** 紧凑构造器：labels 为 null 时归一成空列表，避免下游判空。 */
    public PipelineConfig {
        labels = labels == null ? List.of() : List.copyOf(labels);
    }

    /** 合理默认值：1 fps、队列 4、线程 2、阈值 0.5、连续 3 帧 / 持续 2 秒、全部标签。 */
    public static PipelineConfig defaults() {
        return new PipelineConfig(1.0, 4, 2, 0.5f, 3, 2000L, List.of());
    }

    /**
     * 某个标签是否要保留。
     *
     * <p>未配置 labels 时全部保留 —— 否则「默认配置」会变成「什么都检测不到」，
     * 这类静默失效最难排查。
     */
    public boolean acceptsLabel(String label) {
        if (labels.isEmpty()) {
            return true;
        }
        return label != null && labels.contains(label);
    }

    /** 每帧之间的最小间隔（毫秒）。 */
    public long minIntervalMs() {
        if (fps <= 0) {
            return 0L;
        }
        return (long) (1000.0 / fps);
    }
}
