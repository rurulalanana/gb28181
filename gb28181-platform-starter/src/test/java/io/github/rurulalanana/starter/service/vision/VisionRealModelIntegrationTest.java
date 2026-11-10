package io.github.rurulalanana.starter.service.vision;

import io.github.rurulalanana.starter.config.VisionProperties;
import io.github.rurulalanana.vision.Detection;
import io.github.rurulalanana.vision.Frame;
import io.github.rurulalanana.vision.VisionDetector;
import io.github.rurulalanana.vision.VisionEvent;
import io.github.rurulalanana.vision.pipeline.PipelineConfig;
import io.github.rurulalanana.vision.pipeline.Roi;
import io.github.rurulalanana.vision.pipeline.VisionPipeline;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用<b>真实模型 + 真实抓图</b>验证整条链路。
 *
 * <h3>为什么必须有这个测试</h3>
 * 用假检测器的测试只能证明「接线对」，证明不了「模型真的能在这个管道里出结果」。
 * 之前踩过的坑：单测断言「坐标在 0~1」时，坐标其实是像素值也被 clamp 成 1.0，
 * <b>断言照样通过</b>。所以这里断言的是「真实图片上真的有检测结果」，
 * 而且打印出来给人肉眼看框位置是否合理。
 *
 * <p>模型或抓图不在时<b>跳过</b>（CI 环境没有这些文件），不判失败。
 */
class VisionRealModelIntegrationTest {

    /**
     * 真实抓图（本地跑过一次抓拍就有；线上/CI 没有则跳过）。
     *
     * <p>优先用 {@code bus.jpg}：图里有 4 个清晰的人，能验证「人形检测真的工作」。
     * 设备抓图可能是夜间空场景（实测一张 1280x720 的近全白画面检出 0 个目标），
     * 那种图只能验证「不崩」，验证不了「能检出人」。
     */
    private static final Path SAMPLE_PERSON = Path.of(
            "src/test/resources/bus.jpg");

    /** 设备真实抓图：可能是空场景，只用来验证「畸形/空画面不崩」。 */
    private static final Path SAMPLE_DEVICE = Path.of(
            "record/snap/10001_10001-1.jpg");

    private static final Path MODEL_DIR = Path.of("..", "models");

