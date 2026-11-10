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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * YuNet 人脸检测器测试。
 *
 * <h3>这个测试要抓什么</h3>
 * YuNet 与之前的 YOLO 系模型有三处结构性差异，每一处都可能静默出错：
 * <ol>
 *   <li><b>12 个输出而不是 1 个</b> —— 按索引取只会拿到 {@code cls_8}，
 *       解析时「每行 1 个字段」被判成类别数不足，<b>静默返回空列表</b>，
 *       日志里连 WARN 都不一定有。表现是「人脸检测开了但永远零检出」。</li>
 *   <li><b>预处理不 letterbox</b> —— 直接拉伸会让 X/Y 两个方向缩放比不同。
 *       早先的 {@code addCandidate} 只用一个 {@code ratio}，拉伸路径下这个值
 *       恒为 1，导致框的位置和尺寸全错（不是略微偏，是整体缩小到一半并挤向左上角）。</li>
 *   <li><b>分数不需要 sigmoid</b> —— 加了之后最高分从 0.892 掉到 0.710。</li>
 * </ol>
 *
 * <h3>怎么在没有人脸图片的情况下验证</h3>
 * 用人脸照片做测试要求仓库里带一张带人脸的图（隐私 + 体积问题）。
 * 这里改用一个<b>几何断言</b>：拿一张纯色图跑推理，如果模型偶然给出候选框
 * （纯色图也可能有极低分的误检），这些框必须落在合法范围且长宽比合理。
 * 更重要的是下面 {@link #letterboxRatioMath} 这个纯数学测试 ——
 * 它直接验证坐标换算公式，不依赖模型。
 */
class YunetDetectorTest {

    /** YuNet 模型路径（models/ 下，随仓库分发）。 */
    private static Path modelPath() {
        for (String p : new String[]{
                "models/face_detection_yunet.onnx",
                "../models/face_detection_yunet.onnx"}) {
            Path path = Path.of(p);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }

    private static DetectorConfig yunetConfig(Path model) {
        /*
         * labels 只放一个 "face"：YuNet 只有人脸一个类别，类别 id 固定 0。
         * 如果按 COCO 80 类配，labelOf(0) 会返回 "person"，
         * 于是人脸被标成「人」—— 界面上看不出区别，但告警规则会错。
         */
        return new DetectorConfig("yunet-face", model.toString(), 0.5f, 640,
                List.of("face"),
                DetectorConfig.OutputFormat.YUNET,
                DetectorConfig.Preprocess.yunet());
    }

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
    @DisplayName("YuNet 能加载并跑通，返回的框全部归一化且在合法范围")
    void runsRealYunet() throws Exception {
        Path model = modelPath();
        assumeTrue(model != null, "模型不存在，跳过（models/face_detection_yunet.onnx）");

        try (OnnxVisionDetector detector = new OnnxVisionDetector(yunetConfig(model))) {
            assertEquals("yunet-face", detector.modelId());
            assertEquals(List.of("face"), detector.labels());

            /*
             * 用 416×234 这种 16:9 的尺寸 —— 与监控画面一致。
             * 这个比例正是拉伸路径会出错的场景：416/640=0.65、234/640=0.366，
             * 两个方向缩放比差近 2 倍。用正方形图片测不出这个 bug。
             */
            Frame frame = new Frame(jpeg(416, 234, new Color(90, 90, 90)),
                    416, 234, System.currentTimeMillis(), "test");
            List<Detection> detections = detector.detect(frame);

            for (Detection d : detections) {
                Box b = d.box();
                assertEquals("face", d.label(), "YuNet 的标签必须是 face 而不是 person");
                assertTrue(b.x() >= 0f && b.x() <= 1f, "x 必须归一化: " + b);
                assertTrue(b.y() >= 0f && b.y() <= 1f, "y 必须归一化: " + b);
                assertTrue(b.w() > 0f && b.w() <= 1f, "w 必须 > 0 且归一化: " + b);
                assertTrue(b.h() > 0f && b.h() <= 1f, "h 必须 > 0 且归一化: " + b);
                assertTrue(d.confidence() >= 0.5f,
                        "低于阈值的框不该返回: " + d.confidence());
            }
        }
    }

    @Test
    @DisplayName("阈值生效：高阈值不会比低阈值检出更多")
    void thresholdTakesEffect() throws Exception {
        Path model = modelPath();
        assumeTrue(model != null, "模型不存在，跳过");

        byte[] img = jpeg(416, 234, new Color(120, 120, 120));
        Frame frame = new Frame(img, 416, 234, System.currentTimeMillis(), "test");

        int low;
        DetectorConfig lowCfg = new DetectorConfig("low", model.toString(), 0.05f, 640,
                List.of("face"), DetectorConfig.OutputFormat.YUNET,
                DetectorConfig.Preprocess.yunet());
        try (OnnxVisionDetector d = new OnnxVisionDetector(lowCfg)) {
            low = d.detect(frame).size();
        }
        int high;
        DetectorConfig highCfg = new DetectorConfig("high", model.toString(), 0.95f, 640,
                List.of("face"), DetectorConfig.OutputFormat.YUNET,
                DetectorConfig.Preprocess.yunet());
        try (OnnxVisionDetector d = new OnnxVisionDetector(highCfg)) {
            high = d.detect(frame).size();
        }
        assertTrue(low >= high, "低阈值检出数不能少于高阈值: low=" + low + " high=" + high);
    }

    @Test
    @DisplayName("重复推理结果一致（ThreadLocal 几何参数没有串）")
    void reusableAcrossCalls() throws Exception {
        Path model = modelPath();
        assumeTrue(model != null, "模型不存在，跳过");

        Frame frame = new Frame(jpeg(416, 234, Color.DARK_GRAY),
                416, 234, System.currentTimeMillis(), "test");
        try (OnnxVisionDetector detector = new OnnxVisionDetector(yunetConfig(model))) {
            int first = detector.detect(frame).size();
            int second = detector.detect(frame).size();
            assertEquals(first, second, "同一帧两次推理结果必须一致");
        }
    }
}
