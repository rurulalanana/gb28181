package io.github.rurulalanana.dahua.ext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 定长字符串字段的<b>编码自动判定</b>。
 *
 * <h3>这个测试守的是什么</h3>
 * 大华的字符串编码<b>不统一</b>，同一个 SDK 的不同接口、不同固件都不一样：
 * <ul>
 *   <li>{@code NET_PTZ_PRESET.szName}（预置点名称）：IPC-HFW 系列实测 <b>UTF-8</b></li>
 *   <li>部分老固件的设备名/通道名：<b>GBK</b></li>
 *   <li>{@code configManager} 返回的 JSON（巡航线名等）：实测 <b>UTF-8</b></li>
 *   <li>SN / IP / 型号：纯 ASCII，两种解都一样</li>
 * </ul>
 *
 * <p>早先代码把预置点名称写死按 GBK 解，设备返回 UTF-8 字节时界面上显示成
 * 「闋愮疆榛&#65533;1」—— 这就是用户报的「预置位列表乱码」。
 * 反过来把全部字段写死 UTF-8，老固件的 GBK 名称会变成「&#65533;&#65533;&#65533;」。
 * 所以只能按字节内容判定。
 *
 * <p><b>判定原理</b>：UTF-8 的结构约束很强（多字节序列的首字节与后续字节都有合法范围），
 * GBK 字节碰巧构成合法 UTF-8 的概率极低；反过来 GBK 中文文本几乎不可能通过严格 UTF-8 校验。
 * 因此「严格 UTF-8 解通 → UTF-8，否则 GBK」在两类设备上都能解对。
 */
class PresetNameCharsetTest {

    private static final Charset GBK = Charset.forName("GBK");

    /**
     * 服务器日志里抓到的真实字节（{@code 预置点诊断 #1/4}）：
     * <pre>
     *   原始字节=E9 A0 90 E7 BD AE E9 BB 9E 31
     *   GBK=闋愮疆榛?1   UTF8=預置點1
     * </pre>
     * 这就是乱码的现场 —— 设备给的是 UTF-8，代码按 GBK 解。
     */
    private static final byte[] REAL_UTF8_BYTES = {
            (byte) 0xE9, (byte) 0xA0, (byte) 0x90,   // 預
            (byte) 0xE7, (byte) 0xBD, (byte) 0xAE,   // 置
            (byte) 0xE9, (byte) 0xBB, (byte) 0x9E,   // 點
            (byte) 0x31,                              // 1
    };

    @Test
    @DisplayName("设备返回 UTF-8 字节 → 解出「預置點1」，不是 GBK 乱码")
    void decodesRealUtf8Bytes() {
        assertEquals("預置點1", DahuaExtAccess.decodeName(REAL_UTF8_BYTES));
    }

    @Test
    @DisplayName("同样字节按 GBK 解确实是乱码 —— 证明判定不是碰巧")
    void gbkWouldBeGarbage() {
        // 把错误答案也钉住：改回硬编码 GBK 时这个断言仍然通过，但上一个会失败
        assertEquals("闋愮疆榛", new String(REAL_UTF8_BYTES, 0, 9, GBK).substring(0, 4));
    }

    @Test
    @DisplayName("老固件返回 GBK 字节 → 回退 GBK，解出「预置点1」")
    void fallsBackToGbkForLegacyFirmware() {
        // 「预置点1」的 GBK 编码：D4 A4 D6 C3 B5 E3 31，不是合法 UTF-8
        byte[] gbk = "预置点1".getBytes(GBK);
        assertEquals("预置点1", DahuaExtAccess.decodeName(gbk));
    }

    @Test
    @DisplayName("尾部 0 填充被截断，不把填充字节当内容")
    void stopsAtFirstNul() {
        byte[] field = new byte[32];
        System.arraycopy(REAL_UTF8_BYTES, 0, field, 0, REAL_UTF8_BYTES.length);
        Arrays.fill(field, REAL_UTF8_BYTES.length, field.length, (byte) 0);
        assertEquals("預置點1", DahuaExtAccess.decodeName(field));
    }

    @Test
    @DisplayName("纯 ASCII（SN / IP / 型号）两种判定都能解对")
    void asciiIsStable() {
        assertEquals("10001", DahuaExtAccess.decodeName("10001".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("IPC-HFW1230M", DahuaExtAccess.decodeName("IPC-HFW1230M".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("111.198.60.180", DahuaExtAccess.decodeName("111.198.60.180".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    @DisplayName("全 0 / null → null（表示「没有名称」，不是空字符串）")
    void emptyBecomesNull() {
        assertNull(DahuaExtAccess.decodeName(null));
        assertNull(DahuaExtAccess.decodeName(new byte[16]));
        assertNull(DahuaExtAccess.decodeName(new byte[]{0, 0, 0}));
    }

    @Test
    @DisplayName("首字节就是 0 的字段（未命名预置点）→ null")
    void leadingNulIsEmpty() {
        byte[] field = new byte[32];
        System.arraycopy(REAL_UTF8_BYTES, 0, field, 1, REAL_UTF8_BYTES.length);
        assertNull(DahuaExtAccess.decodeName(field));
    }
}
