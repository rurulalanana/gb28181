package io.github.rurulalanana.dahua.ext;

import java.util.Map;
import java.util.Set;

/**
 * 大华报警事件码 → 中文类型名。
 *
 * <h3>⚠️ 这张表曾经整张都是错的</h3>
 * 早期是凭「网上常见的 0x21xx 对照」硬编码的。把它拿去
 * {@code javap -constants com.netsdk.lib.NetSDKLib} 反查后发现<b>几乎全对不上</b>：
 * <pre>
 *   我们写的        事件码     SDK 真实常量                    真实含义
 *   视频丢失        0x2101    NET_ALARM_ALARM_EX             外部报警输入
 *   视频遮挡        0x2102    NET_MOTION_ALARM_EX            动态检测
 *   动态检测        0x2103    NET_VIDEOLOST_ALARM_EX         视频丢失
 *   视频恢复        0x2104    NET_SHELTER_ALARM_EX           视频遮挡
 *   视频信号恢复    0x2107    NET_DISKERROR_ALARM_EX         硬盘错误
 *   区域入侵        0x211F    NET_ALARM_3GFLOW_EXCEED        3G 流量超限
 *   人脸检测        0x2123    NET_PTZ_LOCATION_EX            云台位置上报
 *   图像翻转        0x300C    NET_START_LISTEN_FINISH_EVENT  订阅完成通知
 * </pre>
 * 后果：{@code 0x2123}（云台位置，<b>云台一动就报一次</b>）被当成「人脸检测」，
 * 单台球机两天攒了 3665 条假「人脸检测」；{@code 0x300C}（订阅完成）被当成
 * 「图像翻转」。整张报警列表因此毫无参考价值。
 *
 * <p><b>现在每个名字都从 {@code NetSDKLib} 常量反查得出</b>（注释里标了常量名）。
 * 新增/修改前先用 {@code javap -constants} 复核，<b>不要凭网上的 0x21xx 对照表</b> ——
 * 那套码是大华老式 DVR 的，与当前 SDK 的 {@code NET_ALARM_*} 定义不一致。
 *
 * <h3>只收「报警」，不收状态通知</h3>
 * {@code CLIENT_StartListenEx} 的回调不区分「报警」和「状态回调」：云台位置、
 * 订阅完成之类的通知从同一个口进来。用 {@link #isAlarm(int)} 过滤掉。
 */
public final class DahuaAlarmCodes {

    private DahuaAlarmCodes() {
    }

