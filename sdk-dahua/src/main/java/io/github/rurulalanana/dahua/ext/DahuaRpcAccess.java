package io.github.rurulalanana.dahua.ext;

import io.github.rurulalanana.dahua.internal.DahuaSdk;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 大华设备的 <b>RPC2 通道</b>（{@code POST /RPC2}，JSON-RPC 风格，Digest 认证）。
 *
 * <h3>为什么 CGI 之外还要这条路</h3>
 * 有些能力<b>只有 RPC2 有</b>，{@code configManager.cgi} 和 netsdk 都没有：
 * <pre>
 * ptz.markLimit {type:0|1}                 ← 「PTZ旋轉限制」设上限/下限（0=上限 1=下限）
 * ptz.startPatternRecord {index,name}      ← 巡迹录制开始（index 是 1-based）
 * ptz.stopPatternRecord  {index}
 * ptz.startPatternReplay {index}           ← 巡迹回放
 * ptz.stopPatternReplay  {index}
 * ptz.setScanLimit {index, limitMode}      ← 线扫边界（'Left' / 'Right'，index 1-based）
 * ptz.getPresets                           ← 预置位清单（含 Position[3] 坐标）
 * ptz.getCurrentProtocolCaps               ← 设备能力清单（上限/区间全在这里）
 * </pre>
 *
 * <h3>会话怎么来</h3>
 * 设备 Web 界面登录后拿一个 {@code session} 串，之后每次请求都要带上。
 * 这里的做法是：用 SDK 登录时已知的账号密码走一次
 * {@code global.login}，拿到 session 后缓存起来复用；过期（设备重启 /
 * 空闲超时）时自动重新登录一次。
 *
 * <h3>注意</h3>
 * <ul>
 *   <li>设备 Web 服务在大量 RPC2 请求下会<b>短时不可用</b>（约 20 秒自恢复），
 *       所以每次调用都带超时，失败时抛出可读的错误。</li>
 *   <li>部分方法（{@code ptz.*}）需要额外带 {@code object}（球机对象 ID），
 *       由 {@code ptz.getCurrentProtocolCaps} 或首次 {@code ptz.getPresets} 取得。</li>
 * </ul>
 */
public class DahuaRpcAccess {

    /** RPC2 业务端点。 */
    private static final String PATH = "/RPC2";
    /**
     * 登录端点。
     *
     * <p>设备把 {@code global.login} 单独放在 {@code /RPC2_Login}：
     * 往 {@code /RPC2} 发登录请求会被<b>直接断 TCP</b>（无响应），
     * 看起来就像「设备关闭了 Web 服务」。
     */
    private static final String LOGIN_PATH = "/RPC2_Login";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(8);
    private static final Charset UTF_8 = StandardCharsets.UTF_8;
    private static final Charset GBK = Charset.forName("GBK");

    /** 设备返回的 session 串；为空表示还没登录。 */
    private final Map<String, String> sessions = new HashMap<>();
    /**
     * 登录后设备下发的 {@code WebClientHttpSessionID} cookie，按 {@code host:port} 缓存。
     *
     * <p>登录成功时设备通过 {@code Set-Cookie} 下发，后续 {@code /RPC2} 调用要带上，
     * 否则设备认不出这个会话（会当成未登录）。
     */
    private final Map<String, String> cookies = new HashMap<>();
    /** 球机 object id（ptz.* 方法要）。 */
    private final Map<String, Long> objects = new HashMap<>();

    /** 「PTZ旋轉限制」的上限。 */
    public static final int LIMIT_TYPE_UPPER = 0;
    /** 「PTZ旋轉限制」的下限。 */
    public static final int LIMIT_TYPE_LOWER = 1;

