package io.github.rurulalanana.dahua;

import io.github.rurulalanana.dahua.ext.DahuaExtAccess;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.deviceaccess.ConnectMode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 录像计划验证（v3）：<b>归一化空白后再比对</b>。
 *
 * <p>前两轮失败的原因：
 * <ol>
 *   <li>v1 整串比较 —— JSON 里 {@code "session"} 每次都变，永远「有变化」</li>
 *   <li>v2 只比 {@code params.table} —— 但设备返回的 JSON <b>空格排版不固定</b>
 *       （{@code {"a":1}} 与 {@code { "a" : 1 }} 都出现过），仍会误判</li>
 * </ol>
 * 本版把 table 段的空白全部剥掉再比，只比语义。
 */
public final class RecordPlanProbe3 {

    private static DahuaSdk.Session session;
    private static DahuaExtAccess ext;

    private static final Pattern TABLE =
            Pattern.compile("\"params\"\\s*:\\s*(\\{.*?\\})\\s*,\\s*\"result\"");

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

        say("=== 1. 原始配置（归一化后）===");
        String original = ext.readConfig(session, "Record", ch);
        String originalNorm = norm(original);
        say("   归一化长度=" + originalNorm.length());
        say("   前 400: " + originalNorm.substring(0,
                Math.min(400, originalNorm.length())));

        say("=== 2. 连读两次验证归一化稳定 ===");
        String againNorm = norm(ext.readConfig(session, "Record", ch));
        say("   归一化后相等: " + originalNorm.equals(againNorm));
        if (!originalNorm.equals(againNorm)) {
            say("   仍不稳定，说明还有每次变化的字段，中止");
            say("   差异位置: " + firstDiff(originalNorm, againNorm));
            return;
        }

        say("=== 3. 写入：通道 0 第 1 时段 星期6 → 星期3 ===");
        /*
         * 顺序很重要：先在【原始文本】上替换（原文是 "6 00:00:00-23:59:59"，
         * 带空格），替换完再归一化算期望值。
         * 反过来先归一化会把空格去掉，变成 "600:00:00-..."，替换必然落空 ——
         * 上一版就是这么错的。
         */
        String originalTable = tableRaw(original);
        String targetTable = originalTable.replaceFirst(
                "\"6 00:00:00-23:59:59\"", "\"3 00:00:00-23:59:59\"");
        say("   table 段替换命中: " + !targetTable.equals(originalTable));
        if (targetTable.equals(originalTable)) {
            say("   未命中，中止");
            return;
        }
        String targetNorm = norm(targetTable);
        say("   期望值已算好（归一化长度 " + targetNorm.length() + "）");

        String toWrite = rebuild(original, targetTable);
        say("=== 4. 写入 ===");
        try {
            boolean restart = ext.writeConfig(session, "Record", ch, toWrite);
            say("   返回: 需要重启=" + restart);
        } catch (Throwable t) {
            say("   写入失败: " + t.getMessage());
            return;
        }

        say("=== 5. 回读（归一化比对）===");
        String afterNorm = norm(ext.readConfig(session, "Record", ch));
        say("   等于写入前的值: " + afterNorm.equals(originalNorm));
        say("   等于期望的新值: " + afterNorm.equals(targetNorm));

        boolean applied = afterNorm.equals(targetNorm);
        if (applied) {
            say("   ★ 录像计划写入真的生效");
        } else if (afterNorm.equals(originalNorm)) {
            say("   ★ 写入被设备忽略（值没变）");
        } else {
            say("   ★ 值变了但不是期望值 —— 设备做了归一化，需看实际内容");
            say("   实际前 400: " + afterNorm.substring(0,
                    Math.min(400, afterNorm.length())));
        }

        say("=== 6. 恢复 ===");
        try {
            ext.writeConfig(session, "Record", ch, rebuild(original, originalTable));
            String restoredNorm = norm(ext.readConfig(session, "Record", ch));
            say("   已恢复到原值: " + restoredNorm.equals(originalNorm));
        } catch (Throwable t) {
            say("   恢复失败: " + t.getMessage());
        }

        System.exit(0);
    }

    /**
     * 剥掉所有空白 + 每次都会变的字段（{@code id} / {@code session}）。
     *
     * <p>真机实测这两个字段连读两次都不同：
     * <pre>
     *   {"id":3348,...,"session":1014386800}
     *   {"id":3860,...,"session":594147773}
     * </pre>
     * 不剥掉它们，任何整串比较都恒为「有变化」，写入校验永远失败。
     */
    private static String norm(String json) {
        if (json == null) {
            return "";
        }
        return json.replaceAll("\\s+", "")
                .replaceAll("\"id\":\\d+,", "")
                .replaceAll("\"session\":\\d+", "");
    }

    /** 提取 params.table 原始文本（保留排版，用于写回）。 */
    private static String tableRaw(String json) {
        Matcher m = TABLE.matcher(json == null ? "" : json);
        return m.find() ? m.group(1) : (json == null ? "" : json);
    }

    /** 用新 table 替换原 JSON 的 table 段。 */
    private static String rebuild(String original, String newTable) {
        Matcher m = TABLE.matcher(original);
        if (!m.find()) {
            return original;
        }
        return original.substring(0, m.start(1)) + newTable + original.substring(m.end(1));
    }

    private static String firstDiff(String a, String b) {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return "位置 " + i + " a=" + a.substring(Math.max(0, i - 30), Math.min(a.length(), i + 30))
                        + " | b=" + b.substring(Math.max(0, i - 30), Math.min(b.length(), i + 30));
            }
        }
        return a.length() == b.length() ? "无差异" : "长度不同 " + a.length() + " vs " + b.length();
    }

    private static String arg(String[] a, int i, String def) {
        return a.length > i && a[i] != null && !a[i].isBlank() ? a[i] : def;
    }

    private static void say(String msg) {
        System.out.println("[plan3] " + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + msg);
        System.out.flush();
    }
}
