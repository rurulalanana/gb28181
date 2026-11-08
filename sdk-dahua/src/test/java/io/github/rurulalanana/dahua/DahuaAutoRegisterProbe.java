package io.github.rurulalanana.dahua;

import com.netsdk.lib.NetSDKLib;
import com.sun.jna.ptr.IntByReference;
import io.github.rurulalanana.dahua.internal.DahuaNativeLibrary;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.deviceaccess.DeviceAccessProvider.ChannelInfo;
import io.github.rurulalanana.deviceaccess.ConnectMode;
import io.github.rurulalanana.deviceaccess.DeviceContext;
import io.github.rurulalanana.deviceaccess.DeviceRegisterTable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 大华设备主动注册 + 全链路探针（开发期手工运行，不进产品代码）。
 *
 * <h3>它解决什么问题</h3>
 * 设计文档 §5.3 的 V0~V11 验证环需要一台真机逐环走通，而平台本身依赖
 * 数据库、媒体服务、前端，起一套成本太高。本类只做<b>原生 SDK 这一层</b>的
 * 端到端验证：监听端口 → 等设备注册 → 登录 → 通道 → 抓图 → 预置位。
 *
 * <h3>为什么不写成 JUnit 用例</h3>
 * 它要长期驻留等设备连上来（设备可能几分钟才注册一次），
 * 与「测试必须快速返回」的约定冲突，放在 surefire 里会把构建卡住。
 *
 * <h3>运行方式</h3>
 * <pre>
 * mvn -o -q test-compile
 * mvn -o -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
 * java -cp "target/classes;target/test-classes;$(cat target/cp.txt)" \
 *      io.github.rurulalanana.dahua.DahuaAutoRegisterProbe
 * </pre>
 *
 * <p>可用环境变量覆盖：{@code DAHUA_LISTEN_PORT}（默认 9500）、
 * {@code DAHUA_USER}、{@code DAHUA_PASS}、{@code DAHUA_SNAP_CHANNEL}（默认 1）、
 * {@code DAHUA_PRESET}（默认 1，设 0 表示跳过预置位）。
 */
public final class DahuaAutoRegisterProbe {

    private static final int LISTEN_PORT = intEnv("DAHUA_LISTEN_PORT", 9500);
    private static final String USER = strEnv("DAHUA_USER", "admin");
    private static final String PASS = strEnv("DAHUA_PASS", "gxhy@123");
    private static final int SNAP_CHANNEL = intEnv("DAHUA_SNAP_CHANNEL", 1);
    private static final int PRESET = intEnv("DAHUA_PRESET", 1);
    private static final Path OUT_DIR = Paths.get(strEnv("DAHUA_SNAP_DIR", "target/dahua-probe"));

    /** 抓图是异步回调，用闩锁把「发指令」和「收到图」串起来。 */
    private static volatile CountDownLatch snapLatch;
    private static volatile byte[] snapBytes;

    /**
     * 回调必须被强引用持有。
     *
     * <p>JNA 回调只被 Java 侧弱引用时会被 GC 回收，之后 SDK 调进来就是野指针，
     * 表现为「抓图指令返回成功但永远收不到图」—— 这是 JNA 最典型的一类坑。
     */
    private static final NetSDKLib.fSnapRev SNAP_CALLBACK = (loginId, buf, len, encode, serial, user) -> {
        if (buf == null || len <= 0) {
            return;
        }
        snapBytes = buf.getByteArray(0, len);
        CountDownLatch latch = snapLatch;
        if (latch != null) {
            latch.countDown();
        }
    };

    private DahuaAutoRegisterProbe() {
    }

