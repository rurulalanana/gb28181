package io.github.rurulalanana.dahua;

import com.netsdk.lib.NetSDKLib;
import io.github.rurulalanana.dahua.internal.DahuaNativeLibrary;
import io.github.rurulalanana.dahua.internal.DahuaPtzCommand;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.deviceaccess.ConnectMode;
import io.github.rurulalanana.deviceaccess.DeviceAccessProvider;
import io.github.rurulalanana.deviceaccess.DeviceContext;
import io.github.rurulalanana.deviceaccess.DeviceKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 大华 NetSDK 接入实现。
 *
 * <h3>能力取舍：为什么预览/回放走 RTSP 而不是 SDK 取码流</h3>
 * SDK 取码流（{@code CLIENT_RealPlayEx} + 回调）要自己解 PS 封装、重排时间戳、
 * 处理丢包补偿，风险高；而 RTSP 这条路<b>复用现有的 FLV/WS-FLV/HLS/WebRTC 输出
 * 与前端播放器</b>，平台侧一行媒体代码都不用加。
 *
 * <p>代价是需要设备开启 RTSP（大华默认开启，端口 554）。
 * 收益是「零 NetSDK 依赖地拿到画面」—— 即使 SDK 原生库加载失败，
 * 只要知道设备地址仍能出图。
 *
 * <h3>SDK 只用于「国标做不到的事」</h3>
 * 通道目录（国标 Catalog 也能做，但 SDK 直连设备根本没有 SIP）、
 * 云台、抓图、以及后续的巡航组/限位/图像参数。
 * 这也符合契约 {@code DeviceAccessProvider} 的定位 —— 见其类注释。
 *
 * <h3>线程安全</h3> 无状态（会话在 {@link DahuaSdk} 单例里），可并发调用。
 */
public final class DahuaDeviceAccessProvider implements DeviceAccessProvider {

    private static final Logger log = LoggerFactory.getLogger(DahuaDeviceAccessProvider.class);

    /** 大华 SDK 默认端口。 */
    private static final int DEFAULT_SDK_PORT = 37777;

    /** 大华 RTSP 默认端口。 */
    private static final int DEFAULT_RTSP_PORT = 554;

    /**
     * 云台速度上限。
     *
     * <p>契约入参是 0~255（与国标口径一致），而大华 SDK 的方向速度范围是
     * <b>1~8</b>。直接塞 129 会被 SDK 夹到上限，用户永远调不出慢速。
     */
    private static final int SDK_SPEED_MAX = 8;

    /** 回放时间格式：大华 RTSP 用 {@code yyyy_MM_dd_HH_mm_ss}（下划线分隔）。 */
    private static final DateTimeFormatter RTSP_TIME = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss");

    /** 契约里的回放时间格式：{@code yyyy-MM-dd HH:mm:ss}。 */
    private static final DateTimeFormatter CONTRACT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * RTSP 主码流通道号。
     *
     * <p>大华的 RTSP 路径按「主码流在前、子码流在后」连续编号：
     * 4 通道设备的主码流是 1~4、子码流是 5~8；8 通道设备则是 1~8 / 9~16。
     * 因此子码流的通道号 = 主码流通道号 + 通道总数。
     */
    private final int rtspPort;

    /** 设备通道总数；未知时按 1 处理（单通道 IPC 最常见）。 */
    private final int channelTotal;

    public DahuaDeviceAccessProvider() {
        this(DEFAULT_RTSP_PORT, 0);
    }

    /**
     * @param rtspPort     设备 RTSP 端口（大华默认 554）
     * @param channelTotal 设备通道总数；{@code <=0} 表示未知（按单通道处理）
     */
    public DahuaDeviceAccessProvider(int rtspPort, int channelTotal) {
        this.rtspPort = rtspPort > 0 ? rtspPort : DEFAULT_RTSP_PORT;
        this.channelTotal = channelTotal;
    }

    @Override
    public DeviceKind kind() {
        return DeviceKind.DAHUA;
    }

