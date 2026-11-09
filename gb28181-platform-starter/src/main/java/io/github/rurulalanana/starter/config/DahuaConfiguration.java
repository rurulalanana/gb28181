package io.github.rurulalanana.starter.config;

import io.github.rurulalanana.dahua.DahuaDeviceAccessProvider;
import io.github.rurulalanana.dahua.DahuaRegisterListener;
import io.github.rurulalanana.dahua.DahuaRegisterTable;
import io.github.rurulalanana.dahua.internal.DahuaNativeLibrary;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import org.springframework.context.annotation.Lazy;
import io.github.rurulalanana.deviceaccess.RegisterListener;
import io.github.rurulalanana.starter.service.DeviceProvisionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 大华 SDK 直连的装配。本类只<b>注册 bean 定义</b>、不实例化：{@code @Bean} 方法是懒的，
 * 只有有人注入 {@code DahuaDeviceAccessProvider} 时才执行方法体，而平台侧唯一取用处
 * {@code DeviceAccessRegistry} 用 {@link ObjectProvider} 延迟解析 —— 所以 SDK 装没装好不影响启动。
 *
 * <p>实测过「大华 SDK 缺失导致整个平台起不来，连国标链路一起废掉」：根因是 {@code NetSDKLib}
 * 静态块会 {@code Native.load} 原生库，容器启动期一旦触发就无法挽回。
 *
 * <p>原生库缺失时 provider 仍被注册（否则无法区分「不支持该类型」与「支持但当前不可用」），
 * 但每个方法快速失败并说明缺什么，见 {@link DahuaNativeLibrary#diagnose()}。
 *
 * <p>SERVER 模式（设备主动注册）默认关闭：它要占用一个端口，且必须在设备侧配置才有意义。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(DahuaProperties.class)
public class DahuaConfiguration {

    /**
     * 接入实现。{@code rtspPort} 影响播放地址，{@code channelTotal} 影响子码流通道号推算
     * （大华把子码流排在主码流之后）。
     */
    @Bean
    public DahuaDeviceAccessProvider dahuaDeviceAccessProvider(DahuaProperties props) {
        log.info("装配大华接入实现: enabled={} rtspPort={} channelTotal={}",
                props.isEnabled(), props.getRtspPort(), props.getChannelTotal());
        return new DahuaDeviceAccessProvider(props.getRtspPort(), props.getChannelTotal());
    }

    /**
     * 设备主动注册表（SERVER 模式的会话地址表）。
     *
     * <h3>⚠️ 这个 bean 必须无条件创建，不能加 {@code @ConditionalOnProperty}</h3>
     * 早期它跟着 {@code server-mode.enabled} 一起是条件的，结果
     * {@code DahuaController} 的构造参数里有它 —— <b>关掉 SERVER 模式的部署直接启动失败</b>：
     * <pre>
     * UnsatisfiedDependencyException: Error creating bean with name 'dahuaController'
     *   ... No qualifying bean of type 'DahuaRegisterTable' available
     * </pre>
     * 本地一直带 {@code --dahua.server-mode.enabled=true} 启动，所以开发期完全看不到；
     * 线上（server profile，未开 SERVER 模式）一部署就起不来。实测踩过。
     *
     * <p>它本身只是一个内存 {@code ConcurrentHashMap}：不开端口、不起线程、
     * 不碰数据库，无条件创建的成本是零。反过来，无条件创建能让
     * {@code /dahua/register/*} 这几个接口在未开 SERVER 模式时返回空列表，
     * 而不是 500 或让整个应用起不来。
     *
     * <p>需要跟着条件走的只有真正有副作用的两个：
     * {@link #dahuaRegisterListener}（占端口）和 {@link #dahuaAutoProvisionRunner}（写库）。
     */
    @Bean
    public DahuaRegisterTable dahuaRegisterTable(DahuaProperties props) {
        long expireMs = props.getServerMode().getExpireSeconds() * 1000L;
        log.info("大华主动注册表: 失效阈值 {} 秒（SERVER 模式{}）", expireMs / 1000,
                props.getServerMode().isEnabled() ? "已启用" : "未启用，仅登记内存不监听端口");
        DahuaRegisterTable table = new DahuaRegisterTable(expireMs);
        /*
         * 注入「活跃登录会话」保活判定。
         *
         * 大华设备主动注册后不会周期性重发注册报文：它建立一条长连接，
         * 由平台在这条连接上登录，之后靠 TCP 连接维持，心跳不回调到
         * NET_DVR_SERIAL_RETURN。所以只按时间戳清理时，设备明明在线
         * （登录成功、报警能收）也会在 expireSeconds 后被当僵尸删掉，
         * 随后 contextOf 回退到直连 37777（NAT 后不可达）→ 判定离线。
         *
         * 用 online() 而不是 sessionBySerial() != null：
         * 断线回调只把会话标记失效、不删除，非空判断会把离线设备误判成在线。
         */
        table.setLiveness(serial -> DahuaSdk.get().online(serial));
        return table;
    }

    /**
     * 主动注册监听器。由 {@link DahuaServerModeStarter} 在容器就绪后启动：启动失败（端口占用）
     * 要能被感知上报，在 {@code @Bean} 方法里抛异常会让整个应用起不来。
     *
     * <p>注册成功后的登录由 {@link #serverLoginHandler} 负责 —— 少了这一步，
     * 连接会一直停在「待验证」状态，设备每 60 秒重连一次（见 {@link DahuaRegisterListener} 类注释）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "dahua.server-mode", name = "enabled", havingValue = "true")
    public RegisterListener dahuaRegisterListener(DahuaRegisterTable table, DahuaProperties props,
                                                 io.github.rurulalanana.starter.service.DeviceService deviceService) {
        return new DahuaRegisterListener(table, serverLoginHandler(props, deviceService));
    }

    /**
     * 「注册即登录」：设备注册上来的<b>那条连接</b>上按 {@code emSpecCap=2 + SN} 登录。
     *
     * <h3>为什么必须登录</h3>
     * 大华的主动注册只把 TCP 连接交给你，真正的「确认」是平台在这条连接上登录。
     * 不登录时连接停在待验证状态，<b>实测 60 秒后被丢弃、设备换源端口重新注册</b>，
     * 永远循环。官方 demo 与《Auto Register User's Manual》§3.2.4 都是这个写法。
     *
     * <h3>为什么用回调里的端口而不是 {@code sdk-port}</h3>
     * {@code sdk-port}（默认 37777）是设备的服务端口，用它能连通，但那是<b>另一条直连连接</b>：
     * 注册连接依旧没人登录，照样 60 秒断一次。要让连接从「待验证」变成会话，
     * 必须登录回调给的那个源端口。
     *
     * <h3>为什么可能返回 null</h3>
     * 没配账号密码时不能瞎试：大华账号连续登录失败会被锁。这种情况返回 {@code null}，
     * 行为退回「只记账」，设备重连频率升高但功能不受影响。
     */
    private static DahuaRegisterListener.Handler serverLoginHandler(
            DahuaProperties props, io.github.rurulalanana.starter.service.DeviceService deviceService) {
        DahuaProperties.ServerMode sm = props.getServerMode();
        if (!sm.isAutoProvision()) {
            return null;
        }
        String fallbackUser = sm.getDefaultUsername();
        String fallbackPassword = sm.getDefaultPassword();
        boolean hasFallback = fallbackPassword != null && !fallbackPassword.isBlank();
        /*
         * 两者都没有时返回 null：不能瞎试 —— 大华账号连续登录失败会被锁。
         * 行为退回「只记账」，设备重连频率升高但功能不受影响。
         *
         * 这里不能像早期那样直接 return null：设备档案里可能已经存了账号密码
         * （手工「按 IP 添加」或之前纳管过），那种情况完全不需要全局默认口令。
         */
        if (!hasFallback) {
            log.info("大华主动注册未配置全局默认口令，将只使用设备档案里已存的账号密码登录");
        }
        return (serial, ip, port) -> {
            /*
             * 凭据优先级：设备档案 > 全局默认值。
             *
             * 【为什么档案优先】gb_device 里存的就是「这台设备」的账号密码，
             * 它是唯一可信的来源；全局默认值只是「同一批设备都用同一个密码」时的便利值。
             * 早期只读全局默认值，导致「设备管理里明明有账号密码，却还要再配一遍环境变量」，
             * 而且两边不一致时永远登不上（用户改的是档案，登录用的是默认值）。
             */
            String user = null;
            String password = null;
            try {
                var d = deviceService.findDevice(serial);
                if (d != null) {
                    user = d.getUsername();
                    password = d.getPassword();
                }
            } catch (Exception e) {
                // 数据库抖动不该让注册登录整体失效，回退默认值即可
                log.warn("主动注册登录读取设备档案失败，改用全局默认口令: sn={} {}", serial, e.getMessage());
            }
            if (user == null || user.isBlank() || password == null || password.isBlank()) {
                user = fallbackUser;
                password = fallbackPassword;
            }
            if (password == null || password.isBlank()) {
                log.warn("主动注册设备没有可用凭据，跳过登录（连接会停在待验证状态）: sn={} {}:{}",
                        serial, ip, port);
                return;
            }
            DahuaSdk.Session session = DahuaSdk.get().session(ip, port, user, password,
                    io.github.rurulalanana.deviceaccess.ConnectMode.SERVER, serial);
            if (session == null) {
                // 已由 DahuaSdk 打出具体原因（错误码 / 账号密码提示），这里只补一句上下文
                log.warn("主动注册登录失败: sn={} {}:{} —— 连接会停在待验证状态，"
                        + "设备约 60 秒后重连一次", serial, ip, port);
            } else {
                log.info("主动注册会话已建立: sn={} {}:{} 通道数={}",
                        serial, ip, port, session.channelCount());
                /*
                 * 注册成功就是「设备此刻在线」的直接证据，立即落库。
                 *
                 * 【为什么不能只等下一轮探测】巡检是 60 秒一轮，而设备重连后
                 * 若恰好在探测之后、下一轮之前，界面会有最多 60 秒的「在线但显示离线」。
                 * 更要紧的是：注册是设备主动送上门的最强信号，
                 * 丢掉它就只能靠「能不能连上」这种弱信号去推断。
                 *
                 * 失败不能影响注册流程本身（这条连接已经建好了），只记日志。
                 */
                markOnlineQuietly(deviceService, serial);
            }
        };
    }

    /**
     * 主动注册成功后立即把设备标记为在线。
     *
     * <p>只更新在线状态与通道，不碰地址/凭据 —— 那些由纳管流程负责，
     * 在这里改会让「设备档案里配的地址」被注册连接的临时地址覆盖。
     *
     * <p>任何异常都吞掉：注册会话已经建立，数据库写入失败不该反过来影响它。
     */
    private static void markOnlineQuietly(
            io.github.rurulalanana.starter.service.DeviceService deviceService, String serial) {
        try {
            if (deviceService.findDevice(serial) == null) {
                // 还没纳管：交给 DahuaAutoProvisionRunner 建档，这里写状态会写空
                return;
            }
            deviceService.setDeviceAndChannelsStatus(serial, true);
        } catch (Exception e) {
            log.warn("主动注册后更新在线状态失败（不影响注册会话）: sn={} {}", serial, e.getMessage());
        }
    }

    /**
     * 应用就绪后启动主动注册监听。失败只记 ERROR、不阻断启动：端口占用的影响范围只有
     * 「大华设备注册不上」，不该让整个平台不可用。
     */
    @Bean
    @ConditionalOnProperty(prefix = "dahua.server-mode", name = "enabled", havingValue = "true")
    public DahuaServerModeStarter dahuaServerModeStarter(RegisterListener listener,
                                                        DahuaProperties props) {
        return new DahuaServerModeStarter(listener, props);
    }

    /**
     * 注册即纳管：把注册表里的设备自动建成 {@code gb_device} 档案。
     *
     * <p>与监听器分开两个 bean 的原因：监听器只负责「设备能连上」，
     * 纳管负责「平台认识它」。两者的失败面完全不同（端口 vs 账号/网络），
     * 合在一起会让排障时分不清是注册没到还是建档案失败。
     */
    @Bean
    @ConditionalOnProperty(prefix = "dahua.server-mode", name = "enabled", havingValue = "true")
    public DahuaAutoProvisionRunner dahuaAutoProvisionRunner(DahuaRegisterTable table,
                                                             DeviceProvisionService provisionService,
                                                             io.github.rurulalanana.starter.service.DeviceService deviceService,
                                                             DahuaProperties props) {
        return new DahuaAutoProvisionRunner(table, provisionService, deviceService, props);
    }

    /**
     * SDk 扩展能力（发现 / 抓图 / 配置 / 预置位 / 巡航 / 报警 / 下载 / 对讲）。
     *
     * <p><b>为什么用 {@code @Lazy}</b>：本类构造时不加载原生库，但它被
     * {@code DahuaCapabilityService} 注入后，第一次调方法就会触发
     * {@code DahuaSdk} 的静态初始化（{@code Native.load}）。标 {@code @Lazy}
     * 让「没装 SDK 的部署」在启动阶段完全不受影响，与
     * {@code DeviceAccessRegistry} 的延迟解析策略一致。
     */
    @Bean
    @Lazy
    public io.github.rurulalanana.dahua.ext.DahuaExtAccess dahuaExtAccess(DahuaProperties props) {
        log.debug("装配大华扩展能力（enabled={}）", props.isEnabled());
        return new io.github.rurulalanana.dahua.ext.DahuaExtAccess();
    }

    /**
     * 关闭钩子：释放大华 SDK。用独立 bean 而非 provider 上的 {@code @PreDestroy}：
     * provider 可能从未被创建过，但 SDK 一旦初始化过就必须 {@code CLIENT_Cleanup}。
     */
    @Bean
    public DahuaShutdownHook dahuaShutdownHook() {
        return new DahuaShutdownHook();
    }

    public static class DahuaServerModeStarter implements org.springframework.boot.ApplicationRunner {

        private final RegisterListener listener;
        private final DahuaProperties props;

        DahuaServerModeStarter(RegisterListener listener, DahuaProperties props) {
            this.listener = listener;
            this.props = props;
        }

        @Override
        public void run(org.springframework.boot.ApplicationArguments args) {
            if (!DahuaNativeLibrary.available()) {
                log.warn("大华主动注册监听未启动：{}", DahuaNativeLibrary.diagnose());
                return;
            }
            DahuaProperties.ServerMode cfg = props.getServerMode();
            try {
                listener.start(cfg.getBindIp(), cfg.getPort());
                log.info("大华主动注册监听已启动 {}:{}，请在设备侧「网络 → 主动注册」中填写本平台地址",
                        cfg.getBindIp(), cfg.getPort());
            } catch (RuntimeException e) {
                // 端口占用等部署期错误：只影响大华接入，不阻断平台启动
                log.error("大华主动注册监听启动失败（仅影响大华设备主动注册）: {}", e.getMessage());
            }
        }
    }

    /** 进程退出时释放 SDK 与全部登录会话。 */
    public static class DahuaShutdownHook {

        @jakarta.annotation.PreDestroy
        public void destroy() {
            try {
                DahuaSdk.get().shutdown();
            } catch (Throwable t) {
                log.warn("释放大华 SDK 异常: {}", t.toString());
            }
        }
    }
}
