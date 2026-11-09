package io.github.rurulalanana.dahua.ext;

import io.github.rurulalanana.dahua.internal.DahuaSdk;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 大华设备的 <b>HTTP CGI 配置通道</b>（{@code /cgi-bin/configManager.cgi}，Digest 认证）。
 *
 * <h3>为什么 SDK 之外还要这条路</h3>
 * 实测某型 IPC（固件 {@code 3.201.0000007.1.R}）的<b>场景数组</b>类配置项
 * —— {@code VideoColor}、{@code VideoInSharpness}、{@code VideoInExposure}、
 * {@code VideoInWhiteBalance}、{@code VideoInDenoise} 等 ——
 * 用 {@code CLIENT_SetNewDevConfig} 写入<b>一律</b>返回
 * {@code err=12 (0xC)}，连「原样读出来再原样写回」都被拒绝；
 * 而<b>对象</b>类配置项（{@code VideoInOptions}）同一个调用能成功。
 *
 * <p>同一批参数设备的 Web 界面能改，抓包确认它走的就是
 * {@code configManager.cgi?action=setConfig&VideoColor[0][0].Brightness=60}。
 * 也就是说参数本身<b>可写</b>，只是这一个 SDK 入口对数组型配置不适用。
 * 所以能力表读取继续走 SDK（{@code GetNewDevConfig} 是好的），
 * <b>写入失败时改走 CGI</b>。
 *
 * <h3>键路径长什么样</h3>
 * {@code action=getConfig&name=VideoColor} 返回：
 * <pre>
 *   table.VideoColor[0][0].Brightness=50
 *   table.VideoColor[0][1].Brightness=50      ← 场景数组，第二维是场景
 *   table.VideoInOptions[0].Flip=false        ← 对象，第一维是通道
 *   table.VideoInOptions[0].NormalOptions.Flip=false
 * </pre>
 * 写回时<b>必须去掉 {@code table.} 前缀</b>，设备只认 {@code VideoColor[0][0].Brightness}。
 *
 * <h3>为什么不用 HttpClient 的 Basic 认证</h3>
 * 设备只接受 Digest（实测 Basic 直接 401）。JDK 的 {@link HttpClient}
 * 带 {@link Authenticator} 时原生支持 Digest 质询重试，不需要自己算 nonce/response。
 */
public class DahuaCgiAccess {

    /** CGI 默认 HTTP 端口。SDK 端口（37777）与 Web 端口是两回事。 */
    public static final int DEFAULT_HTTP_PORT = 80;

    /**
     * 设备 CGI 的<b>响应</b>是 UTF-8。
     *
     * <p>实测确认：设备自己写的 {@code PtzTour[0][2].Name} 原始字节
     * {@code E5 B7 A1 E8 88 AA E7 BE A4 E7 B5 84} 按 UTF-8 解是「巡航群組3」，
     * 按 GBK 解是「宸¤埅缇ょ祫3」。早先整段用 GBK 解码，界面上那串乱码就是它。
     *
     * <p><b>但不能无条件只按 UTF-8 解</b>：设备上可能残留平台早期用 GBK 写入的名字
     * （{@code D1 B2 BA BD C8 BA BD}），它过不了严格 UTF-8 校验。
     * 而 {@code BodyHandlers.ofString(UTF_8)} 用的是宽松解码，会把非法字节
     * 静默替成 {@code U+FFFD}（实测得到 {@code Ѳ��Ⱥ�M1}），
     * 信息就此永久丢失，后面再怎么修都救不回来。
     *
     * <p>所以先取字节、再判定编码：严格 UTF-8 通过 → UTF-8，否则回退 GBK。
     * 这也是 {@code DahuaExtAccess.decodeName} 一直在用的判据。
     */
    private static final Charset UTF_8 = StandardCharsets.UTF_8;
    /** 查询串的编码；请求参数也按 UTF-8 送，与设备的存储编码保持一致。 */
    private static final Charset GBK = Charset.forName("GBK");
    private static final String PATH = "/cgi-bin/configManager.cgi";