    /**
     * 事件码 → [中文名, 紧迫度(1~3，<b>3 最紧急</b>)]。
     *
     * <p><b>方向与国标相反</b>：大华 3 = 紧急、1 = 提示；
     * 国标 {@code AlarmPriority} 1 = 一级最紧急。落库前由调用方换算。
     */
    private static final Map<Integer, String[]> MAPPING = Map.ofEntries(
            // —— 视频类（旧式 _EX 组 0x21xx，与 0x32xx 新式码同义）——
            Map.entry(0x2101, new String[]{"外部报警输入", "2"}),        // NET_ALARM_ALARM_EX
            Map.entry(0x2102, new String[]{"动态检测", "1"}),            // NET_MOTION_ALARM_EX
            Map.entry(0x2103, new String[]{"视频丢失", "3"}),            // NET_VIDEOLOST_ALARM_EX
            Map.entry(0x2104, new String[]{"视频遮挡", "2"}),            // NET_SHELTER_ALARM_EX
            Map.entry(0x2175, new String[]{"外部报警输入", "2"}),        // NET_ALARM_ALARM_EX2
            Map.entry(0x323E, new String[]{"视频遮挡", "2"}),            // NET_ALARM_VIDEOBLIND
            Map.entry(0x3242, new String[]{"视频丢失", "3"}),            // NET_ALARM_VIDEO_LOSS
            // —— 设备/存储类 ——
            Map.entry(0x2106, new String[]{"硬盘满", "3"}),              // NET_DISKFULL_ALARM_EX
            Map.entry(0x2107, new String[]{"硬盘错误", "3"}),            // NET_DISKERROR_ALARM_EX
            Map.entry(0x3167, new String[]{"存储不存在", "3"}),          // NET_ALARM_STORAGE_NOT_EXIST
            Map.entry(0x3170, new String[]{"IP 地址冲突", "3"}),         // NET_ALARM_IP_CONFLICT
            Map.entry(0x3172, new String[]{"电源故障", "3"}),            // NET_ALARM_POWERFAULT
            Map.entry(0x2134, new String[]{"电池电量低", "2"}),          // NET_ALARM_BATTERYLOWPOWER
            Map.entry(0x2135, new String[]{"温度异常", "2"}),            // NET_ALARM_TEMPERATURE
            Map.entry(0x2162, new String[]{"风扇转速异常", "2"}),        // NET_ALARM_FAN_SPEED
            Map.entry(0x211F, new String[]{"3G 流量超限", "2"}),         // NET_ALARM_3GFLOW_EXCEED
            Map.entry(0x2183, new String[]{"无硬盘", "3"}),              // SDK_ALARM_NO_DISK
            Map.entry(0x3194, new String[]{"登录失败", "2"}),            // NET_ALARM_LOGIN_FAILIUR
            Map.entry(0x31B3, new String[]{"新文件产生", "1"}),          // NET_ALARM_NEW_FILE
            Map.entry(0x2178, new String[]{"音频异常", "2"}),            // NET_ALARM_AUDIO_ANOMALY
            // —— 智能分析类（NET_EVENT_* 组 0x218x）——
            Map.entry(0x2188, new String[]{"越界侦测", "3"}),            // NET_EVENT_CROSSLINE_DETECTION
            Map.entry(0x2189, new String[]{"区域入侵", "3"}),            // NET_EVENT_CROSSREGION_DETECTION
            Map.entry(0x218A, new String[]{"物品遗留", "2"}),            // NET_EVENT_LEFT_DETECTION
            Map.entry(0x218B, new String[]{"人脸检测", "2"}),            // NET_EVENT_FACE_DETECTION
            Map.entry(0x218C, new String[]{"IPC 报警", "2"}),            // NET_ALARM_IPC
            Map.entry(0x218D, new String[]{"物品拿取", "2"}),            // NET_EVENT_TAKENAWAYDETECTION
            Map.entry(0x218E, new String[]{"视频异常", "2"}),            // NET_EVENT_VIDEOABNORMALDETECTION
            Map.entry(0x218F, new String[]{"动态检测", "1"}),            // NET_EVENT_MOTIONDETECT
            Map.entry(0x2193, new String[]{"移动侦测", "1"}),            // NET_ALARM_MOVEDETECTION
            Map.entry(0x2194, new String[]{"徘徊侦测", "2"}));           // NET_ALARM_WANDERDETECTION

    /**
     * <b>不是报警</b>的状态/通知事件 —— 从同一个回调进来，但绝不能落库。
     *
     * <p>实测踩过的坑：
     * <ul>
     *   <li>{@code 0x2123 NET_PTZ_LOCATION_EX}：<b>云台每动一下报一次</b>。
     *       被当成「人脸检测」后，一次云台测试就刷出上千条假报警；</li>
     *   <li>{@code 0x300C NET_START_LISTEN_FINISH_EVENT}：<b>订阅完成通知</b>，
     *       每次 {@code CLIENT_StartListenEx} 都来一条，与报警无关；</li>
     *   <li>{@code 0x3169}：<b>SDK 3.061 未定义</b>（整套 Java 源码 + 5 份官方 PDF 都搜不到），
     *       只能靠实测定性，见下。</li>
     * </ul>
     *
     * <h4>{@code 0x3169} 的定性依据</h4>
     * 它是本表里唯一一个<b>官方没给名字</b>的事件码，所以每条证据都记在这里，
     * 将来 SDK 补上定义时可以对照复核：
     * <ol>
     *   <li><b>载荷时间 = 订阅那一刻</b>。实测接收于 {@code 22:44:31 +08:00}（UTC 14:44:31），
     *       而结构体里 {@code NET_TIME} 是 {@code 2026-11-04 14:44:26}(UTC)，相差 5 秒。
     *       对照 {@code 0x2183}（无硬盘）：它载荷里是 {@code 17:07:49}，比接收时间早一个多小时
     *       —— 那是设备在<b>回放历史状态</b>，而 {@code 0x3169} 报的是<b>当下</b>；</li>
     *   <li><b>只在订阅时出现</b>。平台连续运行 2 小时以上，期间反复操作云台、读配置、
     *       都没有再触发；登录设备 Web 后台也不会触发（等 12 秒无事件）；
     *       只有重启平台 / 重新订阅才会来一条；</li>
     *   <li>结构体 100 字节，首字段 {@code dwSize=100} 正好等于实际长度（说明布局没读错）：
     *       {@code dwSize, nAction=0, nSequence=3, NET_TIME=now, 尾部 64 字节 = "lte0"}。</li>
     * </ol>
     * 结论：它是<b>连接/订阅侧的回执</b>，与 {@code 0x300C} 同类，不是设备状态变化。
     * 落库只会让报警列表永远多一条无法解释的记录。
     *
     * <p>⚠️ 这里<b>没有</b>给它编一个中文名。事件码语义必须来自厂商定义，
     * 网上流传的 0x31xx 对照表正是上一版映射表全错的根源（见类注释）。
     */
    private static final Set<Integer> NOT_ALARM = Set.of(
            0x2123,   // NET_PTZ_LOCATION_EX            云台位置上报
            0x300C,   // NET_START_LISTEN_FINISH_EVENT  订阅完成通知
            0x3169    // SDK 未定义，实测为订阅侧回执（依据见上）
    );

