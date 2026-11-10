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

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端：真实 JPEG → 流水线 → 事件 → sink。
 *
 * <h3>为什么用「假检测器 + 真 JPEG」而不是真模型</h3>
 * 这里要验证的是<b>接线</b>（取帧出的 Frame 能被流水线接受、事件能到 sink、
 * 截图能取到原始 JPEG 字节），不是模型精度 —— 后者已经在
 * {@code OnnxRealModelTest} 里用真实图片验证过。
 *
 * <p>用假检测器还有个好处：能<b>精确控制</b>检测结果，从而稳定触发去抖，
 * 不会因为真实模型的分数抖动导致测试时绿时红。
 */
class VisionPipelineIntegrationTest {

    /** 造一张带内容的 JPEG（避免全黑图被压成极小文件）。 */
    private static byte[] jpeg(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x33, 0x66, 0x99));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(0xEE, 0xCC, 0x22));
        g.fillOval(w / 4, h / 4, w / 2, h / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    @Test
    void frameFlowsThroughPipelineToSink() throws Exception {
        byte[] jpg = jpeg(640, 480);
        int[] wh = ZlmFrameSource.jpegSize(jpg);
        // 关键：真实 JPEG 的尺寸必须能被解析出来，否则 Frame.usable() 为 false
        assertEquals(640, wh[0]);
        assertEquals(480, wh[1]);

        Frame frame = new Frame(jpg, wh[0], wh[1], System.currentTimeMillis(), "10001/1");
        assertTrue(frame.usable(), "取帧产出的 Frame 必须可用");

        DbDetectionSink sink = new DbDetectionSink();
        sink.setCooldownMillis(0);

        CountDownLatch eventLatch = new CountDownLatch(1);
        AtomicReference<VisionEvent> captured = new AtomicReference<>();
        sink.setEventHandlers(List.of(e -> {
            captured.set(e);
            eventLatch.countDown();
        }));

        // 假检测器：每帧都返回一个人形框
        VisionDetector alwaysPerson = new VisionDetector() {
            @Override
            public String modelId() {
                return "fake-person";
            }

            @Override
            public List<Detection> detect(Frame f) {
                return List.of(new Detection("person", 0.9f,
                        new io.github.rurulalanana.vision.Box(0.2f, 0.3f, 0.25f, 0.5f)));
            }

            @Override
            public List<String> labels() {
                return List.of("person");
            }

            @Override
            public void close() {
            }
        };

        // 去抖放宽到 1 帧 / 0ms，让事件在第一帧就触发
        PipelineConfig config = new PipelineConfig(20, 8, 1, 0.45f, 1, 0L);
        try (VisionPipeline pipeline = new VisionPipeline(alwaysPerson, config, sink, new Roi(List.of()))) {
            // 直接喂帧（模拟 ZlmFrameSource 的出帧回调）
            var source = new io.github.rurulalanana.vision.pipeline.FrameSource() {
                @Override
                public String sourceId() {
                    return "10001/1";
                }

                @Override
                public AutoCloseable start(java.util.function.Consumer<Frame> onFrame) {
                    Thread t = new Thread(() -> {
                        for (int i = 0; i < 5; i++) {
                            onFrame.accept(new Frame(jpg, wh[0], wh[1],
                                    System.currentTimeMillis(), "10001/1"));
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }, "test-frame-source");
                    t.setDaemon(true);
                    t.start();
                    return () -> {
                    };
                }
            };
            pipeline.attach(source);

            assertTrue(eventLatch.await(5, TimeUnit.SECONDS), "事件应在 5 秒内触发");
        }

        VisionEvent event = captured.get();
        assertNotNull(event);
        assertEquals("10001/1", event.sourceId());
        assertEquals("person", event.label());
        assertEquals(1, event.count());
        assertEquals(0.9f, event.maxConfidence(), 0.001f);
        // 事件里的 frame 是原始 JPEG，业务层要靠它存截图
        assertNotNull(event.frame());
        assertEquals(jpg.length, event.frame().jpeg().length,
                "事件必须带原始 JPEG，否则存截图要重新拉流");

        // 逐帧结果也到了 sink（前端叠框用）
        DbDetectionSink.Latest latest = sink.latestOf("10001/1");
        assertNotNull(latest);
        assertEquals(1, latest.detections().size());
        assertEquals(640, latest.width());
        assertEquals(480, latest.height());
        assertEquals(1L, latest.counts().get("person"));
    }

    @Test
    void registryBuildsDetectorFromProperties() {
        VisionProperties p = new VisionProperties();
        p.setEnabled(true);
        p.setModelDir("models");
        VisionProperties.Model m = new VisionProperties.Model();
        m.setId("yolox-nano");
        m.setFile("yolox_nano.onnx");
        m.setFormat("YOLOX");
        m.setInputSize(416);
        m.setPreprocess("yolox");
        p.setModels(List.of(m));
        p.setDefaultModel("yolox-nano");

        VisionModelRegistry registry = new VisionModelRegistry(p);
        // 模型在仓库根 models/ 下；找不到时跳过（CI 环境）
        if (!registry.available() || registry.detector("yolox-nano") == null) {
            return;
        }
        try {
            assertTrue(registry.loadedModelIds().contains("yolox-nano"));
            // 真实模型的标签必须是 COCO 80 类，否则类别名会对错位
            assertEquals("person", registry.labels("yolox-nano").get(0));
        } finally {
            registry.closeAll();
        }
    }
}
