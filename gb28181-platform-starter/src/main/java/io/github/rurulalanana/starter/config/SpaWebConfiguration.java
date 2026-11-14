package io.github.rurulalanana.starter.config;

import io.github.rurulalanana.starter.service.RecordUrlSigner;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 前端单页应用（SPA）路由回退。Vue Router 用 history 模式，直接访问 {@code /dashboard} 等路由时
 * Spring 找不到控制器会 404，这里把非 API、非静态资源、无扩展名的路径转发到 {@code index.html}。
 *
 * <p>排除清单必须覆盖所有一级静态资源目录：否则 {@code /jessibuca/decoder.js} 这类
 * 「目录 + 带扩展名」的路径会被 {@code /{path}/**} 吃掉，返回 {@code index.html}，
 * 播放器脚本拿到 HTML 无法加载。
 */
@Configuration
public class SpaWebConfiguration implements WebMvcConfigurer {

    /**
     * 交给静态资源/框架自己处理的路径首段，不参与 SPA 兜底。
     * {@code webjars}/{@code swagger-ui}/{@code v3} 属接口文档：Knife4j 的界面在 {@code /doc.html}，
     * 脚本样式在 {@code /webjars/**}。漏了 webjars 的症状是「doc.html 打得开但白屏」——
     * 资源请求被兜底规则吞掉返回 index.html，浏览器按 JS 解析直接语法错误。
     *
     * <p><b>必须带 {@code (?:/|$)} 边界</b>：这个串被直接嵌进负向前瞻，
     * 没有边界就是<b>纯前缀匹配</b>。{@code api} 会吃掉 {@code /apikeys}、
     * {@code record} 会吃掉 {@code /recordings}、{@code error} 会吃掉 {@code /errors}，
     * {@code js} 会吃掉 {@code /jsapi} —— 这些路径会被当成静态资源，
     * 不走 SPA 兜底，浏览器刷新直接 404。
     *
     * <p>路由由后端 {@code gb_menu} 表驱动，「菜单管理」里新配的路径就是
     * 用户可触发的地雷，所以边界必须现在就加上，而不是等现场报了 404 再查。
     */
    private static final String STATIC_PREFIXES =
            "(?:api|assets|record|error|jessibuca|js|webjars|swagger-ui|v3|locales|static)(?:/|$)";

    /**
     * 单段 SPA 兜底的正则（{@code {path:...}} 里那一段）。
     *
     * <p>抽成方法是为了能单测：前缀匹配有没有词边界，只能靠断言具体路径来验证。
     *
     * <p><b>它只用于「首段是否算静态资源前缀」的判定</b>，不要再塞回
     * {@code registry.addViewController("/{path:" + ... + "}")}：Spring Boot 3 的
     * {@code PathPatternParser} 不允许捕获变量的正则里出现 {@code /}
     * （本正则的 {@code (?:/|$)} 边界就带斜杠），启动时直接报
     * {@code Invalid mapping pattern detected ... Expected close capture character}，
     * 整站起不来。兜底改由 {@link SpaFallbackResourceResolver} 在资源解析阶段完成。
     */
    static String spaFallbackRegex() {
        return "^(?!" + STATIC_PREFIXES + ")[^\\.]*$";
    }

    /** 编译一次，资源解析是热路径。 */
    private static final Pattern SPA_FIRST_SEGMENT = Pattern.compile(spaFallbackRegex());

    /**
     * 这个资源路径是否应该回退到 {@code index.html}（即「前端路由」）。
     *
     * <p>两条判据缺一不可：
     * <ul>
     *   <li>首段不在静态资源前缀表里 —— 否则 {@code /api/v1/login} 会被换成 HTML；
     *       前缀表用 {@link #spaFallbackRegex()} 判，保证 {@code apikeys}、{@code recordings}
     *       这类「前缀 + 更多字符」不会被误伤（有单测盯住）；</li>
     *   <li>末段不含点号 —— 带扩展名的是资源请求，找不到文件就该 404，
     *       而不是回一张 index.html（浏览器按 JS 解析直接语法错误）。</li>
     * </ul>
     */
    static boolean isSpaRoute(String resourcePath) {
        if (resourcePath == null || resourcePath.isEmpty()) {
            return false;
        }
        String path = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        int slash = path.indexOf('/');
        String first = slash < 0 ? path : path.substring(0, slash);
        String last = path.substring(path.lastIndexOf('/') + 1);
        if (first.isEmpty() || last.isEmpty()) {
            return false;
        }
        return SPA_FIRST_SEGMENT.matcher(first).matches() && !last.contains(".");
    }

    /**
     * SPA 兜底解析器。
     *
     * <p>为什么不用 {@code addViewControllers}：见 {@link #spaFallbackRegex()} 的注释 ——
     * PathPattern 不接受带斜杠的捕获正则。放在资源解析阶段还有个额外好处：
     * 它的优先级天然低于所有 {@code @RequestMapping}、WebSocket 握手和更具体的
     * 静态资源 handler（{@code /assets/**}、{@code /js/**}、{@code /record/**}），
     * 只有「确实没有这个文件」时才会被问到，不会抢走接口或播放器脚本的请求。
     */
    static class SpaFallbackResourceResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            // 先交给父类做存在性检查与 ../ 越界拦截，命中就正常返回文件
            Resource found = super.getResource(resourcePath, location);
            if (found != null) {
                return found;
            }
            return isSpaRoute(resourcePath)
                    ? new ClassPathResource("static/index.html")
                    : null;
        }
    }

    private final Gb28181Properties props;

    /** 录像地址签名器：本地静态映射（{@code record-static-enabled}）也要校验。 */
    private final RecordUrlSigner recordUrlSigner;

    public SpaWebConfiguration(Gb28181Properties props, RecordUrlSigner recordUrlSigner) {
        this.props = props;
        this.recordUrlSigner = recordUrlSigner;
    }

    /**
     * 本地静态映射下的录像鉴权。标准部署由 nginx 读磁盘、鉴权走 {@code auth_request} 子请求；
     * 但开了 {@code gb28181.record-static-enabled} 时 {@code /record/**} 由本进程发出，
     * 既不经过 nginx 也不经过 Sa-Token（它只拦 {@code /api/v1/**}），不在这里拦就是「换部署形态鉴权就没了」。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                                     Object handler) {
                String query = request.getQueryString();
                String uri = request.getRequestURI() + (query == null ? "" : "?" + query);
                if (recordUrlSigner.allow(uri)) {
                    return true;
                }
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return false;
            }
        }).addPathPatterns("/record/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 缓存策略是「发版后用户是否需要强刷」的关键。不配 Cache-Control 时浏览器走启发式缓存
        // （取距上次修改时间的 10% 当新鲜期）：跑几天后 index.html 新鲜期变成几小时，
        // 用户拿到旧 index.html，引用已被 emptyOutDir 删掉的旧 chunk → 懒加载路由 404 白屏。
        // 实测线上散落各种旧 hash 的 chunk 404（LoginView-CW1M4nM3.js 等）。
        // 正确做法：index.html 用 no-cache（回源协商，没变 304）；带内容 hash 的产物一年 immutable。
        registry.addResourceHandler("/index.html")
                .addResourceLocations("classpath:/static/index.html")
                .setCacheControl(CacheControl.noCache().mustRevalidate());

        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());

        // 图标：文件名固定，但内容基本不变，给一天足够（发版换图标时最多脏一天）
        registry.addResourceHandler("/favicon.svg")
                .addResourceLocations("classpath:/static/favicon.svg")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic());

        // 播放器 SDK（jessibuca.js + decoder.wasm，约 1.3MB）：文件名无内容哈希，
        // 用 7 天而非 immutable，升级 SDK 后最多脏缓存一周，避免每次进实时预览都重下。
        registry.addResourceHandler("/jessibuca/**")
                .addResourceLocations("classpath:/static/jessibuca/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic());

        // 第三方播放器脚本（liveplayer-lib.min.js 1.2MB + 组件 160KB）必须单独注册并入白名单：
        // /js/x.min.js 是「目录 + 带扩展名」的两段路径，会被 SPA 兜底 /{path}/** 吃掉，
        // 前端拿到 index.html，表现为 videojs 未定义、直播页起不来。文件名固定故用 7 天缓存。
        registry.addResourceHandler("/js/**")
                .addResourceLocations("classpath:/static/js/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic());

        // 接口文档静态资源：/{path}/** 兜底会把 /webjars/js/*.js 也转发到 index.html，
        // 文档页拿到 HTML 当脚本 → 白屏 + 语法错误。资源在 knife4j-openapi3-ui 的
        // META-INF/resources/webjars/ 下，文件名带内容哈希，可按 immutable 长缓存。
        registry.addResourceHandler("/webjars/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic().immutable());

        // 录像文件默认交给 nginx：Spring 静态资源处理器处理 Range 极慢，同一段 2MB 实测
        // 16~20 秒（110 KB/s），nginx（sendfile + keep-alive）只要 0.06~0.15 秒（24 MB/s），
        // 差 200 倍，直接决定「起播 15 秒」还是「2 秒」。标准部署见 deploy/nginx-record.conf。
        // 只有无 nginx 的单机/内网部署才打开 record-static-enabled 回退到本进程。
        if (props.isRecordStaticEnabled()) {
            Path root = resolveRecordRoot();
            if (Files.isDirectory(root)) {
                String location = root.toUri().toString();
                if (!location.endsWith("/")) {
                    location = location + "/";
                }
                registry.addResourceHandler("/record/**")
                        .addResourceLocations(location)
                        // 录像文件写一次就不再变，但文件名没有内容哈希，
                        // 用较短缓存避免「删了还能播」的错觉
                        .setCacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic());
            }
        }

        // 兜底必须最后注册，见 addSpaFallback 的注释
        addSpaFallback(registry);
    }

    /** 解析录像根目录（绝对路径）。 */
    private Path resolveRecordRoot() {
        return Path.of(props.resolvedRecordRoot()).toAbsolutePath().normalize();
    }

    /**
     * 把录像文件路径换算成 {@code /record/...} URL，供接口层回填给前端直接 {@code <video src>} 或 Range 下载。
     * 返回值带签名：{@code /record/**} 由 nginx 绕过 Spring 直接发文件，Sa-Token 拦不到，
     * 不签名等于「知道路径就能下载」。这里是全平台产出录像地址的唯一出口，保证各场景不遗漏。
     * 地址与「文件由谁提供」无关（同源相对路径），文件不在录像根目录下返回 {@code null}。
     */
    public String toStaticUrl(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        try {
            Path p = Path.of(filePath).toAbsolutePath().normalize();
            Path rel = resolveRecordRoot().relativize(p);
            if (rel.startsWith("..")) {
                return null;
            }
            return recordUrlSigner.sign("/record/" + rel.toString().replace('\\', '/'));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 把前端路由交给 {@link SpaFallbackResourceResolver} 兜底。
     *
     * <p>必须**最后**注册：同一个 {@code SimpleUrlHandlerMapping} 里，
     * 更具体的模式（{@code /assets/**}、{@code /js/**}、{@code /webjars/**}、
     * {@code /record/**}）优先匹配，{@code /**} 只是兜底。
     *
     * <p>资源目录带上 Spring Boot 默认的那几个（{@code /public/}、{@code /resources/}、
     * {@code /META-INF/resources/}）：knife4j 的 {@code /doc.html} 就住在
     * {@code META-INF/resources} 下，只配 {@code classpath:/static/} 会把它弄丢。
     */
    private void addSpaFallback(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations(
                        "classpath:/static/",
                        "classpath:/public/",
                        "classpath:/resources/",
                        "classpath:/META-INF/resources/")
                // 回退出的 index.html 必须回源协商：发版后用户不能还拿着旧 index.html
                // 去引用已被 emptyOutDir 删掉的旧 chunk
                .setCacheControl(CacheControl.noCache().mustRevalidate())
                .resourceChain(false)
                .addResolver(new SpaFallbackResourceResolver());
    }
}
