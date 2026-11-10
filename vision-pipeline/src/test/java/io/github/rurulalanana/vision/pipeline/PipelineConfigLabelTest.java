package io.github.rurulalanana.vision.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标签过滤的测试（「只检测人形」依赖它）。
 *
 * <h3>为什么必须有这条</h3>
 * COCO 80 类里除了 person 还有 car / truck / bench。不过滤时画面里的车、
 * 椅子都会触发事件，告警中心会被无关事件淹没。而过滤的失效方式很隐蔽：
 * 如果 {@code labels} 为空被误判成「什么都不匹配」，配置漏写时
 * 就变成「什么都检测不到」，从日志完全看不出来。
 */
class PipelineConfigLabelTest {

    @Test
    void emptyLabelsAcceptsEverything() {
        PipelineConfig config = PipelineConfig.defaults();
        assertTrue(config.labels().isEmpty());
        assertTrue(config.acceptsLabel("person"));
        assertTrue(config.acceptsLabel("car"));
        assertTrue(config.acceptsLabel("bench"));
    }

    @Test
    void configuredLabelsFilterOthers() {
        PipelineConfig config = new PipelineConfig(1.0, 4, 2, 0.45f, 3, 2000L,
                List.of("person"));
        assertTrue(config.acceptsLabel("person"), "配置了 person 就必须保留");
        assertFalse(config.acceptsLabel("car"), "没配置 car 就必须过滤掉");
        assertFalse(config.acceptsLabel("truck"));
    }

    @Test
    void nullLabelRejectedWhenFiltering() {
        PipelineConfig config = new PipelineConfig(1.0, 4, 2, 0.45f, 3, 2000L,
                List.of("person"));
        // 模型的 labelOf() 越界时返回 null，不能让 null 混进事件
        assertFalse(config.acceptsLabel(null));
    }

    @Test
    void nullLabelsNormalizedToEmpty() {
        PipelineConfig config = new PipelineConfig(1.0, 4, 2, 0.45f, 3, 2000L, null);
        // null 要归一成空列表而不是保持 null，否则下游 stream 会 NPE
        assertTrue(config.labels().isEmpty());
        assertTrue(config.acceptsLabel("person"));
    }

    @Test
    void legacyConstructorStillWorks() {
        // 6 参数构造器（不传 labels）必须等价于「全部保留」
        PipelineConfig config = new PipelineConfig(1.0, 4, 2, 0.45f, 3, 2000L);
        assertTrue(config.labels().isEmpty());
        assertTrue(config.acceptsLabel("anything"));
    }
}
