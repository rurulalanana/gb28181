package io.github.rurulalanana.starter.service.vision;

import io.github.rurulalanana.starter.config.VisionProperties;
import io.github.rurulalanana.vision.DetectorConfig;
import io.github.rurulalanana.vision.DetectorRegistry;
import io.github.rurulalanana.vision.VisionDetector;
import io.github.rurulalanana.vision.onnx.OnnxVisionDetector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 视觉模型的<b>独立维护入口</b>：把配置里的模型声明变成可用的 {@link VisionDetector}。
 *
 * <h3>为什么要单独一个「模型注册表」</h3>
 * 模型是会换的（YOLOX → RT-DETR → 专用烟火模型），而且换的时候
 * <b>只应该改配置 + 放文件，不该动代码</b>。所以：
 * <ul>
 *   <li>业务层只说「我要 {@code person} 检测」，不说「我要 YOLOX」</li>
 *   <li>模型的文件名、输出格式、预处理口径全在 {@code vision.models[]} 里</li>
 *   <li>换模型 = 改 {@code application.yml} + 放 {@code .onnx}，重启即可</li>
 * </ul>
 *
 * <h3>懒加载</h3>
 * 模型只在第一次被取用时初始化。ONNX 会话初始化要读几 MB 文件、
 * 还会加载原生库，启动时全量加载会拖慢启动，而多数通道可能根本不用某些模型。
 *
 * <h3>失败不致命</h3>
 * 模型文件缺失 / 格式不匹配时只记日志并返回 {@code null}，
 * <b>不影响平台启动</b> —— 视觉识别是增值能力，不能因为它让视频看不了。
 */
@Slf4j
@Service
public class VisionModelRegistry {

    /**
     * 内置 COCO 80 类。
     *
     * <p>绝大多数公开 YOLO 权重都是这个顺序。放在代码里是为了「配置文件不写
     * 80 行 labels 也能跑」；模型用别的类别集时在 {@code vision.models[].labels} 覆盖。
     */
    public static final List<String> COCO_LABELS = List.of(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
            "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
            "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
            "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
            "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup",
            "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
            "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
            "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
            "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
            "toothbrush");

    private final VisionProperties props;

    /** modelId → 已初始化的检测器。初始化失败的不缓存，允许下次重试。 */
    private final Map<String, VisionDetector> detectors = new ConcurrentHashMap<>();

    /** modelId → 配置（用于日志与排障，不参与推理）。 */
    private final Map<String, DetectorConfig> configs = new ConcurrentHashMap<>();

    public VisionModelRegistry(VisionProperties props) {
        this.props = props;
        if (props.isEnabled()) {
            initDeclarations();
        }
    }

    /** 启动时校验一遍声明，把问题<b>在启动日志里</b>暴露出来，而不是等到第一次检测。 */
    private void initDeclarations() {
        if (props.getModels().isEmpty()) {
            log.warn("vision.enabled=true 但 vision.models 为空，视觉识别不会工作");
            return;
        }
        Path dir = modelDir();
        for (VisionProperties.Model m : props.getModels()) {
            if (m.getId() == null || m.getId().isBlank()) {
                log.warn("忽略一个没有 id 的模型声明: {}", m.getFile());
                continue;
            }
            Path file = dir.resolve(m.getFile() == null ? "" : m.getFile());
            if (!Files.isRegularFile(file)) {
                log.warn("模型文件不存在: id={} path={}（视觉识别将不可用，其余功能不受影响）",
                        m.getId(), file.toAbsolutePath());
                continue;
            }
            log.info("视觉模型已声明: id={} file={} format={} input={} preprocess={} size={}KB",
                    m.getId(), file.getFileName(), m.getFormat(), m.getInputSize(),
                    m.getPreprocess(), Files.isRegularFile(file) ? sizeKb(file) : 0);
        }
    }

    /** 模型目录（绝对路径）。 */
    public Path modelDir() {
        return Path.of(props.getModelDir()).toAbsolutePath().normalize();
    }

    /** 是否已启用（总开关 + 至少一个可用模型）。 */
    public boolean available() {
        return props.isEnabled() && !props.getModels().isEmpty();
    }

    /** 默认模型的 id（配置为空时取第一个）。 */
    public String defaultModelId() {
        String id = props.getDefaultModel();
        if (id != null && !id.isBlank()) {
            return id;
        }
        return props.getModels().isEmpty() ? null : props.getModels().get(0).getId();
    }

