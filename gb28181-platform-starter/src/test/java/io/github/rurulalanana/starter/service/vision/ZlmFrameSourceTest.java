package io.github.rurulalanana.starter.service.vision;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JPEG 尺寸解析的测试。
 *
 * <p>为什么要测这个：{@code Frame.usable()} 要求 {@code width>0 && height>0}，
 * 尺寸解析错了会让<b>整帧被流水线丢弃</b>，表现为「ffmpeg 明明在出图，
 * 但一帧都检测不到」—— 这个症状从日志里看不出来，只能靠测试钉住。
 */
class ZlmFrameSourceTest {

    /** 造一张指定尺寸的 JPEG。 */
    private static byte[] jpeg(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        // 画点内容，避免全黑图被优化成极小文件
        for (int y = 0; y < h; y += 7) {
            for (int x = 0; x < w; x += 7) {
                img.setRGB(x, y, 0x336699);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    @Test
    void parsesRealJpegSize() throws Exception {
        for (int[] size : new int[][]{{640, 480}, {1920, 1080}, {704, 576}, {1, 1}}) {
            byte[] bytes = jpeg(size[0], size[1]);
            int[] wh = ZlmFrameSource.jpegSize(bytes);
            assertEquals(size[0], wh[0], "宽不符: " + size[0] + "x" + size[1]);
            assertEquals(size[1], wh[1], "高不符: " + size[0] + "x" + size[1]);
        }
    }

    @Test
    void returnsPlaceholderForGarbage() {
        // 畸形输入不能让调用方抛异常，否则一个坏帧会停掉整路检测
        assertTrue(ZlmFrameSource.jpegSize(null)[0] > 0);
        assertTrue(ZlmFrameSource.jpegSize(new byte[0])[0] > 0);
        assertTrue(ZlmFrameSource.jpegSize(new byte[]{0x00, 0x01, 0x02})[0] > 0);
        // 有 SOI 但没有 SOF：应回退到占位值而不是死循环或抛异常
        byte[] noSof = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                0x00, 0x10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        assertEquals(1, ZlmFrameSource.jpegSize(noSof)[0]);
    }

    @Test
    void placeholderIsUsableByPipeline() {
        // 关键约束：解析失败时给的占位值必须仍满足 Frame.usable()，
        // 否则坏帧会导致整路停摆而不是「这一帧丢弃、下一帧继续」
        int[] wh = ZlmFrameSource.jpegSize(new byte[]{1, 2, 3});
        var frame = new io.github.rurulalanana.vision.Frame(
                new byte[]{1, 2, 3}, wh[0], wh[1], System.currentTimeMillis(), "test");
        assertTrue(frame.usable(), "占位尺寸必须让 Frame 可用");
    }

    /**
     * FLV 地址必须能正确转成 TS 地址。
     *
     * <p>这个转换决定了取帧能否工作：ZLM 的 H.265 FLV 是 codecId=12，
     * ffmpeg 7.0.2 的 flv demuxer 解不了（一帧不出）；TS 原生支持 HEVC。
     * 转换错了会静默退回 FLV，又变成「检测开了但没帧」。
     */
    @Test
    void convertsFlvUrlToTsUrl() {
        assertEquals("http://160.202.254.65:48089/10001/10001-1.live.ts",
                ZlmFrameSource.toTsUrl("http://160.202.254.65:48089/10001/10001-1.live.flv"));
        assertEquals("http://127.0.0.1:48089/a/b.live.ts",
                ZlmFrameSource.toTsUrl("http://127.0.0.1:48089/a/b.live.flv"));
        // 带查询串：后缀替换不能把参数吃掉
        assertEquals("http://h:1/a/b.live.ts?x=1",
                ZlmFrameSource.toTsUrl("http://h:1/a/b.live.flv?x=1"));
        // 普通 .flv（非 .live.flv）
        assertEquals("http://h:1/a/b.ts", ZlmFrameSource.toTsUrl("http://h:1/a/b.flv"));
        // 已经是 TS 或不认识：原样返回
        assertEquals("http://h:1/a/b.live.ts", ZlmFrameSource.toTsUrl("http://h:1/a/b.live.ts"));
        assertEquals("rtsp://h:554/a/b", ZlmFrameSource.toTsUrl("rtsp://h:554/a/b"));
        // 空值不抛异常
        assertEquals(null, ZlmFrameSource.toTsUrl(null));
        assertEquals("", ZlmFrameSource.toTsUrl(""));
    }
}
