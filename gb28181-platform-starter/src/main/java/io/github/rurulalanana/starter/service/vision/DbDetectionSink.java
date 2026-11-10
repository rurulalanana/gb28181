package io.github.rurulalanana.starter.service.vision;

import io.github.rurulalanana.vision.Detection;
import io.github.rurulalanana.vision.DetectionSink;
import io.github.rurulalanana.vision.Frame;
import io.github.rurulalanana.vision.VisionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 检测结果的出口：实时叠框走内存 + WebSocket，事件走数据库 + 截图 + 告警。
 *
 * <h3>为什么「每帧」和「事件」两条路径分开</h3>
 * {@link #onDetections} 每帧都调（1 fps × N 路 = 每秒 N 次），
 * 所以它<b>只碰内存</b>：写库会被高频小事务拖垮。
 * 前端要的「当前画面上的框」本质是最新快照，内存足够。
 *
 * <p>{@link #onEvent} 只在去抖后触发（同通道同标签 30 秒内最多一次），
 * 频率低，才适合落库 + 存图 + 发告警。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DbDetectionSink implements DetectionSink {

    /** 每路最新的检测快照（覆盖写，不追加）。 */
    private final Map<String, Latest> latest = new ConcurrentHashMap<>();

    /** 每路每标签的上次触发时间（冷却用）。 */
    private final Map<String, Long> lastFired = new ConcurrentHashMap<>();

    /**
     * 实时推送器（WebSocket）。
     *
     * <p>用 setter 注入而不是构造器：{@code VisionBroadcaster} 不依赖 sink，
     * 但用构造器会强制所有单测都去构造一个广播器（它会起定时线程，
     * 单测里既不必要也难清理）。这里允许为 {@code null}，表示「不推」。
     */
    private volatile VisionBroadcaster broadcaster;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBroadcaster(VisionBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    /**
     * 一路画面的最新检测结果。
     *
     * @param sourceId    通道编码
     * @param at          帧时间戳
     * @param detections  本帧的框（归一化坐标）
     * @param width       帧宽（前端换算像素用）
     * @param height      帧高
     */
    public record Latest(String sourceId, long at, List<Detection> detections,
                         int width, int height) {

        /** 该帧是否有检测目标。 */
        public boolean hasTarget() {
            return detections != null && !detections.isEmpty();
        }

        /** 按标签统计数量（前端「当前画面 N 人」用）。 */
        public Map<String, Long> counts() {
            if (detections == null || detections.isEmpty()) {
                return Map.of();
            }
            return detections.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            Detection::label, java.util.stream.Collectors.counting()));
        }
    }

    @Override
    public void onDetections(String sourceId, Frame frame, List<Detection> detections) {
        if (sourceId == null) {
            return;
        }
        /*
         * 不在这里过滤空结果：空列表也要覆盖写，
         * 否则前端会一直显示上一次的框（人已经走了框还在）。
         */
        latest.put(sourceId, new Latest(sourceId,
                frame == null ? System.currentTimeMillis() : frame.timestampMs(),
                detections == null ? List.of() : List.copyOf(detections),
                frame == null ? 0 : frame.width(),
                frame == null ? 0 : frame.height()));

        /*
         * 同步喂给实时推送器（WebSocket 用）。
         *
         * 这一步<b>只写内存</b>，不序列化、不发网络：它跑在推理线程上，
         * 任何阻塞都会直接拉低帧率。真正的推送由 VisionBroadcaster 的
         * 独立线程按固定节拍合并后发出。
         *
         * 推送器为 null 表示它没被装配（比如脱离 Spring 的单测），
         * 此时静默跳过 —— 内存快照本身已经写好了，REST 轮询照样能用。
         */
        VisionBroadcaster b = broadcaster;
        if (b != null) {
            b.onDetections(sourceId,
                    frame == null ? System.currentTimeMillis() : frame.timestampMs(),
                    frame == null ? 0 : frame.width(),
                    frame == null ? 0 : frame.height(),
                    detections);
        }
    }

    @Override
    public void onEvent(VisionEvent event) {
        if (event == null || event.sourceId() == null) {
            return;
        }
        String key = event.sourceId() + '\u0000' + event.label();
        long now = System.currentTimeMillis();
        Long prev = lastFired.put(key, now);
        if (prev != null && now - prev < cooldownMillis) {
            log.debug("视觉事件在冷却期内，跳过: source={} label={} 距上次 {}ms",
                    event.sourceId(), event.label(), now - prev);
            return;
        }
        /*
         * 这里只做「记录 + 交给后续处理器」。落库/截图/告警由
         * VisionEventRecorder 做 —— sink 保持无副作用，方便单测替换。
         */
        log.info("视觉事件: source={} label={} count={} conf={} 持续={}ms",
                event.sourceId(), event.label(), event.count(),
                String.format("%.2f", event.maxConfidence()), event.sustainedMs());
        for (EventHandler handler : handlers) {
            try {
                handler.handle(event);
            } catch (RuntimeException e) {
                // 单个处理器失败不能影响其他处理器（比如存图失败不该吞掉告警）
                log.warn("视觉事件处理失败: handler={} {}", handler.getClass().getSimpleName(),
                        e.getMessage());
            }
        }
    }

    /** 事件处理器（落库、存图、告警各自独立）。 */
    public interface EventHandler {
        void handle(VisionEvent event);
    }

    /** 注册的处理器。用 setter 注入避免构造器循环依赖。 */
    private volatile List<EventHandler> handlers = List.of();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEventHandlers(List<EventHandler> handlers) {
        this.handlers = handlers == null ? List.of() : new ArrayList<>(handlers);
        log.info("视觉事件处理器已注册: {}", this.handlers.size());
    }

    /** 事件冷却时长（毫秒），由 VisionProperties 提供；这里给默认值便于单测。 */
    private long cooldownMillis = 30_000;

    public void setCooldownMillis(long millis) {
        this.cooldownMillis = millis <= 0 ? 0 : millis;
    }

    /** 取某路最新结果；没有返回 {@code null}。 */
    public Latest latestOf(String sourceId) {
        return sourceId == null ? null : latest.get(sourceId);
    }

    /** 所有路的最新结果（REST 列表用）。 */
    public List<Latest> allLatest() {
        return new ArrayList<>(latest.values());
    }

    /** 通道停检时清掉它的快照，避免前端一直显示最后一帧的框。 */
    public void forget(String sourceId) {
        if (sourceId == null) {
            return;
        }
        latest.remove(sourceId);
        /*
         * 冷却状态也要清：重新开启检测时应当立即能告警。
         * 键是 sourceId + '\u0000' + label，用前缀匹配即可。
         */
        String prefix = sourceId + '\u0000';
        lastFired.keySet().removeIf(k -> k.startsWith(prefix));
        /*
         * 推送器的待推状态同样要清，否则前端会一直显示最后一帧的框
         * （通道已经停了，但广播器里还留着最后一次的结果）。
         */
        VisionBroadcaster b = broadcaster;
        if (b != null) {
            b.forget(sourceId);
        }
    }
}
