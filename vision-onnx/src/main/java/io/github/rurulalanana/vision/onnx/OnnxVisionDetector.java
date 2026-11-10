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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ONNX Runtime 实现的 {@link VisionDetector}。
 *
 * <p><b>只做三件事</b>：JPEG 解码 → 缩放归一化成模型输入 → 跑会话并解析输出。
 * 不读数据库、不发网络、不写文件 —— 模型文件路径由 {@link DetectorConfig} 给出，
 * 随包分发，禁止运行期联网下载。
 *
 * <p><b>输出解析按 {@link DetectorConfig.OutputFormat} 分派</b>：支持 YOLOv8 的
 * {@code [1, 4+C, N]}、YOLOv5/v7 的 {@code [1, N, 4+C]}、YOLOX 的 {@code [1, N, 5+C]}。
 * 换模型只改配置，不动代码。
 *
 * <p><b>线程安全</b>：ONNX 会话的 {@code run} 本身是线程安全的（内部按需加锁），
 * 所以这里<b>不</b>用 {@code synchronized} —— 早先加了会把多路推理串行化，
 * 8 路退化成一路上限。多路之间的并发控制交给流水线的线程池与队列。
 * 如果发现 CPU 抢占严重，应当调小 {@code workerThreads}，而不是在这里串行化。
 */
public final class OnnxVisionDetector implements VisionDetector {

    private static final Logger log = LoggerFactory.getLogger(OnnxVisionDetector.class);

    /** 前 4 个通道/字段是框：cx, cy, w, h。 */
    private static final int BOX_CHANNELS = 4;

    private final DetectorConfig config;
    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;

