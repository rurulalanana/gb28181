package io.github.rurulalanana.starter.config;

import io.github.rurulalanana.hikvision.HikvisionAlarmListener;
import io.github.rurulalanana.hikvision.HikvisionAlarmType;
import io.github.rurulalanana.hikvision.HikvisionCmsListener;
import io.github.rurulalanana.hikvision.HikvisionDeviceAccessProvider;
import io.github.rurulalanana.hikvision.HikvisionDeviceExtensions;
import io.github.rurulalanana.hikvision.HikvisionIsapi;
import io.github.rurulalanana.hikvision.HikvisionMediaProvider;
import io.github.rurulalanana.hikvision.HikvisionPlatform;
import io.github.rurulalanana.hikvision.HikvisionPlaybackListener;
import io.github.rurulalanana.hikvision.HikvisionRegisterTable;
import io.github.rurulalanana.hikvision.HikvisionSsListener;
import io.github.rurulalanana.hikvision.HikvisionStreamListener;
import io.github.rurulalanana.starter.service.AlarmService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 海康 ISUP 接入的装配。
 *
 * <h3>与大华装配的差别</h3>
 * 大华是「平台主动连设备」，装配时不需要监听端口，{@code @Bean} 可以完全是懒的。
 * 海康是「设备连平台」，<b>必须监听三个端口</b>，而且要在容器启动时就拉起来 ——
 * 否则设备注册不上，等到第一次用时才启动已经晚了。
 *
 * <h3>启动期失败不影响平台</h3>
 * SDK 缺库、端口被占用都只打告警、不抛异常：国标与大华链路照常工作。
 * 这是刻意的 —— 缺一个厂商的 SDK 不该让整个平台起不来
 * （大华那边踩过「SDK 缺失导致容器启动失败」的坑）。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(HikvisionProperties.class)
public class HikvisionConfiguration {

    /**
     * 注册表。
     *
     * <p>无条件创建：它只是内存 Map，不开端口、不起线程。
     * 反过来若跟着 {@code enabled} 走，关掉海康的部署会因为
     * {@code DeviceRegisterRegistry} 注入失败而起不来（大华踩过同样的坑）。
     */
    @Bean
    public HikvisionRegisterTable hikvisionRegisterTable(HikvisionProperties props) {
        long expireMs = Math.max(30, props.getRegisterExpireSeconds()) * 1000L;
        log.info("海康注册表: 失效阈值 {} 秒", expireMs / 1000);
        return new HikvisionRegisterTable(expireMs);
    }

    /** ISAPI 客户端：资源获取、云台、配置、抓图、录像查询都走它。 */
    @Bean
    public HikvisionIsapi hikvisionIsapi(HikvisionRegisterTable table) {
        return new HikvisionIsapi(table);
    }

    /**
     * CMS 注册监听。
     *
     * <p>{@code publicIp} 为空时回落到 {@code bindIp}：单机直连部署下两者相同；
     * NAT 部署必须显式配 {@code publicIp}，否则设备注册到的是内网地址、连不上。
     */
    @Bean
    public HikvisionCmsListener hikvisionCmsListener(HikvisionRegisterTable table,
                                                     HikvisionProperties props) {
        String alarmIp = resolvePublicIp(props);
        return new HikvisionCmsListener(table,
                new HikvisionCmsListener.DeviceEventListener() {
                    @Override
                    public void onOnline(io.github.rurulalanana.hikvision.HikvisionRegisteredDevice d) {
                        log.info("海康设备上线: serial={} {}:{} 通道数={} 型号={}",
                                d.serial(), d.ip(), d.port(), d.channelCount(), d.model());
                    }

                    @Override
                    public void onOffline(String serial) {
                        log.info("海康设备下线: serial={}", serial);
                    }
                },
                props.getCms().getBindIp(),
                props.getCms().getPort(),
                props.getIsupKey(),
                alarmIp,
                props.getAlarm().getPort(),
                props.getAlarm().getPort(),
                // 0-只 UDP，1-UDP+TCP；平台统一给 1，让设备两种都能用
                1,
                props.getPicture().isEnabled() ? alarmIp : "",
                props.getPicture().getPort(),
                props.getPicture().getServerType());
    }

    /** 报警监听：把海康事件桥接到平台报警服务。 */
    @Bean
    public HikvisionAlarmListener hikvisionAlarmListener(HikvisionRegisterTable table,
                                                         HikvisionProperties props,
                                                         AlarmService alarmService) {
        return new HikvisionAlarmListener(table,
                event -> bridgeAlarm(alarmService, event),
                props.getAlarm().getBindIp(),
                props.getAlarm().getPort(),
                props.getAlarm().isReuseCmsPort(),
                props.getAlarm().getProtocolType());
    }

    /** 流媒体监听：设备推 PS 到这里，由 starter 侧的发布器解成裸流喂 ZLM。 */
    @Bean
    public HikvisionStreamListener hikvisionStreamListener(HikvisionRegisterTable table,
                                                           HikvisionProperties props,
                                                           io.github.rurulalanana.starter.service.HikvisionPsPublisher publisher) {
        return new HikvisionStreamListener(table,
                publisher,
                props.getStream().getBindIp(),
                props.getStream().getPort(),
                props.getStream().getLinkMode(),
                resolvePublicIp(props),
                props.getStream().getPort());
    }

