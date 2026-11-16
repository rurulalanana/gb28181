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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 火焰 / 烟雾模型的真实推理测试。
 *
 * <h3>这个模型是仓库里第一个<b>真实 YOLOv8</b> 权重</h3>
 * 它顺带暴露了一个存在很久的解析 bug：{@code YOLO_CHANNEL_FIRST} 分支
 * 一直假设框坐标是 0~1 归一化，而 Ultralytics 导出给的是<b>输入尺度像素</b>。
 * 见 {@link BoxSpaceDetectionTest} 与 {@code OnnxVisionDetector#parseChannelFirst}。
 * 所以本测试除了验证「模型能用」，还专门守住「框不贴满全屏」这条。
 *
 * <h3>模型来源与许可</h3>
 * <pre>
 *   权重  fiacecson20/cctv-ai-fire-smoke  best.onnx (opset 12, 11.58 MB)
 *   许可  权重 MIT；基座 YOLOv8n 为 AGPL-3.0，上游 README 明确提示
 *         「在托管服务中使用这些权重可能需要满足 AGPL 条款」
 *   类别  fire, smoke
 *   指标  val mAP@0.5 = 0.743（fire 0.912 / smoke 0.573）
 *   详情  models/MODELS.md
 * </pre>
 *
 * <h3>测试图来源</h3>
 * {@code src/test/resources/fire-sample.jpg} 是上游仓库发布的验证集预测图
 * （{@code val_batch0_pred.jpg}）里的一格裁切，用于验证「真图能检出火」。
 * 训练数据本身是 CC-BY 4.0，图片不随本项目再分发，仅作测试素材并在此注明出处。
 *
 * <h3>离线可跑</h3>
 * 模型不存在时 {@code assumeTrue} 跳过（允许只 clone 代码、没拉部署模型的场合）。
 */
class FireSmokeModelTest {

    private static final String MODEL_FILE = "fire_smoke_yolov8n.onnx";
    private static final List<String> LABELS = List.of("fire", "smoke");
    private static final int INPUT_SIZE = 320;
    /** 测试阈值：低于线上 0.45，让小目标也能过，便于断言「确实检出了火」。 */
    private static final float THRESHOLD = 0.25f;

    private static DetectorConfig config() {
        Path model = TestModels.locate(MODEL_FILE);
        assumeTrue(model != null, "模型文件不存在，跳过：" + MODEL_FILE);
        return new DetectorConfig("fire-smoke", model.toString(), THRESHOLD, INPUT_SIZE, LABELS,
                DetectorConfig.OutputFormat.YOLO_CHANNEL_FIRST,
                DetectorConfig.Preprocess.yolo());
    }

    /** 读测试资源图。 */
    private static BufferedImage resource(String name) throws Exception {
        try (var in = FireSmokeModelTest.class.getResourceAsStream("/" + name)) {
            assumeTrue(in != null, "测试资源不存在，跳过：" + name);
            return ImageIO.read(in);
        }
    }

    private static Frame frameOf(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return new Frame(out.toByteArray(), img.getWidth(), img.getHeight(),
                System.currentTimeMillis(), "test");
    }

    private static byte[] jpegOf(int w, int h, Color color) throws Exception {
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
    @DisplayName("模型可加载，类别是 fire / smoke")
    void loadsWithExpectedLabels() {
        try (OnnxVisionDetector detector = new OnnxVisionDetector(config())) {
            assertTrue(detector.labels().containsAll(LABELS),
                    "类别表必须包含 fire 和 smoke，实际: " + detector.labels());
            // 只有 2 类：COCO 80 类那套绝不能被误用（否则标签会串成 person/car）
            assertFalse(detector.labels().contains("person"),
                    "这是专用火焰模型，不该出现 COCO 标签");
        }
    }

    @Test
    @DisplayName("真实火焰图能检出 fire")
    void detectsFireOnRealImage() throws Exception {
        BufferedImage img = resource("fire-sample.jpg");
        try (OnnxVisionDetector detector = new OnnxVisionDetector(config())) {
            List<Detection> dets = detector.detect(frameOf(img));

            assertFalse(dets.isEmpty(), "这张图整幅都是火焰，不该一个都没检出");

            boolean hasFire = dets.stream().anyMatch(d -> "fire".equals(d.label()));
            assertTrue(hasFire, "必须检出 fire 类，实际标签: "
                    + dets.stream().map(Detection::label).toList());

            float best = dets.stream()
                    .filter(d -> "fire".equals(d.label()))
                    .map(Detection::confidence)
                    .max(Float::compare)
                    .orElse(0f);
            assertTrue(best >= THRESHOLD, "fire 置信度必须过阈值，实际 " + best);
        }
    }

    @Test
    @DisplayName("框不能贴满全屏（坐标量纲搞错时的指纹）")
    void boxesAreNotFullFrame() throws Exception {
        BufferedImage img = resource("fire-sample.jpg");
        try (OnnxVisionDetector detector = new OnnxVisionDetector(config())) {
            List<Detection> dets = detector.detect(frameOf(img));
            assertFalse(dets.isEmpty(), "前置条件：需要有检出才能检查框尺寸");

            /*
             * 坐标量纲搞错（像素当归一化）时，cx/cy 会被乘 inputSize 再除回去，
             * 结果全部 clamp01 成 1.0 → 框恒为 [1,1,1,1]。
             * 所以「没有任何一个框同时接近满宽满高」就是这条 bug 的判据。
             */
            for (Detection d : dets) {
                Box b = d.box();
                boolean fullFrame = b.w() > 0.98f && b.h() > 0.98f;
                assertFalse(fullFrame,
                        "框贴满整屏说明坐标量纲判错：" + d.label() + " " + b);
            }
        }
    }

    @Test
    @DisplayName("全同色空画面不应报出 fire（负样本）")
    void noFireOnBlankImage() throws Exception {
        try (OnnxVisionDetector detector = new OnnxVisionDetector(config())) {
            // 纯中灰：既不是火色也不是黑场，误报概率最低
            byte[] blank = jpegOf(640, 480, new Color(128, 128, 128));
            List<Detection> dets = detector.detect(
                    new Frame(blank, 640, 480, System.currentTimeMillis(), "test"));

            boolean hasFire = dets.stream().anyMatch(d -> "fire".equals(d.label()));
            assertFalse(hasFire, "纯灰画面报 fire 就是误报，实际: "
                    + dets.stream().map(d -> d.label() + "@" + d.confidence()).toList());
        }
    }

    @Test
    @DisplayName("非方形输入也能正确还原框（去缩放比例要分方向）")
    void handlesNonSquareFrame() throws Exception {
        BufferedImage src = resource("fire-sample.jpg");
        // 拉成 16:9：模型输入是 320 方形，ratioX ≠ ratioY
        BufferedImage wide = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = wide.createGraphics();
        g.drawImage(src, 0, 0, 640, 360, null);
        g.dispose();

        try (OnnxVisionDetector detector = new OnnxVisionDetector(config())) {
            List<Detection> dets = detector.detect(frameOf(wide));
            for (Detection d : dets) {
                Box b = d.box();
                // 框必须落在画面内；分方向还原漏做时会出现越界或整体缩到左上角
                assertTrue(b.x() >= 0f && b.x() <= 1f, "x 越界: " + b);
                assertTrue(b.y() >= 0f && b.y() <= 1f, "y 越界: " + b);
                assertTrue(b.x() + b.w() <= 1.001f, "右边界越界: " + b);
                assertTrue(b.y() + b.h() <= 1.001f, "下边界越界: " + b);
            }
        }
    }
}