    /**
     * 取检测器（懒初始化）。
     *
     * @param modelId 模型 id；为空时用默认模型
     * @return 检测器；未启用 / 文件缺失 / 初始化失败时返回 {@code null}
     */
    public VisionDetector detector(String modelId) {
        if (!props.isEnabled()) {
            return null;
        }
        String id = (modelId == null || modelId.isBlank()) ? defaultModelId() : modelId;
        if (id == null) {
            return null;
        }
        VisionDetector cached = detectors.get(id);
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = detectors.get(id);
            if (cached != null) {
                return cached;
            }
            DetectorConfig config = buildConfig(id);
            if (config == null) {
                return null;
            }
            try {
                VisionDetector created = new OnnxVisionDetector(config);
                detectors.put(id, created);
                configs.put(id, config);
                log.info("视觉模型已加载: id={} labels={}", id, created.labels().size());
                return created;
            } catch (RuntimeException e) {
                /*
                 * 不缓存失败结果：模型文件可能被运维补上，下次调用应能重试。
                 * 也不抛异常：视觉识别是增值能力，不能因为它让调用方（流服务）失败。
                 */
                log.warn("视觉模型加载失败: id={} {}", id, e.getMessage());
                return null;
            }
        }
    }

    /** 已加载的模型 id。 */
    public List<String> loadedModelIds() {
        return new ArrayList<>(detectors.keySet());
    }

    /** 已声明的模型 id（含未加载的）。 */
    public List<String> declaredModelIds() {
        List<String> ids = new ArrayList<>();
        for (VisionProperties.Model m : props.getModels()) {
            if (m.getId() != null && !m.getId().isBlank()) {
                ids.add(m.getId());
            }
        }
        return ids.isEmpty() ? Collections.emptyList() : ids;
    }

    /** 某个模型的标签（未加载时返回声明里的或 COCO）。 */
    public List<String> labels(String modelId) {
        DetectorConfig config = configs.get(modelId);
        if (config != null) {
            return config.labels();
        }
        VisionProperties.Model decl = declaration(modelId);
        return decl == null || decl.getLabels().isEmpty() ? COCO_LABELS : decl.getLabels();
    }

    /** 关闭所有已加载的检测器（Spring 销毁时调）。 */
    public void closeAll() {
        for (Map.Entry<String, VisionDetector> e : detectors.entrySet()) {
            try {
                e.getValue().close();
            } catch (Exception ex) {
                log.debug("关闭视觉模型失败: id={} {}", e.getKey(), ex.getMessage());
            }
        }
        detectors.clear();
        configs.clear();
    }

    // ==================== 内部 ====================

    /** 把配置声明翻译成契约对象；声明缺失 / 文件不存在时返回 {@code null}。 */
    private DetectorConfig buildConfig(String id) {
        VisionProperties.Model m = declaration(id);
        if (m == null) {
            log.warn("未声明视觉模型: id={}（可用: {}）", id, declaredModelIds());
            return null;
        }
        Path file = modelDir().resolve(m.getFile() == null ? "" : m.getFile());
        if (!Files.isRegularFile(file)) {
            log.warn("视觉模型文件不存在: id={} path={}", id, file.toAbsolutePath());
            return null;
        }
        List<String> labels = m.getLabels().isEmpty() ? COCO_LABELS : m.getLabels();
        return new DetectorConfig(id, file.toString(), props.getConfidenceThreshold(),
                m.getInputSize(), labels,
                parseFormat(m.getFormat()), parsePreprocess(m.getPreprocess()));
    }

    private VisionProperties.Model declaration(String id) {
        if (id == null) {
            return null;
        }
        for (VisionProperties.Model m : props.getModels()) {
            if (id.equals(m.getId())) {
                return m;
            }
        }
        return null;
    }

    /**
     * 解析输出格式。写错时<b>回退到默认并告警</b>，不抛异常 ——
     * 拼错一个枚举名不该让整个视觉功能起不来。
     */
    private static DetectorConfig.OutputFormat parseFormat(String raw) {
        if (raw == null || raw.isBlank()) {
            return DetectorConfig.DEFAULT_FORMAT;
        }
        try {
            return DetectorConfig.OutputFormat.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("未知的输出格式 '{}'，按默认 {} 处理（可选: YOLO_CHANNEL_FIRST / "
                    + "YOLO_ANCHOR_FIRST / YOLOX）", raw, DetectorConfig.DEFAULT_FORMAT);
            return DetectorConfig.DEFAULT_FORMAT;
        }
    }

    /** 解析预处理口径：{@code yolox} / {@code yolo}，其余按格式推导。 */
    private static DetectorConfig.Preprocess parsePreprocess(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "yolox" -> DetectorConfig.Preprocess.yolox();
            case "yolo", "yolov8", "yolov5" -> DetectorConfig.Preprocess.yolo();
            default -> {
                log.warn("未知的预处理口径 '{}'，按 yolo 处理（可选: yolox / yolo）", raw);
                yield DetectorConfig.Preprocess.yolo();
            }
        };
    }

    private static long sizeKb(Path file) {
        try {
            return Files.size(file) / 1024;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 把已加载的检测器注册到 {@link DetectorRegistry}（给「只用 api 不想引 Spring」的场景）。
     *
     * <p>当前流水线直接用 {@link #detector(String)}，不需要这一步；
     * 保留它是为了将来做「按 modelId 动态切换」时能复用同一个注册表。
     */
    public void registerInto(DetectorRegistry registry) {
        if (registry == null) {
            return;
        }
        for (String id : declaredModelIds()) {
            registry.register(id, () -> detector(id));
        }
    }
}
