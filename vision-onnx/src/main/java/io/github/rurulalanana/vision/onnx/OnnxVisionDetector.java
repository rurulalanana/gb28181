package io.github.rurulalanana.vision.onnx;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.github.rurulalanana.vision.Box;
import io.github.rurulalanana.vision.DetectorConfig;
import io.github.rurulalanana.vision.Detection;
import io.github.rurulalanana.vision.Frame;
import io.github.rurulalanana.vision.VisionDetector;
import io.github.rurulalanana.vision.VisionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * ONNX Runtime 实现的 {@link VisionDetector}。
 *
 * <p><b>只做三件事</b>：JPEG 解码 → 缩放归一化成模型输入 → 跑会话并解析输出。
 * 不读数据库、不发网络、不写文件 —— 模型文件路径由 {@link DetectorConfig} 给出，
 * 随包分发，禁止运行期联网下载。
 *
 * <p><b>输出解析按 YOLO 导出格式</b>：{@code [1, 84, 8400]}（COCO 80 类 + xywh + 置信度）。
 * 换其它导出格式（如 {@code [1, 8400, 84]}）时改 {@link #parseOutput} 即可，
 * 契约层不受影响。
 *
 * <p><b>线程安全</b>：ONNX 会话的 {@code run} 是线程安全的，但本实现内部用
 * {@code synchronized} 串行化 —— CPU 推理本身就是瓶颈，并行只会抢核。
 * 流水线（vision-pipeline）负责多路之间的调度。
 */
public final class OnnxVisionDetector implements VisionDetector {

    private static final Logger log = LoggerFactory.getLogger(OnnxVisionDetector.class);

    /** YOLO 导出格式的通道数：4（xywh）+ 80（COCO 类别）。 */
    private static final int CHANNELS = 84;
    /** 前 4 个通道是框，后面是各类别置信度。 */
    private static final int BOX_CHANNELS = 4;

    private final DetectorConfig config;
    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;

    /**
     * @param config 检测器配置（模型路径、阈值、输入尺寸、标签表）
     * @throws VisionException 模型文件缺失或会话初始化失败
     */
    public OnnxVisionDetector(DetectorConfig config) {
        if (config == null || !config.usable()) {
            throw new VisionException("检测器配置不可用: " + config);
        }
        this.config = config;
        try {
            this.env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
            options.addCPU(true);
            this.session = env.createSession(config.modelPath(), options);
            this.inputName = session.getInputNames().iterator().next();
        } catch (OrtException e) {
            throw new VisionException("ONNX 会话初始化失败: model=" + config.modelId()
                    + " path=" + config.modelPath() + " err=" + e.getMessage(), e);
        }
        log.info("ONNX 检测器就绪: model={} input={} size={} labels={}",
                config.modelId(), inputName, config.inputSize(), config.labels().size());
    }

    @Override
    public String modelId() {
        return config.modelId();
    }

    @Override
    public List<String> labels() {
        return config.labels();
    }

    @Override
    public List<Detection> detect(Frame frame) {
        if (frame == null || !frame.usable()) {
            return List.of();
        }
        BufferedImage image = decode(frame);
        if (image == null) {
            return List.of();
        }
        float[] input = preprocess(image);
        synchronized (this) {
            return run(input);
        }
    }

    /** JPEG 字节 → 图像；解码失败返回 null（调用方按「无结果」处理，不抛异常）。 */
    private BufferedImage decode(Frame frame) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(frame.jpeg()));
            if (image == null) {
                log.debug("JPEG 解码失败: source={} bytes={}", frame.sourceId(), frame.jpeg().length);
            }
            return image;
        } catch (IOException e) {
            log.debug("JPEG 解码异常: source={} err={}", frame.sourceId(), e.getMessage());
            return null;
        }
    }

    /**
     * 缩放 + 归一化成模型输入（NCHW，值域 0~1）。
     *
     * <p>直接拉伸到正方形（不做 letterbox）：实现简单，且检测框按归一化坐标
     * 返回，拉伸带来的长宽比失真不影响框的位置（只影响小目标的检出率）。
     * 需要更高精度时再加 letterbox。
     */
    private float[] preprocess(BufferedImage image) {
        int size = config.inputSize();
        BufferedImage resized = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, size, size, null);
        g.dispose();

        float[] input = new float[3 * size * size];
        int plane = size * size;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int rgb = resized.getRGB(x, y);
                int i = y * size + x;
                input[i] = ((rgb >> 16) & 0xFF) / 255f;            // R
                input[plane + i] = ((rgb >> 8) & 0xFF) / 255f;     // G
                input[2 * plane + i] = (rgb & 0xFF) / 255f;        // B
            }
        }
        return input;
    }

    /** 跑一次会话并解析输出。 */
    private List<Detection> run(float[] input) {
        int size = config.inputSize();
        try (OnnxTensor tensor = OnnxTensor.createTensor(env,
                FloatBuffer.wrap(input), new long[]{1, 3, size, size});
             OrtSession.Result result = session.run(Map.of(inputName, tensor))) {
            Object value = result.get(0).getValue();
            if (!(value instanceof float[][][] output)) {
                log.warn("ONNX 输出类型不是 float[][][]: {}", value == null ? "null" : value.getClass());
                return List.of();
            }
            return parseOutput(output);
        } catch (OrtException e) {
            throw new VisionException("ONNX 推理失败: model=" + config.modelId()
                    + " err=" + e.getMessage(), e);
        }
    }

    /**
     * 解析 YOLO 导出格式输出 {@code [1, 84, 8400]}。
     *
     * <p>每列是一个候选框：前 4 个值是 cx/cy/w/h（相对输入尺寸），
     * 后 80 个是各类别置信度。取最大类别，超过阈值才保留，最后做 NMS。
     */
    private List<Detection> parseOutput(float[][][] output) {
        float[][] channels = output[0];
        if (channels.length < CHANNELS) {
            log.warn("ONNX 输出通道数不足: {} < {}", channels.length, CHANNELS);
            return List.of();
        }
        int count = channels[0].length;
        List<Detection> candidates = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            float cx = channels[0][i];
            float cy = channels[1][i];
            float w = channels[2][i];
            float h = channels[3][i];
            int bestClass = -1;
            float bestScore = 0f;
            for (int c = BOX_CHANNELS; c < CHANNELS; c++) {
                float score = channels[c][i];
                if (score > bestScore) {
                    bestScore = score;
                    bestClass = c - BOX_CHANNELS;
                }
            }
            if (bestClass < 0 || bestScore < config.confidenceThreshold()) {
                continue;
            }
            String label = config.labelOf(bestClass);
            if (label == null) {
                continue;
            }
            float x = clamp01(cx - w / 2f);
            float y = clamp01(cy - h / 2f);
            float bw = clamp01(w);
            float bh = clamp01(h);
            if (bw <= 0f || bh <= 0f) {
                continue;
            }
            candidates.add(new Detection(label, bestScore, new Box(x, y, bw, bh)));
        }
        return nms(candidates, 0.45f);
    }

    /** 非极大值抑制：同类框重叠度高时只留置信度最高的。 */
    private List<Detection> nms(List<Detection> detections, float iouThreshold) {
        detections.sort(Comparator.comparingDouble(Detection::confidence).reversed());
        List<Detection> kept = new ArrayList<>();
        for (Detection d : detections) {
            boolean suppressed = false;
            for (Detection k : kept) {
                if (k.label().equals(d.label()) && k.box().iou(d.box()) > iouThreshold) {
                    suppressed = true;
                    break;
                }
            }
            if (!suppressed) {
                kept.add(d);
            }
        }
        return kept;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            log.debug("ONNX 会话关闭异常: {}", e.getMessage());
        }
    }
}
