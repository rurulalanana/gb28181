package io.github.rurulalanana.vision.pipeline;

import io.github.rurulalanana.vision.Detection;
import io.github.rurulalanana.vision.Frame;
import io.github.rurulalanana.vision.VisionEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <h3>返回的是完整事件，不是标签字符串</h3>
 * 早先 {@link #accept} 只返回 {@code List<String>}（标签），调用方拿不到触发时的
 * 检测框和帧 —— 于是既没法存截图，也没法落库，返回值最后被丢弃，
 * <b>去抖等于没生效</b>。现在返回 {@link VisionEvent}，业务层能直接用它落库 + 存图。
 *
 * <p>线程安全：同一 sourceId 的帧由单个工作线程串行处理；
 * 不同 sourceId 之间用 {@link ConcurrentHashMap} 隔离。
 */
public final class EventAggregator {

    /**
     * key 分隔符用 {@code \u0000} 而不是 {@code '|'}。
     *
     * <p>通道编码理论上可能含 {@code '|'}（厂商自定义编码），用竖线拼 key 会误伤：
     * {@code clear("a|b")} 会把 {@code "a"} 来源的状态一起清掉。
     */
    private static final char SEP = '\u0000';

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
     * 喂一帧的检测结果，返回本帧<b>新触发</b>的事件（可能为空）。
     *
     * @param sourceId   来源标识
     * @param frame      当前帧
     * @param detections 本帧检测结果（已过滤阈值）
     * @return 本帧新触发的事件；同一标签在一次「出现」期间只返回一次
     */
    public List<VisionEvent> accept(String sourceId, Frame frame, List<Detection> detections) {
        List<Detection> inRoi = roi.filter(detections);
        long now = frame != null ? frame.timestampMs() : System.currentTimeMillis();

        // 本帧出现的标签 → 该标签下的框（触发时要随事件带出去）
        Map<String, List<Detection>> byLabel = new HashMap<>();
        for (Detection d : inRoi) {
            byLabel.computeIfAbsent(d.label(), k -> new ArrayList<>()).add(d);
        }

        List<VisionEvent> fired = new ArrayList<>();
        for (Map.Entry<String, List<Detection>> e : byLabel.entrySet()) {
            String label = e.getKey();
            State s = states.computeIfAbsent(key(sourceId, label), k -> new State());
            if (s.consecutiveFrames == 0) {
                s.firstSeenMs = now;
            }
            s.consecutiveFrames++;
            long sustained = now - s.firstSeenMs;
            if (!s.fired
                    && s.consecutiveFrames >= config.minEventFrames()
                    && sustained >= config.minEventMillis()) {
                s.fired = true;
                fired.add(new VisionEvent(sourceId, label, s.firstSeenMs, now, sustained,
                        List.copyOf(e.getValue()), frame));
            }
        }
        // 本帧消失的标签：清零（下次重新计数）
        Set<String> present = byLabel.keySet();
        for (Map.Entry<String, State> e : states.entrySet()) {
            String[] parts = split(e.getKey());
            if (!parts[0].equals(sourceId) || present.contains(parts[1])) {
                continue;
            }
            e.getValue().consecutiveFrames = 0;
            e.getValue().fired = false;
        }
        return fired;
    }

    /** 清理某来源的全部状态（通道下线时调用）。 */
    public void clear(String sourceId) {
        String id = sourceId == null ? "" : sourceId;
        states.keySet().removeIf(k -> split(k)[0].equals(id));
    }

    /** 当前跟踪的来源数量（排障用）。 */
    public int trackedSources() {
        Set<String> ids = new HashSet<>();
        for (String k : states.keySet()) {
            ids.add(split(k)[0]);
        }
        return ids.size();
    }

    private static String key(String sourceId, String label) {
        return (sourceId == null ? "" : sourceId) + SEP + label;
    }

    /** 拆回 {@code [sourceId, label]}；格式异常时返回原 key + 空串，不抛异常。 */
    private static String[] split(String key) {
        int i = key.indexOf(SEP);
        return i < 0
                ? new String[]{key, ""}
                : new String[]{key.substring(0, i), key.substring(i + 1)};
    }
}
