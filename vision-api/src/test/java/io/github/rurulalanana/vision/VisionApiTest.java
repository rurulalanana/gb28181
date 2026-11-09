package io.github.rurulalanana.vision;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约层单测：只测数据模型与注册表，不依赖任何模型文件与网络。
 */
class VisionApiTest {

    @Test
    void frameUsable() {
        assertTrue(new Frame(new byte[]{1, 2}, 640, 480, "cam-1").usable());
        assertFalse(new Frame(null, 640, 480, "cam-1").usable());
        assertFalse(new Frame(new byte[0], 640, 480, "cam-1").usable());
        assertFalse(new Frame(new byte[]{1}, 0, 480, "cam-1").usable());
    }

    @Test
    void boxCenterAndArea() {
        Box b = new Box(0.1f, 0.2f, 0.3f, 0.4f);
        assertEquals(0.25f, b.centerX(), 1e-6f);
        assertEquals(0.4f, b.centerY(), 1e-6f);
        assertEquals(0.12f, b.area(), 1e-6f);
    }

    @Test
    void boxIou() {
        Box a = new Box(0f, 0f, 0.5f, 0.5f);
        Box same = new Box(0f, 0f, 0.5f, 0.5f);
        Box apart = new Box(0.6f, 0.6f, 0.4f, 0.4f);
        assertEquals(1f, a.iou(same), 1e-6f);
        assertEquals(0f, a.iou(apart), 1e-6f);
        assertEquals(0f, a.iou(null), 1e-6f);
    }

    @Test
    void configLabelOf() {
        DetectorConfig c = new DetectorConfig("m", "m.onnx", 0.5f, 640, List.of("person", "vehicle"));
        assertEquals("person", c.labelOf(0));
        assertEquals("vehicle", c.labelOf(1));
        assertNull(c.labelOf(2));
        assertNull(c.labelOf(-1));
        assertTrue(c.usable());
        assertFalse(new DetectorConfig("", "m.onnx", 0.5f, 640, List.of("person")).usable());
    }

    @Test
    void detectionAbove() {
        Detection d = new Detection("person", 0.8f, new Box(0, 0, 0.1f, 0.1f));
        assertTrue(d.above(0.5f));
        assertFalse(d.above(0.9f));
    }

    @Test
    void registryLazyAndSingleton() {
        DetectorRegistry r = new DetectorRegistry();
        int[] created = {0};
        r.register("fake", () -> {
            created[0]++;
            return new VisionDetector() {
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
                    return List.of(new Detection("person", 0.9f, new Box(0, 0, 0.1f, 0.1f)));
                }
            };
        });
        assertTrue(r.contains("fake"));
        assertEquals(0, created[0], "未取用不应初始化");
        VisionDetector d1 = r.get("fake");
        VisionDetector d2 = r.get("fake");
        assertEquals(1, created[0], "应只初始化一次");
        assertTrue(d1 == d2, "应返回同一单例");
        assertNull(r.get("不存在"));
        assertEquals(List.of("person"), d1.detect(new Frame(new byte[]{1}, 10, 10, "s")).stream()
                .map(Detection::label).toList());
        r.closeAll();
    }
}
