package io.github.rurulalanana.dahua.ext;

import io.github.rurulalanana.dahua.internal.DahuaJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大华相机图像参数的<b>逻辑名 → JSON 配置项 + 字段名</b>映射，含界面元数据。
 *
 * <h3>字段归属全部来自真机 dump，不是猜的</h3>
 * 早期按「图像参数应该都在 VideoInOptions 里」这个直觉填表，结果
 * 亮度/对比度/饱和度/伽马/锐度全部报「该型号不支持」——
 * 而设备 Web 界面明明有这些项。真机导出配置项后才发现大华把它们
 * 分散在 <b>8 个不同的配置项</b>里：
 * <pre>
 *   VideoColor            亮度 对比度 饱和度 色度 色度抑制 伽马 图像风格
 *   VideoInSharpness      锐度 锐度抑制（字段名是 Level）
 *   VideoInOptions        视角(Flip/Mirror/Rotate90) 红外灯
 *   VideoInExposure       抗闪烁 曝光模式 曝光补偿 自动曝光复原 慢快门
 *   VideoInDenoise        2D降噪(2DEnable/2DLevel) 3D降噪(3DType/3DAutoType.AutoflexLevel)
 *   VideoInBacklight      背光模式 强度 强光抑制 宽动态
 *   VideoInWhiteBalance   白平衡模式 色温等级
 *   VideoInDayNight       日夜类型 模式 灵敏度 延迟
 *   VideoInFocus          聚焦模式 灵敏度 PFA 焦距极限
 *   VideoInZoom           数字缩放 缩放速度
 *   VideoInDefog          透雾模式 强度
 * </pre>
 * 教训：<b>不要按「功能相近」猜配置项归属</b>，必须 dump 出来核对。
 *
 * <h3>大多数配置项是「场景数组」</h3>
 * {@code VideoColor} / {@code VideoInExposure} / … 的 {@code params.table}
 * 是 {@code [{Name:"Day",...},{Name:"Night",...},{Name:"Normal",...}]} ——
 * 元素是<b>场景</b>不是通道。所以同一字段会出现 9 次，写入必须全写
 * （见 {@code DahuaCameraConfigService#writeValueAll}）。
 *
 * <h3>字段路径用点号表示层级</h3>
 * 部分字段嵌在子对象里（如 {@code 3DAutoType.AutoLevel}），
 * {@code fields} 里的点号由平台侧的读写实现负责逐层下钻。
 */
public final class DahuaCameraConfig {

    private DahuaCameraConfig() {
    }

    /** 控件类型。前端据此决定渲染成滑块、开关、下拉还是范围输入。 */
    public enum Type {
        /** 数值（滑块）。 */
        NUMBER,
        /** 布尔（开关）。 */
        BOOLEAN,
        /** 枚举（下拉）。取值见 {@link Param#options()}。 */
        ENUM,
        /**
         * 范围数值（两个输入框：最小 - 最大）。
         *
         * <p>设备的手动曝光就是这种形态：增益 0-50、光圈 10-50。
         * 拆成两个独立滑块既不好用，也不符合设备的操作习惯。
         */
        RANGE
    }

    /** 枚举选项。{@code value} 是写回设备的值（数字或字符串）。 */
    public record Option(String value, String label) {
    }

    /**
     * 参数定义。
     *
     * @param key           逻辑名（接口入参、前端 key）
     * @param label         中文名
     * @param group         界面分组（同组控件排在一起）
     * @param configCommand 所属 JSON 配置项
     * @param fields        候选字段路径（按顺序试，支持 {@code A.B} 形式）
     * @param type          控件类型
     * @param options       枚举选项（仅 {@link Type#ENUM} 有值）
     * @param min           数值下限（仅 {@link Type#NUMBER}）
     * @param max           数值上限（同上）
     * @param range         取值范围说明（兼容旧接口）
     * @param virtual       是否为<b>组合参数</b>（不对应单个字段，读写时展开）
     * @param dependsOn     上级参数的 key；为空表示<b>恒显示</b>
     * @param dependsValues 上级参数取这些值时才显示；为空表示「不限」（只看非空/开关打开）
     */
    public record Param(
            String key,
            String label,
            String group,
            String configCommand,
            List<String> fields,
            Type type,
            List<Option> options,
            Integer min,
            Integer max,
            String range,
            boolean virtual,
            String dependsOn,
            List<String> dependsValues) {

        /** 是否受上级参数控制（= 需要联动显示/隐藏）。 */
        public boolean dependent() {
            return dependsOn != null && !dependsOn.isEmpty();
        }
    }

    // ==================== 分组 ====================

    public static final String GROUP_IMAGE = "图像";
    public static final String GROUP_EXPOSURE = "曝光";
    public static final String GROUP_BACKLIGHT = "背光";
    public static final String GROUP_WHITE_BALANCE = "白平衡";
    public static final String GROUP_DAY_NIGHT = "日夜模式";
    public static final String GROUP_FOCUS = "聚焦与缩放";
    public static final String GROUP_LIGHT = "照明器";
    public static final String GROUP_DEFOG = "透雾";

    /**
     * 分组显示顺序。前端按这个顺序排块，不按 JSON 顺序。
     *
     * <p>刻意与设备 Web 界面左侧菜单一致：圖像 / 曝光 / 背光 / 白平衡 /
     * 日/夜模式 / 聚焦與縮放 / 照明器 / 透霧。
     * 「视角」不再是独立分组 —— 设备把「视角 / EIS / 画面冻结」
     * 都放在「图像」页底部，我们也跟着归入图像。
     */
    public static final List<String> GROUPS = List.of(
            GROUP_IMAGE, GROUP_EXPOSURE, GROUP_BACKLIGHT, GROUP_WHITE_BALANCE,
            GROUP_DAY_NIGHT, GROUP_FOCUS, GROUP_LIGHT, GROUP_DEFOG);

    // ==================== 枚举选项（取自设备 Web 界面）====================

    /**
     * 图像风格。
     *
     * <p>取值与标签以设备 Web 界面下拉为准，只有三项：
     * <pre>
     *   柔和     Gentle
     *   標準     Standard
     *   生動     Flamboyant
     * </pre>
     * 早期按语义猜过一版「标准/柔和/鲜艳/自定义」，其中 {@code Vivid}
     * 与 {@code Custom} 设备根本不认，而真实的第二档叫 {@code Flamboyant}。
     *
     * <p><b>这个字段设备不做校验</b>：实测写入 {@code Standard1}、{@code Normal}
     * 都会「保存成功」并原样回读，只是 ISP 不认、画面不会变。
     * 所以合法性只能以 Web 界面为准，不能靠「写进去读得回来」判断。
     */
    private static final List<Option> OPT_STYLE = List.of(
            new Option("Gentle", "柔和"),
            new Option("Standard", "标准"),
            new Option("Flamboyant", "生动"));

    /**
     * 抗闪烁（Web 界面下拉：室外 / 50Hz / 60Hz）。
     *
     * <p>全部经抓包验证：{@code AntiFlicker} 写 0 显示「室外」、1 显示「50Hz」、
     * 2 显示「60Hz」。注意<b>没有「关闭」档</b> —— 早期按常识写成
     * 「关闭/室内 50Hz/室外 60Hz」，三项全错位：用户选「关闭」实际设成了「室外」。
     */
    private static final List<Option> OPT_ANTIFLICKER = List.of(
            new Option("0", "室外"),
            new Option("1", "50Hz"),
            new Option("2", "60Hz"));

    /**
     * 曝光模式（Web 界面下拉：<b>自動 / 光圈優先順序 / 快門優先 / 增益優先順序 / 手動</b>）。
     *
     * <p>取值全部来自在设备界面逐项点击、抓 {@code RPC2 set} 请求得到，
     * <b>不是猜的</b>：自動=0、手動=4、光圈優先=5、增益優先=7、快門優先=8。
     * 注意 7 和 8 与界面顺序相反 —— 按顺序假定 6/7 会写进设备不认的值。
     *
     * <p>各档位在界面上带出不同的下级输入框：
     * <ul>
     *   <li>自動 → 无</li>
     *   <li>光圈優先 → 光圈</li>
     *   <li>快門優先 → 快門</li>
     *   <li>增益優先 → 增益</li>
     *   <li>手動 → 快門 + 增益 + 光圈</li>
     * </ul>
     */
    public static final String EXPOSURE_AUTO = "0";
    public static final String EXPOSURE_MANUAL = "4";
    public static final String EXPOSURE_IRIS_PRIOR = "5";
    public static final String EXPOSURE_GAIN_PRIOR = "7";
    public static final String EXPOSURE_SHUTTER_PRIOR = "8";

    private static final List<Option> OPT_EXPOSURE_MODE = List.of(
            new Option(EXPOSURE_AUTO, "自动"),
            new Option(EXPOSURE_IRIS_PRIOR, "光圈优先"),
            new Option(EXPOSURE_SHUTTER_PRIOR, "快门优先"),
            new Option(EXPOSURE_GAIN_PRIOR, "增益优先"),
            new Option(EXPOSURE_MANUAL, "手动"));

    /**
     * 自动曝光复原（Web 界面下拉：關閉/5分/15分/1小時/2小時）。
     *
     * <p>底层字段 {@code RecoveryTime}，单位是<b>秒</b>（抓包验证 300/900/3600/7200）。
     * 设备默认就是 900（=15 分钟）。早期完全没有这一项。
     */
    private static final List<Option> OPT_RECOVERY_TIME = List.of(
            new Option("0", "关闭"),
            new Option("300", "5 分钟"),
            new Option("900", "15 分钟"),
            new Option("3600", "1 小时"),
            new Option("7200", "2 小时"));

    /**
     * 背光模式（Web 界面下拉：<b>關閉 / 背光補償 / 強光抑制 / 寬動態</b>）。
     *
     * <p>抓 {@code RPC2 set} 与设备 CGI 双重验证的四项取值：關閉={@code Off}、
     * 背光補償={@code Backlight}、強光抑制={@code GlareInhibition}、
     * 寬動態={@code WideDynamic}。
     *
     * <p><b>宽动态曾一度被误删</b>：当时往 {@code Mode} 写 {@code WideDynamic}
     * 全部返回 {@code Bad Request}，于是判定「设备不支持」。真正原因是当时
     * 透雾被留在了非法值上，而设备规则是<b>透雾开启时不允许背光补偿</b>——
     * 把透雾改回关闭后，{@code WideDynamic} 一次写入成功。
     * 写背光模式若被拒，先查透雾是否还开着。
     *
     * <p>联动：选「背光补偿」出现「区域（预设/自定义）」与「强度」；
     * 选「强光抑制」出现强度滑块；选「宽动态」出现宽动态范围滑块。
     */
    private static final List<Option> OPT_BACKLIGHT = List.of(
            new Option("Off", "关闭"),
            new Option("Backlight", "背光补偿"),
            new Option("GlareInhibition", "强光抑制"),
            new Option("WideDynamic", "宽动态"));

    /**
     * 白平衡模式（Web 界面下拉，按设备实际顺序）。
     *
     * <p>抓包验证的两个坑：
     * <ul>
     *   <li>「跟踪」的取值是 <b>{@code ATW}</b>，不是 {@code Tracking}</li>
     *   <li>「钠灯」的取值是 <b>{@code Sodium}</b>，不是 {@code NaLight}</li>
     * </ul>
     * 这两个都是「写进去设备静默忽略、界面看起来没改」的那种错。
     */
    private static final List<Option> OPT_WHITE_BALANCE = List.of(
            new Option("Auto", "自动"),
            new Option("Indoor", "室内"),
            new Option("Outdoor", "室外"),
            new Option("ATW", "跟踪"),
            new Option("Manual", "手动"),
            new Option("Sodium", "钠灯"),
            new Option("Natural", "自然光"),
            new Option("StreetLamp", "路灯"));

    /**
     * 日夜类型（Web 界面是单选按钮，不是下拉：電子 / ICR）。
     *
     * <p>抓包验证：電子 = <b>{@code Electron}</b>，不是 {@code Electronic}。
     */
    private static final List<Option> OPT_DAY_NIGHT_TYPE = List.of(
            new Option("Electron", "电子"),
            new Option("Mechanism", "ICR"));

    /**
     * 日夜模式（Web 界面下拉：彩色 / 黑白 / 自動）。
     *
     * <p>抓包验证：彩色 = {@code Color}、黑白 = {@code BlackWhite}、
     * 自動 = <b>{@code Brightness}</b>（不是 {@code Auto}）。
     * 早期按语义猜成「按亮度/自定义/按时间」，三项全不存在。
     */
    private static final List<Option> OPT_DAY_NIGHT_MODE = List.of(
            new Option("Color", "彩色"),
            new Option("BlackWhite", "黑白"),
            new Option("Brightness", "自动"));

    /**
     * 日夜灵敏度（Web 界面下拉：低 / 中 / 高）。
     *
     * <p><b>取值是反序的</b>：抓包验证 高=1、中=2、低=3。
     * 早期按「0=最低…4=最高」的五级做，选「高」实际会设成「低」。
     */
    private static final List<Option> OPT_SENSITIVITY = List.of(
            new Option("1", "高"),
            new Option("2", "中"),
            new Option("3", "低"));

    /** 日夜切换延迟（Web 界面下拉：2~10 秒，取值就是秒数）。 */
    private static final List<Option> OPT_DELAY = List.of(
            new Option("2", "2 秒"),
            new Option("3", "3 秒"),
            new Option("4", "4 秒"),
            new Option("5", "5 秒"),
            new Option("6", "6 秒"),
            new Option("7", "7 秒"),
            new Option("8", "8 秒"),
            new Option("9", "9 秒"),
            new Option("10", "10 秒"));

    /**
     * 聚焦模式（Web 界面下拉：手動 / 自動 / 半自動）。
     *
     * <p>抓包验证：手動=4、自動=2、半自動=3。
     * 注意界面<b>排列顺序与取值顺序不一致</b>，所以这里按取值升序写。
     */
    private static final List<Option> OPT_FOCUS_MODE = List.of(
            new Option("2", "自动"),
            new Option("3", "半自动"),
            new Option("4", "手动"));

    /**
     * 接近焦距极限（Web 界面下拉：<b>自動</b> + 10cm ~ 200m+）。
     *
     * <p>底层是<b>两个字段</b>：
     * <ul>
     *   <li>选「自动」→ {@code FocusLimitSelectMode=Auto}（距离值保持原样）</li>
     *   <li>选具体距离 → {@code FocusLimit=值} 且 {@code FocusLimitSelectMode=Manual}</li>
     * </ul>
     * 单位毫米（抓包验证 10cm=100、1m=1000、100m=100000），
     * 「200m+」是特殊值 <b>200001</b>（不是 200000 —— 差 1 就是正好 200 米）。
     *
     * <p>早期漏了「自动」这一项，13 项只能选 12 项。
     */
    private static final List<Option> OPT_FOCUS_LIMIT = List.of(
            new Option("Auto", "自动"),
            new Option("100", "10cm"),
            new Option("1000", "1m"),
            new Option("2000", "2m"),
            new Option("3000", "3m"),
            new Option("5000", "5m"),
            new Option("10000", "10m"),
            new Option("20000", "20m"),
            new Option("30000", "30m"),
            new Option("50000", "50m"),
            new Option("100000", "100m"),
            new Option("200000", "200m"),
            new Option("200001", "200m+"));

    /** 「自动」在 {@code FocusLimitSelectMode} 里的取值，写入时需要特殊处理。 */
    public static final String FOCUS_LIMIT_AUTO = "Auto";

    /**
     * 快门速度（曝光模式 = 快门优先 / 手动 时出现）。
     *
     * <p>设备界面上它是<b>一个下拉</b>，底层写的是 {@code Value1}/{@code Value2}
     * 两个字段，单位<b>毫秒</b>：选具体档位时两者写同一个值（{@code 1000/分母}），
     * 选「自訂」才拆成范围。抓包验证：1/50 → 20、1/25 → 40、1/120 → 8.33。
     *
     * <p>值取<b>分母</b>（1/50 → {@code "50"}），与界面「1/xx」一一对应，
     * 换算在服务层做 —— 直接存毫秒会出现 {@code 1000/3} 这种除不尽的浮点尾巴。
     *
     * <h3>档位随抗闪烁变化（实测三种）</h3>
     * <pre>
     *   室外（默认）  40 档：1/30000 … 1/25，再加速门 1/15 … 1，末尾带「自訂」
     *   50Hz          6 档：1/100 1/50 1/25 1/10 1/5 1/4   （<b>没有自訂</b>）
     *   60Hz          6 档：1/120 1/60 1/30 1/12 1/6 1/4   （<b>没有自訂</b>）
     * </pre>
     * 这不是型号差异，是物理约束：防闪烁要求快门与工频同步，
     * 50Hz 只能取 1/(50·2ⁿ)，60Hz 只能取 1/(60·2ⁿ)。
     *
     * <p>早期把三者并成一个 40 档列表，用户在 50Hz 下能选到 1/1000 这种
     * 设备根本不提供的档位 —— 写入后设备照存（{@code Value1} 是自由浮点字段），
     * 但防闪烁实际不生效。所以列表必须跟着抗闪烁走。
     */
    public static final String SHUTTER_CUSTOM = "custom";

    /** 室外 / 默认：完整档位。 */
    private static final List<Option> SHUTTER_OUTDOOR = List.of(
            new Option("30000", "1/30000"),
            new Option("10000", "1/10000"),
            new Option("6000", "1/6000"),
            new Option("4000", "1/4000"),
            new Option("3500", "1/3500"),
            new Option("3000", "1/3000"),
            new Option("2500", "1/2500"),
            new Option("2000", "1/2000"),
            new Option("1750", "1/1750"),
            new Option("1500", "1/1500"),
            new Option("1250", "1/1250"),
            new Option("1000", "1/1000"),
            new Option("725", "1/725"),
            new Option("600", "1/600"),
            new Option("500", "1/500"),
            new Option("425", "1/425"),
            new Option("350", "1/350"),
            new Option("300", "1/300"),
            new Option("250", "1/250"),
            new Option("215", "1/215"),
            new Option("180", "1/180"),
            new Option("150", "1/150"),
            new Option("125", "1/125"),
            new Option("120", "1/120"),
            new Option("100", "1/100"),
            new Option("90", "1/90"),
            new Option("75", "1/75"),
            new Option("60", "1/60"),
            new Option("50", "1/50"),
            new Option("30", "1/30"),
            new Option("25", "1/25"),
            // 以下为慢门（整秒倒数的长曝光）
            new Option("15", "1/15"),
            new Option("12", "1/12"),
            new Option("8", "1/8"),
            new Option("6", "1/6"),
            new Option("4", "1/4"),
            new Option("3", "1/3"),
            new Option("2", "1/2"),
            new Option("1", "1"),
            new Option(SHUTTER_CUSTOM, "自定义"));

    /** 抗闪烁 = 50Hz：只能取 1/(50·2ⁿ)，<b>设备界面不给「自訂」</b>。 */
    private static final List<Option> SHUTTER_50HZ = List.of(
            new Option("100", "1/100"),
            new Option("50", "1/50"),
            new Option("25", "1/25"),
            new Option("10", "1/10"),
            new Option("5", "1/5"),
            new Option("4", "1/4"));

    /** 抗闪烁 = 60Hz：只能取 1/(60·2ⁿ)，<b>设备界面不给「自訂」</b>。 */
    private static final List<Option> SHUTTER_60HZ = List.of(
            new Option("120", "1/120"),
            new Option("60", "1/60"),
            new Option("30", "1/30"),
            new Option("12", "1/12"),
            new Option("6", "1/6"),
            new Option("4", "1/4"));

    /**
     * 该抗闪烁设置下，设备是否提供「自訂」（自定义范围）。
     *
     * <p>抓设备界面确认：室外有 40 档 + 自訂；50Hz / 60Hz <b>只有 6 档、没有自訂</b>。
     * 防闪烁要求快门与工频同步，自由范围会破坏同步，所以设备不给这个入口。
     *
     * <p>这里必须跟着收：50Hz 下把「自定义」摆出来，用户选了能写进去
     * （{@code Value1/Value2} 是自由浮点字段），但防闪烁随即失效，画面会频闪。
     */
    public static boolean shutterSupportsCustom(String antiFlicker) {
        String v = antiFlicker == null ? "" : antiFlicker.trim();
        return !ANTI_FLICKER_50HZ.equals(v) && !ANTI_FLICKER_60HZ.equals(v);
    }

    /** 抗闪烁的三种取值（{@code AntiFlicker} 字段）。 */
    public static final String ANTI_FLICKER_OUTDOOR = "0";
    public static final String ANTI_FLICKER_50HZ = "1";
    public static final String ANTI_FLICKER_60HZ = "2";

    /**
     * 按抗闪烁设置取快门档位。
     *
     * @param antiFlicker {@code AntiFlicker} 的当前值；{@code null} 或未知按室外处理
     */
    public static List<Option> shutterOptions(String antiFlicker) {
        String v = antiFlicker == null ? "" : antiFlicker.trim();
        if (ANTI_FLICKER_50HZ.equals(v)) {
            return SHUTTER_50HZ;
        }
        if (ANTI_FLICKER_60HZ.equals(v)) {
            return SHUTTER_60HZ;
        }
        return SHUTTER_OUTDOOR;
    }

    /**
     * 快门分母 → 毫秒（设备存的 {@code Value1}/{@code Value2}）。
     *
     * <h3>必须四舍五入到 2 位小数</h3>
     * 设备自己的下拉框写入的是<b>保留 2 位小数</b>的毫秒值（抓 RPC2 逐个档位确认）：
     * <pre>
     *   1/30000 → 0.03     1/215 → 4.65     1/90 → 11.11
     *   1/125   → 8        1/120 → 8.33     1/3  → 333.33
     * </pre>
     * 而这些档位的精确值除不尽（{@code 1000/90 = 11.11111…}）。
     * 写精确值时设备<b>照存</b>（{@code Value1} 是自由浮点字段），
     * 但它自己的下拉拿 {@code 11.11111…} 去比内置的 {@code 11.11} 不相等，
     * 于是显示成「自訂」并带出一个 `11.11 - 11.11` 的范围框 ——
     * 用户看到的就是「平台选 1/120、设备显示自定义」。
     *
     * <p>所以写入值必须与设备下拉用的是同一个数：{@code round(1000/分母, 2)}。
     *
     * @return 毫秒（2 位小数）；参数非法时返回 {@code null}
     */
    public static Double shutterMillis(Object denominator) {
        if (denominator == null) {
            return null;
        }
        try {
            double d = Double.parseDouble(String.valueOf(denominator).trim());
            return d > 0 ? Math.round(1000.0 / d * 100.0) / 100.0 : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 毫秒 → 界面要显示的快门值。
     *
     * <h3>为什么不再返回 {@code null}（= 自定义）</h3>
     * 设备把 {@code Value1} 当自由浮点字段存，<b>任何</b>毫秒值都能写进去
     * （实测写 5ms 存 5.000000）。早期用「最接近档位 + 相对误差 5%」判定，
     * 匹配不上就返回 {@code null} 让界面显示「自定义」——
     * 于是「1/200（= 5ms）」这种<i>设备完全支持但不在列表里</i>的值
     * 被误报成自定义，用户以为自己选了范围。
     *
     * <p>现在的口径：
     * <ul>
     *   <li>能量到档位 → 返回档位分母（{@code 1/215}）</li>
     *   <li>量到但不能对应档位 → 返回<b>反算的分母</b>（5ms → {@code 200}）</li>
     * </ul>
     * 「自定义」只留给 {@code Value1 ≠ Value2}（真正的范围），
     * 判定在服务层做，不在这里。
     *
     * @param millis 设备存的毫秒值
     * @param options 当前抗闪烁下可用的档位；{@code null} 用室外全集
     * @return 分母字符串（可能不在 {@code options} 里）；{@code millis} 非法时返回 {@code null}
     */
    public static String shutterDenominator(Object millis, List<Option> options) {
        if (millis == null) {
            return null;
        }
        double ms;
        try {
            ms = Double.parseDouble(String.valueOf(millis).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (ms <= 0) {
            return null;
        }
        /*
         * 先归到 2 位小数再比。
         *
         * 设备自己和档位对比时用的就是 2 位小数（0.03 / 11.11 / 333.33），
         * 早期用相对误差 5% 去「就近匹配」会把 1/215 与 1/200 这类相邻档位搞混，
         * 而且 1/30000（0.03）这种极短值反算会得到 33333，怎么算都不对。
         */
        double rounded = Math.round(ms * 100.0) / 100.0;
        List<Option> pool = (options == null || options.isEmpty()) ? SHUTTER_OUTDOOR : options;
        for (Option o : pool) {
            if (SHUTTER_CUSTOM.equals(o.value())) {
                continue;
            }
            Double candidate = shutterMillis(o.value());
            if (candidate == null) {
                continue;
            }
            // 两边都是 2 位小数，允许 1e-9 只是为了吸收 double 表示误差
            if (Math.abs(candidate - rounded) < 1e-9) {
                return o.value();
            }
        }
        /*
         * 不在档位表里（用户在设备 Web 上直接填过毫秒，或换了抗闪烁）。
         * 反算分母给界面显示，避免误报成「自定义」。
         * 1/200 = 5ms → 200；但 0.03 → 33333 明显不是真实档位，
         * 这种情况交给上层判「自定义」更诚实。
         */
        long d = Math.round(1000.0 / rounded);
        return d > 0 && d <= 30000 ? String.valueOf(d) : null;
    }

    /** 快门速度参数的 key。服务层要按它做「分母 ↔ 毫秒」的换算。 */
    public static final String KEY_SHUTTER_SPEED = "shutterSpeed";

    /**
     * 透雾强度（仅透雾模式=手动时出现）。
     *
     * <p>抓包验证：低/中/高 三档，底层是整数 {@code Intensity}。
     */
    private static final List<Option> OPT_INTENSITY_LEVEL = List.of(
            new Option("0", "低"),
            new Option("1", "中"),
            new Option("2", "高"));

    /**
     * 背光补偿的「預設 / 自訂」选择（{@code BacklightMode}）。
     *
     * <p><b>这里曾经错绑到 {@code IntensityMode}，那是这台设备上「无效」的根因。</b>
     * 逐项对照设备自己的 CGI 验证（SN 10001 / fw 3.201.0000007.1.R）：
     * <pre>
     *   VideoInBacklight[0][n].Mode          = Off | Backlight | GlareInhibition | WideDynamic
     *   VideoInBacklight[0][n].BacklightMode = Default | Custom      ← 「預設 / 自訂」
     *   VideoInBacklight[0][n].BacklightRegion[0..3] = 自定义区域矩形（3096,3096,5096,5096）
     *   VideoInBacklight[0][n].Intensity     = 0~100 背光补偿强度
     *   VideoInBacklight[0][n].IntensityMode = Enable | Disable      ← 只是 Intensity 的使能位
     * </pre>
     * {@code IntensityMode} 写进去设备确实会变、也会回读成功，但它只控制
     * 「强度值是否参与运算」，界面上没有对应的可见效果 —— 用户拨动开关后画面毫无变化，
     * 于是判定「自定义开关无效」。真正决定背光补偿用不用自定义区域的
     * 是 {@code BacklightMode}，配合 {@code BacklightRegion} 才生效。
     */
    private static final List<Option> OPT_BACKLIGHT_AREA = List.of(
            new Option("Default", "预设"),
            new Option("Custom", "自定义"));

    /**
     * 聚焦灵敏度（Web 界面下拉：高 / 預設值 / 低）。
     *
     * <p>抓包验证：高=0、預設值=1、低=2。
     * 早期按 0~100 的滑块做，写进去的是任意数字，设备只认这三个。
     */
    private static final List<Option> OPT_FOCUS_SENSITIVITY = List.of(
            new Option("0", "高"),
            new Option("1", "预设值"),
            new Option("2", "低"));

    /**
     * 3D 降噪（Web 界面是<b>开关</b>，不是下拉）。
     *
     * <p>底层字段 {@code 3DType} 抓包验证只有 {@code Auto}（开）与 {@code Off}（关）两值。
     *
     * <p><b>类型必须是 BOOLEAN，不是 ENUM</b>：设备界面上它是开关，
     * 定义成 ENUM 会渲染成下拉框，与设备不一致。
     * 但底层存的是字符串，所以用 {@link #boolOf} 声明「开关 ↔ 字符串」的映射。
     */
    private static final String VAL_3D_ON = "Auto";
    private static final String VAL_3D_OFF = "Off";

    /**
     * 透雾模式（Web 界面下拉：關閉/手動/自動）。
     *
     * <p><b>「手动」在设备固件里拼成了 {@code Manul}</b>（少一个 a），
     * 抓 {@code RPC2 set} 确认：關閉={@code Off}、手動=<b>{@code Manul}</b>、自動={@code Auto}。
     *
     * <p>早期按正常拼写写 {@code Manual}，设备不认这个值，于是：
     * 下拉框回显失败（显示成原始英文 {@code Manual} 而不是「手动」）、
     * 并且连带挡住宽动态（透雾「开着」的判定一直成立）。
     * 这是「下发后参数不正确、回显失败」的直接原因。
     */
    private static final List<Option> OPT_DEFOG = List.of(
            new Option("Off", "关闭"),
            new Option("Manul", "手动"),
            new Option("Auto", "自动"));

    /**
     * 照明器模式（Web 界面下拉：手動/自動/縮放優先/關閉）。
     *
     * <p>这是<b>独立配置项 {@code Lighting}</b>，不是 {@code VideoInOptions} 里的
     * {@code InfraRed} 布尔。抓包验证：
     * 手動={@code Manual}、自動={@code Auto}、縮放優先={@code ZoomPrio}、關閉={@code Off}。
     *
     * <p>早期用 {@code InfraRed}/{@code InfraRedLevel} 顶替，
     * 那组字段界面上根本没有入口，改了对补光灯毫无影响。
     */
    private static final List<Option> OPT_LIGHTING_MODE = List.of(
            new Option("Manual", "手动"),
            new Option("Auto", "自动"),
            new Option("ZoomPrio", "缩放优先"),
            new Option("Off", "关闭"));

    /**
     * 视角模式的取值。
     *
     * <p>设备 Web 界面的下拉只有<b>两项</b>：正常 / 倒影。
     * 底层 {@code VideoImageControl} 里虽然还有 {@code Mirror} 与 {@code Rotate90}
     * 两个字段，但这台型号的 {@code Rotate90} 只接受 0（写 1/90/180/270 都被拒），
     * 界面上也没有对应入口 —— 所以不提供这两个选项，免得用户选了却没反应。
     *
     * <p>这是<b>组合参数</b>：没有单个 {@code ViewMode} 字段，
     * 由 {@code Flip}/{@code Mirror}/{@code Rotate90} 三个字段组合表示。
     */
    public static final List<Option> OPT_VIEW_MODE = List.of(
            new Option("0", "正常"),
            new Option("1", "倒影"));

    /**
     * 视角 / 稳定 / 冻结 所在的配置项。
     *
     * <h3>为什么不是 VideoInOptions</h3>
     * 早期把 {@code Flip}/{@code Mirror}/{@code Rotate90} 挂在 {@code VideoInOptions} 上，
     * 因为那个配置项里确实有同名字段。但实测两者<b>完全独立</b>：
     * 写 {@code VideoImageControl[0].Flip=true} 后，
     * {@code VideoInOptions[0].Flip} 丝毫不变（反方向同样不联动）。
     *
     * <p>而设备 Web 界面的「视角 / EIS / 画面冻结」三项全部读写 {@code VideoImageControl}
     * （已抓包确认：改画面冻结时发出的是
     * {@code configManager.setTemporaryConfig & name=VideoImageControl}）。
     * 所以 {@code VideoInOptions} 里那一份是历史遗留字段，写它不影响画面。
     */
    public static final String CMD_IMAGE_CONTROL = "VideoImageControl";

    /** 视角模式：正常。 */
    public static final int VIEW_NORMAL = 0;
    /** 视角模式：倒影（垂直翻转）。 */
    public static final int VIEW_FLIP = 1;
    /** 视角模式：走廊（旋转 90°）。 */
    public static final int VIEW_CORRIDOR = 2;
    /** 视角模式：镜像（水平翻转）。 */
    public static final int VIEW_MIRROR = 3;

    /** 组合参数 {@code viewMode} 展开成的成员字段。 */
    public static final List<String> VIEW_MODE_MEMBERS = List.of("flip", "mirror", "rotate90");

    /**
     * 焦距极限模式（只写，界面不出现）。
     *
     * <p>设备的「接近焦距极限」是 13 项合一下拉，但底层跨了两个字段：
     * 距离写 {@code FocusLimit}（整数，毫米），
     * 模式写 {@link #KEY_FOCUS_LIMIT_MODE_FIELD}（{@code Auto}/{@code Manual}）。
     *
     * <p>这个参数是给 {@code focusLimit} 做连带写入用的 ——
     * 用户选「自动」时由服务层自动置为 {@code Auto}，不让用户单独看见。
     *
     * <p>必须声明在 {@code ALL} <b>之前</b>：Java 静态字段按声明顺序初始化，
     * 放在后面会被当成非法前向引用（编译不过）。
     */
    public static final String KEY_FOCUS_LIMIT_MODE = "focusLimitMode";

    /** 参数 key：{@code 接近焦距极限}（服务层要按它做跨字段特殊处理）。 */
    public static final String KEY_FOCUS_LIMIT = "focusLimit";

    /** {@code FocusLimitSelectMode} —— 设备里存焦距极限模式的真实字段名。 */
    public static final String KEY_FOCUS_LIMIT_MODE_FIELD = "FocusLimitSelectMode";

    // ==================== 参数表 ====================

    private static final List<Param> ALL = List.of(
            // —— 图像（VideoColor）——
            en("style", "图像风格", GROUP_IMAGE, "VideoColor",
                    List.of("Style"), OPT_STYLE),
            num("brightness", "亮度", GROUP_IMAGE, "VideoColor",
                    List.of("Brightness", "nBrightness"), 0, 100),
            num("contrast", "对比度", GROUP_IMAGE, "VideoColor",
                    List.of("Contrast", "nContrast"), 0, 100),
            num("saturation", "饱和度", GROUP_IMAGE, "VideoColor",
                    List.of("Saturation", "nSaturation"), 0, 100),
            num("hue", "色度增益", GROUP_IMAGE, "VideoColor",
                    List.of("Hue", "nHue"), 0, 100),
            // 锐度与锐度抑制在独立配置项里；「锐度抑制」的字段名是 Level
            num("sharpness", "锐度", GROUP_IMAGE, "VideoInSharpness",
                    List.of("Sharpness", "nSharpness"), 0, 100),
            num("sharpnessSuppress", "锐度抑制", GROUP_IMAGE, "VideoInSharpness",
                    List.of("Level", "nLevel"), 0, 100),
            /*
             * 伽马排在锐度之后 —— 与设备 Web 界面的顺序一致
             * （樣式 / 亮度 / 對比度 / 飽和度 / 色度增益 / 銳度 / 銳度抑制 / 伽馬）。
             * 参数表按声明顺序渲染，所以这里不能按「配置项分组」来排。
             *
             * 这里也没有「色度抑制」（ChromaSuppress）：设备图像页只有
             * 「色度增益隱藏項目」一个色度项，没有单独的抑制滑块。
             * 该字段配置里确实存在，但界面上无入口 —— 摆出来只会让人问
             * 「为什么设备上没有这一项」。按设备口径去掉。
             */
            num("gamma", "伽马", GROUP_IMAGE, "VideoColor",
                    List.of("Gamma", "nGamma"), 0, 100),

            /*
             * —— 视角 / EIS / 画面冻结（VideoImageControl）——
             *
             * 设备 Web 界面把这三项排在「图像」页里（樣式…伽馬 → 視角 → EIS → 畫面凍結），
             * 所以这里也归入「图像」组，不再单开一个「视角」页。
             */
            new Param("viewMode", "视角", GROUP_IMAGE, CMD_IMAGE_CONTROL,
                    List.of("ViewMode", "nViewMode"), Type.ENUM, OPT_VIEW_MODE,
                    null, null, "0=正常 1=倒影", true, null, List.of()),
            bool("flip", "倒影（垂直翻转）", GROUP_IMAGE, CMD_IMAGE_CONTROL,
                    List.of("Flip", "bFlip")),
            bool("mirror", "镜像（水平翻转）", GROUP_IMAGE, CMD_IMAGE_CONTROL,
                    List.of("Mirror", "bMirror")),
            bool("rotate90", "走廊模式（旋转 90°）", GROUP_IMAGE, CMD_IMAGE_CONTROL,
                    List.of("Rotate90", "bRotate90")),
            bool("eis", "EIS 电子防抖", GROUP_IMAGE, CMD_IMAGE_CONTROL,
                    List.of("Stable")),
            bool("freeze", "画面冻结", GROUP_IMAGE, CMD_IMAGE_CONTROL,
                    List.of("Freeze")),

            // —— 曝光（VideoInExposure）——
            en("antiFlicker", "抗闪烁", GROUP_EXPOSURE, "VideoInExposure",
                    List.of("AntiFlicker"), OPT_ANTIFLICKER),
            en("exposure", "曝光模式", GROUP_EXPOSURE, "VideoInExposure",
                    List.of("Mode", "ExposureMode"), OPT_EXPOSURE_MODE),
            /*
             * 曝光模式各档位带出的下级控件（已在设备界面上逐档确认）：
             *   自动      → 无
             *   光圈优先  → 光圈范围 0-100
             *   快门优先  → 快门下拉
             *   增益优先  → 增益范围 0-100
             *   手动      → 快门下拉 + 增益范围 + 光圈范围
             *
             * <p><b>快门是下拉，不是范围</b>：底层写 {@code Value1}/{@code Value2}（毫秒），
             * 选具体档位时两个字段写同值，只有「自訂」才露出毫秒范围框。
             *
             * <p>这里的 {@code options} 只是<b>兜底默认</b>（室外全集）。
             * 实际档位随抗闪烁变，服务层会按当前 {@code AntiFlicker} 用
             * {@link #shutterOptions} 覆盖，见 DahuaCameraConfigService#capabilities。
             *
             * <p>增益/光圈是「最小 - 最大」两个数字框，两者都限制 <b>0-100</b>
             * （设备界面角标写着 (0-100)，输入框的 min/max 也是 0/100）。
             */
            enDep(KEY_SHUTTER_SPEED, "快门", GROUP_EXPOSURE, "VideoInExposure",
                    List.of("Value1", "Value2"), SHUTTER_OUTDOOR,
                    "exposure", List.of(EXPOSURE_SHUTTER_PRIOR, EXPOSURE_MANUAL)),
            // 只有选「自訂」才出现：设备的「快門 - 毫秒(0-1000)」两个框
            numRangeDep("shutterRange", "快门范围", GROUP_EXPOSURE, "VideoInExposure",
                    List.of("Value1"), List.of("Value2"), 0, 1000,
                    KEY_SHUTTER_SPEED, List.of(SHUTTER_CUSTOM)),
            numRangeDep("gainRange", "增益范围", GROUP_EXPOSURE, "VideoInExposure",
                    List.of("GainMin"), List.of("GainMax"), 0, 100,
                    "exposure", List.of(EXPOSURE_GAIN_PRIOR, EXPOSURE_MANUAL)),
            numRangeDep("irisRange", "光圈范围", GROUP_EXPOSURE, "VideoInExposure",
                    List.of("IrisMin"), List.of("IrisMax"), 0, 100,
                    "exposure", List.of(EXPOSURE_IRIS_PRIOR, EXPOSURE_MANUAL)),
            num("exposureCompensation", "曝光补偿", GROUP_EXPOSURE, "VideoInExposure",
                    List.of("Compensation", "nCompensation"), 0, 100),
            en("exposureRecovery", "自动曝光复原", GROUP_EXPOSURE, "VideoInExposure",
                    List.of("RecoveryTime"), OPT_RECOVERY_TIME),

            // —— 降噪（VideoInDenoise）——
            // 设备 Web 界面把 2D/3D 降噪放在「曝光」页底部，所以归入曝光组
            bool("denoise2dEnable", "2D 降噪", GROUP_EXPOSURE, "VideoInDenoise",
                    List.of("2DEnable")),
            // 关掉 2D 降噪时等级滑块会隐藏
            numDep("denoise2dLevel", "2D 降噪等级", GROUP_EXPOSURE, "VideoInDenoise",
                    List.of("2DLevel"), 0, 100, "denoise2dEnable", List.of()),
            boolOf("denoise3dType", "3D 降噪", GROUP_EXPOSURE, "VideoInDenoise",
                    List.of("3DType"), VAL_3D_ON, VAL_3D_OFF),
            /*
             * 等级在子对象里：3DAutoType.AutoLevel。
             *
             * dependsValues 传空 = 「只看上级是否打开」。
             * 上级现在是开关（BOOLEAN），不能再写 {@code "Auto"} ——
             * 用户的草稿值是 {@code true}，拿它去比字符串 "Auto" 永远不等，
             * 等级滑块会一直不显示。
             */
            numDep("denoise3dLevel", "3D 降噪等级", GROUP_EXPOSURE, "VideoInDenoise",
                    List.of("3DAutoType.AutoLevel", "3DManulType.SnfLevel"), 0, 100,
                    "denoise3dType", List.of()),

            /*
             * —— 背光（VideoInBacklight）——
             * 设备这一页只有「模式」，但不同模式会带出不同下级：
             *   关闭        → 无
             *   背光补偿    → 「区域（预设/自定义）」+「强度」
             *   强光抑制    → 强度滑块（GlareInhibition）
             *   宽动态      → 宽动态范围滑块（WideDynamicRange）
             *
             * <p>「自定义」原本错绑到 {@code IntensityMode}，那只是 Intensity 的使能位，
             * 拨动后画面没有任何可见变化，用户因此判定「开关无效」。
             * 真正的「預設 / 自訂」是 {@code BacklightMode}，配合 {@code BacklightRegion}。
             *
             * <p>{@code BacklightRegion} 是自定义区域的<b>四个坐标</b>
             * （{@code BacklightRegion[0..3]}，这台 IPC 上是 3096/3096/5096/5096
             * 这种 0~8191 的归一化值），不是「下限/上限」那种二元范围。
             * 设备 Web 界面用画面拖框设置，纯数字输入用户无法凭肉眼填对，
             * 所以平台侧<b>不暴露</b>这四项，避免改错反而把区域弄乱。
             */
            en("backlightMode", "背光模式", GROUP_BACKLIGHT, "VideoInBacklight",
                    List.of("Mode"), OPT_BACKLIGHT),
            enDep("backlightArea", "区域", GROUP_BACKLIGHT, "VideoInBacklight",
                    List.of("BacklightMode"), OPT_BACKLIGHT_AREA,
                    "backlightMode", List.of("Backlight")),
            numDep("backlightIntensity", "强度", GROUP_BACKLIGHT, "VideoInBacklight",
                    List.of("Intensity"), 0, 100, "backlightMode", List.of("Backlight")),
            numDep("glareInhibition", "强度", GROUP_BACKLIGHT, "VideoInBacklight",
                    List.of("GlareInhibition"), 0, 100, "backlightMode", List.of("GlareInhibition")),
            /*
             * 宽动态范围：选「宽动态」后才出现的 0-100 滑块。
             *
             * <p>这是数据面真正的强度旋钮，底层字段 {@code WideDynamicRange} 独立于
             * {@code Mode} —— 抓 RPC2 验证：把 Mode 改成 WideDynamic 时这个值不变，
             * 单独拖滑块才写它。早期只做了模式下拉、漏了这一项，
             * 表现就是「能选宽动态，但没法调强弱」。
             */
            numDep("wideDynamicRange", "宽动态范围", GROUP_BACKLIGHT, "VideoInBacklight",
                    List.of("WideDynamicRange"), 0, 100,
                    "backlightMode", List.of("WideDynamic")),

            /*
             * —— 白平衡（VideoInWhiteBalance）——
             * 手动模式才会出现红色增益 / 蓝色增益。
             */
            en("whiteBalance", "白平衡模式", GROUP_WHITE_BALANCE, "VideoInWhiteBalance",
                    List.of("Mode"), OPT_WHITE_BALANCE),
            numDep("gainRed", "红色增益", GROUP_WHITE_BALANCE, "VideoInWhiteBalance",
                    List.of("GainRed"), 0, 100, "whiteBalance", List.of("Manual")),
            numDep("gainBlue", "蓝色增益", GROUP_WHITE_BALANCE, "VideoInWhiteBalance",
                    List.of("GainBlue"), 0, 100, "whiteBalance", List.of("Manual")),

            // —— 日夜（VideoInDayNight）——
            en("dayNightType", "日夜类型", GROUP_DAY_NIGHT, "VideoInDayNight",
                    List.of("Type"), OPT_DAY_NIGHT_TYPE),
            en("dayNightMode", "日夜模式", GROUP_DAY_NIGHT, "VideoInDayNight",
                    List.of("Mode"), OPT_DAY_NIGHT_MODE),
            // 只有「自动」模式才出现灵敏度与延迟；彩色/黑白是固定模式
            enDep("dayNightSensitivity", "灵敏度", GROUP_DAY_NIGHT, "VideoInDayNight",
                    List.of("Sensitivity"), OPT_SENSITIVITY, "dayNightMode", List.of("Brightness")),
            enDep("dayNightDelay", "延迟", GROUP_DAY_NIGHT, "VideoInDayNight",
                    List.of("Delay"), OPT_DELAY, "dayNightMode", List.of("Brightness")),

            // —— 聚焦与缩放（VideoInFocus + VideoInZoom）——
            bool("digitalZoom", "数字缩放", GROUP_FOCUS, "VideoInZoom",
                    List.of("DigitalZoom")),
            num("zoomSpeed", "缩放速度", GROUP_FOCUS, "VideoInZoom",
                    List.of("Speed"), 0, 100),
            en("focus", "聚焦模式", GROUP_FOCUS, "VideoInFocus",
                    List.of("Mode"), OPT_FOCUS_MODE),
            en("focusLimit", "接近焦距极限", GROUP_FOCUS, "VideoInFocus",
                    List.of("FocusLimit"), OPT_FOCUS_LIMIT),
            // 只写不显示：由 focusLimit 连带写入，见 DahuaCameraConfigService#focusLimitSideEffects
            new Param(KEY_FOCUS_LIMIT_MODE, "焦距极限模式", GROUP_FOCUS, "VideoInFocus",
                    List.of("FocusLimitSelectMode"), Type.ENUM,
                    List.of(new Option("Auto", "自动"), new Option("Manual", "手动")),
                    null, null, null, false, null, List.of()),
            bool("autoFocusTrace", "PFA", GROUP_FOCUS, "VideoInFocus",
                    List.of("AutoFocusTrace")),
            en("focusSensitivity", "灵敏度", GROUP_FOCUS, "VideoInFocus",
                    List.of("Sensitivity"), OPT_FOCUS_SENSITIVITY),

            // —— 照明器（Lighting，独立配置项）——
            /*
             * 不同模式带出不同下级：
             *   自动 / 关闭        → 无
             *   手动              → 近光灯 / 远光灯
             *   缩放优先          → 补偿
             */
            en("lightingMode", "模式", GROUP_LIGHT, "Lighting",
                    List.of("Mode"), OPT_LIGHTING_MODE),
            numDep("nearLight", "近光灯", GROUP_LIGHT, "Lighting",
                    List.of("NearLight[0].Light"), 0, 100, "lightingMode", List.of("Manual")),
            numDep("farLight", "远光灯", GROUP_LIGHT, "Lighting",
                    List.of("FarLight[0].Light"), 0, 100, "lightingMode", List.of("Manual")),
            numDep("lightingCorrection", "补偿", GROUP_LIGHT, "Lighting",
                    List.of("Correction"), 0, 100, "lightingMode", List.of("ZoomPrio")),

            /*
             * —— 透雾（VideoInDefog）——
             * 只有「手动」才出现强度（低/中/高）。
             */
            en("defog", "透雾模式", GROUP_DEFOG, "VideoInDefog",
                    List.of("Mode"), OPT_DEFOG),
            enDep("defogIntensity", "强度", GROUP_DEFOG, "VideoInDefog",
                    List.of("Intensity"), OPT_INTENSITY_LEVEL, "defog", List.of("Manul")));

    private static Param num(String key, String label, String group, String cmd,
                             List<String> fields, int min, int max) {
        return new Param(key, label, group, cmd, fields, Type.NUMBER, List.of(),
                min, max, min + "~" + max, false, null, List.of());
    }

    /** 带联动的数值项：上级参数满足条件时才显示。 */
    private static Param numDep(String key, String label, String group, String cmd,
                                List<String> fields, int min, int max,
                                String dependsOn, List<String> dependsValues) {
        return new Param(key, label, group, cmd, fields, Type.NUMBER, List.of(),
                min, max, min + "~" + max, false, dependsOn, dependsValues);
    }

    /**
     * 范围数值项：<b>两个字段</b>组成一个「最小 - 最大」控件。
     *
     * <p>设备的增益/光圈就是这种形态（增益 0-100、光圈 0-100），
     * 拆成两个独立滑块既不好用也不符合设备。
     *
     * <p>{@code fields} 只放第一个（下限）字段；上限字段用带 {@code #max} 后缀的
     * 特殊 key 表达，由服务层识别并分别写入。这里用 {@link #rangeMaxField} 取。
     *
     * <p>{@code min}/{@code max} 是<b>两个框共用的取值范围</b>，
     * 设备界面就靠它限制输入（角标也写着 (0-100)）。
     * 不下发的话前端只能给 0-999999，用户能填出设备根本收不了的值。
     */
    private static Param numRangeDep(String key, String label, String group, String cmd,
                                     List<String> minFields, List<String> maxFields,
                                     int min, int max,
                                     String dependsOn, List<String> dependsValues) {
        List<String> fields = new ArrayList<>(minFields);
        for (String f : maxFields) {
            fields.add("#" + f);
        }
        return new Param(key, label, group, cmd, fields, Type.RANGE, List.of(),
                min, max, min + " ~ " + max, false, dependsOn, dependsValues);
    }

    /**
     * 从 RANGE 参数里取「上限」字段名（去 {@code #} 前缀）。
     *
     * @return 上限字段；不是范围参数时返回 {@code null}
     */
    public static String rangeMaxField(Param p) {
        if (p == null) {
            return null;
        }
        for (String f : p.fields()) {
            if (f.startsWith("#")) {
                return f.substring(1);
            }
        }
        return null;
    }

    /** 从 RANGE 参数里取「下限」字段名（第一个不带 {@code #} 的）。 */
    public static String rangeMinField(Param p) {
        if (p == null) {
            return null;
        }
        for (String f : p.fields()) {
            if (!f.startsWith("#")) {
                return f;
            }
        }
        return null;
    }

    private static Param bool(String key, String label, String group, String cmd,
                              List<String> fields) {
        return new Param(key, label, group, cmd, fields, Type.BOOLEAN, List.of(),
                null, null, "开 / 关", false, null, List.of());
    }

    /**
     * 「开关 ↔ 字符串」布尔项。
     *
     * <h3>为什么需要它</h3>
     * 设备界面上有些控件是<b>开关</b>，但底层存的是<b>字符串</b>：
     * <pre>
     *   3D 降噪    界面是开关  存 Auto / Off
     *   背光-自定义 界面是开关  存 Enable / Disable
     * </pre>
     * 定义成 {@code ENUM} 会渲染成下拉框，与设备的操作习惯不符；
     * 定义成普通 {@code BOOLEAN} 又会把 {@code "Auto"} 当成 false
     * （{@code asBool} 只认 1/true/on），开关永远是关的。
     *
     * <p>所以这里把「开 / 关」两个字符串放进 {@code options}：
     * {@code options.get(0)} 是「开」的值，{@code options.get(1)} 是「关」的值。
     * 服务层按它做双向映射，前端只看到 {@code BOOLEAN}，自然渲染成开关。
     *
     * @param onValue  开关打开时写入的字符串
     * @param offValue 开关关闭时写入的字符串
     */
    private static Param boolOf(String key, String label, String group, String cmd,
                                List<String> fields, String onValue, String offValue) {
        return new Param(key, label, group, cmd, fields, Type.BOOLEAN,
                List.of(new Option(onValue, "开"), new Option(offValue, "关")),
                null, null, "开 / 关", false, null, List.of());
    }

    /**
     * 该布尔参数是否是「开关 ↔ 字符串」型（有自己的开/关字串）。
     *
     * <p>普通的布尔字段（{@code Flip}、{@code Freeze}…）没有 options，
     * 按 JSON 的 true/false 直接读写即可。
     */
    public static boolean hasBoolMapping(Param p) {
        return p != null
                && p.type() == Type.BOOLEAN
                && p.options() != null
                && p.options().size() >= 2;
    }

    /** 「开」对应的字符串值；不是映射型时返回 {@code null}。 */
    public static String boolOnValue(Param p) {
        return hasBoolMapping(p) ? p.options().get(0).value() : null;
    }

    /** 「关」对应的字符串值；不是映射型时返回 {@code null}。 */
    public static String boolOffValue(Param p) {
        return hasBoolMapping(p) ? p.options().get(1).value() : null;
    }

    private static Param en(String key, String label, String group, String cmd,
                            List<String> fields, List<Option> options) {
        return new Param(key, label, group, cmd, fields, Type.ENUM, options,
                null, null, null, false, null, List.of());
    }

    /** 带联动的枚举项。 */
    private static Param enDep(String key, String label, String group, String cmd,
                               List<String> fields, List<Option> options,
                               String dependsOn, List<String> dependsValues) {
        return new Param(key, label, group, cmd, fields, Type.ENUM, options,
                null, null, null, false, dependsOn, dependsValues);
    }

    /** 全部参数定义（前端可据此动态生成表单）。 */
    public static List<Param> all() {
        return ALL;
    }

    /** 按 key 查定义。 */
    public static Param byKey(String key) {
        if (key == null) {
            return null;
        }
        for (Param p : ALL) {
            if (p.key().equalsIgnoreCase(key.trim())) {
                return p;
            }
        }
        return null;
    }

    /** 非组合参数（真正对应 JSON 字段的那些）。 */
    public static List<Param> realParams() {
        List<Param> out = new ArrayList<>();
        for (Param p : ALL) {
            if (!p.virtual()) {
                out.add(p);
            }
        }
        return out;
    }

    /** 该 key 集合涉及的配置项（去重，保持顺序）。组合参数已展开。 */
    public static List<String> commandsOf(List<String> keys) {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (String k : keys) {
            Param p = byKey(k);
            if (p != null && !p.virtual()) {
                seen.putIfAbsent(p.configCommand(), Boolean.TRUE);
                continue;
            }
            for (Param member : membersOf(k)) {
                seen.putIfAbsent(member.configCommand(), Boolean.TRUE);
            }
        }
        return List.copyOf(seen.keySet());
    }

    /**
     * 组合参数展开成的成员参数。
     *
     * <p>只有 {@code viewMode} 一个组合参数，展开成
     * {@code flip} / {@code mirror} / {@code rotate90}。
     */
    public static List<Param> membersOf(String key) {
        List<Param> out = new ArrayList<>();
        if (key == null || !"viewmode".equals(key.trim().toLowerCase())) {
            return out;
        }
        for (String k : VIEW_MODE_MEMBERS) {
            Param p = byKey(k);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * 该参数是否只是某个组合参数的<b>成员字段</b>。
     *
     * <p>成员字段不该在界面上单独出现：用户要表达的是「视角 = 倒影」，
     * 而不是「Flip 开、Mirror 关、Rotate90 关」三个开关 ——
     * 后者既重复又容易配出自相矛盾的状态。
     *
     * <p>但它仍必须留在参数表里：读写组合参数时要靠它拿到真实字段名。
     */
    public static boolean isVirtualMember(String key) {
        if (key == null) {
            return false;
        }
        for (String k : VIEW_MODE_MEMBERS) {
            if (k.equalsIgnoreCase(key.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从设备返回的配置 JSON 里判断每项是否存在（= 能力位）。
     *
     * <p>判据是「JSON 树里出现过该字段的任一候选路径」。这是<b>笨但可靠</b>的办法：
     * 大华没有统一的「能力位查询」JSON，而配置项本身就带着该型号支持的全部字段。
     *
     * <p>组合参数 {@code viewMode} 的能力位是「三个成员字段都在」——
     * 少一个就没法表达完整组合，硬给选项只会写坏设备。
     *
     * @param jsonByCommand 配置项名 → 完整响应包或业务层文本
     * @return key → 是否可用
     */
    public static Map<String, Boolean> readCapabilities(Map<String, String> jsonByCommand) {
        Map<String, Boolean> caps = new LinkedHashMap<>();
        for (Param p : ALL) {
            if (p.virtual()) {
                continue;
            }
            caps.put(p.key(), fieldPresent(jsonByCommand, p));
        }
        boolean viewMode = Boolean.TRUE.equals(caps.get("flip"))
                && Boolean.TRUE.equals(caps.get("mirror"))
                && Boolean.TRUE.equals(caps.get("rotate90"));
        caps.put("viewMode", viewMode);
        return caps;
    }

    /**
     * 该参数的任一候选字段路径是否出现在配置 JSON 里。
     *
     * <p>用树搜索而不是字符串包含：后者会把<b>值</b>恰好等于字段名的情况误判成
     * 「有该字段」，也会在字段名是另一字段名前缀时误判。
     *
     * <p>点号路径（{@code 3DAutoType.AutoLevel}）先按路径精确找，
     * 找不到再退回按末段字段名搜索 —— 场景数组的层级可能与预期不同。
     */
    public static boolean fieldPresent(Map<String, String> jsonByCommand, Param p) {
        for (String field : p.fields()) {
            // RANGE 参数的上限字段带 # 前缀，那是标记不是真实字段名
            String f = field.startsWith("#") ? field.substring(1) : field;
            if (DahuaJson.hasField(jsonByCommand, p.configCommand(), f)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 由 {@code flip}/{@code mirror}/{@code rotate90} 的当前值算出视角模式。
     *
     * <p>取值只有正常(0)与倒影(1)，与设备 Web 界面的两项下拉一致。
     * {@code Mirror} 为真时仍然按「正常」呈现 ——
     * 界面上没有镜像入口，若按倒影呈现会误报，按正常呈现至少不会误导用户以为开了倒影。
     * （镜像本身仍可通过接口直接写字段设置，只是不再作为预设选项暴露。）
     */
    public static int viewModeOf(boolean flip, boolean mirror, boolean rotate90) {
        return flip ? VIEW_FLIP : VIEW_NORMAL;
    }

    /**
     * 把视角模式展开成三个布尔字段的目标值。
     *
     * <p>只有正常/倒影两档，所以展开出来永远是
     * {@code [flip, false, false]} —— 另外两个字段一律清零，
     * 避免上一次遗留的镜像/走廊状态让「正常」看起来没生效。
     *
     * @return 长度 3 的数组：{@code [flip, mirror, rotate90]}
     */
    public static boolean[] expandViewMode(int mode) {
        return mode == VIEW_FLIP
                ? new boolean[]{true, false, false}
                : new boolean[]{false, false, false};
    }
}
