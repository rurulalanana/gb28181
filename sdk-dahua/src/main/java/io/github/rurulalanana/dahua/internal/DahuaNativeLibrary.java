package io.github.rurulalanana.dahua.internal;

import io.github.rurulalanana.deviceaccess.ConnectMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * 大华原生库加载前置检查。
 *
 * <h3>为什么需要它</h3>
 * SDK 的 {@code LibraryLoad} 按 {@code os.name}/{@code os.arch} 决定加载哪个平台目录
 * （{@code win64} / {@code linux64} / {@code mac64} …），从 classpath 读库文件、
 * 解压到 {@code java.io.tmpdir} 后交给 JNA。若 jar 里没有对应平台目录，它会
 * <b>静默失败</b>（提取异常被空 catch 吞掉），最终报：
 * <pre>
 * Unable to load library '/tmp/libdhnetsdk.so':
 *   cannot open shared object file: No such file or directory
 * </pre>
 * 这个错看起来像路径问题，实际根因通常是<b>打包时用错了平台</b>
 * （在 Windows 上构建出 win64 包却部署到 Linux）。
 *
 * <p>本类在 SDK 初始化前主动探测 jar 内是否有当前平台的库，缺失时给出
 * <b>可操作的中文提示</b>，把上面那个容易误判的报错替换掉。
 *
 * <p>只做检查与告警，<b>不修改任何状态</b> —— 解压与加载完全交给 SDK 的
 * {@code LibraryLoad}，避免两套逻辑各算一个路径而互相干扰。
 */
public final class DahuaNativeLibrary {

    private static final Logger log = LoggerFactory.getLogger(DahuaNativeLibrary.class);

    /** 当前平台在 jar 内的目录名；无法识别时为 {@code null}。 */
    private static final String PLATFORM_DIR = resolvePlatformDir();

    /** 探测结果缓存：探测要读 classpath，不必每次重复。 */
    private static volatile Boolean available;

    private DahuaNativeLibrary() {
    }

    /**
     * jar 内是否含当前平台的原生库。
     *
     * <p>为 {@code false} 时所有大华功能都应快速失败，
     * 避免反复抛出底层库加载异常、把日志刷满。
     */
    public static boolean available() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        synchronized (DahuaNativeLibrary.class) {
            if (available != null) {
                return available;
            }
            available = probe();
            return available;
        }
    }

    /**
     * 探测并（首次）打印诊断信息。
     *
     * @return 同 {@link #available()}
     */
    private static boolean probe() {
        if (PLATFORM_DIR == null) {
            log.warn("无法识别当前平台({}/{})，跳过大华原生库检查",
                    System.getProperty("os.name"), System.getProperty("os.arch"));
            // 无法判断时不阻断：交给 SDK 自己决定，失败信息仍有 {@link #diagnose()} 兜底
            return true;
        }
        String probePath = PLATFORM_DIR + "/" + libFileName();
        boolean found = DahuaNativeLibrary.class.getClassLoader().getResource(probePath) != null;
        if (found) {
            log.debug("大华原生库检查通过: jar 内含 {}", probePath);
            return true;
        }
        log.error("======================================================================");
        log.error("大华 SDK 原生库缺失：当前平台为 {}，但 jar 内没有 {} 目录下的库文件",
                PLATFORM_DIR, PLATFORM_DIR);
        log.error("打包时很可能选错了平台。请显式指定平台重新构建（注意 PowerShell 要加引号）：");
        log.error("  Linux  : mvn install \"-Dnetsdk.platform=linux64\"");
        log.error("  Windows: mvn install \"-Dnetsdk.platform=win64\"");
        log.error("（profile 会按操作系统自动激活；交叉打包时用上面的 -D，不要用 -P）");
        log.error("生成原生库的步骤见 sdk-dahua/docs/原生库打包.md");
        log.error("======================================================================");
        return false;
    }

    /**
     * 当前平台在 jar 内的目录名（{@code win64} / {@code linux64} …）。
     *
     * @return 无法识别时返回 {@code null}
     */
    public static String platformDir() {
        return PLATFORM_DIR;
    }

    /**
     * 一行诊断摘要，供接口层展示「大华为什么不可用」。
     *
     * <p>运维看到「不支持该设备类型」是无从下手的，必须能说清缺什么。
     */
    public static String diagnose() {
        String os = System.getProperty("os.name");
        String arch = System.getProperty("os.arch");
        if (!available()) {
            return String.format("大华原生库缺失（当前平台 %s/%s，期望 jar 内有 %s/ 目录）；"
                            + "请用正确的 profile 重新构建，见 docs/原生库打包.md",
                    os, arch, PLATFORM_DIR);
        }
        return String.format("大华原生库就绪（%s/%s，%s/）", os, arch, PLATFORM_DIR);
    }

    /** 当前平台对应的目录名；无法识别时返回 {@code null}。 */
    private static String resolvePlatformDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean is64 = arch.contains("64") || arch.contains("aarch64");

        if (os.startsWith("windows")) {
            return is64 ? "win64" : "win32";
        }
        if (os.startsWith("linux")) {
            if (arch.startsWith("arm") || arch.startsWith("aarch")) {
                // 本项目只打包 linux64；ARM 需自行补库并加 profile
                return "linuxARM";
            }
            return is64 ? "linux64" : "linux32";
        }
        if (os.startsWith("mac") || os.startsWith("darwin")) {
            return "mac64";
        }
        return null;
    }

    /** 探测用的库文件名（与 {@code LibraryLoad.getLibraryName()} 的规则一致）。 */
    private static String libFileName() {
        if (PLATFORM_DIR == null) {
            return "";
        }
        if (PLATFORM_DIR.startsWith("win")) {
            return "dhnetsdk.dll";
        }
        if (PLATFORM_DIR.startsWith("linux")) {
            return "libdhnetsdk.so";
        }
        return "libdhnetsdk.dylib";
    }

    /**
     * 判断某次失败是否属于「原生库根本没加载起来」。
     *
     * <p>用于把 JNA 的英文异常翻译成中文提示。判据是 JNA 的标准错误文案：
     * {@code Unable to load library} / {@code UnsatisfiedLinkError}。
     */
    public static boolean isLibraryLoadFailure(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String msg = c.getMessage();
            if (msg == null) {
                continue;
            }
            String m = msg.toLowerCase(Locale.ROOT);
            if (m.contains("unable to load library")
                    || m.contains("no dhnetsdk in java.library.path")
                    || m.contains("cannot open shared object file")) {
                return true;
            }
            if (c instanceof UnsatisfiedLinkError) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按连接模式给出「该怎么配」的提示。
     *
     * <p>SERVER 模式没收到注册时，最常见的原因不是平台问题而是
     * <b>设备侧没配主动注册</b> —— 这一点必须写进错误信息，
     * 否则运维会一直查平台。
     */
    public static String hintFor(ConnectMode mode) {
        if (mode == ConnectMode.SERVER) {
            return "设备尚未主动注册到本平台：请在设备 Web 界面「网络 → 主动注册」中"
                    + "填写本平台地址与监听端口，并确认防火墙已放行该端口";
        }
        return "请检查设备地址/端口是否可达、用户名密码是否正确";
    }
}
