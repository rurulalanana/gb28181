package io.github.rurulalanana.dahua.ext;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rurulalanana.dahua.internal.DahuaJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SDK 读回来的键路径<b>补通道维度</b>。
 *
 * <h3>这个测试守的是什么</h3>
 * {@code CLIENT_GetNewDevConfig} 传入 channel 后，设备返回的 JSON
 * <b>已经按通道过滤</b>，比 CGI 少一层通道维度。而
 * {@code DahuaPtzConfigService} 的 12 个 parse 方法全部按 <b>CGI 形状</b>写。
 *
 * <p>后果不是报错，是<b>静默读到空</b>：实测 {@code /ptz/config} 返回
 * {@code tours:[]}、{@code scans:[]}、{@code patterns:[]}、{@code autoMovements:[]}，
 * 而日志显示「PtzTour 已通过 SDK 读取（3168 个键）」—— 数据明明读到了，
 * 只是键名对不上，界面整片空白且没有任何提示。
 *
 * <h3>两种真实形状（实测 IPC-HFW / fw 3.201.0000007.1.R）</h3>
 * <table border="1">
 *   <tr><th>配置项</th><th>SDK 的 params.table</th><th>SDK 展平后</th><th>CGI 形状</th></tr>
 *   <tr><td>PtzTour</td><td>裸数组 {@code [{...}]}</td>
 *       <td>{@code PtzTour[0].Name}</td><td>{@code PtzTour[ch][route].Name}</td></tr>
 *   <tr><td>AutoScan</td><td>裸数组 {@code [{...}]}</td>
 *       <td>{@code AutoScan[0].LeftEnable}</td><td>{@code AutoScan[ch][idx].LeftEnable}</td></tr>
 *   <tr><td>PtzSpeed</td><td>对象 {@code {"PtzSpeed":1}}</td>
 *       <td>{@code PtzSpeed=1}（裸字段）</td><td>{@code PtzSpeed[ch].PtzSpeed}</td></tr>
 *   <tr><td>AutoPan</td><td>对象 {@code {"PanSpeed":3}}</td>
 *       <td>{@code PanSpeed=3}</td><td>{@code AutoPan[ch].PanSpeed}</td></tr>
 * </table>
 */
class ConfigChannelDimensionTest {

    /** 通道号用 0（与设备主码流通道一致，也是 {@code cfgChannel} 的实际取值）。 */
    private static final int CH = 0;

    /**
     * 按 {@code readConfigFlat} 的真实逻辑走一遍：展平 → 补通道维度。
     *
     * @param tableJson {@code params.table} 的 JSON（SDK 实际返回的形状）
     */
    private static Map<String, String> sdkFlat(String command, String tableJson) {
        JsonNode business = DahuaJson.business("{\"params\":{\"table\":" + tableJson + "}}");
        boolean arrayShape = business.isArray();
        Map<String, String> flat = new LinkedHashMap<>();
        // 裸数组没有外层名字，用配置项名补上（与 readConfigFlat 一致）
        DahuaExtAccess.flatten(arrayShape ? command : "", business, flat);
        return DahuaExtAccess.withChannelDimension(flat, command, CH, arrayShape);
    }

    /** CGI 形状：table 一定带外层配置项名，且通道维度已经在。 */
    private static Map<String, String> cgiFlat(String tableJson) {
        JsonNode business = DahuaJson.business("{\"params\":{\"table\":" + tableJson + "}}");
        Map<String, String> flat = new LinkedHashMap<>();
        DahuaExtAccess.flatten("", business, flat);
        return flat;
    }

    @Test
    @DisplayName("数组型：SDK 的 PtzTour[0].Name → PtzTour[0][0].Name，与 CGI 完全一致")
    void addsChannelToArrayShape() {
        // SDK 实际返回：裸数组，每条巡航线一个对象
        String sdk = "[{\"Enable\":true,\"Name\":\"巡航群組1\",\"Presets\":[[0,20,7]]},"
                + "{\"Enable\":true,\"Name\":\"巡航群組2\",\"Presets\":[[4,15,7]]}]";
        // CGI 实际返回：外层带配置项名，通道维度在里面
        String cgi = "{\"PtzTour\":[["
                + "{\"Enable\":true,\"Name\":\"巡航群組1\",\"Presets\":[[0,20,7]]},"
                + "{\"Enable\":true,\"Name\":\"巡航群組2\",\"Presets\":[[4,15,7]]}"
                + "]]}";

        Map<String, String> got = sdkFlat("PtzTour", sdk);
        Map<String, String> want = cgiFlat(cgi);

        assertEquals("true", got.get("PtzTour[0][0].Enable"));
        assertEquals("巡航群組1", got.get("PtzTour[0][0].Name"));
        assertEquals("0", got.get("PtzTour[0][0].Presets[0][0]"),
                "预置位号必须落在 [ch][route].Presets[slot][field] 上");
        assertEquals("20", got.get("PtzTour[0][0].Presets[0][1]"));
        assertEquals("巡航群組2", got.get("PtzTour[0][1].Name"));
        assertEquals(want, got, "补维后必须与 CGI 形状完全一致，否则 parse* 读不到");
    }

