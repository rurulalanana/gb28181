package io.github.rurulalanana.dahua;

import io.github.rurulalanana.dahua.ext.DahuaExtAccess;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.deviceaccess.ConnectMode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 录像计划（{@code Record} 配置项）读写验证。
 *
 * <p>目标：确认「设备端录像开关」能否用改录像计划实现。
 * 国标 {@code Record}/{@code StopRecord} 的语义就是让设备侧开始/停止录像，
 * 而大华 IPC 的录像由 <b>录像计划</b> 决定 —— 直接改计划里每天的时间段即可。
 *
 * <p>判定不看返回值：{@code CLIENT_SetNewDevConfig} 返回 true 也不代表生效，
 * 必须<b>回读比对</b>。
 */
public final class RecordPlanProbe {

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
        say("=== 1. 完整读 Record 配置 ===");
        String original;
        try {
            original = ext.readConfig(session, "Record", ch);
            say("   长度=" + original.length());
            say("   全文:");
            say(original);
        } catch (Throwable t) {
            say("   读取失败: " + t.getMessage());
            return;
        }

        say("=== 2. 解析 TimeSection 结构 ===");
        int tsAt = original.indexOf("\"TimeSection\"");
        if (tsAt < 0) {
            say("   没有 TimeSection 字段，无法用计划控制录像");
            return;
        }
        say("   TimeSection 片段: "
                + original.substring(tsAt, Math.min(tsAt + 600, original.length())));

        say("=== 3. 尝试写入：把第 1 天改成全天录像 ===");
        /*
         * 格式是 [["6 00:00:00-23:59:59","0 00:00:00-23:59:59",...], ...]
         * 每组 7 个字符串，"<星期> <起>-<止>"，星期 1~7，0 表示不录。
         * 先只改第 1 组第 1 天，最小改动验证写入链路。
         */
        String modified = original.replaceFirst(
                "\\[\"6 00:00:00-23:59:59\"",
                "[\"1 00:00:00-23:59:59\"");
        if (modified.equals(original)) {
            say("   替换未命中，实际格式与预期不同");
            String seg = original.substring(tsAt, Math.min(tsAt + 200, original.length()));
            say("   实际片段: " + seg);
        } else {
            try {
                boolean restart = ext.writeConfig(session, "Record", ch, modified);
                say("   写入返回: 需要重启=" + restart);
            } catch (Throwable t) {
                say("   写入失败: " + t.getMessage());
            }
            say("=== 4. 回读验证 ===");
            try {
                String after = ext.readConfig(session, "Record", ch);
                boolean changed = !after.equals(original);
                say("   内容有变化: " + changed);
                if (changed) {
                    int at = after.indexOf("\"TimeSection\"");
                    say("   新片段: " + after.substring(at,
                            Math.min(at + 300, after.length())));
                }
            } catch (Throwable t) {
                say("   回读失败: " + t.getMessage());
            }
            say("=== 5. 恢复原配置 ===");
            try {
                ext.writeConfig(session, "Record", ch, original);
                String restored = ext.readConfig(session, "Record", ch);
                say("   已恢复: " + restored.equals(original));
            } catch (Throwable t) {
                say("   恢复失败: " + t.getMessage());
            }
        }

        System.exit(0);
    }

    private static String arg(String[] a, int i, String def) {
        return a.length > i && a[i] != null && !a[i].isBlank() ? a[i] : def;
    }

    private static void say(String msg) {
        System.out.println("[rec] " + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + msg);
        System.out.flush();
    }
}
