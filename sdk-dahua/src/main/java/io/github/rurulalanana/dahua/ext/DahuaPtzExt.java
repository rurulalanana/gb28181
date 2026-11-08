package io.github.rurulalanana.dahua.ext;

import com.netsdk.lib.NetSDKLib;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 大华云台的<b>长尾功能</b>：巡航组、巡迹、线扫、限位、速度、开机/空闲动作、定时任务、重启。
 *
 * <h3>为什么把这些从 {@link DahuaExtAccess} 拆出来</h3>
 * {@code DahuaExtAccess} 已经 700 多行，再塞十来个云台方法会淹没「抓图/配置/报警」
 * 这些完全不同的能力。云台这一族又有一个共同点：<b>全部落在同一个 SDK 入口
 * {@code CLIENT_DHPTZControlEx2}，差异只在命令码与三个参数的含义上</b>。
 * 放一起才能把「哪个参数是组号、哪个是预置位号」讲清楚。
 *
 * <h3>参数含义速查（全部来自大华 {@code NET_PTZ_ControlType} 注释）</h3>
 * <pre>
 *   巡航组：p1 = 巡航线号(1~64)，p2 = 预置位号
 *   巡迹　：p1 = 模式号(1~8)
 *   线扫　：p1 = 线扫组号
 *   限位　：无参数，把当前位置记为左/右边界
 *   速度　：直接走方向指令的 speed 参数（1~8），不是独立命令
 * </pre>
 *
 * <h3>关于「云台速度配置」</h3>
 * 大华没有「设置云台速度」这种持久化配置 —— 速度是<b>每条方向指令自带</b>的参数。
 * 所以 {@link #direction} 的 {@code speed} 就是速度配置本身，
 * {@code 1~8} 对应最慢到最快，{@code 0} 等价于「不动」（会被抬到 1）。
 *
 * <h3>关于「开机动作 / 空闲动作 / 定时任务」</h3>
 * 这三项<b>不是云台指令</b>，而是设备的 JSON 配置项：
 * <pre>
 *   开机动作  PtzPowerOn
 *   空闲动作  PtzIdle
 *   定时任务  PtzTimingTask
 * </pre>
 * 走 {@link DahuaExtAccess#readConfig} / {@link DahuaExtAccess#writeConfig}，
 * 字段结构随机型差异极大，因此这里只提供「读全量 → 改字段 → 写回」的通用入口，
 * 不做字段映射（硬编码字段名在多数型号上会写坏配置）。
 */
public final class DahuaPtzExt {

    private static final Logger log = LoggerFactory.getLogger(DahuaPtzExt.class);

    private DahuaPtzExt() {
    }

    /** 云台速度上限：大华是 1~8（不是国标的 0~255）。 */
    public static final int SPEED_MAX = 8;

    /**
     * 把国标 0~255 的速度折算成大华 1~8。
     *
     * <p>0 也抬到 1：大华的速度 0 表示「不动」，而调用方传 0 的意图通常是「最慢」。
     */
    public static int toSdkSpeed(int speed) {
        int s = Math.max(0, Math.min(255, speed));
        return Math.max(1, Math.min(SPEED_MAX, (int) Math.ceil(s * SPEED_MAX / 255.0)));
    }

    // ==================== 方向 / 变倍（速度在这里）====================

    /**
     * 方向或变倍控制。
     *
     * @param command 指令码（{@code NET_PTZ_UP_CONTROL} / {@code NET_EXTPTZ_LEFTTOP} 等）
     * @param speed   大华速度 1~8
     * @param stop    {@code true} 表示停止该方向
     */
    public static boolean direction(DahuaSdk.Session session, int channel, int command,
                                    int speed, boolean stop) {
        return control(session, channel, command,
                0, Math.max(1, Math.min(SPEED_MAX, speed)), 0, stop, "云台方向");
    }

    // ==================== 巡航组（最多 64 条）====================

    /**
     * 把某个预置位加入巡航线。
     *
     * @param route  巡航线号 1~64
     * @param preset 预置位号
     */
    public static boolean cruiseAddPoint(DahuaSdk.Session session, int channel,
                                         int route, int preset) {
        return control(session, channel, NetSDKLib.NET_EXTPTZ_ControlType.NET_EXTPTZ_ADDTOLOOP,
                route, preset, 0, false, "加入巡航点");
    }

    /** 从巡航线移除一个预置位。 */
    public static boolean cruiseRemovePoint(DahuaSdk.Session session, int channel,
                                            int route, int preset) {
        return control(session, channel, NetSDKLib.NET_EXTPTZ_ControlType.NET_EXTPTZ_DELFROMLOOP,
                route, preset, 0, false, "删除巡航点");
    }

    /**
     * 启动 / 停止整条巡航线。
     *
     * @param start {@code true} 开始巡航，{@code false} 停止
     */
    public static boolean cruiseRun(DahuaSdk.Session session, int channel,
                                    int route, boolean start) {
        /*
         * 走<b>专用接口</b> CLIENT_PTZStartTour / CLIENT_PTZStopTour，不用通用云台命令。
         *
         * <h3>为什么不能用 NET_PTZ_POINT_LOOP_CONTROL（踩过两次）</h3>
         * 那个命令码在 SDK 里确实叫「点间巡航」，注释也很像那么回事，
         * 但在这台设备（IPC 10001 / fw 3.201.0000007.1.R）上：
         * <pre>
         *   CLIENT_DHPTZControlEx2(..., POINT_LOOP_CONTROL=13, route, 0,0, start, null)
         *       → 返回 true（不报错）
         *       → 但设备 PtzMovement 始终是 None/Unknown，云台一动不动
         *   CLIENT_DHPTZControlEx2(..., CLOSELOOP=38, route, ...) 当停止用
         *       → 也只清线路，不停运行中的动作
         * </pre>
         * 更糟的是它<b>静默失败</b>：接口回 true，平台于是提示「已开始巡航」，
         * 用户看到的是「点了没反应」。
         *
         * <h3>为什么 nIndex 用 route - 1</h3>
         * 设备侧巡航线号是 <b>0-based</b>：设备自己的 {@code PtzMovement.Index}
         * 在跑第 1 条线时报 {@code 0}（见 {@code DahuaPtzConfigService#parseRunning}），
         * 而界面/接口对外一律用 1-based 的 {@code route}。
         * 这两个口径必须在<b>唯一一个地方</b>转换，否则「界面上第 2 条、
         * 设备上跑第 3 条」这种错位会非常难查。
         */
        NetSDKLib sdk = requireSdk();
        /*
         * nIndex 用 <b>route 原值（1-based）</b>。
         *
         * <h3>踩过的坑：两个 Index 口径不一样</h3>
         * <pre>
         *   CLIENT_PTZStartTour.nIndex   → 1-based。传 0 报 0x80000015「返回数据错误」
         *   设备 PtzMovement.Index       → 0-based。跑第 1 条线时报 0
         * </pre>
         * 早先按「设备上报 0-based」推断接口也要 0-based，于是把 route-1 传进去，
         * 结果<b>启动一律失败</b>（0x80000015），而停止恰好又能成功
         * （停止不校验这个字段），更让人以为推断是对的。
         *
         * <p>实测证据（SN 10001 / fw 3.201.0000007.1.R）：
         * <pre>
         *   nIndex=0 → err=0x80000015，PtzMovement 仍是 None/Unknown
         *   nIndex=1 → 成功，PtzMovement = Tour/0/Manual（云台开始转）
         * </pre>
         * 所以这里的 route 原样传，<b>不要减一</b>。
         * 「设备上报值 = route - 1」这层换算只发生在读方向上
         * （见 {@code DahuaPtzConfigService#parseRunning} 与前端 {@code activeRun}）。
         */
        if (start) {
            /*
             * 结构体在 com.netsdk.lib.structure 包下，不是 NetSDKLib 的内部类。
             * 写错包名时 Maven 增量编译会把旧 class 当成「已是最新」直接放进 jar，
             * 本地 build 一声不响，直到线上运行才抛 Unresolved compilation problems。
             */
            com.netsdk.lib.structure.NET_IN_PTZ_START_TOUR in =
                    new com.netsdk.lib.structure.NET_IN_PTZ_START_TOUR();
            in.nChannel = channel;
            in.nIndex = route;
            in.write();
            com.netsdk.lib.structure.NET_OUT_PTZ_START_TOUR out =
                    new com.netsdk.lib.structure.NET_OUT_PTZ_START_TOUR();
            out.write();
            boolean ok = sdk.CLIENT_PTZStartTour(session.handle(),
                    in.getPointer(), out.getPointer(), 5_000);
            if (!ok) {
                throw new DahuaSdkException("开始巡航失败（第 " + route + " 条线）",
                        sdk.CLIENT_GetLastError(),
                        "确认该巡航组里已加入预置点，且没有别的动作正在运行");
            }
            log.info("开始巡航: route={} channel={}", route, channel);
            return true;
        }
        com.netsdk.lib.structure.NET_IN_PTZ_STOP_TOUR in =
                new com.netsdk.lib.structure.NET_IN_PTZ_STOP_TOUR();
        in.nChannel = channel;
        in.nIndex = route;
        in.write();
        com.netsdk.lib.structure.NET_OUT_PTZ_STOP_TOUR out =
                new com.netsdk.lib.structure.NET_OUT_PTZ_STOP_TOUR();
        out.write();
        boolean ok = sdk.CLIENT_PTZStopTour(session.handle(),
                in.getPointer(), out.getPointer(), 5_000);
        if (!ok) {
            throw new DahuaSdkException("停止巡航失败（第 " + route + " 条线）",
                    sdk.CLIENT_GetLastError(), "云台可能已不在巡航状态");
        }
        log.info("停止巡航: route={} channel={}", route, channel);
        return true;
    }

    // ==================== 巡迹（自动巡航路径录制）====================

    /**
     * 开始录制巡迹。
     *
     * @param mode 模式号 1~8
     */
    public static boolean patternStartRecord(DahuaSdk.Session session, int channel, int mode) {
        return control(session, channel, NetSDKLib.NET_EXTPTZ_ControlType.NET_EXTPTZ_SETMODESTART,
                mode, 0, 0, false, "开始录制巡迹");
    }

    /** 结束录制巡迹（此后可用 {@link #patternRun} 回放）。 */
    public static boolean patternStopRecord(DahuaSdk.Session session, int channel, int mode) {
        return control(session, channel, NetSDKLib.NET_EXTPTZ_ControlType.NET_EXTPTZ_SETMODESTOP,
                mode, 0, 0, false, "结束录制巡迹");
    }

    /** 运行 / 停止已录制的巡迹。 */
    public static boolean patternRun(DahuaSdk.Session session, int channel,
                                     int mode, boolean start) {
        return control(session, channel,
                start ? NetSDKLib.NET_EXTPTZ_ControlType.NET_EXTPTZ_RUNMODE
                        : NetSDKLib.NET_EXTPTZ_ControlType.NET_EXTPTZ_STOPMODE,
                mode, 0, 0, false, start ? "运行巡迹" : "停止巡迹");
    }

    /** 删除巡迹。 */
    public static boolean patternDelete(DahuaSdk.Session session, int channel, int mode) {
        return control(session, channel, NetSDKLib.NET_EXTPTZ_ControlType.NET_EXTPTZ_DELETEMODE,
                mode, 0, 0, false, "删除巡迹");
    }

    // ==================== 线扫与限位 ====================

    /**
     * 把当前位置设为线扫<b>左</b>边界（线扫用，不是「旋轉限制」的上下限位）。
     *
     * <p>设备「線性掃描」页的左右边界走 {@code AutoScan[ch][idx].LeftEnable +
     * LeftPosition[3]}；这里走 SDK 的 {@code SETLEFTBORDER}，
     * 由设备固件把当前坐标写进对应槽位，两者最终落到同一份配置。
     */
    public static boolean setLeftLimit(DahuaSdk.Session session, int channel, int route) {
        return control(session, channel, NetSDKLib.NET_EXTPTZ_ControlType.NET_EXTPTZ_SETLEFTBORDER,
                route, 0, 0, false, "设置线扫左边界");
    }

    /** 把当前位置设为线扫<b>右</b>边界。 */
    public static boolean setRightLimit(DahuaSdk.Session session, int channel, int route) {
        return control(session, channel, NetSDKLib.NET_EXTPTZ_ControlType.NET_EXTPTZ_SETRIGHTBORDER,
                route, 0, 0, false, "设置线扫右边界");
    }

    /*
     * 旋转限位（「PTZ旋轉限制」的上/下/左/右）<b>不在这个类里</b>。
     *
     * <p>SDK 确实有 {@code CLIENT_PTZSetPanGroupLimit} + {@code NET_EM_PAN_GROUP_LIMIT_MODE}
     * （左/右/上/下四个模式），一度以为就是它。但实测这台 IPC
     * （SN 10001 / fw 3.201.0000007.1.R）返回 {@code 0x8000004F 设备不支持该操作}。
     *
     * <p>真正可行的做法是「读 {@code NET_DEVSTATE_PTZ_LOCATION} 拿当前 tilt →
     * 写 {@code PtzLimit[ch].UpLimit.Boundary}」，
     * 见 {@code DahuaCapabilityService#setRotateLimitBoundary}（实测设置后云台真的被挡住）。
     * 所以这里不再保留那四个方法，避免被误当成可用入口。
     */

    /**
     * 开始 / 停止线扫。
     *
     * <p>必须<b>先设好左右边界</b>再启动，否则设备会在整个水平范围内乱扫
     * （部分型号直接拒绝）。边界就是上面两个方法。
     */
    public static boolean lineScan(DahuaSdk.Session session, int channel,
                                   int route, boolean start) {
        return control(session, channel,
                start ? NetSDKLib.NET_EXTPTZ_ControlType.NET_EXTPTZ_STARTLINESCAN
                        : NetSDKLib.NET_EXTPTZ_ControlType.NET_EXTPTZ_CLOSELINESCAN,
                route, 0, 0, false, start ? "开始线扫" : "停止线扫");
    }

    /**
     * 水平 360° 自动巡航（不分左右边界的连续旋转）。
     *
     * <p>设备「水準旋轉」页只有一个「旋轉速度」滑块 + 开始按钮：
     * 速度取的是 {@code AutoPan[ch].PanSpeed}（界面 1~8），不是这里传的。
     * 所以本方法没有 speed 参数 —— 想调快慢请先改「水準旋轉」页的速度。
     *
     * <h3>停止为什么要试多个组合（实测记录）</h3>
     * 这台 IPC（fw 3.201.0000007.1.R）上，停止<b>不是</b>「一个码搞定」：
     * <ul>
     *   <li>{@code STOPPANCRUISE} + {@code dwStop=1} → <b>0x80000007</b>（参数不合法）</li>
     *   <li>{@code STOPPANCRUISE} + {@code dwStop=0} → 返回 true，但云台<b>继续转</b></li>
     *   <li>{@code RIGHT_CONTROL} + {@code dwStop=1} → <b>0x80000007</b></li>
     *   <li>{@code RIGHT_CONTROL} + {@code dwStop=0} + speed → 正常（这是方向运动）</li>
     * </ul>
     * 而 {@code PtzMovement} 始终报 {@code Function=None}，不把水平旋转算作运行中的任务，
     * 所以也不能靠它判断停没停。
     *
     * <p>因此这里按「最可能有效 → 兜底」的顺序逐个尝试，<b>只要有一条成功就继续试完</b>
     * （停止是安全动作，宁可多下发几条也不能停不住），全部失败才报错。
     * 真正的刹车是「同方向 + {@code dwStop=1} + 该方向的 speed」，不是 speed=0。
     *
     * @param start {@code true} 开始，{@code false} 停止
     */
    public static boolean panCruise(DahuaSdk.Session session, int channel, boolean start) {
        if (start) {
            return control(session, channel,
                    NetSDKLib.NET_EXTPTZ_ControlType.NET_EXTPTZ_STARTPANCRUISE,
                    0, 0, 0, false, "开始水平旋转");
        }
        boolean stopped = false;
        RuntimeException first = null;
        /*
         * 停止尝试序列。每条都是 (命令码, p1, p2, p3, dwStop)：
         *   ① STOPPANCRUISE + dwStop=0 —— 官方语义「停止水平旋转」的直译
         *   ② RIGHT_CONTROL + dwStop=1 + speed=1 —— 方向刹车（部分固件只认这条）
         *   ③ LEFT_CONTROL  + dwStop=1 + speed=1 —— 反向也试（转向不定时）
         */
        int[][] attempts = {
                {NetSDKLib.NET_EXTPTZ_ControlType.NET_EXTPTZ_STOPPANCRUISE, 0, 0, 0, 0},
                {NetSDKLib.NET_PTZ_ControlType.NET_PTZ_RIGHT_CONTROL, 0, 1, 0, 1},
                {NetSDKLib.NET_PTZ_ControlType.NET_PTZ_LEFT_CONTROL, 0, 1, 0, 1},
        };
        for (int[] a : attempts) {
            try {
                control(session, channel, a[0], a[1], a[2], a[3], a[4] != 0, "停止水平旋转");
                stopped = true;
            } catch (RuntimeException e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        if (!stopped && first != null) {
            throw first;
        }
        return true;
    }

    // ==================== 云台重启 ====================

    /**
     * 重启云台（不是重启整机）。
     *
     * <p>走 {@code CLIENT_ControlDevice(CTRLTYPE_CTRL_REBOOT)}。部分型号会对
     * 云台断电复位（镜头回中、短暂离线），属于正常现象。
     */
    public static boolean reboot(DahuaSdk.Session session) {
        NetSDKLib sdk = requireSdk();
        boolean ok = sdk.CLIENT_ControlDevice(session.handle(),
                NetSDKLib.CtrlType.CTRLTYPE_CTRL_REBOOT, null, 10_000);
        if (!ok) {
            throw new DahuaSdkException("云台重启失败",
                    sdk.CLIENT_GetLastError(), "该型号可能不支持单独重启云台");
        }
        return true;
    }

    // ==================== 内部 ====================

    private static boolean control(DahuaSdk.Session session, int channel, int command,
                                   int p1, int p2, int p3, boolean stop, String action) {
        NetSDKLib sdk = requireSdk();
        boolean ok = sdk.CLIENT_DHPTZControlEx2(session.handle(), channel, command,
                p1, p2, p3, stop ? 1 : 0, null);
        if (!ok) {
            throw new DahuaSdkException(action + "失败",
                    sdk.CLIENT_GetLastError(), "该型号可能不支持此功能，或通道无云台");
        }
        return true;
    }

    private static NetSDKLib requireSdk() {
        NetSDKLib sdk = DahuaSdk.get().sdk();
        if (sdk == null) {
            throw new DahuaSdkException("大华 SDK 不可用", DahuaSdk.get().loadError());
        }
        return sdk;
    }
}
