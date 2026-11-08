package io.github.rurulalanana.dahua;

import com.netsdk.lib.NetSDKLib;
import com.sun.jna.ptr.IntByReference;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.deviceaccess.ConnectMode;

import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 恢复被删除的 {@code Record} 配置项。
 *
 * <h3>发生了什么</h3>
 * 探测时调了 {@code CLIENT_DeleteDevConfig("Record")}，本意是清掉误写进去的
 * 嵌套字段，结果设备把整条配置项删掉了 —— 之后 {@code Get/SetNewDevConfig}
 * 全部返回 {@code 0x80000015 (NET_RETURN_DATA_ERROR)}。
 *
 * <h3>恢复策略（从轻到重）</h3>
 * <ol>
 *   <li>重新登录后重读 —— 有些型号在会话重建时会补齐默认配置</li>
 *   <li>设备重启 —— {@code CTRLTYPE_CTRL_REBOOT}，启动时会重建默认配置</li>
 * </ol>
 *
 * <p><b>重启会断流几秒</b>，所以先只做第 1 步；第 2 步要人确认。
 */
public final class RecordRecover {

    private static final Charset GBK = Charset.forName("GBK");

    public static void main(String[] args) throws Exception {
        String host = arg(args, 0, "192.168.10.156");
        int port = Integer.parseInt(arg(args, 1, "37777"));
        String user = arg(args, 2, "admin");
        String pass = arg(args, 3, "gxhy@123");
        String serial = arg(args, 4, "10001");
        boolean reboot = args.length > 5 && "reboot".equalsIgnoreCase(args[5]);

        DahuaSdk sdk = DahuaSdk.get();
        if (!sdk.init()) {
            say("SDK 初始化失败");
            return;
        }

        say("=== 第 1 步：全新登录后重读 ===");
        // 强制新建会话（先登出旧的）
        sdk.logout(host, port);
        Thread.sleep(1000);
        DahuaSdk.Session session = sdk.session(host, port, user, pass,
                ConnectMode.CLIENT, serial);
        if (session == null) {
            say("重新登录失败");
            return;
        }
        say("重新登录成功 句柄=" + session.handle().longValue());

        String got = get(session, "Record", 0);
        if (got != null) {
            say("★ 配置已恢复，长度=" + got.length());
            say("  TimeSection 次数=" + countOf(got, "\"TimeSection\""));
            say("  内容: " + got.substring(0, Math.min(400, got.length())));
            say("=== 尝试写回正确配置 ===");
            tryWrite(session, 0);
            System.exit(0);
        }

        say("重读仍失败，说明配置项确实被删掉了");
        if (!reboot) {
            say("=== 未执行重启（需人工确认）===");
            say("要重启设备恢复默认配置，加参数 reboot：");
            say("  java ... RecordRecover <host> <port> <user> <pass> <sn> reboot");
            System.exit(0);
        }

        say("=== 第 2 步：重启设备 ===");
        boolean ok = sdk.sdk().CLIENT_ControlDevice(session.handle(),
                NetSDKLib.CtrlType.CTRLTYPE_CTRL_REBOOT, null, 5000);
        say("   重启指令 → " + ok + (ok ? "" : "  err=0x"
                + Integer.toHexString(sdk.sdk().CLIENT_GetLastError())));
        if (!ok) {
            System.exit(0);
        }

        say("=== 等待设备重启（60 秒）===");
        for (int i = 1; i <= 12; i++) {
            Thread.sleep(5000);
            say("   " + (i * 5) + " 秒…");
        }

        say("=== 第 3 步：重启后重连并检查 ===");
        sdk.logout(host, port);
        DahuaSdk.Session after = sdk.session(host, port, user, pass,
                ConnectMode.CLIENT, serial);
        if (after == null) {
            say("重启后登录失败，设备可能还没起来，请稍后重试");
            System.exit(0);
        }
        say("重连成功");
        String restored = get(after, "Record", 0);
        if (restored == null) {
            say("★ 配置仍未恢复 —— 需要在设备 Web 界面手工重设录像计划");
            System.exit(0);
        }
        say("★ 配置已恢复，长度=" + restored.length());
        say("  TimeSection 次数=" + countOf(restored, "\"TimeSection\""));
        say("  内容: " + restored.substring(0, Math.min(500, restored.length())));

        System.exit(0);
    }

    private static void tryWrite(DahuaSdk.Session session, int ch) {
        String payload = "{\"params\":{\"table\":{"
                + "\"Format\":\"dav\",\"HolidayEnable\":false,\"PreRecord\":5,"
                + "\"Redundancy\":false,\"SaveAudio\":true,\"SnapShot\":false,"
                + "\"Stream\":0,\"TimeSection\":["
                + "[\"1 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\"],"
                + "[\"1 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\"],"
                + "[\"1 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\"],"
                + "[\"1 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\"],"
                + "[\"1 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\"],"
                + "[\"1 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\"],"
                + "[\"1 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\"],"
                + "[\"1 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\"]]}}}";
        byte[] data = payload.getBytes(GBK);
        IntByReference error = new IntByReference(0);
        IntByReference restart = new IntByReference(0);
        boolean ok = DahuaSdk.get().sdk().CLIENT_SetNewDevConfig(session.handle(),
                "Record", ch, data, data.length, error, restart, 8000);
        say("   写入 → " + ok + " nError=" + error.getValue()
                + " nRestart=" + restart.getValue());
        if (ok) {
            String after = get(session, "Record", ch);
            say("   回读长度=" + (after == null ? 0 : after.length())
                    + " TimeSection次数=" + (after == null ? 0 : countOf(after, "\"TimeSection\"")));
        }
    }

    private static String get(DahuaSdk.Session session, String command, int ch) {
        byte[] buffer = new byte[64 * 1024];
        IntByReference error = new IntByReference(0);
        boolean ok = DahuaSdk.get().sdk().CLIENT_GetNewDevConfig(session.handle(),
                command, ch, buffer, buffer.length, error, 8000, null);
        if (!ok) {
            say("   读取失败 nError=" + error.getValue() + " lastError=0x"
                    + Integer.toHexString(DahuaSdk.get().sdk().CLIENT_GetLastError()));
            return null;
        }
        int end = 0;
        while (end < buffer.length && buffer[end] != 0) {
            end++;
        }
        return new String(buffer, 0, end, GBK);
    }

    private static int countOf(String s, String sub) {
        int n = 0;
        int at = 0;
        while ((at = s.indexOf(sub, at)) >= 0) {
            n++;
            at += sub.length();
        }
        return n;
    }

    private static String arg(String[] a, int i, String def) {
        return a.length > i && a[i] != null && !a[i].isBlank() ? a[i] : def;
    }

    private static void say(String msg) {
        System.out.println("[recov] " + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + msg);
        System.out.flush();
    }
}
