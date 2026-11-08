package io.github.rurulalanana.dahua;

import io.github.rurulalanana.dahua.ext.DahuaExtAccess;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.deviceaccess.ConnectMode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 尝试用「显式全量对象」覆盖掉嵌套残留。
 *
 * <p>上一轮写回干净对象后回读仍有 5 个 TimeSection，说明
 * {@code CLIENT_SetNewDevConfig} 是 <b>merge 语义</b>而不是 replace：
 * 只覆盖 payload 里出现的字段，没出现的旧字段（{@code id/params/result/session}）
 * 原样保留在设备里。
 *
 * <p>对策：payload 里把那些多余字段<b>显式置空</b>，
 * 让设备用空值覆盖掉旧内容。
 */
public final class RecordFix2 {

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

        // 用标准字段重建，并显式清空多余字段
        String timeSection = "[[\"6 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\"],"
                + "[\"6 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\"],"
                + "[\"6 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\"],"
                + "[\"6 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\"],"
                + "[\"6 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\"],"
                + "[\"6 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\"],"
                + "[\"6 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\"],"
                + "[\"6 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\","
                + "\"0 00:00:00-23:59:59\",\"0 00:00:00-23:59:59\"]]";

        say("=== 方案 A：显式置空多余字段 ===");
        String payloadA = "{\"params\":{\"table\":{"
                + "\"Format\":\"dav\",\"HolidayEnable\":false,\"PreRecord\":5,"
                + "\"Redundancy\":false,\"SaveAudio\":true,\"SnapShot\":false,"
                + "\"Stream\":0,"
                + "\"id\":0,"
                + "\"params\":null,"
                + "\"result\":null,"
                + "\"session\":0,"
                + "\"TimeSection\":" + timeSection
                + "}}}";
        say("   payload 长度=" + payloadA.length());
        writeAndCheck(ch, payloadA, "A");

        say("=== 方案 B：只写业务字段（不置空）===");
        String payloadB = "{\"params\":{\"table\":{"
                + "\"Format\":\"dav\",\"HolidayEnable\":false,\"PreRecord\":5,"
                + "\"Redundancy\":false,\"SaveAudio\":true,\"SnapShot\":false,"
                + "\"Stream\":0,"
                + "\"TimeSection\":" + timeSection
                + "}}}";
        writeAndCheck(ch, payloadB, "B");

        say("=== 最终检查 ===");
        String after = ext.readConfig(session, "Record", ch);
        int nests = countOf(after, "\"TimeSection\"");
        say("TimeSection 出现次数=" + nests);
        say(nests == 1 ? "★ 配置已恢复正常" : "★ 仍有 " + nests + " 处嵌套");
        say("完整内容:");
        say(after);

        System.exit(0);
    }

    private static void writeAndCheck(int ch, String payload, String tag) {
        try {
            ext.writeConfig(session, "Record", ch, payload);
            String after = ext.readConfig(session, "Record", ch);
            say("   方案 " + tag + " 写后 TimeSection 次数="
                    + countOf(after, "\"TimeSection\"")
                    + " 长度=" + after.length());
        } catch (Throwable t) {
            say("   方案 " + tag + " 失败: " + t.getMessage());
        }
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
        System.out.println("[fix2] " + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + msg);
        System.out.flush();
    }
}
