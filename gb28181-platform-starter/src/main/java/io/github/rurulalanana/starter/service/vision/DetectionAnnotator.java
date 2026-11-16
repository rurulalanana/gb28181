package io.github.rurulalanana.starter.service.vision;

import io.github.rurulalanana.vision.Detection;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * 在告警截图<b>上画出检测框</b>，让事件证据图自己说明「检出了什么、在哪」。
 *
 * <h3>为什么必须画框</h3>
 * 只存原始帧时，一张火场截图挂到报警记录上，运维看到的是浓烟一片 ——
 * 既不知道 AI 认为哪里是火，也没法判断这次是「真报警」还是「误检」。
 * 画上框之后，误报的形态（比如把夕阳当火）一眼就能认出来，
 * 这对调阈值、判断要不要保留该场景是决定性的信息。
 *
 * <h3>为什么不复用人脸/预览页那套前端叠框</h3>
 * 报警截图是<b>历史证据</b>：它会被下载、发给别人、几个月后再看。
 * 前端叠框只在页面里有效，图一旦离开页面就没框了。
 * 所以必须把框<b>烧进图片像素</b>。
 *
 * <h3>失败必须回退成原图</h3>
 * 画框是「锦上添花」，存图是「必须有」。解码失败、字体缺失、
 * 编码器不可用等任何异常都返回原始字节 —— 宁可少一个框，也不能因为
 * 装饰性操作把整张证据图丢掉。
 *
 * <p>纯 JDK 实现（ImageIO + Java2D），不引入图像库依赖。
 */
@Slf4j
final class DetectionAnnotator {

    /** 输出 JPEG 质量。0.85 在「文字边缘不糊」与「体积不膨胀」之间。 */
    private static final float JPEG_QUALITY = 0.85f;

    private DetectionAnnotator() {
    }

    /**
     * 把检测框画到 JPEG 上。
     *
     * @param jpeg       原始帧（JPEG 字节）
     * @param detections 该事件的检测框（归一化坐标 0~1）
     * @return 画了框的 JPEG；任何失败都返回原始字节
     */
    static byte[] annotate(byte[] jpeg, List<Detection> detections) {
        if (jpeg == null || jpeg.length == 0) {
            return jpeg;
        }
        if (detections == null || detections.isEmpty()) {
            // 没有框可画，直接返回原图（还能省一次解码 + 编码）
            return jpeg;
        }
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (src == null) {
                return jpeg;
            }
            /*
             * 转成 RGB：监控帧常是 TYPE_3BYTE_BGR 或带 alpha 的类型，
             * 直接在这些类型上画半透明底衬，不同 JVM 的合成结果不一致。
             * 统一成 TYPE_INT_RGB 后行为可预期。
             */
            BufferedImage canvas = new BufferedImage(
                    src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = canvas.createGraphics();
            try {
                g.drawImage(src, 0, 0, null);
                drawBoxes(g, canvas.getWidth(), canvas.getHeight(), detections);
            } finally {
                g.dispose();
            }
            return encodeJpeg(canvas);
        } catch (Exception e) {
            log.debug("截图绘制检测框失败，回退原图: {}", e.toString());
            return jpeg;
        }
    }

    private static void drawBoxes(Graphics2D g, int width, int height,
                                  List<Detection> detections) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        /*
         * 线宽与字号按画面短边缩放，而不是写死像素。
         *
         * 同一套参数在 416×234 的子码流截图上很粗、在 3840×2160 的主码流上
         * 细得看不见。按短边比例算，两种分辨率观感一致。
         */
        int shortSide = Math.min(width, height);
        float lineWidth = Math.max(2f, shortSide / 160f);
        int fontSize = Math.max(11, Math.round(shortSide / 28f));
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();

        for (Detection d : detections) {
            if (d == null || d.box() == null) {
                continue;
            }
            int x = Math.round(d.box().x() * width);
            int y = Math.round(d.box().y() * height);
            int w = Math.round(d.box().w() * width);
            int h = Math.round(d.box().h() * height);
            // 归一化坐标理论上已 clamp，但异常模型仍可能给出越界值，这里再兜一层
            w = Math.max(1, Math.min(w, width - Math.max(0, x)));
            h = Math.max(1, Math.min(h, height - Math.max(0, y)));

            Color color = colorOf(d.label());
            g.setColor(color);
            g.setStroke(new BasicStroke(lineWidth, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            g.drawRect(x, y, w, h);

            String text = String.format("%s %.0f%%", d.label(), d.confidence() * 100);
            int padX = Math.round(lineWidth);
            int padY = Math.round(lineWidth * 0.6f);
            int textW = fm.stringWidth(text);
            int chipW = textW + padX * 2;
            int chipH = fm.getHeight() + padY * 2;
            /*
             * 标签默认贴在框的<b>上方</b>（不遮住目标）；框贴着画面顶部时
             * 改贴到框内侧，否则文字会被裁掉一半。
             */
            int chipY = y - chipH >= 0 ? y - chipH : y;
            int chipX = Math.min(x, Math.max(0, width - chipW));

            g.setColor(color);
            g.fillRect(chipX, chipY, chipW, chipH);
            /*
             * 文字颜色按底色亮度选黑或白。
             * 固定用白色的话，在黄/青这类亮色底衬上几乎看不清。
             */
            g.setColor(luminance(color) > 0.6 ? Color.BLACK : Color.WHITE);
            g.drawString(text, chipX + padX, chipY + padY + fm.getAscent());
        }
    }

    /**
     * 标签配色。
     *
     * <p>火用纯红、烟用橙色 —— 这两个是安全类告警，需要在缩略图里最扎眼。
     * 其余按类别固定分配，同一通道多类检出时也能区分开。
     */
    private static Color colorOf(String label) {
        String l = label == null ? "" : label.toLowerCase(java.util.Locale.ROOT);
        return switch (l) {
            case "fire" -> new Color(0xE5, 0x1A, 0x1A);       // 红
            case "smoke" -> new Color(0xF5, 0x7C, 0x00);      // 橙
            case "person" -> new Color(0x2E, 0xA0, 0x43);     // 绿
            case "car", "truck", "bus", "motorcycle", "bicycle" ->
                    new Color(0x1A, 0x73, 0xE8);              // 蓝
            case "face" -> new Color(0xD6, 0x33, 0x84);       // 品红
            default -> new Color(0xF5, 0xC5, 0x18);           // 黄
        };
    }

    /** 相对亮度（0~1），用于选文字颜色。 */
    private static double luminance(Color c) {
        return (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255.0;
    }

    /**
     * 编码成 JPEG。
     *
     * <p>用 {@link ImageWriter} 显式设质量：{@code ImageIO.write(img, "jpg", out)}
     * 无法控制质量，默认压缩率在文字边缘会出现明显块状伪影，
     * 「fire 53%」这种小字会糊成一团。
     */
    private static byte[] encodeJpeg(BufferedImage image) throws java.io.IOException {
        var writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            // 理论上 JDK 自带 JPEG 编码器，走到这里说明运行环境被裁剪过
            ByteArrayOutputStream fallback = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", fallback);
            return fallback.toByteArray();
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), param);
            ios.flush();
            return out.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