    @Override
    public boolean supportsServerMode() {
        // DahuaRegisterListener 已实现 CLIENT_ListenServer 那条路径
        return true;
    }

    // ==================== 探测 ====================

    /**
     * 探测设备可达性。
     *
     * <p>SERVER 模式下<b>先查注册表</b>：设备没主动注册上来时，
     * 拿配置里的地址去连毫无意义（配置里通常就是空的），
     * 只会白等一次登录超时。
     */
    @Override
    public boolean probe(DeviceContext ctx) {
        if (ctx == null || !ctx.connectable()) {
            log.debug("大华设备探测跳过：连接信息不完整 {}", ctx);
            return false;
        }
        if (!DahuaNativeLibrary.available()) {
            // 原生库缺失时不能假装在线：调用方会据此把设备标成 ON
            log.debug("大华设备探测跳过：原生库不可用");
            return false;
        }
        DahuaSdk.Session session = session(ctx);
        if (session == null) {
            log.info("大华设备探测失败: {}，{}", ctx, DahuaNativeLibrary.hintFor(ctx.connectMode()));
            return false;
        }
        return session.valid();
    }

    // ==================== 通道目录 ====================

    /**
     * 拉取通道目录。
     *
     * <p><b>通道编号规则</b>：大华 SDK 的通道号是从 1 开始的整数
     * （0 表示设备自身），平台侧需要一个稳定的字符串编号。
     * 这里用 {@code <deviceId>-<n>} 的形式，例如
     * {@code 34020000001310000001-1}。
     *
     * <p>为什么不用「设备编码 + 国标规则推算」：SDK 直连设备没有国标目录，
     * 拿不到真实通道编码；而 {@code -n} 这种形式既稳定又能反解出通道号
     * （见 {@link #channelIndexOf}），点播和云台都靠它。
     *
     * @return 通道列表；设备不可达时返回<b>空列表</b>而不是抛异常（契约要求）
     */
    @Override
    public List<ChannelInfo> listChannels(DeviceContext ctx) {
        List<ChannelInfo> result = new ArrayList<>();
        if (ctx == null) {
            return result;
        }
        DahuaSdk.Session session = session(ctx);
        if (session == null) {
            log.info("大华设备目录拉取失败: {}，{}", ctx, DahuaNativeLibrary.hintFor(ctx.connectMode()));
            return result;
        }
        int total = session.channelCount() > 0 ? session.channelCount() : channelTotal;
        if (total <= 0) {
            /*
             * 设备没上报通道数。此时不能返回空列表 —— 调用方会据此
             * 把设备的通道全部清理掉（fullSnapshot=true）。
             * 保守地报 1 个通道：单通道 IPC 是最常见形态，
             * 且至少保证「设备有通道可播」这个事实不被抹掉。
             */
            log.warn("大华设备未上报通道数，按单通道处理: {}", ctx.deviceId());
            total = 1;
        }
        for (int i = 1; i <= total; i++) {
            result.add(new ChannelInfo(
                    channelCodeOf(ctx.deviceId(), i),
                    "通道" + i,
                    true,
                    0,
                    "Dahua",
                    ""));
        }
        log.info("大华设备目录: {} 通道数={}", ctx.deviceId(), result.size());
        return result;
    }

    // ==================== 流地址 ====================

    /**
     * 实时流 RTSP 地址。
     *
     * <p>格式：{@code rtsp://<user>:<pass>@<ip>:554/cam/realmonitor?channel=<N>&subtype=<0|1>}
     * <ul>
     *   <li>{@code subtype=0} 主码流、{@code subtype=1} 子码流</li>
     *   <li>{@code channel} 从 1 开始（0 是设备自身，取不到画面）</li>
     * </ul>
     */
    @Override
    public String liveStreamUri(DeviceContext ctx, String channelCode, Integer streamNumber) {
        if (ctx == null) {
            return null;
        }
        String host = resolveHost(ctx);
        if (host == null) {
            log.warn("大华设备缺少可达地址，无法生成流地址: {}", ctx.deviceId());
            return null;
        }
        int index = channelIndexOf(ctx.deviceId(), channelCode);
        int subtype = streamNumber != null && streamNumber == 1 ? 1 : 0;
        return String.format("rtsp://%s:%s@%s:%d/cam/realmonitor?channel=%d&subtype=%d",
                encode(ctx.username()), encode(ctx.password()), host, rtspPort, index, subtype);
    }

