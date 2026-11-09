package io.github.rurulalanana.dahua.ext;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同一份文档里<b>两种编码并存</b>时的解码行为。
 *
 * <p>这不是假想场景，是这台球机（DH-SD49425DB-HNY-GQ-EAU / fw 3.201.0000007.1.R）
 * 的真实状态：平台早期用 GBK 写入巡航组名，设备界面自己写的是 UTF-8，
 * 于是同一份 {@code PtzTour} 响应里两种编码各占一半。
 *
 * <p>曾经的 bug：整份文档只判一次编码。只要有一个 GBK 字节，严格 UTF-8 校验
 * 整体失败 → 全篇按 GBK 解 → 本来正确的 UTF-8 字段全变乱码。
 * 用户看到的就是「前两条正常、后面 30 条全是宸¤埅缇ょ祫N」。
 */
class MixedCharsetDocumentTest {

    private static final Charset GBK = Charset.forName("GBK");

    /** 设备上真实的 GBK 字节：平台早期写入的「巡航群組1」。 */
    private static final String GBK_NAME_HEX = "D1B2BABDC8BABD4D31";

    /** 设备上真实的 UTF-8 字节：设备界面写入的「巡航群組3」。 */
    private static final String UTF8_NAME_HEX = "E5B7A1E888AAE7BEA4E7B58433";

    private static byte[] hex(String s) {
        return java.util.HexFormat.of().parseHex(s);
    }

    /** 拼一份「GBK 与 UTF-8 混合」的 JSON，模拟设备真实响应。 */
    private static byte[] mixedJson() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] head = "{\"PtzTour\":[{\"Name\":\"".getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(head);
        out.writeBytes(hex(GBK_NAME_HEX));
        out.writeBytes("\"},{\"Name\":\"".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(hex(UTF8_NAME_HEX));
        out.writeBytes("\"}]}".getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    @Test
    void gbkAndUtf8InOneJsonAreBothDecodedCorrectly() {
        String json = DahuaExtAccess.decodeForTest(mixedJson());

        assertTrue(json.contains("巡航群組1"), "GBK 字段应解对，实际：" + json);
        assertTrue(json.contains("巡航群組3"), "UTF-8 字段应解对，实际：" + json);
        assertFalse(json.contains("宸¤埅"), "不应出现 UTF-8 被按 GBK 解的乱码：" + json);
        assertFalse(json.contains("\uFFFD"), "不应出现替换字符：" + json);
    }

    @Test
    void jsonStructureIsPreserved() {
        String json = DahuaExtAccess.decodeForTest(mixedJson());

        assertEquals("{\"PtzTour\":[{\"Name\":\"巡航群組1\"},{\"Name\":\"巡航群組3\"}]}", json);
    }

    @Test
    void pureUtf8DocumentStillDecodes() {
        String src = "{\"PtzTour\":[{\"Name\":\"巡航群組3\"}]}";
        String json = DahuaExtAccess.decodeForTest(src.getBytes(StandardCharsets.UTF_8));

        assertEquals(src, json);
    }

    @Test
    void pureGbkDocumentStillDecodes() {
        String src = "{\"PtzTour\":[{\"Name\":\"巡航群組1\"}]}";
        String json = DahuaExtAccess.decodeForTest(src.getBytes(GBK));

        assertEquals(src, json);
    }

    @Test
    void asciiDocumentIsUnchanged() {
        String src = "{\"PtzSpeed\":[{\"PtzSpeed\":3}],\"Enable\":true}";
        assertEquals(src, DahuaExtAccess.decodeForTest(src.getBytes(StandardCharsets.US_ASCII)));
    }

    // ==================== CGI 通路 ====================

    private static byte[] mixedCgiBody() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("table.PtzTour[0][0].Name=".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(hex(GBK_NAME_HEX));
        out.writeBytes("\r\ntable.PtzTour[0][2].Name=".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(hex(UTF8_NAME_HEX));
        out.writeBytes("\r\ntable.PtzSpeed[0].PtzSpeed=3\r\n".getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    @Test
    void cgiMixedLinesAreBothDecodedCorrectly() {
        String body = DahuaCgiAccess.decodeBody(mixedCgiBody());

        assertTrue(body.contains("巡航群組1"), "GBK 行应解对，实际：" + body);
        assertTrue(body.contains("巡航群組3"), "UTF-8 行应解对，实际：" + body);
        assertFalse(body.contains("宸¤埅"), "不应出现乱码：" + body);
        assertFalse(body.contains("\uFFFD"), "不应出现替换字符：" + body);
        assertTrue(body.contains("PtzSpeed=3"), "ASCII 行应原样保留：" + body);
    }

    @Test
    void cgiEmptyBodyIsEmptyString() {
        assertEquals("", DahuaCgiAccess.decodeBody(new byte[0]));
        assertEquals("", DahuaCgiAccess.decodeBody(null));
    }
}