    /**
     * 回放监听：设备按时间推历史码流。
     *
     * <p>端口用独立的 {@code hikvision.playback.port}（默认 7666），
     * <b>不能与预览共用</b>：{@code NET_ESTREAM_StartListenPlayBack} 与
     * {@code StartListenPreview} 是两个监听器，共用端口时后启动的报错误码 72。
     *
     * <p>但告诉设备的 SMS 端口仍是预览那个（设备只知道一个 SMS 端口），
     * 所以 {@code smsPort} 传 {@code stream.port}。
     */
    @Bean
    public HikvisionPlaybackListener hikvisionPlaybackListener(
            HikvisionRegisterTable table,
            HikvisionProperties props,
            io.github.rurulalanana.starter.service.HikvisionPsPublisher publisher) {
        return new HikvisionPlaybackListener(table,
                publisher,
                props.getPlayback().getBindIp(),
                props.getPlayback().getPort(),
                props.getPlayback().getLinkMode(),
                resolvePublicIp(props),
                props.getPlayback().getPort());
    }

    /**
     * 抓图直传监听：设备主动把 JPEG 推到平台。
     *
     * <p>只在 {@code hikvision.picture.enabled=true} 时创建：它要单独占一个端口，
     * 且必须在设备侧开启「报警联动抓图」或「图片上传」才有意义。
     * 未启用时抓图仍走 ISAPI 反向请求，功能不受影响。
     */
    @Bean
    @ConditionalOnProperty(prefix = "hikvision.picture", name = "enabled", havingValue = "true")
    public HikvisionSsListener hikvisionSsListener(
            HikvisionProperties props,
            io.github.rurulalanana.starter.service.HikvisionPictureReceiver receiver) {
        return new HikvisionSsListener(receiver,
                props.getPicture().getBindIp(),
                props.getPicture().getPort(),
                resolvePublicIp(props));
    }

    /** 接入实现（探测/目录/云台）。 */
    @Bean
    public HikvisionDeviceAccessProvider hikvisionDeviceAccessProvider(
            HikvisionRegisterTable table, HikvisionIsapi isapi) {
        return new HikvisionDeviceAccessProvider(table, isapi);
    }

    /** 取流实现（设备推流 → ZLM）。 */
    @Bean
    public HikvisionMediaProvider hikvisionMediaProvider(HikvisionRegisterTable table,
                                                         HikvisionStreamListener listener) {
        return new HikvisionMediaProvider(table, listener);
    }

    /** 扩展能力（抓图/录像检索/预置位/会话释放）。 */
    @Bean
    public HikvisionDeviceExtensions hikvisionDeviceExtensions(HikvisionRegisterTable table,
                                                               HikvisionIsapi isapi) {
        return new HikvisionDeviceExtensions(table, isapi);
    }

    /**
     * 装配体：把三个监听组装起来，并在容器启动时拉起、关闭时停止。
     *
     * <p>用 {@code initMethod}/{@code destroyMethod} 而不是 {@code @PostConstruct}：
     * 后者的执行时机在 Bean 初始化阶段，此时其他 Bean 可能还没就绪；
     * 而 {@code initMethod} 语义更明确，也便于单测直接调 {@code start()}。
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    public HikvisionPlatform hikvisionPlatform(HikvisionRegisterTable registerTable,
                                               HikvisionCmsListener cmsListener,
                                               HikvisionAlarmListener alarmListener,
                                               HikvisionStreamListener streamListener,
                                               HikvisionPlaybackListener playbackListener,
                                               ObjectProvider<HikvisionSsListener> ssProvider,
                                               HikvisionIsapi isapi,
                                               HikvisionDeviceAccessProvider accessProvider,
                                               HikvisionMediaProvider mediaProvider,
                                               HikvisionDeviceExtensions extensions,
                                               HikvisionProperties props) {
        if (!props.isEnabled()) {
            log.info("海康接入已禁用（hikvision.enabled=false），不启动监听");
        }
        // 抓图直传是可选的：未启用时 Bean 方法返回 null，
        // 用 ObjectProvider 取才不会因为 NullBean 报注入失败
        HikvisionSsListener ssListener = ssProvider.getIfAvailable();
        return new HikvisionPlatform(registerTable, cmsListener, alarmListener,
                streamListener, playbackListener, ssListener, isapi, accessProvider,
                mediaProvider, extensions);
    }

    /**
     * 把海康事件翻译成平台报警并落库。
     *
     * <p>通道编码用 {@code 序列号-通道号}，与平台既有规则一致；
     * 通道号为 0 时表示设备级事件（不挂通道）。
     */
    private static void bridgeAlarm(AlarmService alarmService,
                                    HikvisionAlarmListener.HikvisionAlarmEvent event) {
        HikvisionAlarmType type = event.type();
        String channelId = event.channelNo() > 0
                ? event.serial() + "-" + event.channelNo()
                : null;
        /*
         * 平台的 priority 口径是 1=最紧急 … 4=提示，与海康的 4=紧急相反，
         * 所以这里要换算：海康 4 → 平台 1，海康 1 → 平台 4。
         */
        int priority = 5 - type.priority();
        try {
            alarmService.onDeviceAlarm(new io.github.rurulalanana.deviceaccess.DeviceAlarmSink.DeviceAlarm(
                    event.serial(),
                    channelId,
                    priority,
                    // 平台用数字事件码；海康是字符串事件名，取 hashCode 保证同类事件稳定同码
                    Math.abs(event.eventType().hashCode()),
                    type.label(),
                    event.description(),
                    event.ended()));
        } catch (Exception e) {
            log.warn("海康报警落库失败: serial={} {} {}", event.serial(),
                    type.label(), e.getMessage());
        }
    }

    /** 对外地址：优先 {@code publicIp}，留空时用监听地址。 */
    private static String resolvePublicIp(HikvisionProperties props) {
        String ip = props.getPublicIp();
        if (ip != null && !ip.isBlank()) {
            return ip.trim();
        }
        String bind = props.getCms().getBindIp();
        if (bind != null && !bind.isBlank()) {
            return bind.trim();
        }
        // 都没配时给回环：本机自测能通，生产必须显式配 publicIp
        return "127.0.0.1";
    }
}
