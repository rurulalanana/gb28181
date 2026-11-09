package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.dahua.ext.DahuaCgiAccess;
import io.github.rurulalanana.dahua.ext.DahuaExtAccess;
import io.github.rurulalanana.dahua.ext.DahuaSdkException;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.deviceaccess.ConnectMode;
import io.github.rurulalanana.deviceaccess.DeviceKind;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.config.DahuaProperties;
import io.github.rurulalanana.starter.entity.GbDevice;
import com.netsdk.lib.NetSDKLib;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 大华 SDK 扩展能力的平台侧中枢。
 *
 * <h3>为什么需要这一层</h3>
 * {@code sdk-dahua} 里的 {@link DahuaExtAccess} 提供的是「大华语义」的裸能力
 * （预置位号段、JSON 配置项名、事件码）。平台要的是「按 deviceId 干活」：
 * 查设备记录 → 拿地址与凭据 → 找已登录会话 → 调 SDK → 把异常翻成中文业务错误。
 * 这三段逻辑如果散落在每个 Service 里，会出现「有的地方忘了判 source」
 * 「有的地方没把错误码转成提示」这类不一致。
 *
 * <h3>为什么用 ObjectProvider 延迟取</h3>
 * {@code DahuaExtAccess} 本身不加载原生库，但它调用的 {@code DahuaSdk.get()}
 * 会。用 {@link ObjectProvider} 保证「没装 SDK 时平台照常启动，只有大华接口报错」，
 * 与 {@code DeviceAccessRegistry} 的口径一致。
 *
 * <h3>会话从哪来</h3>
 * 复用 {@link DahuaSdk#session}：它会按 SN 或 host:port 复用已登录会话，
 * 句柄失效时自动重登。**不要在业务代码里自己 CLIENT_Login** ——
 * 那会绕开注册表，设备换 IP 后拿到的是死地址。
 */
@Slf4j
@Service
public class DahuaCapabilityService {

    private final DeviceService deviceService;
    private final ObjectProvider<DahuaExtAccess> extProvider;
    /**
     * 扁平配置写入器。
     *
     * <p><b>为什么用 ObjectProvider 而不是直接注入</b>：
     * {@code DahuaPtzConfigService} 反过来依赖本类（要会话、要通道号换算），
     * 直接构造注入会形成循环依赖 —— Spring Boot 3 默认<b>禁止</b>循环引用，
     * 启动直接报 {@code BeanCurrentlyInCreationException}。
     * 用 {@link ObjectProvider} 延迟到首次使用再解析即可。
     */
    private final ObjectProvider<DahuaPtzConfigService> ptzConfigProvider;
    /** 设备 Web CGI 通道（配置写入兜底），无状态可复用。 */
    private final DahuaCgiAccess cgi = new DahuaCgiAccess();
    private final io.github.rurulalanana.dahua.ext.DahuaRpcAccess rpc =
            new io.github.rurulalanana.dahua.ext.DahuaRpcAccess();
    private final DahuaProperties dahuaProperties;

    public DahuaCapabilityService(DeviceService deviceService,
                                  ObjectProvider<DahuaExtAccess> extProvider,
                                  ObjectProvider<DahuaPtzConfigService> ptzConfigProvider,
                                  DahuaProperties dahuaProperties) {
        this.deviceService = deviceService;
        this.extProvider = extProvider;
        this.ptzConfigProvider = ptzConfigProvider;
        this.dahuaProperties = dahuaProperties;
    }

    /** 取扁平配置写入器（首次调用时才解析，见字段注释）。 */
    private DahuaPtzConfigService ptzConfig() {
        DahuaPtzConfigService svc = ptzConfigProvider.getIfAvailable();
        if (svc == null) {
            throw BizException.badRequest("大华配置服务不可用");
        }
        return svc;
    }

    // ==================== 通用：取会话 ====================

    /**
     * 取一台大华设备已登录的会话。
     *
     * <h3>优先用主动注册会话</h3>
     * 设备主动注册上来时，平台已经在<b>那条连接</b>上登录过
     * （{@code DahuaConfiguration#serverLoginHandler}），会话就在内存里。
     * 必须先查它，原因见 {@link #sessionOrDirect} 的注释：
     * NAT 之后的设备用建档地址（服务端口 37777）直连是<b>不可达</b>的。
     *
     * @param deviceId 设备编号（{@code gb_device.device_id}，大华即序列号）
     * @return 会话；设备不是大华、或登录失败时为空
     */
    public Optional<DahuaSdk.Session> session(String deviceId) {
        GbDevice d = findDahuaDevice(deviceId);
        if (d == null) {
            return Optional.empty();
        }
        DahuaSdk sdk = DahuaSdk.get();
        if (!sdk.init()) {
            log.debug("大华 SDK 不可用: {}", sdk.loadError());
            return Optional.empty();
        }
        // 主动注册会话优先：只查内存 map，不发起网络请求
        DahuaSdk.Session live = sdk.sessionBySerial(d.getDeviceId());
        if (live != null && live.valid()) {
            return Optional.of(live);
        }
        return Optional.ofNullable(sessionOrDirect(sdk, d));
    }

    /**
     * 没有主动注册会话时的退路：按建档地址直连。
     *
     * <p>适用于局域网直连部署（设备地址从平台可达），以及主动注册暂时掉线
     * 但设备本身仍在线的情况。
     *
     * <p><b>为什么不做成默认路径</b>：{@code gb_device.remote_port} 存的是
     * 纳管时按配置写的<b>设备服务端口</b>（大华默认 37777）。设备在 NAT/4G 之后时
     * 这个端口从平台侧不可达，直连必然以错误码 9 失败 ——
     * 实测线上表现是「主动注册成功、也纳管了，但没有通道，随后离线」。
     */
    private DahuaSdk.Session sessionOrDirect(DahuaSdk sdk, GbDevice d) {
        String host = d.getRemoteIp();
        int port = d.getRemotePort() == null ? 0 : d.getRemotePort();
        if (host == null || host.isBlank() || port <= 0) {
            return null;
        }
        return sdk.session(host, port, d.getUsername(), d.getPassword(),
                ConnectMode.CLIENT, null);
    }

    /** 查设备记录，并确认它确实是大华接入。 */
    private GbDevice findDahuaDevice(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return null;
        }
        GbDevice d = deviceService.findDevice(deviceId);
        if (d == null) {
            // 传进来的可能是通道编码，反查所属设备
            String owner = deviceService.deviceIdOfChannel(deviceId);
            d = owner == null ? null : deviceService.findDevice(owner);
        }
        if (d == null) {
            return null;
        }
        return DeviceKind.of(d.getSource()) == DeviceKind.DAHUA ? d : null;
    }

    /**
     * 取会话，取不到就抛业务异常。
     *
     * <p>错误信息必须能指导操作：只说「设备不可用」等于没说。
     */
    public DahuaSdk.Session requireSession(String deviceId) {
        GbDevice d = findDahuaDevice(deviceId);
        if (d == null) {
            GbDevice any = deviceService.findDevice(deviceId);
            if (any == null) {
                String owner = deviceService.deviceIdOfChannel(deviceId);
                any = owner == null ? null : deviceService.findDevice(owner);
            }
            if (any == null) {
                throw BizException.notFound("设备不存在：" + deviceId);
            }
            throw BizException.badRequest("该接口仅适用于大华 SDK 直连设备：" + any.getDeviceId());
        }
        return session(d.getDeviceId()).orElseThrow(() -> BizException.badRequest(
                "大华设备未登录（检查设备是否在线、账号密码是否正确）：" + d.getDeviceId()));
    }

    /** 该设备是否走大华 SDK 接入。 */
    public boolean isDahua(String deviceId) {
        return findDahuaDevice(deviceId) != null;
    }

    /**
     * 取扩展能力对象。
     *
     * <p>每次调用都重新取：{@code DahuaExtAccess} 本身无状态（回调强引用放在实例字段里），
     * 但 Spring 单例下不会重复创建，代价可以忽略。
     */
    public DahuaExtAccess ext() {
        DahuaExtAccess ext = extProvider.getIfAvailable();
        if (ext == null) {
            throw BizException.badRequest(
                    "大华 SDK 模块未安装或未启用（sdk-dahua）；见 docs/大华SDK接入.md");
        }
        return ext;
    }

    /**
     * 设置「PTZ旋轉限制」的上/下限位为<b>当前镜头位置</b>。
     *
     * <h3>为什么不用 CLIENT_PTZSetPanGroupLimit</h3>
     * SDK 里确实有 {@code CLIENT_PTZSetPanGroupLimit} +
     * {@code NET_EM_PAN_GROUP_LIMIT_MODE}（左/右/上/下四个模式），语义看起来完全对口。
     * 但实测这台 IPC（SN 10001 / fw 3.201.0000007.1.R）直接返回
     * {@code 0x8000004F 设备不支持该操作}。
     *
     * <h3>实际可行的做法：读位置 → 写配置字段</h3>
     * {@code PtzLimit} 配置项的结构（SDK 读回的原文）：
     * <pre>
     * {"Enable": false, "HorizontalLimitEnable": false,
     *  "UpLimit": {"Boundary": 0, "Enable": true}}
     * </pre>
     * 所以「设上限」= 把当前 {@code tilt} 写进 {@code UpLimit.Boundary}。实测有效：
     * <pre>
     *   写入 Boundary=-150 → 读回 -150
     *   Enable=true 后向上转 3 秒 → tilt 恒为 -150（云台被挡住）
     * </pre>
     * 这与设备界面的行为一致（界面也是「以当前位置为边界」）。
     *
     * <p><b>下限做不了</b>：这台固件的 {@code PtzLimit} 只有 {@code UpLimit}，
     * 没有 {@code DownLimit}（凭空写一个不存在的子对象会被设备忽略）。所以下限如实报不支持，
     * 不让用户以为点了会生效。
     *
     * @param upper {@code true} 设上限，{@code false} 设下限（本型号不支持）
     */
    public Map<String, Object> setRotateLimitBoundary(DahuaSdk.Session session, int channel, boolean upper) {
        if (!upper) {
            throw BizException.badRequest(
                    "这台设备的固件只在 PtzLimit 里提供了「上限」字段，没有「下限」，"
                            + "所以平台无法设置旋转下限。"
                            + "设备自己的 Web 界面「PTZ旋转限制」页如果显示了下限，请在那里设置一次。");
        }
        Map<String, Object> pos = ptzPosition(session, channel);
        if (pos.get("tilt") == null) {
            throw BizException.badRequest("读不到云台当前位置：" + pos.get("error"));
        }
        int tilt = ((Number) pos.get("tilt")).intValue();
        String raw = ext().readConfig(session, "PtzLimit", channel);
        if (raw == null || raw.isBlank()) {
            throw BizException.badRequest("设备未返回 PtzLimit 配置项（该型号可能不支持旋转限制）");
        }
        com.fasterxml.jackson.databind.JsonNode business =
                io.github.rurulalanana.dahua.internal.DahuaJson.business(raw);
        if (!(business instanceof com.fasterxml.jackson.databind.node.ObjectNode obj)) {
            throw BizException.badRequest("PtzLimit 的结构与预期不符，拒绝写入");
        }
        com.fasterxml.jackson.databind.JsonNode up = obj.get("UpLimit");
        if (!(up instanceof com.fasterxml.jackson.databind.node.ObjectNode upObj)) {
            throw BizException.badRequest("PtzLimit 里没有 UpLimit 字段（该型号可能不支持旋转限制）");
        }
        upObj.put("Boundary", tilt);
        ext().writeRaw(session, "PtzLimit", channel,
                io.github.rurulalanana.dahua.internal.DahuaJson.write(obj));
        log.info("旋转上限已设为当前位置: device channel={} tilt={}", channel, tilt);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("boundary", tilt);
        out.put("tilt", tilt);
        out.put("pan", pos.get("pan"));
        return out;
    }

    /**
     * 读云台当前的物理位置（Pan / Tilt / Zoom）。
     *
     * <p>用途：设置「PTZ旋轉限制」的上下限位时，设备要求的是<b>坐标值</b>，
     * 而部分型号不支持 {@code CLIENT_PTZSetPanGroupLimit}（实测这台 IPC 返回
     * {@code 0x8000004F 设备不支持该操作}）。那时只能「读当前位置 → 写进 PtzLimit 配置项」。
     *
     * @return 位置信息；读不到时返回含 {@code error} 的 Map（不抛异常，由调用方决定怎么办）
     */
    public java.util.Map<String, Object> ptzPosition(DahuaSdk.Session session, int channel) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        NetSDKLib sdk = DahuaSdk.get().sdk();
        if (sdk == null) {
            return out;
        }
        NetSDKLib.NET_PTZ_LOCATION_INFO info = new NetSDKLib.NET_PTZ_LOCATION_INFO();
        info.write();
        com.sun.jna.ptr.IntByReference retLen = new com.sun.jna.ptr.IntByReference(0);
        boolean ok = sdk.CLIENT_QueryDevState(session.handle(),
                NetSDKLib.NET_DEVSTATE_PTZ_LOCATION, info.getPointer(), info.size(), retLen, 3_000);
        if (!ok) {
            out.put("error", "查询云台位置失败 err=0x"
                    + String.format("%08X", sdk.CLIENT_GetLastError()));
            return out;
        }
        info.read();
        out.put("channel", channel);
        out.put("pan", info.nPTZPan);
        out.put("tilt", info.nPTZTilt);
        out.put("zoom", info.nPTZZoom);
        out.put("absPan", info.stuAbsPosition.nPosX);
        out.put("absTilt", info.stuAbsPosition.nPosY);
        return out;
    }

    /**
     * 取设备 Web CGI 通道（配置写入的兜底路径）。
     *
     * <p>只在 SDK 写入被设备拒绝时用：部分型号的场景数组类配置项
     * （{@code VideoColor} / {@code VideoInSharpness} …）通过
     * {@code CLIENT_SetNewDevConfig} 一律 {@code err=12}，
     * 而设备自己的 Web 界面能改，走的就是这条 {@code configManager.cgi}。
     * 详见 {@code DahuaCgiAccess} 的类注释。
     */
    public DahuaCgiAccess cgi() {
        return cgi;
    }

    /**
     * RPC2 通道（懒创建，全局共用一个：session 与 object id 需要跨调用缓存）。
     *
     * <p>只在「CGI 与 netsdk 都没有对应能力」时用，见 {@code DahuaRpcAccess} 类注释。
     */
    public io.github.rurulalanana.dahua.ext.DahuaRpcAccess rpc() {
        return rpc;
    }

    /** 设备 Web（HTTP CGI）端口，见 {@code dahua.http-port}。 */
    public int httpPort() {
        return dahuaProperties == null ? DahuaCgiAccess.DEFAULT_HTTP_PORT
                : dahuaProperties.getHttpPort();
    }

    /**
     * 把 SDK 异常翻成业务异常。
     *
     * <p>统一在这里转换的理由：SDK 异常带 {@code sdkError} 与 {@code hint}，
     * 直接抛出去前端只看到英文错误码；而每处调用点各写一遍转换必然漏。
     *
     * <h3>为什么不加「{@code action} 失败：」前缀</h3>
     * {@code sdk-dahua} 抛出的消息本身已经带主语（「对讲建立失败」「抓图失败（通道 0）」
     * 「转到预置位 1 失败」…），再拼前缀会变成
     * 「建立对讲失败：对讲建立失败（…）」这种读起来像结巴的文案。
     * {@code action} 只用于日志标签，方便按动作检索。
     *
     * <p>同理<b>不再追加 {@code hint()}</b> ——
     * {@link DahuaSdkException#getMessage()} 已经把 hint 拼进去了。
     *
     * @param action 动作描述（只进日志，不拼进用户可见消息）
     */
    public BizException translate(String action, DahuaSdkException e) {
        log.warn("大华{}失败: {} err=0x{}", action, e.getMessage(),
                Integer.toHexString(e.sdkError()));
        return BizException.badRequest(e.getMessage());
    }

    // ==================== 通道号换算（IPC 用 0，NVR 用 1~N）====================

    /**
     * 把业务通道编码换算成 SDK 通道号。
     *
     * <p><b>两套编号并存，不能混用</b>：
     * <ul>
     *   <li>取流（RTSP）用 {@code channel=<N>}，N 从 1 起；</li>
     *   <li>云台/抓图/配置的 SDK 通道号：IPC 是 <b>0</b>（设备自身），NVR 是 1~N。</li>
     * </ul>
     * 拿 1 去控 IPC 不会报错，SDK 照样返回 true，只是设备什么都不做 ——
     * 实测同一台 IPC 通道 0 能转、通道 1 死命令。
     *
     * @param session     已登录会话（判定 IPC/NVR）
     * @param channelCode 业务通道编码，如 {@code 10001-1}
     * @return SDK 通道号
     */
    public int sdkChannel(DahuaSdk.Session session, String deviceId, String channelCode) {
        int index = io.github.rurulalanana.dahua.DahuaDeviceAccessProvider
                .channelIndexOf(deviceId, channelCode);
        return session.ipc() ? Math.max(0, index - 1) : index;
    }

    /** 取流用的通道号（RTSP 的 {@code channel=} 参数，恒从 1 起）。 */
    public int streamChannel(String deviceId, String channelCode) {
        return io.github.rurulalanana.dahua.DahuaDeviceAccessProvider
                .channelIndexOf(deviceId, channelCode);
    }

    // ==================== 局域网发现 ====================

    /**
     * 局域网发现设备。
     *
     * @param localIp   本机网卡地址；{@code null} 由 SDK 自选
     * @param timeoutMs 收集时长
     * @return 发现列表（可能为空）
     */
    public List<DahuaExtAccess.Discovered> discoverLan(String localIp, long timeoutMs) {
        try {
            return ext().discoverLan(localIp, timeoutMs);
        } catch (DahuaSdkException e) {
            throw translate("局域网设备发现", e);
        }
    }

    /**
     * 登出并丢弃一台设备的 SDK 会话。
     *
     * <p><b>为什么删除设备前必须调它</b>：SDK 会话是平台主动握着的一条长连接，
     * 删掉 {@code gb_device} 记录不会自动断开它 —— 设备侧仍显示「有客户端登录」，
     * 而且那段内存（会话对象 + 原生句柄）会一直留着。
     *
     * <p>失败只记日志不抛：设备可能已经离线/重启，登不掉也不该阻止删除档案。
     *
     * @param deviceId 设备编号
     */
    public void logoutQuietly(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        try {
            GbDevice d = findDahuaDevice(deviceId);
            if (d == null) {
                return;
            }
            DahuaSdk sdk = DahuaSdk.get();
            if (!sdk.init()) {
                return;
            }
            // SN 索引与 host:port 索引都要清，两个都可能命中
            sdk.logoutBySerial(deviceId);
            if (d.getRemoteIp() != null && d.getRemotePort() != null && d.getRemotePort() > 0) {
                sdk.logout(d.getRemoteIp(), d.getRemotePort());
            }
            log.info("已登出大华 SDK 会话: device={} {}:{}", deviceId, d.getRemoteIp(), d.getRemotePort());
        } catch (Exception e) {
            log.warn("登出大华 SDK 会话失败（不影响删除档案）: device={} {}", deviceId, e.getMessage());
        }
    }

    // ==================== 云台扩展（巡航 / 巡迹 / 限位 / 重启）====================

    /**
     * 执行一次云台扩展操作。
     *
     * <p>用「动作名 + 参数」而不是为每个功能开一个方法：云台扩展有十几种，
     * 逐个包方法会得到一堆签名相似、极易传错参数的函数；而它们的共同点
     * 正是「都是一条 DHPTZControl 指令」。控制器只负责把 HTTP 参数翻译成动作名。
     *
     * @param action 见 {@code DahuaPtzActions}
     * @return 是否需要重启设备
     */
    public boolean ptzAction(String deviceId, String channelCode, String action,
                             Integer route, Integer preset, Integer mode, Boolean start) {
        var session = requireSession(deviceId);
        int sdkChannel = sdkChannel(session, deviceId, channelCode);
        int r = route == null ? 1 : route;
        int p = preset == null ? 1 : preset;
        int m = mode == null ? 1 : mode;
        boolean on = start == null || start;
        try {
            switch (action == null ? "" : action.trim().toLowerCase()) {
                case "cruise-add" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt
                        .cruiseAddPoint(session, sdkChannel, r, p);
                case "cruise-remove" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt
                        .cruiseRemovePoint(session, sdkChannel, r, p);
                /*
                 * 巡航启停：走 SDK 的<b>专用接口</b> CLIENT_PTZStartTour / CLIENT_PTZStopTour。
                 *
                 * <p>为什么不走通用云台命令（踩过两次）：
                 * <ul>
                 *   <li>早期走 RPC2 {@code ptz.startTour}（与设备 Web 界面同一条通路），
                 *       但 RPC2 是 HTTP，设备在 NAT/4G 后面时<b>根本不通</b></li>
                 *   <li>后来改走 {@code NET_PTZ_POINT_LOOP_CONTROL}（注释叫「点间巡航」），
                 *       接口回 true 但设备<b>一动不动、且不报错</b> —— 静默失败最难查</li>
                 * </ul>
                 * 专用接口能穿透 NAT，且失败时会返回明确错误码。
                 */
                case "cruise-run" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt
                        .cruiseRun(session, sdkChannel, r, on);
                /* 巡迹录制 / 回放：SDK 有完整命令码，不再走 RPC2。 */
                case "pattern-record" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt
                        .patternStartRecord(session, sdkChannel, m);
                case "pattern-record-stop" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt
                        .patternStopRecord(session, sdkChannel, m);
                case "pattern-run" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt
                        .patternRun(session, sdkChannel, m, on);
                case "pattern-delete" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt
                        .patternDelete(session, sdkChannel, m);
                /*
                 * 旋转限位（设备「PTZ旋轉限制」页的上/下/左/右）。
                 *
                 * <p><b>不要和线扫边界混为一谈</b>，两者是设备里完全不同的两份数据：
                 * <pre>
                 *   limit-*        → PtzLimit 配置项（「PTZ旋轉限制」）
                 *   scan-limit-*   → AutoScan 配置项（「線性掃描」的左右边界）
                 * </pre>
                 *
                 * <p>上下限位走「读当前位置 → 写 PtzLimit.UpLimit.Boundary」，
                 * 不用 {@code CLIENT_PTZSetPanGroupLimit}（这台设备返回
                 * {@code 0x8000004F 设备不支持该操作}），详见
                 * {@link #setRotateLimitBoundary}。
                 */
                case "limit-up" -> setRotateLimitBoundary(session, sdkChannel, true);
                case "limit-down" -> setRotateLimitBoundary(session, sdkChannel, false);
                /*
                 * 旋转限制的左右两个方向同样写不了：这台设备的 PtzLimit 里
                 * 只有 UpLimit 一个子对象，没有 LeftLimit / RightLimit。
                 * 而 SDK 的 CLIENT_PTZSetPanGroupLimit 在这台设备上返回
                 * 0x8000004F「设备不支持该操作」（见 setRotateLimitBoundary 的注释）。
                 */
                case "limit-left", "limit-right" -> throw BizException.badRequest(
                        "这台设备的固件只在 PtzLimit 里提供了「上限」字段，"
                                + "没有左右/下限，所以平台无法设置该方向的旋转限制。");
                /* 线扫左右边界：SDK 的 SETLEFTBORDER / SETRIGHTBORDER，写 AutoScan 配置项。 */
                case "scan-limit-left" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt
                        .setLeftLimit(session, sdkChannel, r);
                case "scan-limit-right" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt
                        .setRightLimit(session, sdkChannel, r);
                case "line-scan" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt
                        .lineScan(session, sdkChannel, r, on);
                case "pan-cruise" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt
                        .panCruise(session, sdkChannel, on);
                case "reboot" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt.reboot(session);
                /*
                 * PTZ 恢复出厂默认（设备「PTZ 維護」页的「預設值」）。
                 * 只有 RPC2 有这条路；同 limit-up 的理由，明确说明而不是去撞超时。
                 * 会清空预置位/巡航组/线扫/巡迹，属破坏性操作。
                 */
                case "ptz-default" -> throw BizException.badRequest(
                        "PTZ 恢复出厂设置需要设备的 Web 接口（RPC2），"
                                + "当前设备通过主动注册接入，平台无法直连它的 Web 端口。"
                                + "请在设备自己的 Web 界面「PTZ 维护 → 预设值」里操作。");
                default -> throw BizException.badRequest("不支持的云台动作：" + action);
            }
        } catch (DahuaSdkException e) {
            throw translate("云台动作 " + action, e);
        }
        log.info("大华云台动作已执行: device={} channel={} action={} route={} preset={} mode={} start={}",
                deviceId, channelCode, action, r, p, m, on);
        return true;
    }

    /**
     * 写云台速度（手动 / 巡迹）。
     *
     * <p>写完立即回读比对：设备对 {@code PtzSpeed} 有取值校验（只认 0~2），
     * 越界时某些入口会返回成功但不写入，所以不能只信「返回 OK」。
     *
     * <p>走 SDK 的扁平读写（读原结构 → 改字段 → 整项写回），
     * 不再用 CGI —— 设备在 NAT 后面时 Web 端口不可达。
     */
    public void setPtzSpeed(String deviceId, int channel, int ptzSpeed, int patternSpeed) {
        var session = requireSession(deviceId);
        try {
            java.util.Map<String, String> kv = new java.util.LinkedHashMap<>();
            kv.put("PtzSpeed[" + channel + "].PtzSpeed", String.valueOf(ptzSpeed));
            kv.put("PtzSpeed[" + channel + "].PatternSpeed", String.valueOf(patternSpeed));
            ptzConfig().writeFlat(session, "PtzSpeed", channel, kv);

            // 回读确认：设备返回 OK 不等于写进去了
            java.util.Map<String, String> back =
                    ext().readConfigFlat(session, "PtzSpeed", channel);
            int gotPtz = parseInt(back.get("PtzSpeed[" + channel + "].PtzSpeed"), -1);
            int gotPat = parseInt(back.get("PtzSpeed[" + channel + "].PatternSpeed"), -1);
            if (gotPtz != ptzSpeed || gotPat != patternSpeed) {
                throw BizException.badRequest("云台速度写入未生效（回读 手动=" + gotPtz
                        + " 巡迹=" + gotPat + "）。该型号可能不支持这些取值");
            }
        } catch (DahuaSdkException e) {
            throw translate("写入云台速度", e);
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ==================== 设备级控制（重启 / 校时 / 报警 / 灯光雨刷 / 信息）====================

    /**
     * 重启设备整机。
     *
     * <p><b>设备会断线几十秒到两分钟</b>，期间预览与录像全部中断。
     * 调用方应先确认无人在看画面。
     */
    public void reboot(String deviceId) {
        var session = requireSession(deviceId);
        try {
            io.github.rurulalanana.dahua.ext.DahuaDeviceCtrl.reboot(session);
        } catch (DahuaSdkException e) {
            throw translate("设备重启", e);
        }
        log.info("大华设备重启指令已下发: device={}", deviceId);
    }

    /** 读设备当前时间；读不到返回 {@code null}。 */
    public java.time.LocalDateTime deviceTime(String deviceId) {
        return io.github.rurulalanana.dahua.ext.DahuaDeviceCtrl
                .queryTime(requireSession(deviceId));
    }

    /**
     * 设备校时。
     *
     * @param time 目标时间，{@code null} 表示平台当前时间
     */
    public void setDeviceTime(String deviceId, java.time.LocalDateTime time) {
        var session = requireSession(deviceId);
        try {
            io.github.rurulalanana.dahua.ext.DahuaDeviceCtrl.setTime(session, time);
        } catch (DahuaSdkException e) {
            throw translate("设备校时", e);
        }
        log.info("大华设备已校时: device={} time={}", deviceId, time);
    }

    /** 报警复位（清掉通道当前的报警状态）。 */
    public void clearAlarm(String deviceId, String channelCode) {
        var session = requireSession(deviceId);
        int sdkChannel = sdkChannel(session, deviceId, channelCode);
        try {
            io.github.rurulalanana.dahua.ext.DahuaDeviceCtrl
                    .clearAlarm(session, sdkChannel);
        } catch (DahuaSdkException e) {
            throw translate("报警复位", e);
        }
    }

    /**
     * 灯光 / 雨刷。
     *
     * <p>大华把补光灯与雨刷合成一条指令，靠 {@code index} 区分。
     * <b>返回值不能证明设备动作了</b>，只能看画面确认。
     */
    public void setLamp(String deviceId, String channelCode, boolean on, int index) {
        var session = requireSession(deviceId);
        int sdkChannel = sdkChannel(session, deviceId, channelCode);
        try {
            io.github.rurulalanana.dahua.ext.DahuaDeviceCtrl
                    .setLamp(session, sdkChannel, on, index);
        } catch (DahuaSdkException e) {
            throw translate("灯光雨刷控制", e);
        }
    }

    /** 设备基本信息。 */
    public io.github.rurulalanana.dahua.ext.DahuaDeviceCtrl.DeviceInfo deviceInfo(String deviceId) {
        return io.github.rurulalanana.dahua.ext.DahuaDeviceCtrl
                .deviceInfo(requireSession(deviceId));
    }
}
