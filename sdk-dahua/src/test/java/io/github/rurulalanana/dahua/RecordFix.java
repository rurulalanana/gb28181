package io.github.rurulalanana.dahua;

import com.sun.jna.ptr.IntByReference;
import io.github.rurulalanana.dahua.ext.DahuaExtAccess;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.deviceaccess.ConnectMode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 修复被写坏的 {@code Record} 配置。
 *
 * <h3>怎么坏的</h3>
 * 大华 {@code CLIENT_GetNewDevConfig} 返回的是<b>完整响应包</b>：
 * <pre>
 * {"id":3348,"params":{"table":{业务字段}},"result":true,"session":1014386800}
 * </pre>
 * 而 {@code CLIENT_SetNewDevConfig} 只接受<b>业务字段那一层</b>。
 * 把整个响应包原样写回去，设备会把 {@code id/params/result/session}
 * 也当成业务字段存进 {@code table} —— 于是每写一次就多嵌一层：
 * <pre>
 * {"params":{"table":{"Format":"dav",…,
 *                      "id":3348,"params":{"table":{…"params":{"table":{…}}}}}}}
 * </pre>
 * 真机实测嵌了 4 层。
 *
 * <h3>怎么修</h3>
 * 递归剥到最内层那个<b>只含业务字段</b>的 table（判定标准：有 {@code TimeSection}），
 * 再以正确的格式写回。
 */
public final class RecordFix {

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
        String raw = ext.readConfig(session, "Record", ch);
        Files.write(Paths.get("target/record-broken.json"),
                raw.getBytes(StandardCharsets.UTF_8));
        say("已备份原始（损坏）配置到 target/record-broken.json，长度=" + raw.length());

        String clean = extractBusiness(raw);
        if (clean == null) {
            say("无法提取业务字段，中止");
            return;
        }
        say("提取到的干净业务字段长度=" + clean.length());
        say("前 300: " + clean.substring(0, Math.min(300, clean.length())));
        say("嵌套层数检查：TimeSection 出现次数=" + countOf(clean, "\"TimeSection\""));

        say("=== 写回干净配置 ===");
        String payload = "{\"params\":{\"table\":" + clean + "}}";
        say("写入 payload 长度=" + payload.length());
        try {
            boolean restart = ext.writeConfig(session, "Record", ch, payload);
            say("   返回: 需要重启=" + restart);
        } catch (Throwable t) {
            say("   写入失败: " + t.getMessage());
            return;
        }

        say("=== 回读验证 ===");
        String after = ext.readConfig(session, "Record", ch);
        Files.write(Paths.get("target/record-fixed.json"),
                after.getBytes(StandardCharsets.UTF_8));
        int nests = countOf(after, "\"TimeSection\"");
        say("回读长度=" + after.length());
        say("TimeSection 出现次数=" + nests + "（1 = 正常，>1 = 还有嵌套）");
        say("回读内容前 500: " + after.substring(0, Math.min(500, after.length())));
        say(nests == 1 ? "★ 已修复" : "★ 仍未修复，需人工处理");

        System.exit(0);
    }

    /**
     * 从响应包里剥出最内层的业务字段对象。
     *
     * <p>判定「这是业务层」的标准：含有 {@code TimeSection} 且<b>不含</b> {@code "params"}。
     * 损坏的 JSON 里外层也含 TimeSection（因为它是被嵌进去的），
     * 只有最内层不含 params。
     */
    private static String extractBusiness(String json) {
        String cur = json;
        for (int depth = 0; depth < 10; depth++) {
            String table = tableOf(cur);
            if (table == null) {
                say("  第 " + depth + " 层：找不到 params.table");
                return null;
            }
            boolean hasNestedParams = table.contains("\"params\"");
            say("  第 " + depth + " 层：长度=" + table.length()
                    + " 含 params=" + hasNestedParams
                    + " 含 TimeSection=" + table.contains("\"TimeSection\""));
            if (!hasNestedParams) {
                return table;
            }
            cur = table;
        }
        say("  嵌套超过 10 层，放弃");
        return null;
    }

    /**
     * 取 {@code params.table} 的对象文本（含最外层大括号）。
     *
     * <p>手写括号配对而不引 JSON 库：{@code sdk-dahua} 的运行期依赖只有 JNA + slf4j，
     * 为了剥一层 JSON 引 Jackson 不值得。
     */
    private static String tableOf(String json) {
        int at = json.indexOf("\"table\"");
        if (at < 0) {
            return null;
        }
        int brace = json.indexOf('{', at);
        if (brace < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        for (int i = brace; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(brace, i + 1);
                }
            }
        }
        return null;
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
        System.out.println("[fix] " + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + msg);
        System.out.flush();
    }
}