    /** 读配置项超时：设备同时在推流，给它宽一点。 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(6);
    /** 写配置项超时。 */
    private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /**
     * 读一个配置项。
     *
     * @param httpPort 设备 Web 端口；{@code <=0} 时用 {@value #DEFAULT_HTTP_PORT}
     * @return <b>已去掉 {@code table.} 前缀</b>的键路径 → 值；可直接回传给 {@link #write}
     */
    public Map<String, String> read(DahuaSdk.Session session, String command, int httpPort) {
        String body = get(session, httpPort,
                "action=getConfig&name=" + URLEncoder.encode(command, GBK), READ_TIMEOUT);
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : body.split("\\r?\\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = stripTablePrefix(line.substring(0, eq).trim());
            if (key.isEmpty() || key.startsWith("Error")) {
                continue;
            }
            out.put(key, line.substring(eq + 1));
        }
        if (out.isEmpty()) {
            throw new DahuaSdkException("设备未返回配置项 " + command, 0,
                    "该型号可能不支持此配置项，或 Web 端口不是 " + httpPort(session, httpPort));
        }
        return out;
    }

    /**
     * 批量写配置项。
     *
     * <p>设备接受<b>一次请求多个键值对</b>，所以「把亮度改到所有场景」
     * 是一次 HTTP 往返，不是 N 次。
     *
     * @param kv 键路径（带不带 {@code table.} 前缀都可）→ 新值
     */
    public void write(DahuaSdk.Session session, Map<String, String> kv, int httpPort) {
        if (kv == null || kv.isEmpty()) {
            return;
        }
        StringBuilder query = new StringBuilder("action=setConfig");
        for (Map.Entry<String, String> e : kv.entrySet()) {
            query.append('&')
                    .append(encode(stripTablePrefix(e.getKey())))
                    .append('=')
                    .append(encode(e.getValue() == null ? "" : e.getValue()));
        }
        String body = get(session, httpPort, query.toString(), WRITE_TIMEOUT);
        /*
         * 成功时设备返回 "OK"，失败时是 "Error" 或 "Bad Request!"。
         * 不能只看 HTTP 200 —— 参数名拼错设备也回 200 + Error。
         */
        String trimmed = body == null ? "" : body.trim();
        if (!"OK".equalsIgnoreCase(trimmed)) {
            throw new DahuaSdkException("设备拒绝写入配置",
                    0, "设备返回：" + (trimmed.isEmpty() ? "空响应" : trimmed));
        }
    }

    // ==================== 内部 ====================

    /**
     * 发一个 GET 并返回响应体。
     *
     * <h3>为什么自己算 Digest 摘要</h3>
     * 设备只接受 Digest（实测 Basic 直接 401）。JDK 的
     * {@link HttpClient.Builder#authenticator} 理论上支持 Digest 质询重试，
     * 但对这台设备<b>完全不触发</b> —— 加了 Authenticator 仍是 401，
     * 说明它没进质询重试流程。与其猜是 nonce 格式还是连接复用的原因，
     * 不如自己按 RFC 7616 算一遍：逻辑只有几十行，行为完全可预期。
     */
    private String get(DahuaSdk.Session session, int httpPort, String query, Duration timeout) {
        String path = PATH + "?" + query;
        String url = "http://" + session.host() + ":" + httpPort(session, httpPort) + path;
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                // 关掉 JDK 自己的认证：我们自己加 Authorization 头
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        try {
            HttpResponse<String> resp = send(client, url, null, timeout);
            if (resp.statusCode() == 401) {
                String challenge = resp.headers().firstValue("WWW-Authenticate").orElse("");
                String auth = authorization(challenge, session, "GET", path);
                if (auth == null) {
                    throw new DahuaSdkException("设备 Web 接口要求认证，但它没有返回可用的认证方式",
                            401, "请确认设备已开启 Web 服务，且账号有配置权限");
                }
                resp = send(client, url, auth, timeout);
            }
            return checkStatus(resp, session, httpPort);
        } catch (IOException e) {
            throw new DahuaSdkException("连接设备 Web 接口失败（" + session.host() + ":"
                    + httpPort(session, httpPort) + "）", 0,
                    "该型号可能关闭了 Web 服务，或 Web 端口不是默认值");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DahuaSdkException("连接设备 Web 接口被中断", 0, null);
        }
    }

