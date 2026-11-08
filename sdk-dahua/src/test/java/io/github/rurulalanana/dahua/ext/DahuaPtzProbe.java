package io.github.rurulalanana.dahua.ext;

import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.deviceaccess.ConnectMode;

/**
 * 云台扩展能力的真机探针（开发期手工运行，不进产品）。
 *
 * <p>目的：在写 UI 之前先确认「这台设备到底支持哪几项」。云台的 11 个子功能
 * 在不同型号上开关差异极大，凭 SDK 文档写出来的代码经常是「调用了但没反应」，
 * 必须逐项打到设备上拿到 true/false 与错误码。
 *
 * <h3>运行</h3>
 * <pre>
 * mvn -o -q test-compile
 * mvn -o -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
 * java -cp "target/classes;target/test-classes;$(cat target/cp.txt)" \
 *      io.github.rurulalanana.dahua.ext.DahuaPtzProbe
 * </pre>
 */
public final class DahuaPtzProbe {

    private static final String HOST = System.getProperty("dahua.host", "192.168.10.156");
    private static final int PORT = Integer.getInteger("dahua.port", 37777);
    private static final String USER = System.getProperty("dahua.user", "admin");
    private static final String PASS = System.getProperty("dahua.pass", "gxhy@123");
    private static final int CH = Integer.getInteger("dahua.channel", 1);

    public static void main(String[] args) {
        DahuaSdk sdk = DahuaSdk.get();
        if (!sdk.init()) {
            log("SDK 初始化失败: " + sdk.loadError());
            return;
        }
        DahuaSdk.Session session = sdk.session(HOST, PORT, USER, PASS, ConnectMode.CLIENT, null);
        if (session == null) {
            log("登录失败 " + HOST + ":" + PORT);
            return;
        }
        log("登录成功: " + HOST + ":" + PORT + " 通道数=" + session.channelCount());
        DahuaExtAccess ext = new DahuaExtAccess();

        log("──────── 配置读取（非破坏性）────────");
        for (String cmd : new String[]{"PtzTour", "PtzAutoMovement", "PtzPreset", "PtzLimit"}) {
            try {
                String json = ext.readConfig(session, cmd, CH);
                log("READ  " + cmd + "  OK  " + json.length() + " 字符 → "
                        + json.substring(0, Math.min(220, json.length())).replaceAll("\\s+", " "));
            } catch (Throwable t) {
                log("READ  " + cmd + "  FAIL " + brief(t));
            }
        }

        log("──────── 云台动作（会真的转动镜头）────────");
        act("巡航-加入预置位1到线1", () -> DahuaPtzExt.cruiseAddPoint(session, CH, 1, 1));
        act("巡航-启动线1", () -> DahuaPtzExt.cruiseRun(session, CH, 1, true));
        sleep(4000);
        act("巡航-停止线1", () -> DahuaPtzExt.cruiseRun(session, CH, 1, false));

        act("巡迹-开始录制模式1", () -> DahuaPtzExt.patternStartRecord(session, CH, 1));
        sleep(1500);
        act("巡迹-结束录制模式1", () -> DahuaPtzExt.patternStopRecord(session, CH, 1));
        act("巡迹-运行模式1", () -> DahuaPtzExt.patternRun(session, CH, 1, true));
        sleep(2500);
        act("巡迹-停止模式1", () -> DahuaPtzExt.patternRun(session, CH, 1, false));

        act("限位-设左边界", () -> DahuaPtzExt.setLeftLimit(session, CH, 1));
        act("限位-设右边界", () -> DahuaPtzExt.setRightLimit(session, CH, 1));
        act("线扫-开始", () -> DahuaPtzExt.lineScan(session, CH, 1, true));
        sleep(2500);
        act("线扫-停止", () -> DahuaPtzExt.lineScan(session, CH, 1, false));
        act("水平巡航-开始", () -> DahuaPtzExt.panCruise(session, CH, true));
        sleep(2000);
        act("水平巡航-停止", () -> DahuaPtzExt.panCruise(session, CH, false));

        if (Boolean.getBoolean("dahua.reboot")) {
            act("云台重启（整机）", () -> DahuaPtzExt.reboot(session));
        } else {
            log("SKIP  云台重启（加 -Ddahua.reboot=true 才执行，会让设备离线约 1 分钟）");
        }

        log("完成。");
        System.exit(0);
    }

    private interface Action {
        boolean run() throws Exception;
    }

    private static void act(String name, Action a) {
        try {
            boolean ok = a.run();
            log((ok ? "PASS  " : "FAIL  ") + name);
        } catch (Throwable t) {
            log("FAIL  " + name + " | " + brief(t));
        }
    }

    private static String brief(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void log(String s) {
        System.out.println("[ptz] " + s);
        System.out.flush();
    }

    private DahuaPtzProbe() {
    }
}
