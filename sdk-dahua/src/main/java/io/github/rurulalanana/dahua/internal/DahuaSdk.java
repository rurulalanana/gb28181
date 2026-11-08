package io.github.rurulalanana.dahua.internal;

import com.netsdk.lib.NetSDKLib;
import com.netsdk.lib.NetSDKLib.LLong;
import com.netsdk.lib.NetSDKLib.NET_IN_LOGIN_WITH_HIGHLEVEL_SECURITY;
import com.netsdk.lib.NetSDKLib.NET_OUT_LOGIN_WITH_HIGHLEVEL_SECURITY;
import com.sun.jna.Pointer;
import io.github.rurulalanana.deviceaccess.ConnectMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 大华 NetSDK 生命周期与登录会话管理。
 *
 * <h3>职责边界</h3>
 * 只管「SDK 初始化」与「登录句柄的生命周期」，<b>不含任何业务判断</b>：
 * 不知道设备表、不知道平台编号规则、不碰数据库。
 * 这样它可以被单测直接构造（原生库缺失时降级为不可用），
 * 也不依赖 Spring。
 *
 * <h3>为什么 SDK 实例是懒加载的</h3>
 * 不能写 {@code static final NetSDKLib SDK = NetSDKLib.NETSDK_INSTANCE} ——
 * 那个字段会在<b>类加载阶段</b>触发 JNA 加载原生库。一旦 jar 里缺当前平台的库，
 * 异常在类初始化时抛出，调用方再怎么 try/catch 也拦不住，
 * 会直接把宿主应用（Spring 容器）的启动拖垮。
 *
 * <p>本项目实测过这个后果：大华 SDK 没装好导致整个平台起不来，国标链路一起废掉。
 * 因此改为首次使用时加载，并把失败原因缓存下来供接口层展示。
 *
 * <h3>两种连接方向的会话索引</h3>
 * <ul>
 *   <li>{@link ConnectMode#CLIENT} —— 平台主动连设备，用 {@code host:port} 取会话；</li>
 *   <li>{@link ConnectMode#SERVER} —— 设备主动注册，用 <b>SN</b> 取会话
 *       （设备换网/IP 变化后 {@code host:port} 会失效，SN 不会）。</li>
 * </ul>
 *
 * <h3>句柄失效与自动重登</h3>
 * 设备断线时 SDK 的断线回调只<b>标记</b>句柄失效、不删除会话。
 * 取会话时检测到失效标记就用缓存凭据重新登录 ——
 * 设备重连后旧句柄会作废，继续用会报 {@code 0x80000004 句柄无效}。
 */
public final class DahuaSdk {

    private static final Logger log = LoggerFactory.getLogger(DahuaSdk.class);

    /**
     * 主动注册登录方式：{@code EM_LOGIN_SPAC_CAP_TYPE.EM_LOGIN_SPEC_CAP_SERVER_CONN}。
     *
     * <p>直接写字面量 2 而不引用 SDK 常量：常量类在 {@code NetSDKLib} 的静态块里，
     * 引用它会触发原生库加载，本类的「懒加载」就失效了。
     */
    private static final int SPEC_CAP_SERVER_CONN = 2;

    /** 单例：SDK 全局只应初始化一次，且会话表必须全局唯一。 */
    private static final DahuaSdk INSTANCE = new DahuaSdk();

    public static DahuaSdk get() {
        return INSTANCE;
    }

    /** SDK 实例；{@code null} 表示尚未成功加载。 */
    private volatile NetSDKLib sdk;

    /** 加载失败原因（供诊断展示）；{@code null} 表示没失败过。 */
    private volatile String loadError;

    /** {@code CLIENT_Init} 是否已成功执行。 */
    private volatile boolean initialized;

    private final Object initLock = new Object();

    /** 登录会话，key = {@code host:port}。 */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** SN → {@code host:port} 的索引（SERVER 模式用 SN 取会话）。 */
    private final Map<String, String> snIndex = new ConcurrentHashMap<>();

    private DahuaSdk() {
    }

    // ==================== 初始化 ====================

    /**
     * 初始化 SDK（幂等，线程安全）。
     *
     * @return 是否可用
     */
    public boolean init() {
        if (initialized) {
            return true;
        }
        synchronized (initLock) {
            if (initialized) {
                return true;
            }
            if (!DahuaNativeLibrary.available()) {
                loadError = DahuaNativeLibrary.diagnose();
                return false;
            }
            try {
                NetSDKLib instance = NetSDKLib.NETSDK_INSTANCE;
                if (!instance.CLIENT_Init(DisconnectCallback.INSTANCE, null)) {
                    loadError = "CLIENT_Init 返回 false（SDK 内部初始化失败）";
                    log.error("大华 NetSDK 初始化失败: {}", loadError);
                    return false;
                }
                // 断线后由 SDK 内部持续重连，重连成功回调里打日志便于排障
                instance.CLIENT_SetAutoReconnect(ReconnectCallback.INSTANCE, null);
                // 登录超时 5 秒、重试 1 次：默认值（1 秒 / 1 次）在跨公网时太短
                instance.CLIENT_SetConnectTime(5000, 1);
                sdk = instance;
                initialized = true;
                log.info("大华 NetSDK 初始化完成（平台目录 {}）", DahuaNativeLibrary.platformDir());
                return true;
            } catch (Throwable t) {
                loadError = DahuaNativeLibrary.isLibraryLoadFailure(t)
                        ? DahuaNativeLibrary.diagnose()
                        : String.valueOf(t.getMessage());
                log.error("大华 NetSDK 初始化异常: {}", loadError, t);
                return false;
            }
        }
    }

    /** SDK 是否已初始化成功。 */
    public boolean ready() {
        return initialized;
    }

    /** 加载失败原因；未失败时返回 {@code null}。 */
    public String loadError() {
        return loadError;
    }

    /** 取 SDK 实例；未初始化时抛异常（调用方应先用 {@link #init()}）。 */
    public NetSDKLib sdk() {
        NetSDKLib instance = sdk;
        if (instance == null) {
            throw new IllegalStateException("大华 NetSDK 未初始化：" + (loadError == null ? "尚未调用 init()" : loadError));
        }
        return instance;
    }

    // ==================== 会话 ====================

    /**
     * 取（必要时建立）会话。
     *
     * <p>SERVER 模式按 SN 索引；CLIENT 模式按 {@code host:port}。
     * 已有会话的句柄若被标记失效，会先用缓存凭据重新登录。
     *
     * @param host     设备地址；SERVER 模式下会被注册表里的实时地址覆盖
     * @param port     SDK 端口
     * @param username 登录用户名
     * @param password 登录密码
     * @param mode     连接方向
     * @param serial   设备序列号（SERVER 模式必需）
     * @return 会话；失败返回 {@code null}（原因已记日志）
     */
    public Session session(String host, int port, String username, String password,
                           ConnectMode mode, String serial) {
        if (mode == ConnectMode.SERVER) {
            return serverSession(host, port, username, password, serial);
        }
        return clientSession(host, port, username, password);
    }

    /**
     * 按 SN 查已注册设备的会话（不触发登录）。
     *
     * @return 未注册返回 {@code null}
     */
    public Session sessionBySerial(String serial) {
        if (serial == null) {
            return null;
        }
        String key = snIndex.get(normalizeSn(serial));
        return key == null ? null : sessions.get(key);
    }

    /** 设备是否在线（会话存在<b>且</b>句柄有效）。 */
    public boolean online(String serial) {
        Session s = sessionBySerial(serial);
        // 不能只判非空：断线回调只标记失效、不删会话，
        // 非空判断会把离线设备误判成在线
        return s != null && s.valid();
    }

    /** 当前全部会话（诊断用）。 */
    public Collection<Session> sessions() {
        return sessions.values();
    }

    /** 登出指定 {@code host:port} 并移除会话。 */
    public void logout(String host, int port) {
        removeSession(key(host, port));
    }

    /** 按 SN 登出（设备主动注册断开时用）。 */
    public void logoutBySerial(String serial) {
        if (serial == null) {
            return;
        }
        String key = snIndex.get(normalizeSn(serial));
        if (key != null) {
            removeSession(key);
        }
    }

    /** 标记会话失效，下次取会话时自动重登。 */
    public void invalidate(Session session) {
        if (session != null) {
            session.invalidate();
        }
    }

    // ==================== 内部实现 ====================

    /**
     * SERVER 模式：按 SN 找会话。
     *
     * <h3>⚠️ 已有有效会话时必须直接复用，<b>不能用传入的 host:port 去纠正</b></h3>
     * 早期这里在「{@code oldKey != newKey}」时会认为「设备换网了」，
     * 删掉旧会话再按 {@code newKey} 重登。实测线上直接踩死：
     * <pre>
     *   00:00:23  大华设备登录成功: 111.198.60.180:15178（主动注册 SN=10001）  ← 真实可用的会话
     *   00:01:42  大华设备登录失败: 111.198.60.180:37777 错误码=9              ← 业务侧传进来的「地址」
     * </pre>
     * 15178 是设备主动连上来的<b>源端口</b>，只在本次注册有效；
     * 37777 是纳管时按配置写进 {@code gb_device} 的<b>服务端口</b>。
     * 设备位于 NAT / 4G 之后时，37777 从平台侧<b>根本不可达</b> ——
     * 于是每轮巡检都把好会话删掉、重登失败，表现为「主动注册成功但没通道、随即离线」。
     *
     * <p>所以：只要该 SN 有<b>有效会话</b>，就直接返回，
     * {@code host}/{@code port} 只在这个 SN 完全没会话时用作登录目标。
     * 设备真的换网时 {@code DahuaRegisterListener} 会再次触发注册回调，
     * 由那边重建会话，不需要业务侧拿旧地址「纠正」。
     */
    private Session serverSession(String host, int port, String username, String password, String serial) {
        if (serial == null || serial.isBlank()) {
            log.warn("主动注册设备缺少 SN，无法登录: {}:{}", host, port);
            return null;
        }
        String sn = normalizeSn(serial);
        String newKey = key(host, port);
        String oldKey = snIndex.get(sn);

        if (oldKey != null) {
            Session existing = sessions.get(oldKey);
            if (existing != null && existing.valid()) {
                if (!oldKey.equals(newKey)) {
                    // 不是「地址变更」，只是调用方拿了个过期的地址（通常是建档时写的服务端口）。
                    // 记 DEBUG：本条链路在 NAT 场景下会高频命中，INFO 会刷屏。
                    log.debug("大华设备 {} 复用已注册会话 {}（忽略传入的 {}）", sn, oldKey, newKey);
                }
                return existing;
            }
            log.info("大华设备 {} 句柄已失效（{}），重新登录: {}:{}", sn, oldKey, host, port);
            removeSession(oldKey);
        }

        Session created = login(host, port, username, password, sn, true);
        if (created != null) {
            sessions.put(newKey, created);
            snIndex.put(sn, newKey);
        }
        return created;
    }

    /** CLIENT 模式：按 {@code host:port} 复用会话。 */
    private Session clientSession(String host, int port, String username, String password) {
        String k = key(host, port);
        Session cached = sessions.get(k);
        if (cached != null && cached.valid()) {
            return cached;
        }
        synchronized (this) {
            cached = sessions.get(k);
            if (cached != null && cached.valid()) {
                return cached;
            }
            // 失效的旧会话先清掉，避免占着 key 让新会话插不进来
            if (cached != null) {
                removeSession(k);
            }
            Session created = login(host, port, username, password, null, false);
            if (created != null) {
                sessions.put(k, created);
                if (created.serial() != null && !created.serial().isBlank()) {
                    snIndex.put(normalizeSn(created.serial()), k);
                }
            }
            return created;
        }
    }

    /**
     * 执行一次登录。
     *
     * @param autoRegistered {@code true} = 主动注册（{@code emSpecCap=2} 且必须带 SN）
     */
    private Session login(String host, int port, String username, String password,
                          String serial, boolean autoRegistered) {
        if (!init()) {
            return null;
        }
        NET_IN_LOGIN_WITH_HIGHLEVEL_SECURITY in = new NET_IN_LOGIN_WITH_HIGHLEVEL_SECURITY();
        in.nPort = port;
        in.szIP = toCString(host, in.szIP.length);
        in.szUserName = toCString(username, in.szUserName.length);
        in.szPassword = toCString(password, in.szPassword.length);

        Pointer snPointer = null;
        if (autoRegistered) {
            /*
             * 主动注册登录：emSpecCap 必须为 SERVER_CONN，且 pCapParam 指向 SN。
             * 少了这一步设备会直接拒绝 —— 这是「只开了监听、没做这步」
             * 导致接入失败的典型原因。
             *
             * 用 JNA 的 Memory 手工分配而不调 ToolKits.GetGBKStringToPointer：
             * 后者返回的指针没有明确的释放时机，且 SN 是纯 ASCII，
             * 按 GBK 或 UTF-8 编码字节序列相同，不必引入那条路径。
             */
            in.emSpecCap = SPEC_CAP_SERVER_CONN;
            snPointer = toNativeString(serial);
            in.pCapParam = snPointer;
        }

        NET_OUT_LOGIN_WITH_HIGHLEVEL_SECURITY out = new NET_OUT_LOGIN_WITH_HIGHLEVEL_SECURITY();
        out.stuDeviceInfo = new NetSDKLib.NET_DEVICEINFO_Ex();

        try {
            LLong handle = sdk().CLIENT_LoginWithHighLevelSecurity(in, out);
            if (handle == null || handle.longValue() == 0) {
                log.error("大华设备登录失败: {}:{}{} 错误码={} 用户名={}",
                        host, port, autoRegistered ? "（主动注册 SN=" + serial + "）" : "",
                        out.nError, username);
                log.error("  提示：{}", describeLoginError(out.nError));
                return null;
            }
            String actualSerial = serial;
            if (actualSerial == null && out.stuDeviceInfo != null) {
                actualSerial = readCString(out.stuDeviceInfo.sSerialNumber);
            }
            int channelCount = out.stuDeviceInfo == null ? 0 : out.stuDeviceInfo.byChanNum;
            int deviceType = out.stuDeviceInfo == null ? 0 : out.stuDeviceInfo.byDVRType;
            log.info("大华设备登录成功: {}:{}{} 句柄={} 通道数={} 设备类型={}（{}）", host, port,
                    autoRegistered ? "（主动注册 SN=" + serial + "）" : "",
                    handle.longValue(), channelCount, deviceType,
                    deviceType == NetSDKLib.NET_DEVICE_TYPE.NET_IPC_SERIAL ? "IPC，云台通道用 0" : "非 IPC，云台通道用 1~N");
            return new Session(host, port, username, password, actualSerial,
                    autoRegistered, handle, channelCount, deviceType);
        } catch (Throwable t) {
            if (DahuaNativeLibrary.isLibraryLoadFailure(t)) {
                loadError = DahuaNativeLibrary.diagnose();
                log.error("大华设备登录失败：原生库不可用", t);
            } else {
                log.error("大华设备登录异常: {}:{}", host, port, t);
            }
            return null;
        } finally {
            if (snPointer != null) {
                try {
                    // JNA Memory 是 GC 管理的，但主动清空能让原生侧尽早看到空值
                    snPointer.setMemory(0, 1, (byte) 0);
                } catch (Throwable ignored) {
                    // 清理失败不影响登录结果
                }
            }
        }
    }

    /** 移除会话并登出。 */
    private void removeSession(String key) {
        Session session = sessions.remove(key);
        if (session == null) {
            return;
        }
        if (session.serial() != null) {
            snIndex.remove(normalizeSn(session.serial()), key);
        }
        try {
            sdk().CLIENT_Logout(session.handle());
        } catch (Throwable t) {
            log.warn("大华设备登出异常: {} {}", key, t.toString());
        }
    }

    /** 释放全部会话并清理 SDK（进程退出时调用）。 */
    public void shutdown() {
        for (String key : sessions.keySet()) {
            removeSession(key);
        }
        sessions.clear();
        snIndex.clear();
        if (initialized) {
            try {
                sdk().CLIENT_Cleanup();
                log.info("大华 NetSDK 已释放");
            } catch (Throwable t) {
                log.warn("大华 NetSDK 释放异常: {}", t.toString());
            } finally {
                initialized = false;
            }
        }
    }

    /**
     * 登录错误码 → 中文可操作提示。
     *
     * <p>只覆盖现场最常遇到的几个；其余原样给出错误码，便于查厂商手册。
     * 这些码取自大华 NetSDK 开发 FAQ 与实测。
     */
    private static String describeLoginError(int code) {
        return switch (code) {
            case 1 -> "用户名或密码错误";
            case 2 -> "用户无权限";
            case 3 -> "登录失败：设备不支持该登录方式（SERVER 模式要求设备已主动注册）";
            case 4 -> "账号被锁定";
            case 5 -> "账号已在别处登录且不允许重复登录";
            case 18 -> "登录超时：设备不可达或被防火墙拦截";
            case 24 -> "设备要求国密 TLS 登录";
            default -> "错误码 " + code + "（详见大华 NetSDK 开发 FAQ）";
        };
    }

    private static String key(String host, int port) {
        return host + ":" + port;
    }

    private static String normalizeSn(String serial) {
        return serial.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** Java 字符串 → 定长 C 字符数组（UTF-8，尾部留 0）。 */
    private static byte[] toCString(String value, int length) {
        byte[] dest = new byte[length];
        if (value == null) {
            return dest;
        }
        byte[] src = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(src, 0, dest, 0, Math.min(src.length, length - 1));
        return dest;
    }

    /** Java 字符串 → 原生内存中的 C 字符串。 */
    private static Pointer toNativeString(String value) {
        if (value == null) {
            return null;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        com.sun.jna.Memory memory = new com.sun.jna.Memory(bytes.length + 1L);
        memory.write(0, bytes, 0, bytes.length);
        memory.setByte(bytes.length, (byte) 0);
        return memory;
    }

    /** 读定长 C 字符数组（去尾部 0）。 */
    private static String readCString(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        int end = 0;
        while (end < bytes.length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8).trim();
    }

    // ==================== 回调 ====================

    /**
     * 断线回调。
     *
     * <p>SDK 内部线程调用，<b>只做标记、不做耗时操作</b>：
     * 标记句柄失效后，下次取会话会自动重登。
     * 回调对象必须是强引用（静态单例），否则被 GC 后原生侧回调会崩。
     */
    private static final class DisconnectCallback implements NetSDKLib.fDisConnect {
        static final DisconnectCallback INSTANCE = new DisconnectCallback();

        @Override
        public void invoke(LLong loginId, String ip, int port, Pointer user) {
            long value = loginId == null ? 0 : loginId.longValue();
            log.warn("大华设备断线: {}:{} 句柄={}", ip, port, value);
            // 直接访问单例的会话表：断线回调是静态内部类，拿不到外部实例字段，
            // 但它在同一个外层类里，可以直接读 sessions
            for (Session s : DahuaSdk.get().sessions.values()) {
                if (s.handle() != null && s.handle().longValue() == value) {
                    s.invalidate();
                }
            }
        }
    }

    /** 重连成功回调。 */
    private static final class ReconnectCallback implements NetSDKLib.fHaveReConnect {
        static final ReconnectCallback INSTANCE = new ReconnectCallback();

        @Override
        public void invoke(LLong loginId, String ip, int port, Pointer user) {
            log.info("大华设备重连成功: {}:{} 句柄={}", ip, port,
                    loginId == null ? 0 : loginId.longValue());
        }
    }

    // ==================== 会话 ====================

    /**
     * 一次登录会话。
     *
     * <p>{@code password} 只存内存供重登使用，<b>不写日志、不落盘</b>。
     */
    public static final class Session {

        private final String host;
        private final int port;
        private final String username;
        private final String password;
        private final String serial;
        private final boolean autoRegistered;
        private final LLong handle;
        private final int channelCount;
        /** 设备类型（{@code NET_DEVICEINFO_Ex.byDVRType}），云台通道基址靠它区分 IPC 与 NVR。 */
        private final int deviceType;

        /**
         * 句柄是否已失效。
         *
         * <p>设备重连、SDK 内部重连后旧句柄作废，继续用会报
         * {@code 0x80000004 句柄无效}。标记后取会话时会自动重登。
         */
        private volatile boolean invalid;

        Session(String host, int port, String username, String password, String serial,
                boolean autoRegistered, LLong handle, int channelCount, int deviceType) {
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
            this.serial = serial;
            this.autoRegistered = autoRegistered;
            this.handle = handle;
            this.channelCount = channelCount;
            this.deviceType = deviceType;
        }

        public String host() {
            return host;
        }

        public int port() {
            return port;
        }

        public String username() {
            return username;
        }

        /** 登录密码（仅供内部重登）。 */
        public String password() {
            return password;
        }

        public String serial() {
            return serial;
        }

        public boolean autoRegistered() {
            return autoRegistered;
        }

        public LLong handle() {
            return handle;
        }

        /** 设备上报的通道数（诊断用，可为 0）。 */
        public int channelCount() {
            return channelCount;
        }

        /** 设备类型码（{@code NET_DEVICE_TYPE}）。 */
        public int deviceType() {
            return deviceType;
        }

        /**
         * 是否为 IPC（单机网络摄像机）。
         *
         * <p><b>这个判定直接决定云台能不能用</b>：大华 SDK 对 IPC 的云台通道号是
         * <b>0</b>（设备自身），而对 NVR/DVR 是 1~N（下挂的摄像机）。
         * 用 1 去控 IPC 不会报错、不会抛异常，只是<b>什么都不会发生</b> ——
         * 实测同一台 IPC：通道 0 云台 tilt 从 -111 转到 -150、zoom 6→11；
         * 通道 1 前后完全没变化。
         *
         * <p>IPC 的类型码目前只有 {@code NET_IPC_SERIAL = 20} 一个；
         * 其余（{@code NET_NVR_SERIAL=31} 等）都按 NVR 处理。
         */
        public boolean ipc() {
            return deviceType == NetSDKLib.NET_DEVICE_TYPE.NET_IPC_SERIAL;
        }

        public boolean valid() {
            return !invalid;
        }

        void invalidate() {
            this.invalid = true;
        }

        @Override
        public String toString() {
            return "Session{" + host + ":" + port
                    + ", sn=" + serial
                    + ", handle=" + (handle == null ? 0 : handle.longValue())
                    + ", valid=" + valid() + '}';
        }
    }
}
