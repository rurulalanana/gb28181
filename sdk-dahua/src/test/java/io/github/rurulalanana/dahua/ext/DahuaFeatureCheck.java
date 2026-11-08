package io.github.rurulalanana.dahua.ext;

import io.github.rurulalanana.dahua.DahuaRegisterListener;
import io.github.rurulalanana.dahua.DahuaRegisterTable;
import io.github.rurulalanana.dahua.internal.DahuaNativeLibrary;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.deviceaccess.DeviceRegisterTable;

import java.nio.file.Paths;
import java.util.List;

/**
 * 大华能力的<b>真机体检</b>（开发期手工运行）。
 *
 * <p>它不做业务，只回答一个问题：<b>这台设备到底支持哪几项</b>。
 * 结果直接用来填交付矩阵的「已验证 / 型号不支持 / 未实现」三态，
 * 避免把"代码写了"当成"功能可用"。
 *
 * <h3>运行方式</h3>
 * <pre>
 * mvn -o -q test-compile
 * mvn -o -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
 * java -cp "target/classes;target/test-classes;$(cat target/cp.txt)" \
 *      io.github.rurulalanana.dahua.ext.DahuaFeatureCheck
 * </pre>
 */
public final class DahuaFeatureCheck {

    private static final int PORT = 9500;
    private static final String USER = "admin";
    private static final String PASS = "gxhy@123";

    public static void main(String[] args) throws Exception {
        log("平台 " + System.getProperty("os.name") + "/" + System.getProperty("os.arch")
                + "，原生库 " + DahuaNativeLibrary.platformDir());
        DahuaSdk sdk = DahuaSdk.get();
        if (!sdk.init()) {
            log("SDK 初始化失败: " + sdk.loadError());
            return;
        }

        DahuaRegisterTable table = new DahuaRegisterTable(180_000L);
        DahuaRegisterListener listener = new DahuaRegisterListener(table);
        listener.start("0.0.0.0", PORT);
        log("监听 " + PORT + "，等待设备注册…");

        DeviceRegisterTable.RegisteredDevice dev = waitForDevice(table, 120_000L);
        if (dev == null) {
            log("120 秒内未收到注册，体检中止");
            return;
        }
        log("设备 " + dev.serial() + " @ " + dev.ip() + ":" + dev.port());

        DahuaSdk.Session session = sdk.session(dev.ip(), dev.port(), USER, PASS,
                io.github.rurulalanana.deviceaccess.ConnectMode.SERVER, dev.serial());
        if (session == null) {
            log("登录失败，体检中止");
            return;
        }
        log("登录成功，通道数 " + session.channelCount());

        DahuaExtAccess ext = new DahuaExtAccess();

        // ① 抓图
        check("抓图 CLIENT_ManualSnap", () -> {
            byte[] jpeg = ext.snapshot(session, 1);
            boolean isJpeg = jpeg.length > 3 && (jpeg[0] & 0xFF) == 0xFF && (jpeg[1] & 0xFF) == 0xD8;
            String file = ext.snapshotToFile(session, 1, Paths.get("target/dahua-probe")).toString();
            return jpeg.length + " 字节，JPEG头=" + isJpeg + "，落盘=" + file;
        });

        // ② 预置位清单
        check("预置位清单 PtzPreset", () -> {
            List<DahuaExtAccess.Preset> presets = ext.listPresets(session, 1);
            return presets.size() + " 个 → " + presets;
        });

        // ③ 转到预置位
        check("转到预置位 1", () -> String.valueOf(ext.gotoPreset(session, 1, 1)));

        // ④ 设置预置位 8（保存当前位置）
        check("设置预置位 8", () -> String.valueOf(ext.setPreset(session, 1, 8)));

        // ⑤ 读图像配置
        check("读配置 VideoInOptions", () -> {
            String json = ext.readConfig(session, "VideoInOptions", 1);
            return json.length() + " 字符，前 120: "
                    + json.substring(0, Math.min(120, json.length()));
        });

        // ⑥ 报警订阅
        check("报警订阅 StartListenEx", () -> {
            ext.subscribeAlarm(session, e -> log("  [报警] cmd=0x"
                    + Integer.toHexString(e.command()) + " 来自 " + e.deviceIp()
                    + " 长度=" + e.rawBytes()));
            return "已订阅，等待 8 秒观察事件";
        });
        Thread.sleep(8_000);
        ext.unsubscribeAlarm(session);

        // ⑦ 局域网发现（受限：本机与被测设备不同网段时收不到组播应答）
        check("局域网搜索 StartSearchDevices", () -> {
            List<DahuaExtAccess.Discovered> found = ext.discoverLan(null, 5_000L);
            return found.size() + " 台 → " + found;
        });

        listener.stop();
        log("体检结束");
        System.exit(0);
    }

    // ==================== 框架 ====================

    private interface Action {
        String run() throws Exception;
    }

    private static void check(String name, Action action) {
        try {
            String detail = action.run();
            log("PASS  " + name + " | " + detail);
        } catch (DahuaSdkException e) {
            log("FAIL  " + name + " | " + e.getMessage());
        } catch (Throwable t) {
            log("FAIL  " + name + " | " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static DeviceRegisterTable.RegisteredDevice waitForDevice(DahuaRegisterTable table,
                                                                     long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<DeviceRegisterTable.RegisteredDevice> list = table.list();
            if (!list.isEmpty()) {
                return list.get(0);
            }
            Thread.sleep(500L);
        }
        return null;
    }

    private static void log(String msg) {
        System.out.println("[check] " + msg);
        System.out.flush();
    }

    private DahuaFeatureCheck() {
    }
}
