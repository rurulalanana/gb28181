package io.github.rurulalanana.starter.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用<b>真实的 {@code application.yml}</b> 做一次绑定，验证视觉配置写对了。
 *
 * <h3>为什么必须有这个测试</h3>
 * 配置类的绑定错误不会在编译期暴露，也不会在 {@code vision.enabled=false} 时暴露 ——
 * 线上第一次打开视觉识别才发现「模型没加载」，而根因只是 yml 里一个连字符写错。
 * 这类问题在单测里用真实 yml 跑一遍绑定，成本极低、收益很直接。
 *
 * <p>尤其 {@code scenarios}：它默认是空列表，在这个测试之前<b>从未被真正绑定过</b>
 * （yml 里一直没配）。{@code Scenario} 是 record，绑定走构造器解析，
 * 属性名少写连字符（{@code modelId} 而不是 {@code model-id}）会静默绑成 null。
 *
 * <p>本测试不启动 Spring 容器，只用 {@link Binder}，所以不需要数据库 / 端口。
 */
class VisionPropertiesBindingTest {

    private static VisionProperties bindRealYaml() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources =
                loader.load("application.yml", new ClassPathResource("application.yml"));
        MutablePropertySources ps = new MutablePropertySources();
        sources.forEach(ps::addLast);
        return new Binder(ConfigurationPropertySources.from(ps))
                .bind("vision", Bindable.of(VisionProperties.class))
                .orElseThrow(() -> new AssertionError("application.yml 里没有 vision 段"));
    }

    @Test
    @DisplayName("models 段：yolox-nano 与 fire-smoke 都绑定成功")
    void modelsBind() throws Exception {
        VisionProperties props = bindRealYaml();
        List<String> ids = props.getModels().stream()
                .map(VisionProperties.Model::getId).toList();
        assertTrue(ids.contains("yolox-nano"), "缺 yolox-nano：" + ids);
        assertTrue(ids.contains("fire-smoke"), "缺 fire-smoke：" + ids);

        VisionProperties.Model fire = props.getModels().stream()
                .filter(m -> "fire-smoke".equals(m.getId()))
                .findFirst().orElseThrow();

        // 这四个字段必须完全对上，任一错都会让框位置/标签出错（见 MODELS.md）
        assertEquals("fire_smoke_yolov8n.onnx", fire.getFile());
        assertEquals("YOLO_CHANNEL_FIRST", fire.getFormat());
        assertEquals(320, fire.getInputSize());
        assertEquals("yolo", fire.getPreprocess());
        assertEquals(List.of("fire", "smoke"), fire.getLabels());

        // 展示名与分类：界面按 category 分组的依据
        assertEquals("火焰烟雾检测", fire.getDisplayName());
        assertEquals("fire", fire.getCategory());
        assertEquals(45, fire.getEstimatedMsPerFrame());
    }

    @Test
    @DisplayName("scenarios 段：record 构造器绑定正常（这是第一次真正验证它）")
    void scenariosBind() throws Exception {
        VisionProperties props = bindRealYaml();
        assertFalse(props.getScenarios().isEmpty(), "scenarios 不该是空的");

        VisionProperties.Scenario fire = props.getScenarios().stream()
                .filter(s -> "fire-smoke".equals(s.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "缺 fire-smoke 场景，实际: "
                                + props.getScenarios().stream().map(VisionProperties.Scenario::id).toList()));

        // model-id 少写连字符时会绑成 null —— 那就是「界面能选但一启动就报找不到模型」
        assertEquals("fire-smoke", fire.modelId(), "model-id 绑定失败");
        assertEquals("火焰烟雾检测", fire.name());
        assertEquals("fire", fire.category());
        assertEquals(List.of("fire", "smoke"), fire.labels());
        assertNotNull(fire.description());
        assertFalse(fire.description().isBlank(), "说明文字不该为空");
    }

    @Test
    @DisplayName("每个场景引用的 model-id 都必须在 models 里声明")
    void scenarioModelIdsExist() throws Exception {
        VisionProperties props = bindRealYaml();
        List<String> declared = props.getModels().stream()
                .map(VisionProperties.Model::getId).toList();
        for (VisionProperties.Scenario s : props.getScenarios()) {
            assertTrue(declared.contains(s.modelId()),
                    "场景 " + s.id() + " 引用了未声明的模型 " + s.modelId()
                            + "（已声明: " + declared + "）");
        }
    }

    @Test
    @DisplayName("默认关闭：vision.enabled 不配就该是 false，不能默认吃 CPU")
    void disabledByDefault() throws Exception {
        VisionProperties props = bindRealYaml();
        assertFalse(props.isEnabled(), "默认必须关闭，由运维显式打开");
    }
}
