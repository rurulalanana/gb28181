package io.github.rurulalanana.dahua;

import io.github.rurulalanana.dahua.ext.DahuaExtAccess;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.deviceaccess.ConnectMode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 录像计划精确验证。
 *
 * <p><b>关键坑</b>：{@code Record} 配置的 JSON 里带 {@code "session":<每次都变>}
 * 与 {@code "id":<设备内部号>}，整串比较永远「有变化」——
 * 必须只比 {@code params.table} 这一段，否则会误判成写入成功。
 *
 * <p>结构（真机实测）：
 * <pre>
 * "TimeSection": [
 *   ["6 00:00:00-23:59:59", "0 00:00:00-23:59:59", ...],   ← 通道 0 的 6 个时段
 *   [...],                                                  ← 通道 1
 *   ... 共 8 组（8 个通道位）
 * ]
 * </pre>
 * 每段 {@code "<星期> <起>-<止>"}，星期 1~7，<b>0 表示该时段不录像</b>。
 */
public final class RecordPlanProbe2 {

    private static DahuaSdk.Session session;
    private static DahuaExtAccess ext;

    /** 抓 params.table 那一段（去掉 session/id 这类每次都变的字段）。 */
    private static final Pattern TABLE = Pattern.compile("\"params\"\\s*:\\s*(\\{.*?\\})\\s*,\\s*\"result\"");

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

        say("=== 1. 读原始配置 ===");
        String original = ext.readConfig(session, "Record", ch);
        String originalTable = table(original);
        say("   params.table 长度=" + originalTable.length());
        say("   TimeSection = " + timeSection(originalTable));

        say("=== 2. 连读两次，确认 session 字段确实每次都变 ===");
        String again = ext.readConfig(session, "Record", ch);
        say("   整串相等: " + original.equals(again));
        say("   table 相等: " + originalTable.equals(table(again)));
        say("   ← 整串不等但 table 相等，证明整串比较是错的");

        say("=== 3. 写入：把通道 0 的第 1 个时段改成星期 3 全天 ===");
        /*
         * 只改第一组的第一个时段，其余原样。这是最小改动，
         * 避免一次动太多导致无法判断是哪个字段生效。
         */
        String target = originalTable.replaceFirst(
                "\"6 00:00:00-23:59:59\"", "\"3 00:00:00-23:59:59\"");
        say("   替换是否命中: " + !target.equals(originalTable));
        if (target.equals(originalTable)) {
            say("   未命中，中止");
            return;
        }

        String modifiedJson = rebuild(original, target);
        say("=== 4. 写入 ===");
        try {
            boolean restart = ext.writeConfig(session, "Record", ch, modifiedJson);
            say("   返回: 需要重启=" + restart);
        } catch (Throwable t) {
            say("   写入失败: " + t.getMessage());
            return;
        }

        say("=== 5. 回读（只比 table）===");
        String after = ext.readConfig(session, "Record", ch);
        String afterTable = table(after);
        say("   写入前 table == 写入后 table: " + afterTable.equals(originalTable));
        say("   写入后 table == 期望值:        " + afterTable.equals(target));
        say("   写入后 TimeSection = " + timeSection(afterTable));

        boolean applied = afterTable.equals(target);
        say("   ★ 录像计划写入" + (applied ? "真的生效了" : "没生效（设备忽略了）"));

        say("=== 6. 恢复原配置 ===");
        try {
            ext.writeConfig(session, "Record", ch, rebuild(original, originalTable));
            String restored = table(ext.readConfig(session, "Record", ch));
            say("   已恢复: " + restored.equals(originalTable));
            say("   当前 TimeSection = " + timeSection(restored));
        } catch (Throwable t) {
            say("   恢复失败: " + t.getMessage());
        }

        System.exit(0);
    }

    /** 提取 params.table 段。 */
    private static String table(String json) {
        if (json == null) {
            return "";
        }
        Matcher m = TABLE.matcher(json);
        return m.find() ? m.group(1) : json;
    }

    /** 提取 TimeSection 数组（诊断用，截断显示）。 */
    private static String timeSection(String table) {
        int at = table.indexOf("\"TimeSection\"");
        if (at < 0) {
            return "(无)";
        }
        String s = table.substring(at, Math.min(at + 200, table.length()));
        return s;
    }

    /** 用新的 table 段替换原 JSON 里的 table 段。 */
    private static String rebuild(String original, String newTable) {
        Matcher m = TABLE.matcher(original);
        if (!m.find()) {
            return original;
        }
        return original.substring(0, m.start(1)) + newTable + original.substring(m.end(1));
    }

    private static String arg(String[] a, int i, String def) {
        return a.length > i && a[i] != null && !a[i].isBlank() ? a[i] : def;
    }

    private static void say(String msg) {
        System.out.println("[plan] " + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + msg);
        System.out.flush();
    }
}