    /**
     * 回放流 RTSP 地址。
     *
     * <p>大华的时间回放路径：{@code /cam/playback?channel=<N>&subtype=<0|1>&starttime=...&endtime=...}，
     * 时间格式是 <b>下划线</b>分隔的 {@code yyyy_MM_dd_HH_mm_ss}。
     *
     * <p>这个格式与国标要求的 {@code T} 分隔完全不同 ——
     * 本项目在国标侧就因为用了空格分隔导致录像检索永远超时（见排障实录 §1.1），
     * 这里必须按厂商格式来。
     *
     * @param startTime {@code yyyy-MM-dd HH:mm:ss}
     * @param endTime   同上
     */
    @Override
    public String playbackStreamUri(DeviceContext ctx, String channelCode,
                                    String startTime, String endTime) {
        if (ctx == null) {
            return null;
        }
        String host = resolveHost(ctx);
        if (host == null) {
            log.warn("大华设备缺少可达地址，无法生成回放地址: {}", ctx.deviceId());
            return null;
        }
        String start = toRtspTime(startTime);
        String end = toRtspTime(endTime);
        if (start == null || end == null) {
            log.warn("大华回放时间格式不合法（应为 yyyy-MM-dd HH:mm:ss）: {} ~ {}", startTime, endTime);
            return null;
        }
        int index = channelIndexOf(ctx.deviceId(), channelCode);
        return String.format("rtsp://%s:%s@%s:%d/cam/playback?channel=%d&subtype=0&starttime=%s&endtime=%s",
                encode(ctx.username()), encode(ctx.password()), host, rtspPort, index, start, end);
    }

    // ==================== 云台 ====================

