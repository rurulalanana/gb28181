package io.github.rurulalanana.starter.service.vision;

import io.github.rurulalanana.vision.Box;
import io.github.rurulalanana.vision.Detection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 告警截图上画检测框。
 *
 * <h3>为什么这段逻辑要测</h3>
 * 它出错的形态很隐蔽：框画歪了、颜色选错、把图编码坏了 ——
 * 而截图一旦存坏，那条告警的证据就永久没了（历史数据不可重算）。
 * 所以这里既验「画出去了」，也验「画坏的图仍能解码」「异常时回退原图」。
 *
 * <p>不校验像素级外观（那太脆），而是验<b>颜色出现在它该出现的区域</b>：
 * 把一个框放在左上四分之一，然后断言那个区域里出现了该标签的代表色，
 * 而右下四分之一没有。框画错位置（比如坐标没乘宽高）会立刻失败。
 */
class DetectionAnnotatorTest {

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

    private static BufferedImage decode(byte[] jpeg) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }

    private static Detection det(String label, float conf, float x, float y, float w, float h) {
        return new Detection(label, conf, new Box(x, y, w, h), Map.of());
    }

    /** 统计区域内「接近该颜色」的像素数（JPEG 有损，用距离判断而不是精确相等）。 */
    private static int countNear(BufferedImage img, int x0, int y0, int x1, int y1, Color target) {
        int n = 0;
        for (int y = Math.max(0, y0); y < Math.min(img.getHeight(), y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(img.getWidth(), x1); x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (Math.abs(r - target.getRed()) < 60
                        && Math.abs(g - target.getGreen()) < 60
                        && Math.abs(b - target.getBlue()) < 60) {
                    n++;
                }
            }
        }
        return n;
    }

    @Test
    @DisplayName("fire 框画在左上四分之一：左上出现红色，右下没有")
    void drawsFireBoxInPlace() throws Exception {
        byte[] src = jpeg(400, 300, new Color(60, 60, 60));   // 深灰底，便于看出红框
        // 框落在左上四分之一
        byte[] out = DetectionAnnotator.annotate(src,
                List.of(det("fire", 0.62f, 0.05f, 0.05f, 0.40f, 0.40f)));

        assertFalse(java.util.Arrays.equals(src, out), "输出必须与输入不同（真的画了）");

        BufferedImage img = decode(out);
        assertNotNull(img, "画完仍必须是合法 JPEG");
        assertEquals(400, img.getWidth());
        assertEquals(300, img.getHeight(), "尺寸不能被改变");

        int redTopLeft = countNear(img, 0, 0, 200, 150, new Color(0xE5, 0x1A, 0x1A));
        int redBottomRight = countNear(img, 220, 170, 400, 300, new Color(0xE5, 0x1A, 0x1A));

        assertTrue(redTopLeft > 100, "左上四分之一应出现大量红色像素，实际 " + redTopLeft);
        assertEquals(0, redBottomRight, "右下不该有红色 —— 有就说明框位置算错了");
    }

    @Test
    @DisplayName("不同标签用不同颜色：fire 红、person 绿，互不混淆")
    void labelColorsDiffer() throws Exception {
        byte[] src = jpeg(400, 300, new Color(60, 60, 60));
        byte[] out = DetectionAnnotator.annotate(src, List.of(
                det("fire", 0.9f, 0.02f, 0.02f, 0.30f, 0.40f),
                det("person", 0.9f, 0.60f, 0.02f, 0.30f, 0.40f)));

        BufferedImage img = decode(out);
        int red = countNear(img, 0, 0, 160, 140, new Color(0xE5, 0x1A, 0x1A));
        int green = countNear(img, 220, 0, 400, 140, new Color(0x2E, 0xA0, 0x43));

        assertTrue(red > 50, "左半应有红框，实际 " + red);
        assertTrue(green > 50, "右半应有绿框，实际 " + green);
    }

    @Test
    @DisplayName("没有检测框时原样返回（不解码不重编码，省 CPU）")
    void noDetectionsReturnsSameInstance() throws Exception {
        byte[] src = jpeg(200, 150, Color.GRAY);
        assertSame(src, DetectionAnnotator.annotate(src, List.of()));
        assertSame(src, DetectionAnnotator.annotate(src, null));
    }

    @Test
    @DisplayName("坏数据不抛异常，原样返回（截图是证据，不能因为画框失败丢掉）")
    void badInputFallsBackToOriginal() {
        byte[] notJpeg = "this is not a jpeg".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = DetectionAnnotator.annotate(notJpeg,
                List.of(det("fire", 0.5f, 0.1f, 0.1f, 0.2f, 0.2f)));
        assertSame(notJpeg, out, "解码失败必须回退原图");

        assertSame(null, DetectionAnnotator.annotate(null, List.of(det("x", 0.1f, 0, 0, 1, 1))));
        byte[] empty = new byte[0];
        assertSame(empty, DetectionAnnotator.annotate(empty, List.of(det("x", 0.1f, 0, 0, 1, 1))));
    }

    @Test
    @DisplayName("框贴着画面顶部时标签不越界（文字不能被裁掉）")
    void labelAtTopEdgeStaysInside() throws Exception {
        byte[] src = jpeg(320, 240, new Color(60, 60, 60));
        // y=0：标签默认画在框上方会跑到画面外，实现应改画到框内
        byte[] out = DetectionAnnotator.annotate(src,
                List.of(det("fire", 0.88f, 0.10f, 0.0f, 0.50f, 0.50f)));

        BufferedImage img = decode(out);
        assertNotNull(img);
        // 关键是不抛异常、图片仍合法；框内应有红色
        int red = countNear(img, 20, 0, 200, 120, new Color(0xE5, 0x1A, 0x1A));
        assertTrue(red > 50, "顶部框仍应画出可见的红框，实际 " + red);
    }

    @Test
    @DisplayName("越界坐标被夹到画面内，不会 ArrayIndexOutOfBounds")
    void outOfRangeBoxClamped() throws Exception {
        byte[] src = jpeg(200, 150, new Color(60, 60, 60));
        // 模型异常时可能给出 >1 的坐标
        byte[] out = DetectionAnnotator.annotate(src,
                List.of(det("fire", 0.3f, 0.9f, 0.9f, 1.5f, 1.5f)));
        assertNotNull(decode(out), "越界值必须被夹住而不是画出画面外");
    }
}