    /**
     * 当前线程上一次 {@link #preprocess} 的几何参数
     * {@code [ratioX, ratioY, origW, origH]}（<b>两个方向分别的</b>缩放比 + 原图宽高）。
     *
     * <h3>为什么必须拆成 ratioX / ratioY（这个 bug 真实存在过）</h3>
     * 早先只存一个 {@code ratio}，letterbox 路径下是对的（等比缩放），
     * 但<b>非 letterbox 的拉伸路径</b>（{@code Preprocess.yolo()} / {@code yunet()}）
     * 把 1280×720 拉成 640×640 时，水平缩了 2 倍、垂直缩了 1.125 倍，
     * 两个方向不一样。用一个 ratio=1 去还原，算出来的是
     * {@code x_norm * size / origW = 0.5}，框会整体缩小一半并挤到左上角。
     *
     * <p>YOLOv8 走的就是拉伸路径，所以这个隐患一直存在 —— 只是线上两个
     * 模型（yolox-nano / yolox-tiny）都用 letterbox，没被触发。
     * 接 YuNet 时才发现：人脸框位置全错。
     *
     * <p>用 {@link ThreadLocal} 而不是实例字段：同一个检测器实例会被流水线的
     * 多个工作线程并发调用，实例字段会串（A 线程的缩放比被 B 线程覆盖，
     * 框位置随之漂移）。
     */
    private final ThreadLocal<float[]> geometry =
            ThreadLocal.withInitial(() -> new float[]{1f, 1f, 0f, 0f});

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
        return run(input);
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
     * 缩放 + 归一化成模型输入（NCHW）。
     *
     * <h3>为什么必须支持 letterbox（踩过的坑）</h3>
     * 直接拉伸到正方形会改变长宽比。对 16:9 的监控画面（1280×720）拉伸成 416×416，
     * 水平方向被压缩 3 倍多 —— 站立的行人会变成细长的竖条，检出率断崖式下降。
     *
     * <p>更要命的是<b>预处理口径必须和模型训练时一致</b>。YOLOX 官方 {@code preproc}：
     * <pre>
     *   padded = np.ones((input, input, 3)) * 114   # 填充 114
     *   r = min(input/h, input/w)                   # 保持长宽比
     *   padded[:resized_h, :resized_w] = resized    # 贴左上角
     *   return padded.transpose(2,0,1), r           # <b>不除 255</b>，BGR
     * </pre>
     * 而 YOLOv8 是 {@code /255} + RGB。用错口径的症状是「框位置看着还行、
     * 但分数全挤在 0.31~0.34」—— 模型在乱猜，却不会报任何错。
     *
     * <p>几何参数（两个方向的缩放比 + 原图宽高）记录在 {@link #geometry}（ThreadLocal），
     * 由 {@link #addCandidate} 用来把框还原到原图。
     */
    private float[] preprocess(BufferedImage image) {
        DetectorConfig.Preprocess spec = config.preprocessSpec();
        int size = config.inputSize();

        int origW = Math.max(1, image.getWidth());
        int origH = Math.max(1, image.getHeight());
        int drawW;
        int drawH;
        float ratioX;
        float ratioY;
        if (spec.letterbox()) {
            // 等比缩放，两方向同一个比：取较小的那个，保证整张图都进得去
            float ratio = Math.min((float) size / origW, (float) size / origH);
            drawW = Math.max(1, Math.round(origW * ratio));
            drawH = Math.max(1, Math.round(origH * ratio));
            ratioX = ratio;
            ratioY = ratio;
        } else {
            // 直接拉伸：两方向各自缩放，比例不同（这正是长宽比会被改变的原因）
            drawW = size;
            drawH = size;
            ratioX = (float) size / origW;
            ratioY = (float) size / origH;
        }
        // 记录【两个方向的缩放比 + 原图宽高】，供 addCandidate 把框还原到原图
        geometry.set(new float[]{ratioX, ratioY, origW, origH});

        BufferedImage canvas = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        if (spec.letterbox()) {
            int pad = spec.padValue();
            g.setColor(new java.awt.Color(pad, pad, pad));
            g.fillRect(0, 0, size, size);
        }
        g.drawImage(image, 0, 0, drawW, drawH, null);
        g.dispose();

        float scale = spec.valueScale();
        boolean bgr = spec.channels() == DetectorConfig.Preprocess.ChannelOrder.BGR;
        float[] input = new float[3 * size * size];
        int plane = size * size;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int rgb = canvas.getRGB(x, y);
                int i = y * size + x;
                int r = (rgb >> 16) & 0xFF;
                int gg = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                input[i] = (bgr ? b : r) * scale;
                input[plane + i] = gg * scale;
                input[2 * plane + i] = (bgr ? r : b) * scale;
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
            /*
             * YuNet 有 12 个输出，必须按名字取；其它模型只有 1 个，走 get(0)。
             * 不能统一按名字取：YOLOX 导出的输出名是 "output"，但用户的模型
             * 可能叫别的名字，按索引取才是兼容的。
             */
            if (config.outputFormat() == DetectorConfig.OutputFormat.YUNET) {
                return parseYunet(result);
            }
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
     * 解析 YuNet 的 12 个输出。
     *
     * <h3>输出怎么组合（每个尺度 4 个张量）</h3>
     * <pre>
     *   cls_s   [1, N, 1]   分类分数（已是概率）
     *   obj_s   [1, N, 1]   目标性分数（已是概率）
     *   bbox_s  [1, N, 4]   框回归量（<b>未解码</b>）
     *   kps_s   [1, N, 10]  5 个关键点（本平台不用）
     *   score = sqrt(cls × obj)                    ← 几何平均，不是相乘
     *   cx = (raw_x + grid_x) * stride
     *   cy = (raw_y + grid_y) * stride
     *   w  = exp(raw_w) * stride
     *   h  = exp(raw_h) * stride
     * </pre>
     *
     * <h3>三个必须注意的点（都是实测踩出来的）</h3>
     * <ol>
     *   <li><b>按名字取，不按索引</b>：ONNX 不保证输出顺序稳定。实测导出的顺序是
     *       {@code cls_8, cls_16, cls_32, obj_8, ...}（同类聚在一起），
     *       而不是「一个尺度的四个张量挨着」。按索引取会把 obj 当 cls。</li>
     *   <li><b>不要再 sigmoid</b>：cls/obj 导出时已经是概率。加 sigmoid 后
     *       最高分从 0.892 掉到 0.710 —— 分数整体被压平，阈值 0.45 下会漏检。</li>
     *   <li><b>网格顺序 row-major</b>：{@code gy} 外层、{@code gx} 内层，
     *       与训练时的 {@code meshgrid} 展开一致。反了的话框会沿对角线错位。</li>
     * </ol>
     */
    private List<Detection> parseYunet(OrtSession.Result result) throws OrtException {
        int size = config.inputSize();
        int[] strides = {8, 16, 32};
        Map<String, float[][]> tensors = new HashMap<>();
        for (Map.Entry<String, ai.onnxruntime.OnnxValue> e : result) {
            Object v = e.getValue().getValue();
            if (v instanceof float[][][] arr && arr.length > 0) {
                tensors.put(e.getKey(), arr[0]);
            }
        }
        if (tensors.isEmpty()) {
            log.warn("YuNet 输出为空或类型不符: model={}", config.modelId());
            return List.of();
        }

        List<Detection> candidates = new ArrayList<>();
        for (int stride : strides) {
            float[][] cls = tensors.get("cls_" + stride);
            float[][] obj = tensors.get("obj_" + stride);
            float[][] bbox = tensors.get("bbox_" + stride);
            if (cls == null || obj == null || bbox == null) {
                log.warn("YuNet 缺少尺度 {} 的输出: model={}", stride, config.modelId());
                continue;
            }
            int side = size / stride;
            int expected = side * side;
            if (cls.length < expected || bbox.length < expected) {
                log.warn("YuNet 尺度 {} 候选数不足: 期望 {} 实际 cls={} bbox={}",
                        stride, expected, cls.length, bbox.length);
                continue;
            }
            for (int gy = 0; gy < side; gy++) {
                for (int gx = 0; gx < side; gx++) {
                    int i = gy * side + gx;
                    // cls/obj 已是概率，直接几何平均
                    float score = (float) Math.sqrt(
                            Math.max(0f, cls[i][0]) * Math.max(0f, obj[i][0]));
                    if (score < config.confidenceThreshold()) {
                        continue;
                    }
                    float[] r = bbox[i];
                    float cx = (r[0] + gx) * stride;
                    float cy = (r[1] + gy) * stride;
                    float w = (float) (Math.exp(r[2]) * stride);
                    float h = (float) (Math.exp(r[3]) * stride);
                    /*
                     * YuNet 只有「人脸」一个类别（类别 id 固定 0）。
                     * 直接传 0 给 addCandidate，它会按 config.labels() 取标签，
                     * 而人脸模型的 labels 配成 ["face"]。
                     */
                    addCandidate(candidates, 0, score, cx, cy, w, h, false);
                }
            }
        }
        return nms(candidates, 0.3f);
    }

    /**
     * 按 {@link DetectorConfig#outputFormat()} 分派解析。
     *
     * <p><b>为什么不能写死一种格式</b>：实测两种模型的输出差别很大 ——
     * <pre>
     *   YOLOv8n    : [1, 84, 8400]   通道在前，84 = 4 框 + 80 类，坐标归一化
     *   YOLOX-nano : [1, 3549, 85]   候选在前，85 = 4 框 + 1 obj + 80 类，
     *                                但前 4 个值是<b>未解码的网格偏移与 log 尺度</b>
     * </pre>
     */
    private List<Detection> parseOutput(float[][][] output) {
        if (output.length == 0 || output[0].length == 0) {
            return List.of();
        }
        float[][] a = output[0];
        return switch (config.outputFormat()) {
            case YOLO_CHANNEL_FIRST -> parseChannelFirst(a);
            case YOLO_ANCHOR_FIRST -> parseAnchorFirst(a, false, false, false);
            case YOLOX -> parseYolox(a);
            // 不该走到这里：YUNET 在 run() 里已按多输出提前分派
            case YUNET -> {
                log.warn("YUNET 格式走了单输出解析分支，这是实现错误: model={}", config.modelId());
                yield List.of();
            }
        };
    }

    /**
     * 解析 YOLOX 官方导出的<b>原始 head 输出</b> {@code [1, N, 5+C]}。
     *
     * <h3>为什么不能直接读 cx/cy/w/h（这个坑踩过）</h3>
     * 官方 {@code yolox_nano.onnx}（Megvii releases 0.1.1rc0）输出的是
     * <b>未解码</b>的 head 张量，必须按 YOLOX 的 {@code demo_postprocess} 还原：
     * <pre>
     *   cx = (raw[0] + gridX) * stride
     *   cy = (raw[1] + gridY) * stride
     *   w  = exp(raw[2]) * stride
     *   h  = exp(raw[3]) * stride
     *   obj   = sigmoid(raw[4])
     *   cls   = max(sigmoid(raw[5:]))
     *   score = obj × cls
     * </pre>
     * 把 {@code raw[0..3]} 当坐标直接用的后果：raw 值在 0 附近，
     * 于是<b>所有框都塌到画面左上角、尺寸只有几像素</b>，
     * 而置信度会出现 7.879 这种超出 0~1 的值（logit 没过 sigmoid）。
     * 这两个现象同时出现，就是「没做 postprocess」的典型指纹。
     *
     * <h3>网格顺序</h3>
     * 三个 stride 依次是 8/16/32，特征图边长 {@code inputSize/stride}
     * （416 → 52/26/13，共 {@code 52²+26²+13² = 3549} 个候选，与输出维度吻合）。
     * 遍历顺序是「stride 外层 → y → x」，与 {@code meshgrid} 的 row-major 展开一致。
     */
    private List<Detection> parseYolox(float[][] rows) {
        int size = config.inputSize();
        int[] strides = {8, 16, 32};
        int perRow = rows[0].length;
        int classStart = BOX_CHANNELS + 1;
        int classCount = perRow - classStart;
        if (classCount <= 0) {
            log.warn("YOLOX 输出每行字段数不足: {}", perRow);
            return List.of();
        }
        List<Detection> candidates = new ArrayList<>();
        int idx = 0;
        for (int stride : strides) {
            int side = size / stride;
            for (int gy = 0; gy < side; gy++) {
                for (int gx = 0; gx < side; gx++) {
                    if (idx >= rows.length) {
                        break;
                    }
                    float[] r = rows[idx++];
                    // 先看 obj，绝大多数候选在这里就被否掉，省掉类别循环
                    float obj = sigmoid(r[BOX_CHANNELS]);
                    if (obj < config.confidenceThreshold()) {
                        continue;
                    }
                    int bestClass = -1;
                    float bestScore = 0f;
                    for (int c = 0; c < classCount; c++) {
                        float score = sigmoid(r[classStart + c]);
                        if (score > bestScore) {
                            bestScore = score;
                            bestClass = c;
                        }
                    }
                    float score = obj * bestScore;
                    if (bestClass < 0 || score < config.confidenceThreshold()) {
                        continue;
                    }
                    // 解码：网格偏移 + stride，宽高是 exp 尺度
                    float cx = (r[0] + gx) * stride;
                    float cy = (r[1] + gy) * stride;
                    float w = (float) (Math.exp(r[2]) * stride);
                    float h = (float) (Math.exp(r[3]) * stride);
                    // YOLOX 给的是模型输入尺度的像素 → normalized=false
                    addCandidate(candidates, bestClass, score, cx, cy, w, h, false);
                }
            }
        }
        return nms(candidates, 0.45f);
    }


    /**
     * {@code [1, 4+C, N]}：每列一个候选框，前 4 个是 cx/cy/w/h（归一化），
     * 后 C 个是各类别置信度。YOLOv8/v11 默认导出。
     */
    private List<Detection> parseChannelFirst(float[][] channels) {
        int count = channels[0].length;
        int classCount = channels.length - BOX_CHANNELS;
        if (classCount <= 0) {
            log.warn("ONNX 输出通道数不足: {} <= {}", channels.length, BOX_CHANNELS);
            return List.of();
        }
        List<Detection> candidates = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int bestClass = -1;
            float bestScore = 0f;
            for (int c = 0; c < classCount; c++) {
                float score = channels[BOX_CHANNELS + c][i];
                if (score > bestScore) {
                    bestScore = score;
                    bestClass = c;
                }
            }
            if (bestClass < 0 || bestScore < config.confidenceThreshold()) {
                continue;
            }
            // YOLOv8 给的是 0~1 归一化坐标 → normalized=true
            addCandidate(candidates, bestClass, bestScore,
                    channels[0][i], channels[1][i], channels[2][i], channels[3][i], true);
        }
        return nms(candidates, 0.45f);
    }

    /**
     * {@code [1, N, 5+C]} 或 {@code [1, N, 4+C]}：每行一个候选框，坐标<b>已解码</b>。
     *
     * <p>适用于 YOLOv5/v7 的 ONNX 导出 —— 它们的导出脚本已经把网格解码做掉了。
     * 坐标单位由 {@code normalized} 声明（YOLOv5 默认给像素）。
     *
     * @param hasObjectness {@code true} 表示第 5 个值是 obj 分数
     * @param applySigmoid  {@code true} 表示输出是 logits，需要过 sigmoid
     * @param normalized    {@code true} 表示 cx/cy/w/h 已是 0~1，{@code false} 表示是像素
     */
    private List<Detection> parseAnchorFirst(float[][] rows, boolean hasObjectness,
                                             boolean applySigmoid, boolean normalized) {
        int count = rows.length;
        int perRow = rows[0].length;
        int classStart = hasObjectness ? BOX_CHANNELS + 1 : BOX_CHANNELS;
        int classCount = perRow - classStart;
        if (classCount <= 0) {
            log.warn("ONNX 输出每行字段数不足: {} (obj={})", perRow, hasObjectness);
            return List.of();
        }
        List<Detection> candidates = new ArrayList<>();
        for (float[] r : rows) {
            int bestClass = -1;
            float bestScore = 0f;
            for (int c = 0; c < classCount; c++) {
                float score = r[classStart + c];
                if (score > bestScore) {
                    bestScore = score;
                    bestClass = c;
                }
            }
            if (bestClass < 0) {
                continue;
            }
            float objScore = hasObjectness ? r[BOX_CHANNELS] : 1f;
            float score = hasObjectness ? objScore * bestScore : bestScore;
            if (applySigmoid) {
                // logits → 概率
                score = hasObjectness
                        ? sigmoid(objScore) * sigmoid(bestScore)
                        : sigmoid(bestScore);
            }
            if (score < config.confidenceThreshold()) {
                continue;
            }
            addCandidate(candidates, bestClass, score, r[0], r[1], r[2], r[3], normalized);
        }
        return nms(candidates, 0.45f);
    }

    /** 数值稳定的 sigmoid。 */
    private static float sigmoid(float x) {
        return (float) (1.0 / (1.0 + Math.exp(-x)));
    }

    /**
     * 把模型输入坐标 {@code cx/cy/w/h} 转成<b>原图</b>的归一化 {@code x/y/w/h} 并加入候选。
     *
     * <p>三步换算，顺序不能乱：
     * <ol>
     *   <li><b>像素化</b>：模型坐标是「相对 inputSize 的像素」（YOLOX 直接给像素，
     *       YOLOv8 给 0~1），统一乘 {@code inputSize} 得到模型输入尺度上的像素值</li>
     *   <li><b>去 letterbox</b>：除以缩放比 {@code ratio}，还原到原图尺度。
     *       注意 letterbox 是「贴左上角」，没有居中偏移，所以只需除 ratio</li>
     *   <li><b>归一化</b>：除以原图宽高，得到 0~1 的 {@code Box}</li>
     * </ol>
     *
     * <p>漏掉第 2 步的后果：框会整体缩小并偏向左上角 —— 16:9 画面在 416 方形里
     * 只占上 234 像素高，不还原的话检测框的高度只有实际的一半左右。
     *
     * <p><b>第 2 步必须用两个方向的缩放比</b>（{@code ratioX} / {@code ratioY}）：
     * letterbox 是等比缩放，两个比相同；而拉伸路径（YOLOv8 / YuNet）两个比不同，
     * 用一个统一的值会让框在水平或垂直方向错位。
     *
     * @param normalized {@code true} 表示入参已是 0~1（YOLOv8），{@code false} 表示是像素（YOLOX）
     */
    private void addCandidate(List<Detection> out, int classId, float score,
                              float cx, float cy, float w, float h, boolean normalized) {
        String label = config.labelOf(classId);
        if (label == null) {
            return;
        }
        int size = Math.max(1, config.inputSize());
        float[] geo = geometry.get();
        float ratioX = geo[0] > 0 ? geo[0] : 1f;
        float ratioY = geo[1] > 0 ? geo[1] : 1f;
        float origW = geo[2] > 0 ? geo[2] : size;
        float origH = geo[3] > 0 ? geo[3] : size;

        // ① 统一到「模型输入尺度的像素」
        float scale = normalized ? size : 1f;
        cx *= scale;
        cy *= scale;
        w *= scale;
        h *= scale;
        // ② 去缩放 → 原图像素（两方向各用自己的比）
        cx /= ratioX;
        cy /= ratioY;
        w /= ratioX;
        h /= ratioY;
        // ③ 归一化到原图
        float x = clamp01((cx - w / 2f) / origW);
        float y = clamp01((cy - h / 2f) / origH);
        float bw = clamp01(w / origW);
        float bh = clamp01(h / origH);
        if (bw <= 0f || bh <= 0f) {
            return;
        }
        out.add(new Detection(label, score, new Box(x, y, bw, bh)));
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
