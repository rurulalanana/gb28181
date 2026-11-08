package io.github.rurulalanana.dahua;

import io.github.rurulalanana.dahua.ext.DahuaExtAccess;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.deviceaccess.ConnectMode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 把 Record 配置完整 dump 到文件，交给 Python 精确解析。
 *
 * <p>Java 侧只做「取原文 + 写回」，不做 JSON 比较 ——
 * 前面三轮都在用字符串比较，被 {@code id}/{@code session} 字段与空白排版坑了三次。
 * 交给真正的 JSON 解析器判断才可靠。
 */
public final class RecordDump {

    private static DahuaSdk.Session session;
    private static DahuaExtAccess ext;

    public static void main(String[] args) throws Exception {
        String host = arg(args, 0, "192.168.10.156");
        int port = Integer.parseInt(arg(args, 1, "37777"));
        String user = arg(args, 2, "admin");
        String pass = arg(args, 3, "gxhy@123");
        String serial = arg(args, 4, "10001");
        String out = arg(args, 5, "target/record.json");

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

        String json = ext.readConfig(session, "Record", 0);
        Path p = Paths.get(out);
        Files.createDirectories(p.toAbsolutePath().getParent());
        Files.write(p, json.getBytes(StandardCharsets.UTF_8));
        say("已写入 " + p.toAbsolutePath() + "（" + json.length() + " 字符）");

        System.exit(0);
    }

    private static String arg(String[] a, int i, String def) {
        return a.length > i && a[i] != null && !a[i].isBlank() ? a[i] : def;
    }

    private static void say(String msg) {
        System.out.println("[dump] " + msg);
        System.out.flush();
    }
}