    @Test
    @DisplayName("对象型：SDK 的裸字段 PanSpeed=3 → AutoPan[0].PanSpeed=3")
    void addsChannelToObjectShape() {
        // SDK 实测：AutoPan 返回 {"PanSpeed":3}，展平后就是裸的 PanSpeed=3
        Map<String, String> got = sdkFlat("AutoPan", "{\"PanSpeed\":3}");

        assertEquals("3", got.get("AutoPan[0].PanSpeed"),
                "parseAutoPan 读的是 AutoPan[ch].PanSpeed，裸字段会读不到 → 界面显示下限 1");
        assertEquals(1, got.size());
    }

    @Test
    @DisplayName("对象型多字段：PtzSpeed / PatternSpeed 都要补上配置项名前缀")
    void addsChannelToMultiFieldObject() {
        // SDK 实测：{"PatternSpeed":5,"PtzSpeed":1}
        Map<String, String> got = sdkFlat("PtzSpeed", "{\"PatternSpeed\":5,\"PtzSpeed\":1}");

        assertEquals("1", got.get("PtzSpeed[0].PtzSpeed"));
        assertEquals("5", got.get("PtzSpeed[0].PatternSpeed"));
    }

    @Test
    @DisplayName("对象型嵌套：PtzLimit 的 UpLimit.Boundary 补维后是 PtzLimit[0].UpLimit.Boundary")
    void addsChannelToNestedObject() {
        // SDK 实测：{"Enable":false,"HorizontalLimitEnable":false,
        //            "UpLimit":{"Boundary":0,"Enable":true}}
        Map<String, String> got = sdkFlat("PtzLimit",
                "{\"Enable\":false,\"HorizontalLimitEnable\":false,"
                        + "\"UpLimit\":{\"Boundary\":0,\"Enable\":true}}");

        assertEquals("false", got.get("PtzLimit[0].Enable"));
        assertEquals("0", got.get("PtzLimit[0].UpLimit.Boundary"));
        assertEquals("true", got.get("PtzLimit[0].UpLimit.Enable"));
    }

    @Test
    @DisplayName("第一条巡航组不能丢：PtzTour[0] 的 0 是线号不是通道号")
    void firstTourIsNotLost() {
        /*
         * 真实回归：曾用「首段数字 == 通道号」判断「是否已带通道维度」，
         * 而巡航线 0 的下标恰好是 0、通道也是 0，于是 PtzTour[0].Name
         * 被当成「已带通道」跳过补维 —— 第 1 条巡航组整个消失，
         * 第 2 条以后（下标 1、2…）照常补维所以都正常。
         * 线上表现：tours 从 route 2 开始，用户在设备上建的第一组看不见。
         */
        String sdk = "[{\"Name\":\"巡航群組1\"},{\"Name\":\"巡航群組2\"}]";
        Map<String, String> got = sdkFlat("PtzTour", sdk);

        assertEquals("巡航群組1", got.get("PtzTour[0][0].Name"),
                "线号 0（界面上是第 1 组）必须落在 [0][0]");
        assertEquals("巡航群組2", got.get("PtzTour[0][1].Name"));
        assertFalse(got.containsKey("PtzTour[0].Name"),
                "不能因为下标等于通道号就不补维：" + got.keySet());
    }

    @Test
    @DisplayName("通道号非 0 时同样正确（多通道 NVR 的通道 1）")
    void worksForNonZeroChannel() {
        Map<String, String> got = DahuaExtAccess.withChannelDimension(
                Map.of("PtzSpeed", "2"), "PtzSpeed", 1, false);
        assertEquals("2", got.get("PtzSpeed[1].PtzSpeed"));
    }

    @Test
    @DisplayName("数组型：巡航线 1 补成 [通道0][线1]，不是 [0][0]")
    void doesNotMistakeRouteForChannel() {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("PtzTour[1].Name", "第二组");
        Map<String, String> got = DahuaExtAccess.withChannelDimension(input, "PtzTour", CH, true);
        assertTrue(got.containsKey("PtzTour[0][1].Name"),
                "巡航线 1 必须补成 [通道0][线1]，实际：" + got.keySet());
        assertEquals("第二组", got.get("PtzTour[0][1].Name"));
    }

    @Test
    @DisplayName("空输入不产生任何键")
    void emptyStaysEmpty() {
        assertTrue(DahuaExtAccess.withChannelDimension(Map.of(), "PtzTour", CH, true).isEmpty());
        assertTrue(DahuaExtAccess.withChannelDimension(Map.of(), "PtzSpeed", CH, false).isEmpty());
    }

    @Test
    @DisplayName("AutoPattern 与 PatternPlayDetail 都是数组型，补维后才能按 idx 对上")
    void patternShapesAlign() {
        Map<String, String> pattern = sdkFlat("AutoPattern",
                "[{\"Enable\":true,\"Name\":\"大门口巡视轨迹\"},{\"Enable\":true,\"Name\":\"巡跡2\"}]");
        Map<String, String> detail = sdkFlat("PatternPlayDetail",
                "[{\"StartPatternPlayDelay\":15000},{\"StartPatternPlayDelay\":15000}]");

        assertEquals("大门口巡视轨迹", pattern.get("AutoPattern[0][0].Name"),
                "巡迹名称含中文，补维不能影响编码");
        assertEquals("巡跡2", pattern.get("AutoPattern[0][1].Name"));
        assertEquals("15000", detail.get("PatternPlayDetail[0][1].StartPatternPlayDelay"));
        assertNotNull(detail.get("PatternPlayDetail[0][0].StartPatternPlayDelay"));
    }
}
