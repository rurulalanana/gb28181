package io.github.rurulalanana.vision.pipeline;

import io.github.rurulalanana.vision.Detection;
import io.github.rurulalanana.vision.Frame;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 事件聚合：把逐帧检测聚合成「事件」（去抖）。
 *
 * <p><b>为什么必须有这一层</b>：单帧检测会抖动（上一帧有、下一帧没有）。
 * 直接按帧落库/告警会刷屏。聚合规则：同一标签连续出现 {@code minEventFrames} 帧
 * 且持续 {@code minEventMillis} 毫秒，才算一个事件；事件期间只报一次。
 *
 * <p><b>为什么按标签聚合而不是按目标跟踪</b>：目标跟踪（trackId）需要跨帧关联，
 * 是另一个量级的功能。先按标签聚合能覆盖「区域有人 / 有火 / 有车」这类
 * 场景告警；需要「某个人滞留多久」时再加跟踪层。
 *
 * <p>线程安全：同一 sourceId 的帧由单个工作线程串行处理，
 * 不同 sourceId 之间用 {@link ConcurrentHashMap} 隔离。
 */
public final class EventAggregator {

    private final PipelineConfig config;
    private final Roi roi;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    /** 单个标签的聚合状态。 */
    private static final class State {
        int consecutiveFrames;
        long firstSeenMs;
        boolean fired;
    }

    public EventAggregator(PipelineConfig config, Roi roi) {
        this.config = config;
        this.roi = roi == null ? new Roi(List.of()) : roi;
    }

    /**
     * 喂一帧的检测结果，返回本帧触发的事件标签（可能为空）。
     *
     * @param sourceId 来源标识
     * @param frame    当前帧
     * @param detections 本帧检测结果（已过滤阈值）
     * @return 本帧新触发的事件标签列表
     */
    public List<String> accept(String sourceId, Frame frame, List<Detection> detections) {
        List<Detection> inRoi = roi.filter(detections);
        long now = frame != null ? frame.timestampMs() : System.currentTimeMillis();
        java.util.Set<String> present = new java.util.HashSet<>();
        for (Detection d : inRoi) {
            present.add(d.label());
        }

        java.util.List<String> fired = new java.util.ArrayList<>();
        // 本帧出现的标签：累加
        for (String label : present) {
            State s = states.computeIfAbsent(sourceId + "|" + label, k -> new State());
            if (s.consecutiveFrames == 0) {
                s.firstSeenMs = now;
            }
            s.consecutiveFrames++;
            if (!s.fired
                    && s.consecutiveFrames >= config.minEventFrames()
                    && now - s.firstSeenMs >= config.minEventMillis()) {
                s.fired = true;
                fired.add(label);
            }
        }
        // 本帧消失的标签：清零（下次重新计数）
        for (Map.Entry<String, State> e : states.entrySet()) {
            if (!e.getKey().startsWith(sourceId + "|")) {
                continue;
            }
            String label = e.getKey().substring(sourceId.length() + 1);
            if (!present.contains(label)) {
                e.getValue().consecutiveFrames = 0;
                e.getValue().fired = false;
            }
        }
        return fired;
    }

    /** 清理某来源的全部状态（通道下线时调用）。 */
    public void clear(String sourceId) {
        states.keySet().removeIf(k -> k.startsWith(sourceId + "|"));
    }
}
