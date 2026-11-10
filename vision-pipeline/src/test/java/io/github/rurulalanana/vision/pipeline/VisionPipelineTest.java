package io.github.rurulalanana.vision.pipeline;

import io.github.rurulalanana.vision.Box;
import io.github.rurulalanana.vision.Detection;
import io.github.rurulalanana.vision.DetectionSink;
import io.github.rurulalanana.vision.Frame;
import io.github.rurulalanana.vision.VisionDetector;
import io.github.rurulalanana.vision.VisionEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流水线单测：用假检测器与假帧来源验证「节流、背压、ROI、事件聚合」，
 * 不依赖模型文件与网络。
 */
class VisionPipelineTest {

    /** 恒返回 person 的假检测器。 */
    private static final class FakeDetector implements VisionDetector {
        @Override
        public String modelId() {
            return "fake";
        }

        @Override
        public List<String> labels() {
            return List.of("person");
        }

        @Override
        public List<Detection> detect(Frame frame) {
            return List.of(new Detection("person", 0.9f, new Box(0.1f, 0.1f, 0.2f, 0.3f)));
        }
    }

    private Frame frame(String sourceId) {
        return new Frame(new byte[]{1, 2, 3}, 320, 240, System.currentTimeMillis(), sourceId);
    }

    @Test
    void roiContains() {
        Roi roi = new Roi(List.of(
                new Roi.Point(0.2f, 0.2f), new Roi.Point(0.8f, 0.2f),
                new Roi.Point(0.8f, 0.8f), new Roi.Point(0.2f, 0.8f)));
        assertTrue(roi.configured());
        assertTrue(roi.contains(new Box(0.4f, 0.4f, 0.1f, 0.1f)), "中心在区域内");
        assertFalse(roi.contains(new Box(0f, 0f, 0.05f, 0.05f)), "中心在区域外");
        assertTrue(new Roi(List.of()).contains(new Box(0f, 0f, 0.05f, 0.05f)), "未配置区域视为全画面");
    }

    @Test
    void aggregatorDebounces() {
        PipelineConfig config = new PipelineConfig(10, 4, 1, 0.5f, 3, 100L);
        EventAggregator aggregator = new EventAggregator(config, new Roi(List.of()));
        List<Detection> detections = List.of(
                new Detection("person", 0.9f, new Box(0.1f, 0.1f, 0.2f, 0.2f)));
        long t0 = System.currentTimeMillis();
        // 第 1、2 帧：未达连续帧数，不触发
        assertTrue(aggregator.accept("cam", new Frame(new byte[]{1}, 10, 10, t0, "cam"), detections).isEmpty());
        assertTrue(aggregator.accept("cam", new Frame(new byte[]{1}, 10, 10, t0 + 50, "cam"), detections).isEmpty());
        // 第 3 帧且已持续 100ms：触发
        List<VisionEvent> fired = aggregator.accept("cam",
                new Frame(new byte[]{1}, 10, 10, t0 + 150, "cam"), detections);
        assertEquals(1, fired.size());
        VisionEvent ev = fired.get(0);
        assertEquals("person", ev.label());
        assertEquals("cam", ev.sourceId());
        assertEquals(1, ev.count(), "事件必须带上触发时的检测框，否则业务层没法存截图");
        assertEquals(0.9f, ev.maxConfidence(), 1e-6);
        assertEquals(t0, ev.triggeredAt(), "triggeredAt 是首次出现时间");
        assertEquals(t0 + 150, ev.firedAt(), "firedAt 是达到阀值的时间");
        assertEquals(150, ev.sustainedMs());
        // 持续期间只报一次
        assertTrue(aggregator.accept("cam",
                new Frame(new byte[]{1}, 10, 10, t0 + 200, "cam"), detections).isEmpty());
        // 消失后清零，下次重新计数
        aggregator.accept("cam", new Frame(new byte[]{1}, 10, 10, t0 + 250, "cam"), List.of());
        assertTrue(aggregator.accept("cam",
                new Frame(new byte[]{1}, 10, 10, t0 + 300, "cam"), detections).isEmpty());
    }

