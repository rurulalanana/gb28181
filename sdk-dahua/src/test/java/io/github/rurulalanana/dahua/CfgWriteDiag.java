package io.github.rurulalanana.dahua;

import com.netsdk.lib.NetSDKLib;
import com.sun.jna.ptr.IntByReference;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.deviceaccess.ConnectMode;

import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 诊断 {@code CLIENT_SetNewDevConfig} 的失败原因。
 *
 * <p>上一轮写 {@code Record} 直接失败，但没打印错误码 —— 这次把
 * {@code nError} 与 {@code CLIENT_GetLastError} 都打出来，
 * 并逐个试几种 payload 形态，找出设备接受哪一种。
 */
public final class CfgWriteDiag {

    private static final Charset GBK = Charset.forName("GBK");
    private static DahuaSdk.Session session;

    public static void main(String[] args) throws Exception {
        String host = arg(args, 0, "192.168.10.156");
        int port = Integer.parseInt(arg(args, 1, "37777"));
        String user = arg(args, 2, "admin");
        String pass = arg(args, 3, "gxhy@123");
        String serial = arg(args, 4, "10001");

        DahuaSdk sdk = DahuaSdk.get();
        if (!sdk.init()) {
            say("SDK 初始化失败");
            return;
        }
        session = sdk.session(host, port, user, pass, ConnectMode.CLIENT, serial);
        if (session == null) {
            say("登录失败");
            return;
        }
        say("登录成功");

        String ts = "[[\"3 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\"]]";

        String[][] variants = {
                {"A 完整 params.table", "{\"params\":{\"table\":{\"Format\":\"dav\","
                        + "\"HolidayEnable\":false,\"PreRecord\":5,\"Redundancy\":false,"
                        + "\"SaveAudio\":true,\"SnapShot\":false,\"Stream\":0,"
                        + "\"TimeSection\":" + ts + "}}}"},
                {"B 只 table", "{\"table\":{\"Format\":\"dav\",\"HolidayEnable\":false,"
                        + "\"PreRecord\":5,\"Redundancy\":false,\"SaveAudio\":true,"
                        + "\"SnapShot\":false,\"Stream\":0,\"TimeSection\":" + ts + "}}"},
                {"C 裸业务字段", "{\"Format\":\"dav\",\"HolidayEnable\":false,"
                        + "\"PreRecord\":5,\"Redundancy\":false,\"SaveAudio\":true,"
                        + "\"SnapShot\":false,\"Stream\":0,\"TimeSection\":" + ts + "}"},
                {"D 只 TimeSection", "{\"params\":{\"table\":{\"TimeSection\":" + ts + "}}}"},
        };

        for (String[] v : variants) {
            say("=== " + v[0] + "（长度 " + v[1].length() + "）===");
            set("Record", 0, v[1]);
            // 每次写完都试读，确认是否被接受
            String got = get("Record", 0);
            say("   读回: " + (got == null ? "读不到"
                    : "长度=" + got.length() + " TimeSection次数=" + countOf(got, "\"TimeSection\"")));
            if (got != null && countOf(got, "\"TimeSection\"") == 1) {
                say("   ★ 这个形态被接受且干净");
                say("   " + got.substring(0, Math.min(400, got.length())));
                break;
            }
        }

        System.exit(0);
    }

    private static void set(String command, int ch, String json) {
        byte[] data = json.getBytes(GBK);
        IntByReference error = new IntByReference(0);
        IntByReference restart = new IntByReference(0);
        boolean ok = DahuaSdk.get().sdk().CLIENT_SetNewDevConfig(session.handle(),
                command, ch, data, data.length, error, restart, 8000);
        int lastErr = DahuaSdk.get().sdk().CLIENT_GetLastError();
        say("   SetNewDevConfig=" + ok + " nError=" + error.getValue()
                + " nRestart=" + restart.getValue()
                + " lastError=0x" + Integer.toHexString(lastErr));
    }

    private static String get(String command, int ch) {
        byte[] buffer = new byte[64 * 1024];
        IntByReference error = new IntByReference(0);
        boolean ok = DahuaSdk.get().sdk().CLIENT_GetNewDevConfig(session.handle(),
                command, ch, buffer, buffer.length, error, 8000, null);
        if (!ok) {
            say("   GetNewDevConfig 失败 nError=" + error.getValue()
                    + " lastError=0x"
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
        System.out.println("[diag] " + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + msg);
        System.out.flush();
    }
}
