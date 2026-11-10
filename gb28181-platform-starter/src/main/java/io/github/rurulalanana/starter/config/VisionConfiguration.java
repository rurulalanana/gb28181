package io.github.rurulalanana.starter.config;

import io.github.rurulalanana.starter.service.vision.DbDetectionSink;
import io.github.rurulalanana.starter.service.vision.VisionEventRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * 视觉识别的装配。
 *
 * <h3>为什么需要这个类</h3>
 * 只为了两件事，都不适合放到 service 里：
 * <ol>
 *   <li>{@code @EnableConfigurationProperties(VisionProperties.class)} ——
 *       与 Dahua / Hikvision 的做法保持一致，各厂商/能力的配置类各自开启，
 *       不往主类上堆注解。</li>
 *   <li>把 {@link VisionEventRecorder} 注册成 {@link DbDetectionSink} 的处理器 ——
 *       sink 用 setter 注入处理器列表，构造器注入会形成
 *       「sink → recorder → sink」的循环（recorder 需要 sink 吗？不需要，
 *       但 Spring 无法区分，用 {@code @Autowired(required=false)} 的 setter 更省事）。</li>
 * </ol>
 *
 * <h3>关闭时不装配任何东西</h3>
 * {@code vision.enabled=false} 时，{@code VisionModelRegistry} 不加载模型，
 * {@code VisionService} 不 attach 任何通道，{@code ZlmFrameSource} 不会被创建，
 * 因此<b>一个 ffmpeg 进程都不会起</b>，CPU 占用为 0。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(VisionProperties.class)
public class VisionConfiguration {

    private final VisionProperties props;
    private final DbDetectionSink sink;
    private final VisionEventRecorder recorder;

    @PostConstruct
    public void wire() {
        // 冷却时长由配置决定；sink 里给默认值是为了「脱离 Spring 单测」也能用
        sink.setCooldownMillis(props.getEventCooldownMillis());
        sink.setEventHandlers(List.of(recorder));
        if (props.isEnabled()) {
            log.info("视觉识别已启用: models={} channels={} fps={} threshold={}",
                    props.getModels().size(), props.getChannels().size(),
                    props.getFps(), props.getConfidenceThreshold());
        }
    }
}