    @Test
    void realModelRunsThroughStarterPipeline() throws Exception {
        Path sample = Files.isRegularFile(SAMPLE_PERSON) ? SAMPLE_PERSON : SAMPLE_DEVICE;
        if (!Files.isRegularFile(sample)) {
            System.out.println("[skip] 没有真实图片: " + SAMPLE_PERSON.toAbsolutePath());
            return;
        }
        byte[] jpeg = Files.readAllBytes(sample);
        int[] wh = ZlmFrameSource.jpegSize(jpeg);
        System.out.println("真实图片: " + sample + " " + wh[0] + "x" + wh[1]
                + " " + jpeg.length + " 字节");

        VisionProperties p = new VisionProperties();
        p.setEnabled(true);
        // 测试的 cwd 是 gb28181-platform-starter/，模型在仓库根的 models/
        p.setModelDir(MODEL_DIR.toString());
        VisionProperties.Model m = new VisionProperties.Model();
        m.setId("yolox-nano");
        m.setFile("yolox_nano.onnx");
        m.setFormat("YOLOX");
        m.setInputSize(416);
        m.setPreprocess("yolox");
        p.setModels(List.of(m));
        p.setDefaultModel("yolox-nano");

        VisionModelRegistry registry = new VisionModelRegistry(p);
        VisionDetector detector = registry.detector("yolox-nano");
        if (detector == null) {
            System.out.println("[skip] 模型不可用: " + MODEL_DIR.toAbsolutePath());
            return;
        }

        try {
            // 直接调一次，把结果打出来看（这是唯一能发现「框位置错」的方式）
            Frame frame = new Frame(jpeg, wh[0], wh[1], System.currentTimeMillis(), "10001/10001-1");
            long t0 = System.currentTimeMillis();
            List<Detection> detections = detector.detect(frame);
            long cost = System.currentTimeMillis() - t0;
            System.out.println("推理耗时 " + cost + " ms，检出 " + detections.size() + " 个目标:");
            for (Detection d : detections) {
                float x = d.box().x() * wh[0];
                float y = d.box().y() * wh[1];
                float w = d.box().w() * wh[0];
                float h = d.box().h() * wh[1];
                System.out.printf("  %-12s conf=%.3f 归一化(%.3f,%.3f %.3fx%.3f) 像素(%.0f,%.0f %.0fx%.0f)%n",
                        d.label(), d.confidence(),
                        d.box().x(), d.box().y(), d.box().w(), d.box().h(),
                        x, y, w, h);
            }

            // 坐标必须在 0~1：模型层契约要求归一化，业务层按画面尺寸乘
            for (Detection d : detections) {
                assertTrue(d.box().x() >= 0 && d.box().x() <= 1, "x 越界: " + d.box().x());
                assertTrue(d.box().y() >= 0 && d.box().y() <= 1, "y 越界: " + d.box().y());
                assertTrue(d.box().w() > 0 && d.box().w() <= 1, "w 越界: " + d.box().w());
                assertTrue(d.box().h() > 0 && d.box().h() <= 1, "h 越界: " + d.box().h());
                assertTrue(d.confidence() > 0 && d.confidence() <= 1,
                        "置信度必须在 0~1（>1 说明漏了 sigmoid）: " + d.confidence());
            }

            /*
             * 核心验收：bus.jpg 里有 4 个清晰的人，人形检测必须检出 person。
             *
             * 这条断言是整条链路唯一能发现「模型参数配错」的守卫 ——
             * 之前踩过的坑：预处理口径用错时分数全挤在 0.31~0.34，
             * 坐标全塌到左上角，而「坐标在 0~1」的断言照样通过。
             * 只有「已知有人的图上必须检出 person」才能钉住。
             */
            boolean usingPersonSample = sample == SAMPLE_PERSON;
            if (usingPersonSample) {
                long persons = detections.stream().filter(d -> "person".equals(d.label())).count();
                assertTrue(persons >= 1,
                        "bus.jpg 里有 4 个清晰的人，必须至少检出 1 个 person，实际: " + detections.size() + " 个目标");
                System.out.println("人形检测验收通过: 检出 " + persons + " 个 person");
            }

            // 走一遍完整流水线，确认事件能出来
            DbDetectionSink sink = new DbDetectionSink();
            sink.setCooldownMillis(0);
            CountDownLatch eventLatch = new CountDownLatch(1);
            CountDownLatch frameLatch = new CountDownLatch(1);
            AtomicReference<VisionEvent> captured = new AtomicReference<>();
            sink.setEventHandlers(List.of(e -> {
                captured.set(e);
                eventLatch.countDown();
            }));
            // 用一个包装 sink 观察「逐帧结果到了」——不能只看 pipeline.close() 之后的状态，
            // 那样会因时序问题假失败（workers 还没消费到帧就被 shutdownNow）
            io.github.rurulalanana.vision.DetectionSink countingSink = new io.github.rurulalanana.vision.DetectionSink() {
                @Override
                public void onDetections(String sourceId, Frame frame,
                                         List<Detection> ds) {
                    sink.onDetections(sourceId, frame, ds);
                    frameLatch.countDown();
                }

                @Override
                public void onEvent(VisionEvent event) {
                    sink.onEvent(event);
                }
            };

            // 阈值降到 0.3 保证这张图能触发（不同图片目标数不同）
            PipelineConfig config = new PipelineConfig(20, 8, 1, 0.3f, 1, 0L);
            try (VisionPipeline pipeline = new VisionPipeline(detector, config, countingSink, new Roi(List.of()))) {
                var source = new io.github.rurulalanana.vision.pipeline.FrameSource() {
                    @Override
                    public String sourceId() {
                        return "10001/10001-1";
                    }

                    @Override
                    public AutoCloseable start(java.util.function.Consumer<Frame> onFrame) {
                        Thread t = new Thread(() -> {
                            for (int i = 0; i < 3; i++) {
                                onFrame.accept(new Frame(jpeg, wh[0], wh[1],
                                        System.currentTimeMillis(), "10001/10001-1"));
                                try {
                                    Thread.sleep(60);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                            }
                        }, "real-frame-source");
                        t.setDaemon(true);
                        t.start();
                        return () -> {
                        };
                    }
                };
                pipeline.attach(source);

                // 逐帧结果必须到达 sink（不管有没有检出目标）
                assertTrue(frameLatch.await(10, TimeUnit.SECONDS),
                        "逐帧结果必须到达 sink（前端叠框依赖它）");

                if (!detections.isEmpty()) {
                    assertTrue(eventLatch.await(10, TimeUnit.SECONDS),
                            "真实图上有目标，流水线必须产出事件");
                    VisionEvent event = captured.get();
                    assertNotNull(event);
                    System.out.println("事件: label=" + event.label()
                            + " count=" + event.count()
                            + " conf=" + String.format("%.2f", event.maxConfidence())
                            + " 持续=" + event.sustainedMs() + "ms");
                    assertNotNull(event.frame(), "事件必须带原始 JPEG 供存截图");
                } else {
                    System.out.println("该图片未检出目标（可能是空场景/夜间画面），跳过事件断言");
                }
            }

            DbDetectionSink.Latest latest = sink.latestOf("10001/10001-1");
            assertNotNull(latest, "逐帧结果必须写入 sink（前端叠框依赖它）");
        } finally {
            registry.closeAll();
        }
    }
}
