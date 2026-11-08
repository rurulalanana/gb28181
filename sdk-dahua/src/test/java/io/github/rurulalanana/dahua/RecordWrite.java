package io.github.rurulalanana.dahua;

import io.github.rurulalanana.dahua.ext.DahuaExtAccess;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.deviceaccess.ConnectMode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 配置项删除后重新写入干净值，并验证写入是否生效。
 *
 * <p>关键结论（真机实测）：
 * <ul>
 *   <li>{@code CLIENT_GetNewDevConfig} 返回<b>完整响应包</b>：
 *       {@code {"id":..,"params":{"table":{业务}},"result":true,"session":..}}</li>
 *   <li>{@code CLIENT_SetNewDevConfig} 只接受<b>业务层</b>，
 *       且是 <b>merge 语义</b>（没写的字段保留旧值）</li>
 *   <li>所以写回时 payload 必须是 {@code {"params":{"table":{业务}}}}
 *       —— 只放业务字段，别把 id/result/session 带进去</li>
 * </ul>
 */
public final class RecordWrite {

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

        say("=== 1. 写一个最小配置（只 1 天录像）===");
        String payload = "{\"params\":{\"table\":{"
                + "\"Format\":\"dav\",\"HolidayEnable\":false,\"PreRecord\":5,"
                + "\"Redundancy\":false,\"SaveAudio\":true,\"SnapShot\":false,"
                + "\"Stream\":0,"
                + "\"TimeSection\":["
                + timeSectionFor(3) + ","
                + timeSectionFor(0) + ","
                + timeSectionFor(0) + ","
                + timeSectionFor(0) + ","
                + timeSectionFor(0) + ","
                + timeSectionFor(0) + ","
                + timeSectionFor(0) + ","
                + timeSectionFor(0)
                + "]}}}";
        say("   payload 长度=" + payload.length());
        try {
            boolean restart = ext.writeConfig(session, "Record", ch, payload);
            say("   写入返回: 需要重启=" + restart);
        } catch (Throwable t) {
            say("   写入失败: " + t.getMessage());
            return;
        }

        say("=== 2. 回读验证 ===");
        String after = ext.readConfig(session, "Record", ch);
        int nests = countOf(after, "\"TimeSection\"");
        say("   TimeSection 次数=" + nests + "（1 = 干净）");
        say("   长度=" + after.length());
        say("   内容: " + after.substring(0, Math.min(500, after.length())));

        say("=== 3. 改成 2 个时段，验证写入真的生效 ===");
        String payload2 = payload.replace("\"3 00:00:00-23:59:59\"",
                "\"5 00:00:00-23:59:59\"");
        try {
            ext.writeConfig(session, "Record", ch, payload2);
            String after2 = ext.readConfig(session, "Record", ch);
            boolean changed = after2.contains("\"5 00:00:00-23:59:59\"")
                    && !after2.contains("\"3 00:00:00-23:59:59\"");
            say("   星期3 → 星期5 生效: " + changed);
            say("   " + (changed ? "★ 录像计划写入确实生效" : "★ 写入被忽略"));
        } catch (Throwable t) {
            say("   第二次写入失败: " + t.getMessage());
        }

        System.exit(0);
    }

    /** 生成一组 6 个时段；weekday=0 表示全部不录。 */
    private static String timeSectionFor(int weekday) {
        String on = weekday > 0 ? weekday + " 00:00:00-23:59:59" : "0 00:00:00-23:59:59";
        String off = "0 00:00:00-23:59:59";
        return "[\"" + on + "\",\"" + off + "\",\"" + off + "\",\""
                + off + "\",\"" + off + "\",\"" + off + "\"]";
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
        System.out.println("[write] " + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + msg);
        System.out.flush();
    }
}
