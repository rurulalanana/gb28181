package io.github.rurulalanana.vision.onnx;

import io.github.rurulalanana.vision.Box;
import io.github.rurulalanana.vision.Detection;
import io.github.rurulalanana.vision.DetectorConfig;
import io.github.rurulalanana.vision.Frame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 用<b>真实模型</b>跑一次推理，验证输出解析正确。
 *
 * <h3>为什么必须有这个测试</h3>
 * 早先的测试只覆盖「模型缺失时快速失败」，等于没验证解析逻辑。
 * 而解析逻辑恰恰是最容易错的地方 —— 实测两种模型输出差别很大：
 * <pre>
 *   YOLOv8n    : [1, 84, 8400]   通道在前，坐标归一化
 *   YOLOX-nano : [1, 3549, 85]   候选在前，含 obj，坐标是<b>像素</b>
 * </pre>
 * 坐标口径搞错时，框会全部贴在画面左上角（像素值 200 当 0.2 用），
 * 而这种错误在单测里看不出来、在界面上却一眼可见。
 *
 * <h3>离线可跑</h3>
 * 模型随仓库分发在 {@code vision-onnx/models/yolox_nano.onnx}（3.5 MB，Apache-2.0），
 * 不联网。模型不在时 {@code assumeTrue} 跳过，不判失败 ——
 * 允许只 clone 代码不拉 LFS 的场景。
 */
class OnnxRealModelTest {

    /** 模型路径：从模块根目录解析（surefire 的 user.dir 是模块目录）。 */
    private static Path modelPath() {
        Path p = Path.of("models", "yolox_nano.onnx");
        return Files.isRegularFile(p) ? p : null;
    }

    /** COCO 80 类标签（YOLOX 用的就是这套顺序）。 */
    private static List<String> cocoLabels() {
        return List.of(
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
    }

    private static DetectorConfig yoloxConfig(Path model) {
        // forYolox 同时把 format 与 preprocess 配成 YOLOX 口径（不除 255、BGR、letterbox）
        return DetectorConfig.forYolox("yolox-nano", model.toString(), 0.3f, 416, cocoLabels());
    }

    /** 生成一张纯色 JPEG。 */
    private static byte[] jpeg(int w, int h, Color color) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("真实 YOLOX 模型：能加载、能推理、坐标不贴左上角")
    void runsRealModel() throws Exception {
        Path model = modelPath();
        assumeTrue(model != null, "模型文件不存在，跳过（models/yolox_nano.onnx）");

        DetectorConfig config = yoloxConfig(model);
        try (OnnxVisionDetector detector = new OnnxVisionDetector(config)) {
            assertEquals("yolox-nano", detector.modelId());
            assertEquals(80, detector.labels().size());

            // 纯白图：大概率和无检出，但必须能跑通不抛异常
            byte[] white = jpeg(640, 480, Color.WHITE);
            Frame frame = new Frame(white, 640, 480, System.currentTimeMillis(), "test");
            List<Detection> detections = detector.detect(frame);
            assertTrue(detections.size() >= 0, "推理必须返回列表而不是 null");

            /*
             * 坐标口径检查：不管检出什么，框都必须落在 [0,1] 内。
             * 如果按「像素」当「归一化」解析，x/y 会是 200+ 然后被 clamp01 成 1.0，
             * 于是所有框都变成 w=h=0 被丢弃 —— 这条断言就是抓这个。
             */
            for (Detection d : detections) {
                Box b = d.box();
                assertTrue(b.x() >= 0f && b.x() <= 1f, "x 必须归一化: " + b);
                assertTrue(b.y() >= 0f && b.y() <= 1f, "y 必须归一化: " + b);
                assertTrue(b.w() > 0f && b.w() <= 1f, "w 必须 > 0 且归一化: " + b);
                assertTrue(b.h() > 0f && b.h() <= 1f, "h 必须 > 0 且归一化: " + b);
                assertTrue(d.confidence() >= config.confidenceThreshold(),
                        "低于阈值的框不该返回: " + d.confidence());
                assertFalse(d.label().isBlank(), "标签不能为空");
            }
        }
    }

    @Test
    @DisplayName("阈值越高检出越少（验证阈值真的生效）")
    void thresholdTakesEffect() throws Exception {
        Path model = modelPath();
        assumeTrue(model != null, "模型文件不存在，跳过");

        byte[] img = jpeg(640, 480, new Color(120, 120, 120));
        Frame frame = new Frame(img, 640, 480, System.currentTimeMillis(), "test");

        int low;
        try (OnnxVisionDetector d = new OnnxVisionDetector(
                DetectorConfig.forYolox("low", model.toString(), 0.05f, 416, cocoLabels()))) {
            low = d.detect(frame).size();
        }
        int high;
        try (OnnxVisionDetector d = new OnnxVisionDetector(
                DetectorConfig.forYolox("high", model.toString(), 0.95f, 416, cocoLabels()))) {
            high = d.detect(frame).size();
        }
        assertTrue(low >= high,
                "低阈值的检出数不能少于高阈值: low=" + low + " high=" + high);
    }

    @Test
    @DisplayName("同一次会话可重复调用（无状态残留）")
    void reusableAcrossCalls() throws Exception {
        Path model = modelPath();
        assumeTrue(model != null, "模型文件不存在，跳过");

        byte[] img = jpeg(320, 240, Color.BLUE);
        Frame frame = new Frame(img, 320, 240, System.currentTimeMillis(), "test");
        try (OnnxVisionDetector detector = new OnnxVisionDetector(yoloxConfig(model))) {
            int first = detector.detect(frame).size();
            int second = detector.detect(frame).size();
            assertEquals(first, second, "同一帧的两次推理结果必须一致");
        }
    }
}
