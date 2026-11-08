package io.github.rurulalanana.dahua;

import com.netsdk.lib.NetSDKLib;
import io.github.rurulalanana.dahua.ext.DahuaExtAccess;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.deviceaccess.ConnectMode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 用 {@code CLIENT_DeleteDevConfig} 删掉 {@code Record} 配置项，让它回到出厂默认。
 *
 * <p>为什么必须用删除而不是覆盖：{@code CLIENT_SetNewDevConfig} 是 <b>merge 语义</b>，
 * payload 里没出现的字段原样保留。之前误把整个响应包写回，
 * 在 {@code table} 里嵌进了 {@code id/params/result/session}，
 * 之后无论怎么写都清不掉（实测显式置 null 也只能把 5 层降到 2 层）。
 * 删除配置项是唯一能彻底清干净的办法。
 *
 * <p>风险：{@code Record} 是录像计划，删除后回到出厂默认（通常是全时段录像）。
 * 这正是我们想要的基线 —— 之后再用正确的 payload 写入。
 */
public final class RecordReset {

    private static DahuaSdk.Session session;
    private static DahuaExtAccess ext;

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
        ext = new DahuaExtAccess();
        say("登录成功");

        int ch = 0;
        say("=== 删除前 ===");
        String before = ext.readConfig(session, "Record", ch);
        say("   TimeSection 次数=" + countOf(before, "\"TimeSection\"")
                + " 长度=" + before.length());

        say("=== CLIENT_DeleteDevConfig(Record) ===");
        NetSDKLib.NET_IN_DELETECFG in = new NetSDKLib.NET_IN_DELETECFG();
        in.szCommand = "Record";
        in.write();
        NetSDKLib.NET_OUT_DELETECFG out = new NetSDKLib.NET_OUT_DELETECFG();
        out.write();
        boolean ok = sdk.sdk().CLIENT_DeleteDevConfig(session.handle(), in, out, 8000);
        out.read();
        say("   返回=" + ok + " nError=" + out.nError + " nRestart=" + out.nRestart
                + (ok ? "" : "  err=0x"
                + Integer.toHexString(sdk.sdk().CLIENT_GetLastError())));

        say("=== 删除后回读 ===");
        try {
            String after = ext.readConfig(session, "Record", ch);
            int nests = countOf(after, "\"TimeSection\"");
            say("   TimeSection 次数=" + nests + " 长度=" + after.length());
            say(nests == 1 ? "★ 配置已恢复干净" : "★ 仍有嵌套");
            say("   内容: " + after.substring(0, Math.min(600, after.length())));
        } catch (Throwable t) {
            say("   回读失败: " + t.getMessage());
        }

        System.exit(0);
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
        System.out.println("[reset] " + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + msg);
        System.out.flush();
    }
}
