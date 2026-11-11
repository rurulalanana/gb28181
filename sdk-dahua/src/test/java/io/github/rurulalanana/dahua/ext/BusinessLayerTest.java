package io.github.rurulalanana.dahua.ext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link DahuaExtAccess#businessLayer} 的解析测试。
 *
 * <h3>为什么这个方法是"必须测"的</h3>
 * 它是防止「把整包写回设备导致配置被永久污染」的唯一屏障（见
 * {@code docs/实现状态-七模块.md} §0.1 第 9 条）。解析错一次就会写坏设备，
 * 而且 merge 语义下删不掉 —— 只能靠这里守住。
 */
class BusinessLayerTest {

    @Test
    @DisplayName("完整响应包 → 剥出 params.table 的业务层")
    void extractFromEnvelope() {
        String envelope = "{\"id\":3348,\"params\":{\"table\":{\"Format\":\"dav\","
                + "\"PreRecord\":5}},\"result\":true,\"session\":1014386800}";
        assertEquals("{\"Format\":\"dav\",\"PreRecord\":5}",
                DahuaExtAccess.businessLayer(envelope));
    }

    @Test
    @DisplayName("业务层原样返回（幂等）")
    void idempotentOnBusinessLayer() {
        String business = "{\"Format\":\"dav\",\"PreRecord\":5}";
        assertEquals(business, DahuaExtAccess.businessLayer(business));
        // 再套一层也不变 —— 调用方无脑连续调用也安全
        assertEquals(business, DahuaExtAccess.businessLayer(DahuaExtAccess.businessLayer(business)));
    }

    @Test
    @DisplayName("嵌套对象与数组里的括号不影响配对")
    void nestedBracesAndArrays() {
        String envelope = "{\"id\":1,\"params\":{\"table\":{\"TimeSection\":"
                + "[[\"6 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\"]],"
                + "\"Nested\":{\"A\":{\"B\":true}}}},\"result\":true}";
        assertEquals("{\"TimeSection\":[[\"6 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\"]],"
                        + "\"Nested\":{\"A\":{\"B\":true}}}",
                DahuaExtAccess.businessLayer(envelope));
    }

    @Test
    @DisplayName("字符串里的括号与转义引号不会破坏配对")
    void bracesInsideStrings() {
        // 现实场景：OSD 文本、通道名里出现 { } 和 \"
        String envelope = "{\"params\":{\"table\":{\"Name\":\"a{b}c\","
                + "\"Note\":\"say \\\"hi\\\"\"}},\"result\":true}";
        assertEquals("{\"Name\":\"a{b}c\",\"Note\":\"say \\\"hi\\\"\"}",
                DahuaExtAccess.businessLayer(envelope));
    }

    @Test
    @DisplayName("空输入返回空串，不抛异常")
    void blankInput() {
        assertEquals("", DahuaExtAccess.businessLayer(null));
        assertEquals("", DahuaExtAccess.businessLayer(""));
        assertEquals("", DahuaExtAccess.businessLayer("   "));
    }

    @Test
    @DisplayName("没有 table 字段时原样返回（不吞内容）")
    void noTableField() {
        String json = "{\"Something\":1}";
        assertEquals(json, DahuaExtAccess.businessLayer(json));
    }
}
