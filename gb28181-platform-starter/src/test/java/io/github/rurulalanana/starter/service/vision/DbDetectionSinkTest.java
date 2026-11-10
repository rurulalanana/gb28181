package io.github.rurulalanana.starter.service.vision;

import io.github.rurulalanana.vision.Box;
import io.github.rurulalanana.vision.Detection;
import io.github.rurulalanana.vision.Frame;
import io.github.rurulalanana.vision.VisionEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检测结果出口的测试。
 *
 * <p>重点在三条容易被忽略的行为：
 * <ol>
 *   <li><b>空结果也要覆盖写</b> —— 否则人走了框还在</li>
 *   <li><b>事件冷却</b> —— 否则站着不动的人每 2 秒刷一条报警</li>
 *   <li><b>处理器异常隔离</b> —— 存图失败不该吞掉告警</li>
 * </ol>
 */
class DbDetectionSinkTest {

    private static Frame frame(String sourceId) {
        return new Frame(new byte[]{1, 2, 3}, 1920, 1080, System.currentTimeMillis(), sourceId);
    }

    private static Detection person(float conf) {
        return new Detection("person", conf, new Box(0.1f, 0.2f, 0.3f, 0.4f));
    }

    private static VisionEvent event(String sourceId, String label) {
        long now = System.currentTimeMillis();
        return new VisionEvent(sourceId, label, now - 3000, now, 3000,
                List.of(person(0.8f)), frame(sourceId));
    }

    @Test
    void emptyDetectionsOverwritePrevious() {
        DbDetectionSink sink = new DbDetectionSink();
        sink.onDetections("ch1", frame("ch1"), List.of(person(0.9f)));
        assertEquals(1, sink.latestOf("ch1").detections().size());

        // 人走了：空列表必须覆盖掉上一次的框
        sink.onDetections("ch1", frame("ch1"), List.of());
        assertNotNull(sink.latestOf("ch1"));
        assertTrue(sink.latestOf("ch1").detections().isEmpty(), "空结果必须覆盖，否则前端框不消失");
        assertFalse(sink.latestOf("ch1").hasTarget());
    }

    @Test
    void countsByLabel() {
        DbDetectionSink sink = new DbDetectionSink();
        sink.onDetections("ch1", frame("ch1"),
                List.of(person(0.9f), person(0.8f),
                        new Detection("car", 0.7f, new Box(0.5f, 0.5f, 0.2f, 0.2f))));
        Map<String, Long> counts = sink.latestOf("ch1").counts();
        assertEquals(2L, counts.get("person"));
        assertEquals(1L, counts.get("car"));
    }

    @Test
    void eventIsDispatchedToHandlers() {
        DbDetectionSink sink = new DbDetectionSink();
        AtomicInteger handled = new AtomicInteger();
        sink.setEventHandlers(List.of(e -> handled.incrementAndGet()));

        sink.onEvent(event("ch1", "person"));
        assertEquals(1, handled.get());
    }

    @Test
    void cooldownSuppressesRepeatedEvents() {
        DbDetectionSink sink = new DbDetectionSink();
        sink.setCooldownMillis(60_000);
        AtomicInteger handled = new AtomicInteger();
        sink.setEventHandlers(List.of(e -> handled.incrementAndGet()));

        // 同一通道同一标签连续触发：只有第一条通过
        sink.onEvent(event("ch1", "person"));
        sink.onEvent(event("ch1", "person"));
        sink.onEvent(event("ch1", "person"));
        assertEquals(1, handled.get(), "冷却期内必须只处理一次");

        // 不同标签不受影响
        sink.onEvent(event("ch1", "car"));
        assertEquals(2, handled.get(), "不同标签应有独立冷却");
    }

    @Test
    void handlerFailureDoesNotBreakOthers() {
        DbDetectionSink sink = new DbDetectionSink();
        List<String> called = new ArrayList<>();
        sink.setEventHandlers(List.of(
                e -> {
                    throw new IllegalStateException("存图失败");
                },
                e -> called.add(e.label())));

        sink.onEvent(event("ch1", "person"));
        assertEquals(List.of("person"), called, "前一个处理器抛异常不能影响后一个");
    }

    @Test
    void forgetClearsState() {
        DbDetectionSink sink = new DbDetectionSink();
        sink.setCooldownMillis(60_000);
        sink.onDetections("ch1", frame("ch1"), List.of(person(0.9f)));
        sink.onEvent(event("ch1", "person"));
        assertNotNull(sink.latestOf("ch1"));

        sink.forget("ch1");
        assertNull(sink.latestOf("ch1"), "停检后必须清掉快照，否则前端一直显示最后一帧的框");

        // 冷却状态也要清：重新开启检测应立即能告警
        AtomicInteger handled = new AtomicInteger();
        sink.setEventHandlers(List.of(e -> handled.incrementAndGet()));
        sink.onEvent(event("ch1", "person"));
        assertEquals(1, handled.get(), "forget 后冷却应失效");
    }

    @Test
    void nullSourceIdIgnored() {
        DbDetectionSink sink = new DbDetectionSink();
        sink.onDetections(null, frame("x"), List.of(person(0.9f)));
        sink.onEvent(null);
        assertTrue(sink.allLatest().isEmpty());
    }
}
