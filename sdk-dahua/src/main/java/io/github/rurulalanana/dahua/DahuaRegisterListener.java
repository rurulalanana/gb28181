package io.github.rurulalanana.dahua;

import com.netsdk.lib.NetSDKLib;
import com.sun.jna.Pointer;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.deviceaccess.RegisterListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 大华「主动注册」监听服务（服务器模式）。
 *
 * <h3>设备主动注册的完整流程</h3>
 * <pre>
 *   平台                                    设备（NAT 后 / 4G / 无固定 IP）
 *    │ CLIENT_ListenServer(0.0.0.0:9500)      │
 *    │ ◄────── TCP 连上来 + 上报序列号 ────────│  ← 方向是设备→平台
 *    │ 回调 NET_DEV_AUTOREGISTER_RETURN        │
 *    │ 记下 sn / ip / port                     │
 *    │ CLIENT_LoginWithHighLevelSecurity       │
 *    │   emSpecCap=2（主动注册）, pCapParam=sn  │
 *    │ ◄────────── 登录成功 ──────────────────│
 * </pre>
 *
 * <p><b>关键点：注册 ≠ 登录</b>。注册只解决「知道设备在哪」，
 * 后续所有操作仍然要登录拿句柄 —— 而登录时必须以「主动注册方式」
 * （{@code emSpecCap=2}）并带上序列号，否则设备会拒绝。
 * 很多接入失败就是因为只开了监听、没做这一步。
 *
 * <h3>⚠️ 注册之后必须立刻登录，否则设备每 60 秒重连一次</h3>
 * 实测（2026-11，IPC 10001，fw 3.201）：只记 IP 不登录时，日志里稳定出现
 * <pre>
 *   19:07:40.255 大华设备已注销注册: sn=10001
 *   19:07:40.472 大华设备主动注册: sn=10001 ip=192.168.10.156:56624   ← 源端口每次都变
 *   19:08:40.259 大华设备已注销注册: sn=10001
 * </pre>
 * 间隔精确到 60.004 秒。原因是这条连接在 SDK 里处于<b>「待验证」</b>状态 ——
 * 这正是 {@link NetSDKLib.EM_LISTEN_TYPE#NET_DVR_DISCONNECT}（{@code -1}）
 * 官方注释「<b>验证</b>期间设备断线回调」的字面含义：不是「设备下线」，
 * 是「验证超时，连接被丢弃」。设备随即换一个源端口重新注册，永远循环。
 *
 * <p>对照实验：同一条连接上按 {@code emSpecCap=2 + SN} 登录后，
 * 连续观察 152 秒，注册次数停在 1、再没出现过一次断线重连。
 * 官方 demo（{@code AutoRegisterConsole.ServiceCB}）与《Auto Register User's Manual》
 * §3.2.4 也是同一个写法：回调里 {@code new Thread(() -> login(pIp, wPort, ...))}。
 *
 * <p>所以本类在注册成功后<b>通过 {@link Handler} 通知上层去登录</b>。
 * 监听器本身不认识账号密码（凭据属于平台配置，不属于协议层），
 * 登录由装配方在 {@link Handler} 里做。
 *
 * <h3>为什么回调里不能直接登录</h3>
 * 回调跑在 SDK 的原生线程上，登录是阻塞操作（网络往返 + 可能的超时）。
 * 在里面同步登录会阻塞整个监听线程，后续设备注册全部排队 ——
 * 设备多时表现为「前几台能连、后面的都超时」。官方手册也明确要求
 * 「If SDK interfaces needed to be called in the callback function,
 * please create a separate thread; otherwise the program will crash」。
 * 因此这里把 {@link Handler} 丢到单独线程执行，回调只做「记表」。
 */
public class DahuaRegisterListener implements RegisterListener {

    private static final Logger log = LoggerFactory.getLogger(DahuaRegisterListener.class);

    /** 国标设备编码/序列号基本都是 ASCII；用 GBK 兜底是为了兼容少数固件的原始字节。 */
    private static final Charset SN_CHARSET = Charset.forName("GBK");

    /**
     * 设备注册成功后的通知。
     *
     * <p>调用方应在这里<b>用 {@code emSpecCap=2 + SN} 在 {@code ip:port} 上登录</b>，
     * 把这条连接从「待验证」变成登录会话。不登录设备会每 60 秒重连一次
     * （见类注释的实测记录）。
     *
     * <p>实现方在本类的工作线程上执行，可以阻塞（登录、写库都行）。
     */
    @FunctionalInterface
    public interface Handler {
        /**
         * @param serial 设备序列号
         * @param ip     设备地址（设备侧 TCP 连接的对端 IP）
         * @param port   <b>设备本次连接的 TCP 源端口</b>。它只在本次注册有效，
         *               但正是登录这条连接必须用的端口 —— 换成配置里的 37777 就变成
         *               另一条直连连接，原连接仍然留在待验证状态、照样 60 秒断开
         */
        void onRegistered(String serial, String ip, int port);
    }

    private final DahuaRegisterTable table;
    /** 可为 {@code null}：只记账不登录（直连部署、或没配账号密码时）。 */
    private final Handler handler;
    /** 回调对象必须由 Java 侧强引用：JNA 只弱引用，被 GC 后原生回调会崩。 */
    private final NetSDKLib.fServiceCallBack callback = new ServiceCallback();
    private final AtomicLong registered = new AtomicLong();

    /**
     * 单线程派发。不用「每次注册 new Thread」：设备集体上电时会有几百次注册，
     * 一次性起几百个线程只会把登录握手挤成一团。
     */
    private volatile ExecutorService dispatcher;

    private volatile NetSDKLib.LLong handle;
    private volatile int boundPort;

    /** 只记账、不登录。设备会每 60 秒重连一次（可接受，但日志里会看到）。 */
    public DahuaRegisterListener(DahuaRegisterTable table) {
        this(table, null);
    }

    public DahuaRegisterListener(DahuaRegisterTable table, Handler handler) {
        this.table = table;
        this.handler = handler;
    }

    @Override
    public boolean running() {
        NetSDKLib.LLong h = handle;
        return h != null && h.longValue() != 0;
    }

    @Override
    public int port() {
        return boundPort;
    }

    @Override
    public long registeredCount() {
        return registered.get();
    }

    @Override
    public synchronized void start(String bindIp, int port) {
        if (running()) {
            log.debug("大华注册监听已在运行，端口 {}", boundPort);
            return;
        }
        String ip = (bindIp == null || bindIp.isBlank()) ? "0.0.0.0" : bindIp.trim();
        /*
         * 必须先确保 SDK 已初始化。
         *
         * <p>{@code CLIENT_ListenServer} 在未初始化时返回 0x8000001D（NET_NO_INIT），
         * 而平台侧这个监听是在 ApplicationRunner 里启动的 —— 那时可能还没有任何
         * 业务调用触发过懒初始化，于是启动必定失败，报错却写着"端口被占用"，
         * 排查时会一直盯着端口查（实测踩过）。
         */
        DahuaSdk sdk = DahuaSdk.get();
        if (!sdk.init()) {
            throw new IllegalStateException(
                    "大华 SDK 初始化失败，无法启动主动注册监听：" + sdk.loadError());
        }
        // 第三个参数是「通道超时」（毫秒），不是连接超时；SDK 示例与二次开发手册都用 1000
        NetSDKLib.LLong h = NetSDKLib.NETSDK_INSTANCE.CLIENT_ListenServer(ip, port, 1000, callback, null);
        if (h == null || h.longValue() == 0) {
            // 失败必须抛，不能只打日志：端口占用是部署期最常见的错误，
            // 静默不工作会让运维以为「设备有问题」而白查很久
            int err = NetSDKLib.NETSDK_INSTANCE.CLIENT_GetLastError();
            throw new IllegalStateException(String.format(
                    "大华主动注册监听启动失败: %s:%d，错误码 0x%08X（%s）",
                    ip, port, err, reasonOf(err)));
        }
        handle = h;
        boundPort = port;
        log.info("大华主动注册监听已启动: {}:{}", ip, port);
    }

    /** 错误码 → 可读原因；未知码回落到最常见的端口类原因。 */
    private static String reasonOf(int err) {
        return switch (err) {
            case 0x8000001D -> "SDK 未初始化";
            case 0x80000007 -> "参数不合法（端口超出范围）";
            case 0x80000004 -> "句柄无效";
            default -> "端口被占用或地址不可绑定";
        };
    }

    @Override
    public synchronized void stop() {
        NetSDKLib.LLong h = handle;
        handle = null;
        ExecutorService d = dispatcher;
        dispatcher = null;
        if (d != null) {
            d.shutdownNow();
        }
        if (h == null || h.longValue() == 0) {
            return;
        }
        try {
            NetSDKLib.NETSDK_INSTANCE.CLIENT_StopListenServer(h);
            log.info("大华主动注册监听已停止（原端口 {}）", boundPort);
        } catch (Throwable t) {
            log.warn("停止大华注册监听失败: {}", t.toString());
        }
        boundPort = 0;
    }

    /** 注册回调：只记表，不做任何阻塞操作（登录由调用方触发）。 */
    private final class ServiceCallback implements NetSDKLib.fServiceCallBack {
        @Override
        public int invoke(NetSDKLib.LLong lHandle, String pIp, int wPort,
                          int lCommand, Pointer pParam, int dwParamLen,
                          Pointer dwUserData) {
            try {
                String sn = readSerial(pParam, dwParamLen);
                switch (lCommand) {
                    case NetSDKLib.EM_LISTEN_TYPE.NET_DEV_AUTOREGISTER_RETURN:
                    case NetSDKLib.EM_LISTEN_TYPE.NET_DVR_SERIAL_RETURN:
                    case NetSDKLib.EM_LISTEN_TYPE.NET_DEV_NOTIFY_IP_RETURN:
                        if (sn == null || sn.isBlank()) {
                            log.warn("收到大华注册回调但序列号为空: ip={}:{} cmd={}", pIp, wPort, lCommand);
                            return 0;
                        }
                        table.register(sn, pIp, wPort, "cmd=" + lCommand);
                        registered.incrementAndGet();
                        dispatch(sn, pIp, wPort);
                        return 0;
                    case NetSDKLib.EM_LISTEN_TYPE.NET_DVR_DISCONNECT:
                        /*
                         * ⚠️ 这里**不是**「设备注销」。

                         * SDK 对 -1 的注释是「验证期间设备断线回调」，含义是这条注册连接
                         * 在「待验证」阶段被丢弃了（典型原因：平台没在它上面登录，60 秒验证超时）。
                         * 设备随后立刻换源端口重新注册，所以：
                         *   - 不能 unregister：会把一条 300 毫秒后就重新有效的记录删掉，
                         *     /register/list 会闪一下，日志里每分钟两行 INFO 噪音；
                         *   - 真正的离线判定交给注册表的 expireSeconds 超时。
                         *
                         * 官方 demo 对这个分支也是空实现（只 break）。
                         */
                        log.debug("大华注册连接断开（验证阶段）: sn={} ip={}:{}", sn, pIp, wPort);
                        return 0;
                    default:
                        log.debug("未处理的大华注册回调类型: cmd={} ip={}:{}", lCommand, pIp, wPort);
                        return 0;
                }
            } catch (Throwable t) {
                // 回调里绝不能抛：异常穿过原生栈会直接让进程崩溃
                log.warn("处理大华注册回调失败: {}", t.toString());
                return -1;
            }
        }
    }

    /**
     * 把「设备注册了」派发到工作线程，让上层去登录。
     *
     * <p>绝不能在回调线程里直接调 {@link Handler}：登录是阻塞的网络往返，
     * 阻塞原生回调线程会让后续设备的注册全部排队（见类注释）。
     */
    private void dispatch(String serial, String ip, int port) {
        Handler h = handler;
        if (h == null) {
            return;
        }
        try {
            dispatcher().execute(() -> {
                try {
                    h.onRegistered(serial, ip, port);
                } catch (Throwable t) {
                    log.warn("注册后处理失败: sn={} {}:{} — {}", serial, ip, port, t.toString());
                }
            });
        } catch (Throwable t) {
            // 线程池已关闭（停机中）：丢弃即可，不能把异常抛回原生线程
            log.debug("注册派发被拒绝（监听器已停止）: sn={}", serial);
        }
    }

    /** 懒创建单线程派发器；{@link #stop()} 关掉后再次 {@link #start(String, int)} 会重建。 */
    private ExecutorService dispatcher() {
        ExecutorService d = dispatcher;
        if (d == null || d.isShutdown()) {
            synchronized (this) {
                d = dispatcher;
                if (d == null || d.isShutdown()) {
                    d = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "dahua-register-login");
                        t.setDaemon(true);
                        return t;
                    });
                    dispatcher = d;
                }
            }
        }
        return d;
    }

    /**
     * 从回调参数里读序列号。
     *
     * <p>SDK 对 {@code NET_DVR_SERIAL_RETURN} 的定义是 {@code char* szDevSerial}，
     * 即一个 C 字符串，长度由 {@code dwParamLen} 给出。这里按长度读取再 trim，
     * <b>不能用 {@code Pointer.getString()}</b> —— 那样会依赖 NUL 结尾，
     * 而部分固件会把长度算进去导致尾部带填充字节。
     */
    private static String readSerial(Pointer p, int len) {
        if (p == null || len <= 0) {
            return null;
        }
        byte[] buf = new byte[len];
        p.read(0, buf, 0, len);
        String s = new String(buf, SN_CHARSET).trim();
        // 顺手去掉可能存在的尾部 NUL
        int nul = s.indexOf('\0');
        return nul >= 0 ? s.substring(0, nul).trim() : s;
    }
}