    @Test
    void sourceIdWithPipeDoesNotLeak() {
        /*
         * 回归：早先用 sourceId + "|" + label 拼 key，若 sourceId 自身含 '|'，
         * clear("a|b") 会把来源 "a" 的状态一起清掉。
         */
        PipelineConfig config = new PipelineConfig(10, 4, 1, 0.5f, 1, 0L);
        EventAggregator aggregator = new EventAggregator(config, new Roi(List.of()));
        List<Detection> detections = List.of(
                new Detection("person", 0.9f, new Box(0.1f, 0.1f, 0.2f, 0.2f)));
        long t0 = System.currentTimeMillis();
        // 两个来源都触发一次
        assertEquals(1, aggregator.accept("a|b", new Frame(new byte[]{1}, 10, 10, t0, "a|b"), detections).size());
        assertEquals(1, aggregator.accept("a", new Frame(new byte[]{1}, 10, 10, t0, "a"), detections).size());
        assertEquals(2, aggregator.trackedSources());
        // 只清 "a|b"，不能误伤 "a"
        aggregator.clear("a|b");
        assertEquals(1, aggregator.trackedSources(), "clear 不能误伤同前缀的来源");
    }

    @Test
    void pipelineEndToEnd() throws Exception {
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        PipelineConfig config = new PipelineConfig(50, 8, 1, 0.5f, 1, 0L);
        try (VisionPipeline pipeline = new VisionPipeline(new FakeDetector(), config,
                (sourceId, frame, detections) -> received.add(sourceId + ":" + detections.size()),
                new Roi(List.of()))) {
            FrameSource source = new FrameSource() {
                @Override
                public String sourceId() {
                    return "cam-1";
                }

                @Override
                public AutoCloseable start(java.util.function.Consumer<Frame> onFrame) {
                    Thread t = new Thread(() -> {
                        for (int i = 0; i < 5; i++) {
                            onFrame.accept(frame("cam-1"));
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    });
                    t.setDaemon(true);
                    t.start();
                    return () -> {
                    };
                }
            };
            try (AutoCloseable handle = pipeline.attach(source)) {
                // 等流水线处理完
                long deadline = System.currentTimeMillis() + 3000;
                while (received.size() < 3 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20);
                }
            }
            assertTrue(received.size() >= 3, "应收到多帧结果，实际: " + received.size());
            assertTrue(received.stream().allMatch(s -> s.startsWith("cam-1:1")));
            assertTrue(pipeline.inferredFrames() >= 3);
        }
    }

    /**
     * 回归：{@code onEvent} 必须真的被调用。
     *
     * <p>早先 {@code VisionPipeline} 把 {@code aggregator.accept()} 的返回值丢弃，
     * 业务层只能按帧落库 —— 去抖算了但没生效。这个测试钉住那条通路。
     */
    @Test
    void pipelineDeliversEventsToSink() throws Exception {
        CopyOnWriteArrayList<VisionEvent> events = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<String> frames = new CopyOnWriteArrayList<>();
        // 连续 2 帧即触发，持续 0ms：让测试跑得快
        PipelineConfig config = new PipelineConfig(50, 8, 1, 0.5f, 2, 0L);
        DetectionSink sink = new DetectionSink() {
            @Override
            public void onDetections(String sourceId, Frame frame, List<Detection> detections) {
                frames.add(sourceId);
            }

            @Override
            public void onEvent(VisionEvent event) {
                events.add(event);
            }
        };
        try (VisionPipeline pipeline = new VisionPipeline(new FakeDetector(), config, sink,
                new Roi(List.of()))) {
            FrameSource source = new FrameSource() {
                @Override
                public String sourceId() {
                    return "cam-ev";
                }

                @Override
                public AutoCloseable start(java.util.function.Consumer<Frame> onFrame) {
                    Thread t = new Thread(() -> {
                        for (int i = 0; i < 6; i++) {
                            onFrame.accept(frame("cam-ev"));
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    });
                    t.setDaemon(true);
                    t.start();
                    return () -> {
                    };
                }
            };
            try (AutoCloseable handle = pipeline.attach(source)) {
                long deadline = System.currentTimeMillis() + 3000;
                while (frames.size() < 4 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20);
                }
            }
        }
        assertFalse(events.isEmpty(), "去抖事件必须送到 sink.onEvent，否则等于没去抖");
        assertEquals(1, events.size(),
                "同一标签持续出现只应触发一次，实际: " + events.size());
        VisionEvent ev = events.get(0);
        assertEquals("cam-ev", ev.sourceId());
        assertEquals("person", ev.label());
        assertTrue(ev.count() >= 1, "事件要带上检测框");
        assertTrue(ev.firedAt() >= ev.triggeredAt());
    }
}