    /**
     * 云台控制。
     *
     * <p>{@code command} 为 {@code stop} 时发「停」动作，其余映射到具体指令码。
     * 无法识别的方向词<b>抛异常</b>而不是静默当作停止 ——
     * 静默失败会让现场以为「云台坏了」。
     *
     * @param speed 0~255（国标口径），内部折算成大华的 1~8
     * @throws IllegalArgumentException 指令无法识别，或原生库不可用
     */
    @Override
    public void ptz(DeviceContext ctx, String channelCode, String command, int speed) {
        if (ctx == null) {
            throw new IllegalArgumentException("缺少设备上下文");
        }
        DahuaSdk.Session session = session(ctx);
        if (session == null) {
            throw new IllegalStateException("大华设备未登录，无法控制云台: " + ctx.deviceId()
                    + "；" + DahuaNativeLibrary.hintFor(ctx.connectMode()));
        }
        int index = channelIndexOf(ctx.deviceId(), channelCode);
        /*
         * IPC 的云台通道号是 0，不是 1。
         *
         * 大华 SDK 里「通道」有两套口径：
         *   IPC（直连单机摄像机）→ 设备自身是通道 0；
         *   NVR/DVR             → 下挂摄像机是通道 1~N。
         *
         * 用 1 去控 IPC 不会报错也不会抛异常，SDK 照样返回 true，
         * 只是设备**什么都不做** —— 实测同一台 IPC：
         *   通道 0：UP 后 tilt -111 → -150，变倍后 zoom 6 → 11（真的在动）
         *   通道 1：前后位置完全一致（死命令）
         * 这种「静默无效」最难查，因为所有返回值都是成功。
         */
        int sdkChannel = session.ipc() ? Math.max(0, index - 1) : index;
        int sdkSpeed = toSdkSpeed(speed);

        try {
            if (DahuaPtzCommand.isStop(command)) {
                /*
                 * 「停」要对所有方向都发一次才可靠：大华 SDK 的 stop 是按
                 * 指令码分别生效的，只发 UP 的 stop 不会让正在左转的云台停下。
                 * 逐个发一遍是最稳的做法（每次调用都是毫秒级）。
                 */
                for (DahuaPtzCommand c : new DahuaPtzCommand[]{
                        DahuaPtzCommand.UP, DahuaPtzCommand.DOWN,
                        DahuaPtzCommand.LEFT, DahuaPtzCommand.RIGHT,
                        DahuaPtzCommand.UP_LEFT, DahuaPtzCommand.UP_RIGHT,
                        DahuaPtzCommand.DOWN_LEFT, DahuaPtzCommand.DOWN_RIGHT,
                        DahuaPtzCommand.ZOOM_IN, DahuaPtzCommand.ZOOM_OUT}) {
                    callPtz(session, sdkChannel, c, sdkSpeed, true);
                }
                return;
            }
            DahuaPtzCommand cmd = DahuaPtzCommand.parse(command);
            if (cmd == null) {
                throw new IllegalArgumentException("不支持的大华云台指令：" + command);
            }
            callPtz(session, sdkChannel, cmd, sdkSpeed, false);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IllegalStateException e) {
            // callPtz 已写好可读原因（含错误码），不要再包一层把它埋进 cause
            throw e;
        } catch (Throwable t) {
            if (DahuaNativeLibrary.isLibraryLoadFailure(t)) {
                throw new IllegalStateException("大华原生库不可用：" + DahuaNativeLibrary.diagnose(), t);
            }
            throw new IllegalStateException("大华云台控制失败: " + ctx.deviceId() + " " + command, t);
        }
    }

    /**
     * 发一次云台指令。
     *
     * <p><b>失败必须抛出去</b>（停止指令除外）：早期只 {@code log.warn} 不抛，
     * 接口恒回 {@code {"code":0}}，用户点方向键看不到任何反馈 ——
     * 既不知道是设备不支持、通道号错了，还是会话掉了。
     *
     * <p>停止指令不抛是有原因的：大华按指令码分别维护运动状态，
     * 「停一个本来就没在动的方向」必然返回失败，那是正常现象。
     */
    private void callPtz(DahuaSdk.Session session, int channel, DahuaPtzCommand cmd,
                         int speed, boolean stop) {
        // dwStop: 0 = 开始动作，1 = 停止
        int stopFlag = stop ? 1 : 0;
        boolean ok = DahuaSdk.get().sdk().CLIENT_DHPTZControlEx2(
                session.handle(), channel, cmd.code(), 0, speed, 0, stopFlag, null);
        if (ok) {
            return;
        }
        int err = NetSDKLib.NETSDK_INSTANCE.CLIENT_GetLastError();
        if (stop) {
            // 停未在动的方向必然失败，属正常
            log.debug("大华云台停止指令未生效（该方向未在运动）: 通道={} 指令={}", channel, cmd);
            return;
        }
        log.warn("大华云台指令失败: {}:{} 通道={} 指令={} 错误码=0x{}",
                session.host(), session.port(), channel, cmd,
                String.format("%08X", err));
        throw new IllegalStateException(String.format(
                "云台指令未被设备接受（%s，错误码 0x%08X %s）。"
                        + "常见原因：该通道不是球机 / 通道号不对 / 登录会话已失效",
                cmd, err, ptzErrHint(err)));
    }

    /** 云台失败错误码 → 可读提示。未知码返回空串，调用方仍有原始码可查。 */
    private static String ptzErrHint(int err) {
        return switch (err) {
            case 0x80000004, 0x80000005 -> "句柄无效，请重新查询设备状态";
            case 0x80000007 -> "参数不合法，通道号可能超出范围";
            case 0x8000001D -> "SDK 未初始化";
            default -> "";
        };
    }

