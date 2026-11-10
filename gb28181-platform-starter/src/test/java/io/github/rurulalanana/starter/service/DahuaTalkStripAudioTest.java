package io.github.rurulalanana.starter.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对讲下行 DHAV 帧的剥头/剥尾。
 *
 * <p><b>为什么值得单独钉住</b>：这里剥多剥少都不会报错，只会在用户耳朵里表现为
 * 「持续鞭炮声」。第一版按 20 字节头实现，每帧多留 28 字节（20 内层头 + 8 尾部
 * {@code "dhav"}），实测帧边界跳变平均 18128、最大 22029；剥对后是 1 和 3。
 *
 * <p>测试数据按真机取样构造（IPC fw 3.201，2096 字节/帧，63 帧逐帧校验过尾部 magic）。
 */
class DahuaTalkStripAudioTest {

    /** 真机帧总长。 */
    private static final int FRAME = 2096;
    /** 头部：外层 20 + 内层 20。 */
    private static final int HEADER = 40;
    /** 尾部：{@code "dhav"} + uint32 帧长。 */
    private static final int TRAILER = 8;
    /** 一帧的 PCM 载荷：2048 字节 = 1024 采样点 = 128 ms @ 8 kHz。 */
    private static final int PCM = FRAME - HEADER - TRAILER;

    /** 造一帧与真机同构的数据；{@code pcmSeed} 用来区分不同帧的载荷。 */
    private static byte[] frame(int pcmSeed, boolean withTrailer) {
        byte[] f = new byte[withTrailer ? FRAME : FRAME - TRAILER];
        // [0..4) "DHAV"
        System.arraycopy("DHAV".getBytes(StandardCharsets.US_ASCII), 0, f, 0, 4);
        // [4..8) F0 00 00 00
        f[4] = (byte) 0xF0;
        // [8..40) 内层头：真机上这几处每帧恒定（83 01 10 02 88 / fc 00 00 00 / 96 01 00 00）
        f[8] = (byte) 0x83;
        f[9] = 0x01;
        f[10] = 0x10;
        f[11] = 0x02;
        f[12] = (byte) 0x88;
        f[16] = (byte) 0xFC;
        f[20] = (byte) 0x96;
        f[21] = 0x01;
        // PCM：低频小幅波形，方便断言「一字节不差」
        for (int i = 0; i < PCM; i++) {
            f[HEADER + i] = (byte) ((i + pcmSeed) & 0x7F);
        }
        if (withTrailer) {
            System.arraycopy("dhav".getBytes(StandardCharsets.US_ASCII), 0, f, FRAME - TRAILER, 4);
            int len = FRAME;
            f[FRAME - 4] = (byte) (len & 0xFF);
            f[FRAME - 3] = (byte) ((len >>> 8) & 0xFF);
        }
        return f;
    }

    @Test
    @DisplayName("剥掉 40 字节头 + 8 字节尾，载荷一字节不差")
    void 剥头尾后只剩裸PCM() {
        byte[] f = frame(0, true);
        byte[] pcm = DahuaTalkService.stripAudioHeader(f);
        assertNotNull(pcm);
        assertEquals(PCM, pcm.length, "载荷必须是 2048 字节（1024 采样点 = 128 ms）");
        assertArrayEquals(Arrays.copyOfRange(f, HEADER, HEADER + PCM), pcm);

        // 关键回归点：头部里的 0x83/0x88/0x96 与尾部的 "dhav" 都不能漏进 PCM
        assertEquals(f[HEADER], pcm[0], "PCM 必须从第 40 字节开始，不能是内层头的 0x83/0x88");
        String tail = new String(pcm, pcm.length - 4, 4, StandardCharsets.US_ASCII);
        assertTrue(!"dhav".equals(tail), "尾部 magic 必须被剥掉，否则每帧一记爆音");
    }

    @Test
    @DisplayName("没有尾部 magic 的固件：只剥 40 字节头")
    void 无尾部时按无尾处理() {
        byte[] f = frame(7, false);
        byte[] pcm = DahuaTalkService.stripAudioHeader(f);
        assertNotNull(pcm);
        assertEquals(FRAME - TRAILER - HEADER, pcm.length);
    }

    @Test
    @DisplayName("结构对不上时返回 null（丢弃），绝不整帧透传")
    void 结构不符时丢弃() {
        // 没有 DHAV magic
        assertNull(DahuaTalkService.stripAudioHeader(new byte[FRAME]));
        // 太短
        assertNull(DahuaTalkService.stripAudioHeader(new byte[16]));
        assertNull(DahuaTalkService.stripAudioHeader(null));
        // 载荷为奇数长度：说明切歪了
        byte[] odd = new byte[HEADER + 4 + TRAILER + 1];
        System.arraycopy("DHAV".getBytes(StandardCharsets.US_ASCII), 0, odd, 0, 4);
        assertNull(DahuaTalkService.stripAudioHeader(odd));
    }

    @Test
    @DisplayName("按 20 字节剥头的老实现会漏 28 字节：钉住这个差异")
    void 老实现会漏28字节() {
        byte[] f = frame(0, true);
        // 老逻辑：只切 [20, len)
        byte[] oldStyle = Arrays.copyOfRange(f, 20, f.length);
        byte[] pcm = DahuaTalkService.stripAudioHeader(f);
        assertEquals(oldStyle.length - pcm.length, 28,
                "老实现每帧多留 28 字节 = 14 个采样点，正是「鞭炮声」的来源");
    }
}
