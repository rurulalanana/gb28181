package io.github.rurulalanana.dahua.internal;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DahuaJson} 的解析与剥壳测试。
 *
 * <h3>为什么这个类必须测</h3>
 * 它是防止「把整包写回设备导致配置被永久污染」的唯一屏障
 * （见 {@code docs/实现状态-七模块.md} §0.1）。剥壳剥错一次就会写坏设备，
 * 而且 merge 语义下删不掉 —— 只能靠这里守住。
 *
 * <p>这些用例原是 {@code DahuaExtAccess#businessLayer} 的手写括号配对测试，
 * 改用 Jackson 后行为必须完全一致，所以用例保留、只换实现。
 */
class DahuaJsonTest {

    @Test
    @DisplayName("完整响应包 → 剥出 params.table 的业务层")
    void extractFromEnvelope() {
        String envelope = "{\"id\":3348,\"params\":{\"table\":{\"Format\":\"dav\","
                + "\"PreRecord\":5}},\"result\":true,\"session\":1014386800}";
        assertEquals("{\"Format\":\"dav\",\"PreRecord\":5}",
                DahuaJson.businessLayer(envelope));
    }

    @Test
    @DisplayName("业务层原样返回（幂等）")
    void idempotentOnBusinessLayer() {
        String business = "{\"Format\":\"dav\",\"PreRecord\":5}";
        assertEquals(business, DahuaJson.businessLayer(business));
        // 再套一层也不变 —— 调用方无脑连续调用也安全
        assertEquals(business, DahuaJson.businessLayer(DahuaJson.businessLayer(business)));
    }

    @Test
    @DisplayName("嵌套对象与数组不影响剥壳")
    void nestedBracesAndArrays() {
        String envelope = "{\"id\":1,\"params\":{\"table\":{\"TimeSection\":"
                + "[[\"6 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\"]],"
                + "\"Nested\":{\"A\":{\"B\":true}}}},\"result\":true}";
        JsonNode business = DahuaJson.business(envelope);
        assertNotNull(business);
        assertEquals(2, business.size());
        assertEquals(1, business.get("TimeSection").size());
        assertTrue(business.get("Nested").get("A").get("B").asBoolean());
    }

    @Test
    @DisplayName("字符串里的括号与转义引号不会被当成结构")
    void bracesInsideStrings() {
        // 现实场景：OSD 文本、通道名里出现 { } 和 "
        String envelope = "{\"params\":{\"table\":{\"Name\":\"a{b}c\","
                + "\"Note\":\"say \\\"hi\\\"\"}},\"result\":true}";
        JsonNode business = DahuaJson.business(envelope);
        assertNotNull(business);
        assertEquals("a{b}c", business.get("Name").asText());
        assertEquals("say \"hi\"", business.get("Note").asText());
    }

    @Test
    @DisplayName("空输入返回空串 / null，不抛异常")
    void blankInput() {
        assertEquals("", DahuaJson.businessLayer(null));
        assertEquals("", DahuaJson.businessLayer(""));
        assertEquals("", DahuaJson.businessLayer("   "));
        assertNull(DahuaJson.business(null));
        assertNull(DahuaJson.parse(""));
    }

    @Test
    @DisplayName("非法 JSON 不抛异常，按「读不到」处理")
    void malformedReturnsNull() {
        assertNull(DahuaJson.parse("{not json"));
        assertNull(DahuaJson.business("{not json"));
        assertEquals("", DahuaJson.businessLayer("{not json"));
    }

    @Test
    @DisplayName("没有 table 字段时把根当业务层")
    void noTableField() {
        String json = "{\"Something\":1}";
        JsonNode business = DahuaJson.business(json);
        assertNotNull(business);
        assertEquals(1, business.get("Something").asInt());
    }

    @Test
    @DisplayName("只有 table 没有 params 的变体也能剥")
    void tableWithoutParams() {
        String json = "{\"table\":{\"A\":1},\"result\":true}";
        assertEquals("{\"A\":1}", DahuaJson.businessLayer(json));
    }

    // ==================== wrapForWrite ====================

    @Test
    @DisplayName("写回时自动包成 params.table（整包输入）")
    void wrapWholeEnvelope() {
        String envelope = "{\"id\":3348,\"params\":{\"table\":{\"A\":1}},"
                + "\"result\":true,\"session\":99}";
        String payload = DahuaJson.wrapForWrite(envelope);
        // id / result / session 必须被丢掉，不能混进业务层
        assertFalse(payload.contains("\"session\""));
        assertFalse(payload.contains("\"result\""));
        JsonNode node = DahuaJson.parse(payload);
        assertNotNull(node);
        assertEquals(1, node.get("params").get("table").get("A").asInt());
    }

    @Test
    @DisplayName("写回时业务层输入也包一层（幂等）")
    void wrapBusinessLayer() {
        String payload = DahuaJson.wrapForWrite("{\"A\":1}");
        JsonNode node = DahuaJson.parse(payload);
        assertNotNull(node);
        assertEquals(1, node.get("params").get("table").get("A").asInt());
        // 再包一次不应该出现嵌套的 params
        String again = DahuaJson.wrapForWrite(payload);
        assertEquals(payload, again);
    }

    @Test
    @DisplayName("空内容写回要报错，不能给设备发空配置")
    void wrapEmptyThrows() {
        assertThrows(IllegalArgumentException.class, () -> DahuaJson.wrapForWrite(""));
        assertThrows(IllegalArgumentException.class, () -> DahuaJson.wrapForWrite(null));
        assertThrows(IllegalArgumentException.class, () -> DahuaJson.wrapForWrite("  "));
    }

    // ==================== 字段查找 ====================

    @Test
    @DisplayName("递归查找字段：顶层与嵌套层都能命中")
    void findFieldRecursively() {
        String json = "{\"params\":{\"table\":{"
                + "\"Flip\":false,"
                + "\"NormalOptions\":{\"Flip\":true},"
                + "\"NightOptions\":{\"Deep\":{\"Flip\":false}}}}}";
        JsonNode root = DahuaJson.business(json);
        JsonNode hit = DahuaJson.findField(root, "Flip");
        assertNotNull(hit);
        // 深度优先，先命中顶层那个
        assertFalse(hit.asBoolean());
        assertNotNull(DahuaJson.findField(root, "Deep"));
        assertNull(DahuaJson.findField(root, "NotExist"));
    }

    @Test
    @DisplayName("hasField：字段名只作为值出现时不算命中（string contains 的坑）")
    void hasFieldDoesNotMatchValues() {
        String json = "{\"params\":{\"table\":{\"Mode\":\"Flip\"}}}";
        Map<String, String> byCommand = Map.of("Cfg", json);
        assertFalse(DahuaJson.hasField(byCommand, "Cfg", "Flip"),
                "Flip 只是 Mode 的值，不是字段名");
        assertTrue(DahuaJson.hasField(byCommand, "Cfg", "Mode"));
    }

    @Test
    @DisplayName("hasField：配置项不存在时返回 false")
    void hasFieldMissingCommand() {
        Map<String, String> byCommand = Map.of("Cfg", "");
        assertFalse(DahuaJson.hasField(byCommand, "Cfg", "Flip"));
        assertFalse(DahuaJson.hasField(byCommand, "NotThere", "Flip"));
    }

    // ==================== 取值 ====================

    @Test
    @DisplayName("intValue 同时兼容数字、数字字符串与布尔")
    void intValueCoercion() {
        JsonNode root = DahuaJson.parse("{\"a\":1,\"b\":\"2\",\"c\":true,\"d\":\"x\",\"e\":null}");
        assertNotNull(root);
        assertEquals(1, DahuaJson.intValue(root.get("a")));
        assertEquals(2, DahuaJson.intValue(root.get("b")), "数字字符串");
        assertEquals(1, DahuaJson.intValue(root.get("c")), "布尔 true → 1");
        assertNull(DahuaJson.intValue(root.get("d")), "非数字字符串");
        assertNull(DahuaJson.intValue(root.get("e")));
        assertNull(DahuaJson.intValue(null));
    }
}
