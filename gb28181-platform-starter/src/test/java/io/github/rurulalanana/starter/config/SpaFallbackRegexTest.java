package io.github.rurulalanana.starter.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPA 兜底正则：静态资源前缀必须是<b>整段</b>匹配，不能是纯前缀。
 *
 * <h3>为什么值得单独测</h3>
 * {@code STATIC_PREFIXES} 被直接嵌进负向前瞻 {@code (?!...)}，没有词边界时是纯前缀匹配。
 * 后果不是「多转发一次」，而是<b>用户页面打不开</b>：
 * 一个叫 {@code /recordings} 的菜单会被 {@code record} 吃掉，
 * 判成静态资源、不走 SPA 兜底，浏览器刷新直接 404。
 *
 * <p>而路由是后端 {@code gb_menu} 表驱动的 —— 「菜单管理」里新配一个路径就触发了，
 * 不需要改代码，所以这类 bug 只会在现场出现，开发时看不见。
 */
class SpaFallbackRegexTest {

    /** 单段兜底正则（与 {@code addViewControllers} 里用的是同一个）。 */
    private static final Pattern FALLBACK =
            Pattern.compile(SpaWebConfiguration.spaFallbackRegex());

    /** 走 SPA 兜底 = 该路径会被转发到 index.html。 */
    private static boolean fallsBack(String firstSegment) {
        return FALLBACK.matcher(firstSegment).matches();
    }

    @Test
    @DisplayName("静态资源首段本身不兜底（否则资源请求被换成 index.html）")
    void staticPrefixesExcludedExactly() {
        for (String seg : new String[]{
                "api", "assets", "record", "error", "jessibuca", "js",
                "webjars", "swagger-ui", "v3", "locales", "static"}) {
            assertFalse(fallsBack(seg), "「" + seg + "」应判为静态资源，不参与 SPA 兜底");
        }
    }

    @Test
    @DisplayName("以静态前缀开头但更长的路径仍要兜底（这是这次修复的核心）")
    void longerNamesStillFallBack() {
        // 每一个都是「某个静态前缀 + 更多字符」，没有词边界时会被误判
        for (String seg : new String[]{
                "apikeys",        // api
                "recordings",     // record
                "errors",         // error
                "jsapi",          // js
                "locales-report", // locales
                "staticfiles",    // static
                "assets2"         // assets
        }) {
            assertTrue(fallsBack(seg), "「" + seg + "」不该被前缀误伤，应走 SPA 兜底");
        }
    }

    @Test
    @DisplayName("普通菜单路径正常兜底")
    void normalRoutesFallBack() {
        for (String seg : new String[]{
                "dashboard", "live", "devices", "channels", "playback",
                "cloud", "alarms", "cascade", "users", "roles",
                "menus", "oplogs", "settings", "statistics"}) {
            assertTrue(fallsBack(seg), "「" + seg + "」应走 SPA 兜底");
        }
    }

    @Test
    @DisplayName("带扩展名的路径不兜底，交给静态资源处理器")
    void dottedPathsExcluded() {
        // 这条不靠前缀表，靠正则里的 [^\.]* —— 资源请求必须能落到真正的文件上
        assertFalse(fallsBack("index.html"));
        assertFalse(fallsBack("main.js"));
        assertFalse(fallsBack("app.css"));
        assertFalse(fallsBack("favicon.ico"));
    }

    @Test
    @DisplayName("大小写敏感：/API 不是静态资源前缀，按 SPA 路由处理")
    void caseSensitive() {
        // Spring 的路径匹配默认大小写敏感，前缀表也应当如此。
        // 若哪天改成忽略大小写，/APIxxx 就会被误判成静态资源，这条断言会拦住。
        assertTrue(fallsBack("API"));
        assertTrue(fallsBack("Record"));
    }

    // ------------------------------------------------------------------
    // 多段路径：兜底改由资源解析器判定（PathPattern 不允许捕获正则里带 /），
    // 所以这里盯住 isSpaRoute 的两条判据：首段前缀 + 末段无扩展名。
    // ------------------------------------------------------------------

    @Test
    @DisplayName("多段前端路由（/cloud/detail、/share/xxx）要兜底")
    void multiSegmentRoutesFallBack() {
        for (String path : new String[]{
                "cloud/detail", "cloud/detail/list", "cloud/plans",
                "share/abc123", "monitor/live/1"}) {
            assertTrue(SpaWebConfiguration.isSpaRoute(path), "「" + path + "」应回退 index.html");
        }
    }

    @Test
    @DisplayName("多段资源路径不兜底：接口、脚本、录像都必须落到真正的处理器")
    void multiSegmentResourcesExcluded() {
        for (String path : new String[]{
                "api/v1/login",          // 接口被换成 HTML 就等于接口挂了
                "assets/js/index-abc.js",
                "js/liveplayer-lib.min.js",
                "webjars/js/app.js",
                "record/2026/11/14/a.mp4",
                "jessibuca/decoder.wasm",
                "doc.html"}) {
            assertFalse(SpaWebConfiguration.isSpaRoute(path), "「" + path + "」不该被 SPA 兜底吃掉");
        }
    }

    @Test
    @DisplayName("命名空间以静态前缀开头但更长的多段路由仍要兜底")
    void longerFirstSegmentStillFallsBack() {
        assertTrue(SpaWebConfiguration.isSpaRoute("apikeys/list"));
        assertTrue(SpaWebConfiguration.isSpaRoute("recordings/1"));
    }
}