    /**
     * 0~255 → 大华 1~8。
     *
     * <p>0 也要抬到 1：大华的速度 0 表示「不动」，传 0 会让指令变成无效果，
     * 而调用方传 0 的意图通常是「最慢」而不是「不动」。
     */
    private static int toSdkSpeed(int speed) {
        int s = Math.max(0, Math.min(255, speed));
        return Math.max(1, Math.min(SDK_SPEED_MAX, (int) Math.ceil(s * SDK_SPEED_MAX / 255.0)));
    }

    // ==================== 内部工具 ====================

    /**
     * 取会话。
     *
     * <p>SERVER 模式用 SN 索引（地址会变），并优先用注册表里的实时地址；
     * CLIENT 模式用配置地址。
     */
    private DahuaSdk.Session session(DeviceContext ctx) {
        if (!DahuaSdk.get().init()) {
            log.debug("大华 SDK 不可用，跳过: {}", ctx.deviceId());
            return null;
        }
        String host = ctx.host();
        int port = ctx.port() > 0 ? ctx.port() : DEFAULT_SDK_PORT;
        return DahuaSdk.get().session(host, port, ctx.username(), ctx.password(),
                ctx.connectMode(), ctx.serial());
    }

    /**
     * 解析设备可达地址。
     *
     * <p>SERVER 模式下配置里的 host 通常是空的，必须用注册表回填的实时地址；
     * 直接读 {@code ctx.host()} 会拼出 {@code rtsp://...@null:554}。
     */
    private String resolveHost(DeviceContext ctx) {
        if (ctx.host() != null && !ctx.host().isBlank()) {
            return ctx.host().trim();
        }
        if (ctx.connectMode() == ConnectMode.SERVER && ctx.serial() != null) {
            DahuaSdk.Session s = DahuaSdk.get().sessionBySerial(ctx.serial());
            if (s != null) {
                return s.host();
            }
        }
        return null;
    }

    /** 通道编号：{@code <deviceId>-<n>}。 */
    public static String channelCodeOf(String deviceId, int index) {
        return deviceId + "-" + index;
    }

    /**
     * 从通道编号反解通道号。
     *
     * <p>兼容三种入参：
     * <ol>
     *   <li>本类生成的 {@code <deviceId>-<n>} → 取最后一段</li>
     *   <li>纯数字（前端或调用方直接传了通道号）→ 原样使用</li>
     *   <li>其它（例如手工填的国标 20 位编码）→ 回落到 1，
     *       并打日志说明 —— 猜错通道号会控制到错误的摄像头，比失败更糟</li>
     * </ol>
     *
     * <p>返回值最小为 1：大华的通道 0 是设备自身，云台/取流都用不了。
     */
    public static int channelIndexOf(String deviceId, String channelCode) {
        if (channelCode != null && !channelCode.isBlank()) {
            String v = channelCode.trim();
            if (v.matches("\\d{1,3}")) {
                return Math.max(1, Integer.parseInt(v));
            }
            if (deviceId != null && v.startsWith(deviceId + "-")) {
                String suffix = v.substring(deviceId.length() + 1);
                if (suffix.matches("\\d{1,3}")) {
                    return Math.max(1, Integer.parseInt(suffix));
                }
            }
        }
        if (channelCode != null && !channelCode.isBlank()) {
            log.warn("无法从通道编号解析通道号，按 1 处理: {}", channelCode);
        }
        return 1;
    }

    /** {@code yyyy-MM-dd HH:mm:ss} → {@code yyyy_MM_dd_HH_mm_ss}；解析失败返回 {@code null}。 */
    private static String toRtspTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            LocalDateTime t = LocalDateTime.parse(text.trim(), CONTRACT_TIME);
            return t.format(RTSP_TIME);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** URL 编码（账号密码可能含 {@code @} / {@code :} 等会破坏 URL 的字符）。 */
    private static String encode(String s) {
        return s == null ? "" : URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
