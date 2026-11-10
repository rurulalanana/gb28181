package io.github.rurulalanana.starter.service.vision;

import io.github.rurulalanana.starter.config.VisionProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型注册表的测试：重点在<b>降级行为</b>。
 *
 * <p>视觉识别是增值能力，任何「模型缺失 / 配置写错」都不应该让平台起不来。
 * 这些测试钉的就是这条底线。
 */
class VisionModelRegistryTest {

    private static VisionProperties props(boolean enabled, String file) {
        VisionProperties p = new VisionProperties();
        p.setEnabled(enabled);
        p.setModelDir("models");
        VisionProperties.Model m = new VisionProperties.Model();
        m.setId("test-model");
        m.setFile(file);
        p.setModels(new ArrayList<>(List.of(m)));
        p.setDefaultModel("test-model");
        return p;
    }

    @Test
    void disabledRegistryReturnsNull() {
        VisionModelRegistry registry = new VisionModelRegistry(props(false, "yolox_nano.onnx"));
        assertFalse(registry.available());
        assertNull(registry.detector("test-model"), "关闭时必须返回 null，不能去加载模型");
    }

    @Test
    void missingFileDegradesGracefully() {
        VisionModelRegistry registry = new VisionModelRegistry(props(true, "不存在的模型.onnx"));
        // available() 只看「启用 + 有声明」，文件缺失在 detector() 时才暴露
        assertTrue(registry.available());
        assertNull(registry.detector("test-model"), "文件缺失必须返回 null 而不是抛异常");
    }

    @Test
    void unknownModelIdReturnsNull() {
        VisionModelRegistry registry = new VisionModelRegistry(props(true, "yolox_nano.onnx"));
        assertNull(registry.detector("没有这个模型"));
    }

    @Test
    void defaultModelIdFallsBackToFirst() {
        VisionProperties p = props(true, "yolox_nano.onnx");
        p.setDefaultModel("");
        VisionModelRegistry registry = new VisionModelRegistry(p);
        assertEquals("test-model", registry.defaultModelId());
    }

    @Test
    void labelsFallBackToCoco() {
        VisionModelRegistry registry = new VisionModelRegistry(props(false, "x.onnx"));
        List<String> labels = registry.labels("test-model");
        assertEquals(VisionModelRegistry.COCO_LABELS.size(), labels.size());
        assertEquals("person", labels.get(0), "COCO 第 0 类是 person，人形检测依赖这个顺序");
    }

    @Test
    void customLabelsOverrideCoco() {
        VisionProperties p = props(false, "x.onnx");
        p.getModels().get(0).setLabels(List.of("person", "helmet"));
        VisionModelRegistry registry = new VisionModelRegistry(p);
        assertEquals(List.of("person", "helmet"), registry.labels("test-model"));
    }

    @Test
    void loadsRealModelWhenPresent() {
        // 模型在仓库根 models/ 下；本地开发目录结构是 <repo>/models
        VisionModelRegistry registry = new VisionModelRegistry(props(true, "yolox_nano.onnx"));
        var detector = registry.detector("test-model");
        if (detector == null) {
            // 模型不在时跳过（CI / 未放模型的环境），不判失败
            return;
        }
        try {
            assertEquals(80, detector.labels().size());
            assertEquals("person", detector.labels().get(0));
            assertTrue(registry.loadedModelIds().contains("test-model"));
        } finally {
            registry.closeAll();
        }
    }
}
