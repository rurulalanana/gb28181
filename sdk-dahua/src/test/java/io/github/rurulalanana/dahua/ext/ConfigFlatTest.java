package io.github.rurulalanana.dahua.ext;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rurulalanana.dahua.internal.DahuaJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SDK 的 JSON 配置 → CGI 扁平键路径 的转换。
 *
 * <h3>为什么这个转换必须逐字符对齐</h3>
 * 云台配置页的 12 个解析方法（{@code parseTours} / {@code parseSpeed} / …）全部按
 * <b>CGI 的键路径</b>写的，形如 {@code PtzSpeed[0].PtzSpeed}。
 * SDK 读回来是 JSON，若键名差一个字符（多一层 {@code params.}、下标位置不同、
 * 少了外层配置项名），解析就会静默拿到空值 —— 页面一片空，但没有任何报错。
 *
 * <p>改造前这条路只有 CGI 一条，所以这种偏差不会有；现在多了 SDK 通路，
 * 这个测试就是两条通路「结果等价」的唯一保证。
 *
 * <p>对照的真实 CGI 输出（设备 {@code getConfig?name=PtzSpeed} 实测）：
 * <pre>
 *   table.PtzSpeed[0].PtzSpeed=3
 *   table.PtzSpeed[0].PatternSpeed=5
 * </pre>
 * 注意 {@code DahuaCgiAccess.read} 会剥掉 {@code table.} 前缀，
 * 所以最终键是 {@code PtzSpeed[0].PtzSpeed}。
 */
class ConfigFlatTest {

    @Test
    @DisplayName("单层数组：对象键在前、下标紧随其后，与 CGI 一致")
    void flatMatchesCgiForSimpleArray() {
        // 设备 SDK 读 PtzSpeed 的实际形状
        JsonNode business = DahuaJson.business(
                "{\"params\":{\"table\":{\"PtzSpeed\":[{\"PtzSpeed\":3,\"PatternSpeed\":5}]}}}");

        Map<String, String> flat = new LinkedHashMap<>();
        DahuaExtAccess.flatten("", business, flat);

        assertEquals("3", flat.get("PtzSpeed[0].PtzSpeed"),
                "键必须与 CGI 的 table.PtzSpeed[0].PtzSpeed 剥壳后完全一致");
        assertEquals("5", flat.get("PtzSpeed[0].PatternSpeed"));
        assertEquals(2, flat.size(), "不应多出任何键：" + flat.keySet());
    }

    @Test
    @DisplayName("二维数组：PtzTour[ch][route].Presets[slot][idx]")
    void flatHandlesNestedArrays() {
        JsonNode business = DahuaJson.business("{\"params\":{\"table\":{\"PtzTour\":["
                + "[{\"Enable\":true,\"Name\":\"T1\",\"Presets\":[[1,15,7],[2,20,8]]}]"
                + "]}}}");

        Map<String, String> flat = new LinkedHashMap<>();
        DahuaExtAccess.flatten("", business, flat);

        assertEquals("true", flat.get("PtzTour[0][0].Enable"));
        assertEquals("T1", flat.get("PtzTour[0][0].Name"));
        assertEquals("1", flat.get("PtzTour[0][0].Presets[0][0]"));
        assertEquals("15", flat.get("PtzTour[0][0].Presets[0][1]"));
        assertEquals("7", flat.get("PtzTour[0][0].Presets[0][2]"));
        assertEquals("2", flat.get("PtzTour[0][0].Presets[1][0]"));
    }

    @Test
    @DisplayName("业务层是纯数组时用配置项名包一层（保住 PtzSpeed[0] 开头）")
    void wrapsBareArrayWithCommandName() {
        // 部分固件返回的 table 直接就是数组，没有外层名字
        JsonNode business = DahuaJson.business(
                "{\"params\":{\"table\":[{\"PtzSpeed\":3,\"PatternSpeed\":5}]}}");

        Map<String, String> flat = new LinkedHashMap<>();
        // 与 readConfigFlat 内部的处理保持一致：纯数组时用 command 作外层名
        DahuaExtAccess.flatten("PtzSpeed", business, flat);

        assertEquals("3", flat.get("PtzSpeed[0].PtzSpeed"),
                "纯数组业务层必须补上配置项名，否则键会变成 [0].PtzSpeed，"
                        + "12 个 parse 方法全部读不到");
    }

    @Test
    @DisplayName("叶子值统一按文本输出：布尔是 true/false，数字不带小数点")
    void leavesArePlainText() {
        JsonNode business = DahuaJson.business("{\"params\":{\"table\":"
                + "{\"X\":[{\"Enable\":false,\"Speed\":4,\"Name\":\"中文名\"}]}}}");

        Map<String, String> flat = new LinkedHashMap<>();
        DahuaExtAccess.flatten("", business, flat);

        assertEquals("false", flat.get("X[0].Enable"));
        assertEquals("4", flat.get("X[0].Speed"));
        assertEquals("中文名", flat.get("X[0].Name"), "中文不能被转义或截断");
    }

    @Test
    @DisplayName("空表与缺失字段不产生任何键（避免 parse 拿到空串当 0）")
    void emptyTableProducesNoKeys() {
        JsonNode business = DahuaJson.business("{\"params\":{\"table\":{}}}");
        Map<String, String> flat = new LinkedHashMap<>();
        DahuaExtAccess.flatten("", business, flat);
        assertEquals(0, flat.size());
    }
}