    /**
     * 把当前位置设为水平旋转的上限或下限。
     *
     * @param type {@link #LIMIT_TYPE_UPPER} 或 {@link #LIMIT_TYPE_LOWER}
     */
    public void markLimit(DahuaSdk.Session session, int httpPort, int type) {
        if (type != LIMIT_TYPE_UPPER && type != LIMIT_TYPE_LOWER) {
            throw new DahuaSdkException("限位类型只能是 0(上限) 或 1(下限)：" + type, 0, null);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", type);
        call(session, httpPort, "ptz.markLimit", params, true);
    }

    /**
     * 把当前位置设为某条线扫的左/右边界。
     *
     * @param index 线扫编号，<b>1-based</b>（与设备界面「序號」一致）
     * @param left  {@code true}=左边界，{@code false}=右边界
     */
    public void setScanLimit(DahuaSdk.Session session, int httpPort, int index, boolean left) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("index", index);
        params.put("limitMode", left ? "Left" : "Right");
        call(session, httpPort, "ptz.setScanLimit", params, true);
    }

    /**
     * 通过 RPC2 写一个配置项（{@code configManager.setConfig}）。
     *
     * <p><b>为什么需要这条通路</b>：有些配置项设备只认 RPC2，CGI 的
     * {@code configManager.cgi?action=setConfig} 一律返回 HTTP 400。
     * 实测 {@code AutoPan} 就是这种（水平旋转速度）：
     * <ul>
     *   <li>CGI 写 {@code AutoPan[0].PanSpeed} → <b>400 Bad Request</b>，值不变；</li>
     *   <li>设备自己的 Web UI 写同一个字段 → <b>成功</b>，抓包看走的是
     *       {@code POST /RPC2} 的 {@code configManager.setConfig}。</li>
     * </ul>
     * 所以不能只依赖 CGI。
     *
     * <p><b>⚠️ table 的层级必须与 {@code getConfig} 回读的结构完全一致。</b>
     * 实测把 {@code PtzTour} 传成 {@code [32 个对象]}（少了一层通道）会把设备配置
     * <b>永久写坏</b>：之后 CGI 读 {@code PtzTour} 返回空、单条读报
     * {@code Error -1 getting param}、设备 Web UI 该页报「操作失敗」且所有写入 400。
     * 正确姿势是先从 {@link #getConfigItem} 拿到原结构，改字段后原样写回。
     *
     * @param name  配置项名，如 {@code "AutoPan"}
     * @param table 与回读结构一致的 table 数组
     */
    public void setConfigItem(DahuaSdk.Session session, int httpPort, String name, List<?> table) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("table", table);
        // options 必须是空数组：设备 UI 也是这么发的，缺省时部分固件直接 400
        params.put("options", new ArrayList<>());
        call(session, httpPort, "configManager.setConfig", params, false);
    }

    /**
     * 通过 RPC2 读一个配置项（{@code configManager.getConfig}）。
     *
     * <p>返回 {@code params.table}，结构与写回时要求的完全一致。
     * 取不到时返回 {@code null}。
     */
    @SuppressWarnings("unchecked")
    public Object getConfigItem(DahuaSdk.Session session, int httpPort, String name) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        Map<String, Object> resp = call(session, httpPort, "configManager.getConfig", params, false);
        Object p = resp.get("params");
        if (p instanceof Map<?, ?> m) {
            return m.get("table");
        }
        return null;
    }

    /**
     * 巡迹录制开始。
     *
     * @param index 巡迹编号，<b>1-based</b>
     * @param name  名称（设备会把名字写进 {@code AutoPattern[ch][index-1].Name}）
     */
    public void startPatternRecord(DahuaSdk.Session session, int httpPort, int index, String name) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("index", index);
        params.put("name", name == null || name.isBlank() ? "pattern" + index : name.trim());
        call(session, httpPort, "ptz.startPatternRecord", params, true);
    }

    /** 巡迹录制结束。 */
    public void stopPatternRecord(DahuaSdk.Session session, int httpPort, int index) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("index", index);
        call(session, httpPort, "ptz.stopPatternRecord", params, true);
    }

    /** 巡迹回放开始。 */
    public void startPatternReplay(DahuaSdk.Session session, int httpPort, int index) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("index", index);
        call(session, httpPort, "ptz.startPatternReplay", params, true);
    }

    /** 巡迹回放停止。 */
    public void stopPatternReplay(DahuaSdk.Session session, int httpPort, int index) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("index", index);
        call(session, httpPort, "ptz.stopPatternReplay", params, true);
    }

    /**
     * 启动 / 停止一条巡航线（设备「巡航群組」页的「執行」开关）。
     *
     * <p><b>与 {@code PtzTour[ch][route].Enable} 不是一回事</b>：
     * {@code Enable} 是配置项里的一个持久标记，写 true 设备照样不动；
     * 「執行」调的是 {@code ptz.startTour} / {@code ptz.stopTour}，是<b>运行时动作</b>。
     * 早期平台把两者当成同一个东西，表现为「界面上全显示启用，但设备根本没在巡航」。
     *
     * <p>设备还会拒绝空线（0 个预置点）的启动 —— 界面上那条线的开关直接是灰的。
     *
     * @param index 巡航线号，<b>1-based</b>
     */
    public void startTour(DahuaSdk.Session session, int httpPort, int index) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("index", index);
        call(session, httpPort, "ptz.startTour", params, true);
    }

    /** 停止一条巡航线。 */
    public void stopTour(DahuaSdk.Session session, int httpPort, int index) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("index", index);
        call(session, httpPort, "ptz.stopTour", params, true);
    }

    /**
     * 把云台配置恢复出厂默认（设备「PTZ 維護」页的「預設值」）。
     *
     * <p>实测 {@code ptz.reset} 存在且返回 {@code result:true}；
     * 同期的 {@code ptz.setDefault} 返回 {@code Method not found}。
     * <b>危险操作</b>：会清掉所有预置位、巡航组、线扫、巡迹，
     * 调用方必须自己先做二次确认。
     */
    public void resetPtzDefaults(DahuaSdk.Session session, int httpPort) {
        call(session, httpPort, "ptz.reset", new LinkedHashMap<>(), true);
    }

    /**
     * 读设备能力清单（{@code ptz.getCurrentProtocolCaps}）。
     *
     * <p>这是判断「这个型号支持到多少」的<b>唯一可靠来源</b>：
     * 配置里可能有 32 条航线，但能力位写 {@code TourMax=8}，
     * 界面就只显示 8 条；写第 9 条不会报错但也不会出现在界面上。
     *
     * @return 原始 map（结构随机型差异大，不建模）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> protocolCaps(DahuaSdk.Session session, int httpPort) {
        Map<String, Object> resp = call(session, httpPort, "ptz.getCurrentProtocolCaps",
                Map.of(), true);
        Object params = resp.get("params");
        if (params instanceof Map<?, ?> p && p.get("caps") instanceof Map<?, ?> caps) {
            return (Map<String, Object>) caps;
        }
        return Map.of();
    }

    // ==================== 底层 ====================

    /**
     * 调一个 RPC2 方法。
     *
     * @param withObject {@code true} 表示带上球机 object id（{@code ptz.*} 需要）
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> call(DahuaSdk.Session session, int httpPort, String method,
                                     Map<String, Object> params, boolean withObject) {
        String key = keyOf(session, httpPort);
        String sid = sessions.get(key);
        if (sid == null) {
            sid = login(session, httpPort);
        }
        Map<String, Object> resp = post(session, httpPort, method, params, sid, withObject);
        // session 过期（设备重启 / 空闲超时）时重登一次；其它错误原样抛出
        if (isInvalidSession(resp)) {
            sessions.remove(key);
            sid = login(session, httpPort);
            resp = post(session, httpPort, method, params, sid, withObject);
        }
        if (Boolean.FALSE.equals(resp.get("result"))) {
            throw new DahuaSdkException("设备拒绝执行 " + method, 0, describe(resp));
        }
        return resp;
    }

    private static boolean isInvalidSession(Map<String, Object> resp) {
        Object err = resp.get("error");
        if (!(err instanceof Map<?, ?> e)) {
            return false;
        }
        Object msg = e.get("message");
        return msg != null && String.valueOf(msg).toLowerCase().contains("session");
    }

    private static String describe(Map<String, Object> resp) {
        Object err = resp.get("error");
        if (err instanceof Map<?, ?> e) {
            return "设备返回：" + e.get("message") + "（code=" + e.get("code") + "）";
        }
        Object params = resp.get("params");
        if (params instanceof Map<?, ?> p && p.get("options") != null) {
            return "设备返回：" + p.get("options");
        }
        return "设备未说明原因";
    }

    /**
     * RPC2 登录，<b>两步</b>，走 {@code /RPC2_Login}（不是 {@code /RPC2}）。
     *
     * <h3>抓到的真实报文（固件 3.201.0000007.1.R）</h3>
     * <pre>
     * ① POST /RPC2_Login
     *    {"method":"global.login","params":{"userName":"admin","password":"",
     *                                        "clientType":"Web5.0"},"id":4}
     *    ← {"error":{"code":268632079,"message":"Component error: login challenge!"},
     *       "params":{"authorityType":["Default"],"encryption":"Default",
     *                 "realm":"Login to 5354e664...","random":"1380809999","qop":["auth"],
     *                 "opaque":"..."},
     *       "result":false,"session":"d78c724eb085ae39ce19fba2cc1a9b60"}
     *                                    ↑ session 在<b>顶层</b>，不在 params 里
     *
     * ② POST /RPC2_Login   （带上 ① 的 session）
     *    {"method":"global.login","params":{"userName":"admin","password":"<摘要>",
     *        "clientType":"Web5.0","realm":"Login to 5354e664...","random":"1380809999",
     *        "passwordType":"Default","authorityType":"Default"},"id":5,"session":"d78c..."}
     *    ← Set-Cookie: WebClientHttpSessionID=6c479925...
     *    ← {"id":5,"params":{"isPwdOverdue":false,"keepAliveInterval":60},
     *       "result":true,"session":"6c4799258b09fd487b1847cd32d13368"}
     * </pre>
     *
     * <h3>三个必须踩准的点</h3>
     * <ol>
     *   <li><b>登录路径是 {@code /RPC2_Login}</b>。往 {@code /RPC2} 发 {@code global.login}
     *       会被设备<b>直接断 TCP</b>（无响应），报出来就像「Web 服务关闭」——
     *       这正是之前「预置点能用、RPC2 全失败」的根因：两条完全不同的通路。</li>
     *   <li><b>session 在响应顶层</b>，不在 {@code params} 内。</li>
     *   <li><b>绝不能带 HTTP Digest</b>：设备鉴权全在 JSON 里，带 Authorization 会断连。</li>
     * </ol>
     *
     * <p>登录成功后要把 {@code WebClientHttpSessionID} cookie 与 session 一起缓存，
     * 后续 {@code /RPC2} 的业务调用才认得这个会话。
     */
    private String login(DahuaSdk.Session session, int httpPort) {
        String user = session.username() == null ? "admin" : session.username();
        String pass = session.password() == null ? "" : session.password();

        // ① 空密码拿挑战
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("userName", user);
        first.put("password", "");
        first.put("clientType", "Web5.0");
        Map<String, Object> resp = post(session, httpPort, "global.login", first, null, false);
        Object p1 = resp.get("params");
        if (!(p1 instanceof Map<?, ?> challenge)) {
            throw new DahuaSdkException("设备 RPC2 登录未返回挑战参数", 0, describe(resp));
        }
        /*
         * 第一步返回 result:false + error.code 268632079（login challenge），
         * 这是<b>正常</b>的挑战响应，不能按失败处理。
         */
        String sid0 = topSession(resp);
        Object realmObj = challenge.get("realm");
        Object randomObj = challenge.get("random");
        if (realmObj == null || randomObj == null) {
            /* 已免密 / 已登录的设备第一步就直接给 session，不用走第二步。 */
            if (sid0 != null) {
                sessions.put(keyOf(session, httpPort), sid0);
                return sid0;
            }
            throw new DahuaSdkException("设备 RPC2 登录未返回 realm/random", 0, describe(resp));
        }
        String realm = String.valueOf(realmObj);
        String random = String.valueOf(randomObj);

        // ② 用挑战算摘要再登录
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("userName", user);
        second.put("password", digestPassword(user, realm, random, pass));
        second.put("clientType", "Web5.0");
        second.put("realm", realm);
        second.put("random", random);
        second.put("passwordType", "Default");
        second.put("authorityType", "Default");
        Map<String, Object> resp2 = post(session, httpPort, "global.login", second, sid0, false);
        /*
         * 第二步成功：{"params":{"isPwdOverdue":false,"keepAliveInterval":60},
         *             "result":true,"session":"6c47..."}
         * 失败：result:false + error.code 268632085 + params.remainLoginTimes。
         */
        String sid = topSession(resp2);
        if (Boolean.FALSE.equals(resp2.get("result")) || sid == null) {
            String remain = "";
            Object p2 = resp2.get("params");
            if (p2 instanceof Map<?, ?> m && m.get("remainLoginTimes") != null) {
                remain = "（剩余尝试次数 " + m.get("remainLoginTimes") + "）";
            }
            throw new DahuaSdkException("设备 RPC2 登录被拒（账号或密码不对）" + remain,
                    0, describe(resp2));
        }
        sessions.put(keyOf(session, httpPort), sid);
        return sid;
    }

    /**
     * 取响应<b>顶层</b>的 {@code session} 字段。
     *
     * <p>设备的 {@code global.login} 把 session 放在顶层，而业务方法的响应
     * 往往把它放在 {@code params} 里；这里只处理登录场景，所以只看顶层。
     */
    private static String topSession(Map<String, Object> resp) {
        Object s = resp == null ? null : resp.get("session");
        return s == null ? null : String.valueOf(s);
    }

    /**
     * 设备的两步登录摘要，<b>从设备前端 JS 反解并双样本验证</b>。
     *
     * <pre>
     * inner    = MD5(user + ":" + realm + ":" + pass).toUpperCase()
     * password = MD5(user + ":" + random + ":" + inner).toUpperCase()
     * </pre>
     *
     * <h3>三个必须踩准的点</h3>
     * <ol>
     *   <li><b>分隔符是 {@code ":"}</b>，不是直接拼接</li>
     *   <li><b>内层要转大写</b>再参与外层计算 —— 只差这一步，算出来就完全不同</li>
     *   <li>{@code realm} 必须用设备给的<b>完整串</b>（含 {@code "Login to "} 前缀），
     *       截掉前缀会导致校验失败并扣减 {@code remainLoginTimes}</li>
     * </ol>
     *
     * <p>实测样本（固件 3.201.0000007.1.R，user=admin）：
     * <pre>
     * realm="Login to 5354e6647df99b484f415913f167f5d5"
     * inner = 2688419B17D83C5E96A7267513BE7FF2
     * random=119962034  → 222125404C774CF1902F3BA716E4138A  ✓
     * random=1579031029 → 0462AA1967465616F647655F37D119E1  ✓
     * </pre>
     */
    private static String digestPassword(String user, String realm, String random, String pass) {
        String inner = md5Hex(user + ":" + realm + ":" + pass).toUpperCase();
        return md5Hex(user + ":" + random + ":" + inner).toUpperCase();
    }

    private static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] out = md.digest(s.getBytes(UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new DahuaSdkException("本机不支持 MD5", 0, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(DahuaSdk.Session session, int httpPort, String method,
                                     Map<String, Object> params, String sid, boolean withObject) {
        /*
         * global.login 必须走 /RPC2_Login，其余业务方法走 /RPC2。
         * 往 /RPC2 发 global.login 会被设备直接断 TCP（无响应）。
         */
        String path = "global.login".equals(method) ? LOGIN_PATH : PATH;
        String url = "http://" + session.host() + ":" + port(httpPort) + path;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("method", method);
        body.put("params", params);
        body.put("id", nextId());
        if (sid != null) {
            body.put("session", sid);
        }
        if (withObject) {
            Long obj = objectId(session, httpPort, sid);
            if (obj != null) {
                body.put("object", obj);
            }
        }
        String json = toJson(body);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        try {
            /*
             * 直接发，不带任何 HTTP 认证头。
             *
             * 设备 RPC2 的鉴权在 JSON body 里（session 串），不在 HTTP 层：
             * 实测一旦带上 Authorization: Digest，设备直接断开连接（无响应），
             * 报出来就是「连接设备 RPC2 失败」。早期版本就是这么错的。
             *
             * 但要带上登录时下发的 cookie，否则设备认不出会话。
             */
            HttpResponse<String> resp = send(client, url, json, null, cookies.get(keyOf(session, httpPort)));
            if (resp.statusCode() != 200) {
                throw new DahuaSdkException("设备 RPC2 返回 HTTP " + resp.statusCode(),
                        resp.statusCode(), "确认设备 Web 端口正确（默认 80）");
            }
            /* 登录响应会下发 WebClientHttpSessionID，缓存起来供后续业务调用使用。 */
            resp.headers().firstValue("Set-Cookie").ifPresent(setCookie -> {
                String pair = setCookie.split(";", 2)[0].trim();
                if (pair.startsWith("WebClientHttpSessionID=")) {
                    cookies.put(keyOf(session, httpPort), pair);
                }
            });
            Object parsed = parseJson(resp.body());
            if (!(parsed instanceof Map)) {
                throw new DahuaSdkException("设备 RPC2 响应不是 JSON 对象", 0,
                        "响应：" + truncate(resp.body()));
            }
            return (Map<String, Object>) parsed;
        } catch (IOException e) {
            /*
             * 设备对「格式不对的请求」会直接断连而不是回 4xx，所以这里
             * 不能武断地说「Web 服务关闭」—— 预置点走 SDK 能用就说明设备是通的。
             */
            throw new DahuaSdkException("连接设备 RPC2 失败（" + session.host() + ":"
                    + port(httpPort) + "）", 0,
                    "设备拒绝了这次请求（原样：" + e.getMessage() + "）。"
                            + "常见原因：登录挑战未完成，或设备在巡航/高负载时暂时拒绝 RPC2");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DahuaSdkException("连接设备 RPC2 被中断", 0, null);
        }
    }

    /**
     * 取球机 object id（{@code ptz.*} 方法都要带）。
     *
     * <p>正确姿势是 {@code ptz.factory.instance} 带 {@code {"channel":0}}，
     * 响应里 {@code result} 就是 object id（实测 {@code 79203864}）。
     *
     * <p>早先这里用 {@code ptz.getPresets} 去探，但那个方法本身就<b>需要</b> object，
     * 不带 object 会返回 {@code -267976703}，于是永远取不到 —— 后续所有
     * {@code ptz.*} 调用都失败。{@code ptz.factory.instance} 不带 channel 时
     * 返回 {@code 0}，也不能用。
     */
    private Long objectId(DahuaSdk.Session session, int httpPort, String sid) {
        String key = keyOf(session, httpPort);
        Long cached = objects.get(key);
        if (cached != null && cached > 0) {
            return cached;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("channel", 0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("method", "ptz.factory.instance");
        body.put("params", params);
        body.put("id", nextId());
        if (sid != null) {
            body.put("session", sid);
        }
        String json = toJson(body);
        String url = "http://" + session.host() + ":" + port(httpPort) + PATH;
        HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        try {
            /* 同 post()：不带 Authorization，只带会话 cookie。 */
            HttpResponse<String> resp = send(client, url, json, null, cookies.get(keyOf(session, httpPort)));
            Object parsed = parseJson(resp.body());
            if (parsed instanceof Map<?, ?> m && m.get("result") instanceof Number n) {
                long id = n.longValue();
                if (id > 0) {
                    objects.put(key, id);
                    return id;
                }
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        return null;
    }

    private static HttpResponse<String> send(HttpClient client, String url, String body,
                                             String authorization) throws IOException,
            InterruptedException {
        return send(client, url, body, authorization, null);
    }

    private static HttpResponse<String> send(HttpClient client, String url, String body,
                                             String authorization, String cookie)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(CALL_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8));
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        if (cookie != null && !cookie.isEmpty()) {
            b.header("Cookie", cookie);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString(UTF_8));
    }

    private static String keyOf(DahuaSdk.Session session, int httpPort) {
        return session.host() + ":" + port(httpPort);
    }

    private static int port(int httpPort) {
        return httpPort > 0 ? httpPort : DahuaCgiAccess.DEFAULT_HTTP_PORT;
    }

    private static long idSeq = 10_000;

    private static synchronized long nextId() {
        return ++idSeq;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    // ==================== 极简 JSON ====================

    /**
     * 序列化。只覆盖本类用到的类型（String / Number / Boolean / Map / List / null），
     * 不引第三方库 —— sdk-dahua 的目标是不给宿主应用带额外依赖。
     */
    static String toJson(Object v) {
        StringBuilder sb = new StringBuilder();
        writeJson(sb, v);
        return sb.toString();
    }

    private static void writeJson(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            escape(sb, s);
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                escape(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeJson(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeJson(sb, o);
            }
            sb.append(']');
        } else if (v instanceof Object[] arr) {
            sb.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeJson(sb, arr[i]);
            }
            sb.append(']');
        } else {
            escape(sb, String.valueOf(v));
        }
    }

    private static void escape(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /**
     * 解析。只支持标准 JSON 子集（对象/数组/字符串/数字/布尔/null），
     * 足够处理设备响应 —— 它不返回注释、单引号或尾随逗号。
     */
    static Object parseJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value();
        return v;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        Object value() {
            skipWs();
            if (i >= s.length()) {
                return null;
            }
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            skipWs();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (i < s.length()) {
                skipWs();
                String k = string();
                skipWs();
                if (i < s.length() && s.charAt(i) == ':') {
                    i++;
                }
                m.put(k, value());
                skipWs();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                if (i < s.length() && s.charAt(i) == '}') {
                    i++;
                }
                break;
            }
            return m;
        }

        List<Object> array() {
            List<Object> list = new ArrayList<>();
            i++; // [
            skipWs();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return list;
            }
            while (i < s.length()) {
                list.add(value());
                skipWs();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                if (i < s.length() && s.charAt(i) == ']') {
                    i++;
                }
                break;
            }
            return list;
        }

        String string() {
            if (i >= s.length() || s.charAt(i) != '"') {
                return "";
            }
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    break;
                }
                if (c == '\\' && i < s.length()) {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (i + 4 <= s.length()) {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                            }
                        }
                        default -> sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Object literal(String lit, Object v) {
            if (s.startsWith(lit, i)) {
                i += lit.length();
                return v;
            }
            return number();
        }

        Object number() {
            int start = i;
            while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            String t = s.substring(start, i);
            if (t.isEmpty()) {
                i++;
                return null;
            }
            try {
                if (t.contains(".") || t.contains("e") || t.contains("E")) {
                    return Double.parseDouble(t);
                }
                long l = Long.parseLong(t);
                return l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE ? (Object) (int) l : (Object) l;
            } catch (NumberFormatException e) {
                return t;
            }
        }
    }

}