    public static void main(String[] args) throws Exception {
        say("平台 = " + System.getProperty("os.name") + "/" + System.getProperty("os.arch")
                + "，原生库目录 = " + DahuaNativeLibrary.platformDir());
        if (!DahuaNativeLibrary.available()) {
            say("原生库不可用：" + DahuaNativeLibrary.diagnose());
            return;
        }

        DahuaSdk sdk = DahuaSdk.get();
        if (!sdk.init()) {
            say("SDK 初始化失败：" + sdk.loadError());
            return;
        }
        say("SDK 初始化成功");

        DahuaRegisterTable table = new DahuaRegisterTable(180_000L);
        DahuaRegisterListener listener = new DahuaRegisterListener(table);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                listener.stop();
            } catch (Throwable ignored) {
                // 退出路径不抛
            }
        }));

        listener.start("0.0.0.0", LISTEN_PORT);
        say("已监听 0.0.0.0:" + listener.port() + "，等待设备主动注册…");

        Set<String> handled = new HashSet<>();
        long lastTick = 0;
        while (true) {
            List<DeviceRegisterTable.RegisteredDevice> devices = table.list();
            long now = System.currentTimeMillis();
            if (now - lastTick > 10_000) {
                lastTick = now;
                say("心跳：已注册 " + devices.size() + " 台，累计注册次数 " + listener.registeredCount());
            }
            for (DeviceRegisterTable.RegisteredDevice dev : devices) {
                if (handled.contains(dev.serial())) {
                    continue;
                }
                handled.add(dev.serial());
                say("──────── 收到注册：" + dev.serial() + " @ " + dev.ip() + ":" + dev.port()
                        + "（来源端口 " + dev.port() + "）");
                try {
                    runChain(sdk, dev);
                } catch (Throwable t) {
                    say("全链路失败：" + t.getClass().getSimpleName() + ": " + t.getMessage());
                    t.printStackTrace(System.out);
                    // 允许 60 秒后重试同一台设备（设备可能正在起服务）
                    new Thread(() -> {
                        try {
                            Thread.sleep(60_000L);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        handled.remove(dev.serial());
                    }, "probe-retry").start();
                }
            }
            Thread.sleep(1_000L);
        }
    }

    /** 一台设备的完整闭环：登录 → 通道 → 抓图 → 预置位。 */
    private static void runChain(DahuaSdk sdk, DeviceRegisterTable.RegisteredDevice dev) throws Exception {
        // ① 登录（SERVER 模式：地址取注册回调里的实时来源地址）
        DahuaSdk.Session session = sdk.session(dev.ip(), dev.port(), USER, PASS,
                ConnectMode.SERVER, dev.serial());
        if (session == null) {
            say("① 登录失败（账号/密码或设备侧主动注册配置需核对）");
            return;
        }
        say("① 登录成功：handle=" + session.handle() + " valid=" + session.valid()
                + " 通道数=" + session.channelCount());

        // ② 通道目录
        DeviceContext ctx = new DeviceContext(dev.serial(), dev.ip(), dev.port(),
                USER, PASS, ConnectMode.SERVER, dev.serial(), 0);
        List<ChannelInfo> channels = new DahuaDeviceAccessProvider().listChannels(ctx);
        say("② 通道目录：" + channels.size() + " 个 → " + channels);

        // ③ 抓图（同步请求一帧，走全局抓图回调）
        doSnap(sdk, session, SNAP_CHANNEL);

        // ④ 预置位（转过去，验证云台通路）
        if (PRESET > 0) {
            doGotoPreset(sdk, session, SNAP_CHANNEL, PRESET);
        } else {
            say("④ 预置位：已跳过（DAHUA_PRESET=0）");
        }
    }

    private static void doSnap(DahuaSdk sdk, DahuaSdk.Session session, int channel) {
        try {
            sdk.sdk().CLIENT_SetSnapRevCallBack(SNAP_CALLBACK, null);
            NetSDKLib.SNAP_PARAMS params = new NetSDKLib.SNAP_PARAMS();
            params.Channel = channel;
            params.Quality = 6;   // 1~6，取最高
            params.ImageSize = 0; // 0=QCIF/原始尺寸，具体由设备决定
            params.mode = 0;      // 0=请求一帧
            params.CmdSerial = (int) (System.currentTimeMillis() % 60000);
            params.write();

            CountDownLatch latch = new CountDownLatch(1);
            snapBytes = null;
            snapLatch = latch;

            boolean ok = sdk.sdk().CLIENT_SnapPictureEx(
                    session.handle(), params, new IntByReference(0));
            if (!ok) {
                say("③ 抓图：指令被拒，lastError=" + sdk.sdk().CLIENT_GetLastError()
                        + "（通道 " + channel + "）");
                return;
            }
            boolean got = latch.await(10, TimeUnit.SECONDS);
            if (!got || snapBytes == null) {
                say("③ 抓图：指令成功但 10 秒内没收到图（检查通道号是否为视频通道）");
                return;
            }
            Files.createDirectories(OUT_DIR);
            String name = "snap-" + session.serial() + "-ch" + channel + "-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss")) + ".jpg";
            Path file = OUT_DIR.resolve(name);
            Files.write(file, snapBytes);
            say("③ 抓图成功：" + snapBytes.length + " 字节 → " + file.toAbsolutePath());
        } catch (Throwable t) {
            say("③ 抓图异常：" + t);
        } finally {
            snapLatch = null;
        }
    }

    private static void doGotoPreset(DahuaSdk sdk, DahuaSdk.Session session, int channel, int preset) {
        try {
            NetSDKLib.PTZ_CONTROL_GOTOPRESET param = new NetSDKLib.PTZ_CONTROL_GOTOPRESET();
            param.nPresetIndex = preset;
            param.stuSpeed.fPositionX = 1.0f;
            param.stuSpeed.fPositionY = 1.0f;
            param.stuSpeed.fZoom = 1.0f;
            param.write();

            // dwStop: 0=开始动作，1=停止
            boolean ok = sdk.sdk().CLIENT_DHPTZControlEx2(
                    session.handle(), channel, NetSDKLib.NET_EXTPTZ_ControlType.NET_EXTPTZ_GOTOPRESET,
                    0, 0, 0, 0, param.getPointer());
            say("④ 预置位 " + preset + "（通道 " + channel + "）："
                    + (ok ? "下发成功，球机应开始转动"
                          : "下发失败，lastError=" + sdk.sdk().CLIENT_GetLastError()));
        } catch (Throwable t) {
            say("④ 预置位异常：" + t);
        }
    }

    private static void say(String msg) {
        System.out.println("[probe] " + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + msg);
        System.out.flush();
    }

    private static String strEnv(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static int intEnv(String key, int def) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