    private static HttpResponse<String> send(HttpClient client, String url, String authorization,
                                             Duration timeout) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET();
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        /*
         * 取字节而不是直接 ofString：编码要自己判（见 UTF_8 的注释）。
         * 解码失败/乱码的代价是不可逆的，不能交给宽松解码器默默替换。
         */
        HttpResponse<byte[]> raw = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new RawResponse(raw.statusCode(), raw.headers(), decodeBody(raw.body()));
    }

    /**
     * 判定响应体编码：<b>逐行</b>严格 UTF-8 优先，不通过则按 GBK。
     *
     * <h3>为什么必须逐行判</h3>
     * 设备上的数据可能<b>同时含两种编码</b>。实测同一份 {@code PtzTour} 响应：
     * <pre>
     *   PtzTour[0][0].Name=D1 B2 BA BD C8 BA BD 4D 31   ← GBK（平台早期写入）
     *   PtzTour[0][2].Name=E5 B7 A1 E8 88 AA …  33      ← UTF-8（设备界面写入）
     * </pre>
     * 整份响应做一次判定时，一个 GBK 字节就让整体校验失败，
     * 全篇退化成 GBK，把本来正确的 UTF-8 行解成「宸¤埅缇ょ祫3」。
     * CGI 响应本来就是「一行一个键值对」，按行切分既自然又精确。
     *
     * <p>为什么用 REPORT 而不是 {@code new String(bytes, UTF_8)}：后者会把非法字节
     * 静默换成 {@code U+FFFD} 且永远「成功」，判不出真假，
     * 而 GBK 字节被当成 UTF-8 时恰好就会这样 —— 信息就此永久丢失。
     */
    static String decodeBody(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder(body.length);
        int start = 0;
        for (int i = 0; i <= body.length; i++) {
            if (i == body.length || body[i] == '\n') {
                if (i > start) {
                    out.append(decodeLine(body, start, i));
                }
                if (i < body.length) {
                    out.append('\n');
                }
                start = i + 1;
            }
        }
        return out.toString();
    }

    /** 解一行（不含换行符）：严格 UTF-8 优先，不通过则按 GBK。 */
    private static String decodeLine(byte[] body, int from, int to) {
        boolean ascii = true;
        for (int i = from; i < to; i++) {
            if (body[i] < 0) {
                ascii = false;
                break;
            }
        }
        if (ascii) {
            return new String(body, from, to - from, StandardCharsets.US_ASCII);
        }
        ByteBuffer buf = ByteBuffer.wrap(body, from, to - from);
        try {
            return UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(buf)
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(body, from, to - from, GBK);
        }
    }

    /** 只带状态码/响应头/已解码正文的最小响应载体。 */
    private record RawResponse(int statusCode, java.net.http.HttpHeaders headers, String body)
            implements HttpResponse<String> {
        @Override
        public java.util.Optional<HttpResponse<String>> previousResponse() {
            return java.util.Optional.empty();
        }

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
            return java.util.Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("http://device");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private static String checkStatus(HttpResponse<String> resp, DahuaSdk.Session session,
                                      int httpPort) {
        if (resp.statusCode() == 401 || resp.statusCode() == 403) {
            throw new DahuaSdkException("设备 Web 接口认证失败", resp.statusCode(),
                    "确认账号密码正确且该账号有配置权限");
        }
        if (resp.statusCode() != 200) {
            throw new DahuaSdkException("设备 Web 接口返回 HTTP " + resp.statusCode(),
                    resp.statusCode(), "确认设备已开启 Web 服务");
        }
        return resp.body();
    }

    /**
     * 按 {@code WWW-Authenticate} 质询生成 {@code Authorization} 头。
     *
     * <p>支持 Digest（设备用的这个）与 Basic 两种；都不认识时返回 {@code null}。
     *
     * @param challenge 形如 {@code Digest realm="X", nonce="Y", qop="auth"}
     */
    private static String authorization(String challenge, DahuaSdk.Session session,
                                        String method, String uri) {
        if (challenge == null || challenge.isBlank()) {
            return null;
        }
        if (challenge.regionMatches(true, 0, "Basic", 0, 5)) {
            String raw = session.username() + ":" + (session.password() == null ? "" : session.password());
            return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(GBK));
        }
        if (!challenge.regionMatches(true, 0, "Digest", 0, 6)) {
            return null;
        }
        Map<String, String> p = parseChallenge(challenge.substring(6));
        String realm = p.getOrDefault("realm", "");
        String nonce = p.getOrDefault("nonce", "");
        String qop = p.get("qop");
        String opaque = p.get("opaque");
        String algorithm = p.getOrDefault("algorithm", "MD5");

        String cnonce = randomHex();
        String nc = "00000001";
        String ha1 = md5(session.username() + ":" + realm + ":"
                + (session.password() == null ? "" : session.password()));
        if ("MD5-sess".equalsIgnoreCase(algorithm)) {
            ha1 = md5(ha1 + ":" + nonce + ":" + cnonce);
        }
        String ha2 = md5(method + ":" + uri);
        // qop 可能被声明成 "auth,auth-int"，实际只用 auth
        boolean useQop = qop != null && qop.contains("auth");
        String response = useQop
                ? md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2)
                : md5(ha1 + ":" + nonce + ":" + ha2);

        StringBuilder sb = new StringBuilder("Digest ");
        sb.append("username=\"").append(session.username()).append("\", ");
        sb.append("realm=\"").append(realm).append("\", ");
        sb.append("nonce=\"").append(nonce).append("\", ");
        sb.append("uri=\"").append(uri).append("\", ");
        sb.append("algorithm=").append(algorithm).append(", ");
        sb.append("response=\"").append(response).append('"');
        if (useQop) {
            sb.append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append('"');
        }
        if (opaque != null) {
            sb.append(", opaque=\"").append(opaque).append('"');
        }
        return sb.toString();
    }

    /** 解析质询参数（{@code a=b, c="d"} → map）。 */
    private static Map<String, String> parseChallenge(String text) {
        Map<String, String> out = new HashMap<>();
        for (String part : text.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = part.substring(0, eq).trim().toLowerCase();
            String v = part.substring(eq + 1).trim();
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                v = v.substring(1, v.length() - 1);
            }
            out.put(k, v);
        }
        return out;
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(GBK));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 MD5 实现", e);
        }
    }

    /** cnonce：只要每次不同即可，不需要密码学强度。 */
    private static String randomHex() {
        long v = ThreadLocalRandom.current().nextLong();
        return String.format("%016x", v);
    }

    private static int httpPort(DahuaSdk.Session session, int httpPort) {
        return httpPort > 0 ? httpPort : DEFAULT_HTTP_PORT;
    }

    private static String stripTablePrefix(String key) {
        return key.startsWith("table.") ? key.substring("table.".length()) : key;
    }

    /**
     * 查询串编码。
     *
     * <p><b>空格必须编成 {@code %20}，不能是 {@code +}</b>：
     * 设备不按 {@code application/x-www-form-urlencoded} 解 {@code +}，
     * 而是把它当字面字符存下来。实测写
     * {@code TimeSection[0][0]=1 08:00:00-18:00:00} 后回读得到
     * {@code 1+08:00:00-18:00:00} —— 多一个加号，时间段直接失效，
     * 而且设备不报错。{@link URLEncoder#encode} 默认就产出 {@code +}，必须替换。
     */
    private static String encode(String s) {
        return URLEncoder.encode(s, UTF_8).replace("+", "%20");
    }
}