    /** 中文类型名；认不出的码返回 {@code 事件 0xXXXX}（原样透出，不丢）。 */
    public static String name(int command) {
        String[] hit = MAPPING.get(command);
        return hit != null ? hit[0] : String.format("事件 0x%04X", command);
    }

    /**
     * 大华口径的紧迫度 1~3（<b>3 最紧急</b>）。认不出的码按 1（提示）。
     *
     * <p><b>方向与国标 {@code AlarmPriority} 相反</b>，落库前要换算。
     */
    public static int urgency(int command) {
        String[] hit = MAPPING.get(command);
        return hit != null ? Integer.parseInt(hit[1]) : 1;
    }

    /** 是否是可识别的事件（用于统计「有多少未知事件」）。 */
    public static boolean known(int command) {
        return MAPPING.containsKey(command);
    }

    /**
     * 是不是一条<b>真正的报警</b>（而不是云台位置/订阅完成这类状态回调）。
     *
     * <p>{@code false} 时调用方必须直接丢掉、不落库 ——
     * 否则报警列表会被噪声淹没，用户看到的就是「设备在乱报警」。
     */
    public static boolean isAlarm(int command) {
        return !NOT_ALARM.contains(command);
    }

    /**
     * 该事件码的结构体首 4 字节是否就是 <b>{@code dwState}</b>
     * （0 = 报警结束，非 0 = 报警触发）。
     *
     * <p><b>为什么需要这个判断</b>：{@code CLIENT_StartListenEx} 订阅成功的瞬间，
     * 设备会把<b>当前全部报警状态整份推回来</b>。实测一台工作正常的球机在订阅后
     * 立刻上送 6 条 {@code _EX} 事件，首字段<b>全是 0</b>，语义是「这些报警都已结束」
     * —— 也就是设备在说「我一切正常」。早期不判这个 0，平台把这份体检报告
     * 存成了 6 条新报警，用户看到的就是「一重启就报警：视频丢失 + 遮挡 + 硬盘错误」。
     *
     * <p>只有大华老式 {@code *_ALARM_EX} 家族是这个布局；新式 {@code NET_ALARM_*}
     * （{@code 0x3xxx} 段）各有自己的结构体，首字段不一定是状态，<b>不能套用</b>。
     */
    public static boolean hasState(int command) {
        return STATE_FIRST.contains(command);
    }

    /** 首字段为 {@code dwState} 的老式事件码。 */
    private static final Set<Integer> STATE_FIRST = Set.of(
            0x2101,   // NET_ALARM_ALARM_EX
            0x2102,   // NET_MOTION_ALARM_EX
            0x2103,   // NET_VIDEOLOST_ALARM_EX
            0x2104,   // NET_SHELTER_ALARM_EX
            0x2106,   // NET_DISKFULL_ALARM_EX
            0x2107,   // NET_DISKERROR_ALARM_EX
            0x211F,   // NET_ALARM_3GFLOW_EXCEED
            0x2175    // NET_ALARM_ALARM_EX2
    );
}
