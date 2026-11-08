package io.github.rurulalanana.starter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 海康 ISUP 接入配置（{@code hikvision.*}）。
 *
 * <h3>为什么是顶层 {@code hikvision} 而不是 {@code gb28181.hikvision}</h3>
 * 与大华同理：ISUP 是与国标<b>并列</b>的接入通道，不经过 SIP、
 * 不用 MANSCDP。挂在 {@code gb28181} 下会暗示它是国标的子项，语义上错。
 *
 * <h3>端口规划（部署必看）</h3>
 * ISUP 是「设备连平台」，平台必须监听三个端口，且要<b>对外可达</b>：
 * <ul>
 *   <li>{@code cms-port} —— 设备注册（信令）。设备端「ISUP 平台接入」里填的就是它；</li>
 *   <li>{@code stream-port} —— 设备推码流（媒体）。视频都从这里进来，带宽最大；</li>
 *   <li>{@code alarm-port} —— 设备上报报警。可复用 CMS 端口省一次放行。</li>
 * </ul>
 * NAT 环境必须做端口映射，否则设备注册不上、或注册上了但没画面 ——
 * 这与大华的「RTSP 反向连不通」是同一类问题，只是方向相反。
 */
@Data
@ConfigurationProperties(prefix = "hikvision")
public class HikvisionProperties {

    /**
     * 是否启用海康接入。
     *
     * <p>关闭后 provider 仍会被注册（否则平台无法区分「不支持」与「支持但被关掉」），
     * 但所有操作会明确报「已禁用」。
     *
     * <p>默认开启：原生库缺失时本来就会自动降级，不需要靠这个开关兜底。
     */
    private boolean enabled = true;

    /** 设备端配置的 ISUP 校验值（ISUP Key）。设备端填的值必须与这里一致。 */
    private String isupKey = "";

    /**
     * 平台对外地址。
     *
     * <p>用于两处：告诉设备「报警推给谁」与「码流推给谁」。
     * <b>必须是设备能访问到的地址</b> —— NAT 后填公网 IP，
     * 不能填 {@code 0.0.0.0} 或内网回环，否则设备连不上。
     */
    private String publicIp = "";

    /** CMS 注册监听配置。 */
    private Listen cms = new Listen();

    /** 流媒体（SMS）预览监听配置。 */
    private Listen stream = newListen(7665);

    /**
     * 回放监听配置。
     *
     * <p>回放与预览是 SDK 里两个独立的监听器
     * （{@code NET_ESTREAM_StartListenPlayBack} / {@code StartListenPreview}），
     * <b>不能共用端口</b>：后启动的那个会返回错误码 72（端口占用）。
     * 默认 7666，与预览的 7665 分开。
     */
    private Listen playback = newListen(7666);

    /** 报警监听配置。 */
    private AlarmListen alarm = new AlarmListen();

    /**
     * 造一个指定端口的监听配置。
     *
     * <p>三个 {@code Listen} 字段的端口默认值不同（CMS 7660 / SMS 7665 /
     * 回放 7666），不能靠字段初始化器的同一个默认值。
     */
    private static Listen newListen(int port) {
        Listen l = new Listen();
        l.setPort(port);
        return l;
    }

    /** 图片直传（SS）配置：设备主动把抓图推给平台，省一次拉流解码。 */
    private Picture picture = new Picture();

    /** 监听端口配置。 */
    @Data
    public static class Listen {
        /**
         * 监听网卡地址；留空表示 {@code 0.0.0.0}（所有网卡）。
         *
         * <p>多网卡机器建议显式指定，避免 SDK 选错网卡。
         */
        private String bindIp = "";

        /** 端口。 */
        private int port = 7660;

        /**
         * 链路方式：0-TCP，1-UDP，2-HRUDP。
         *
         * <p>默认 TCP：UDP 在公网丢包严重，画面会花；
         * HRUDP 需要设备支持，兼容性差。
         */
        private int linkMode = 0;
    }

    /** 报警监听配置。 */
    @Data
    public static class AlarmListen {
        /** 监听网卡地址；留空表示所有网卡。 */
        private String bindIp = "";

        /** 端口。 */
        private int port = 7663;

        /**
         * 是否复用 CMS 端口。
         *
         * <p>开启后报警走 CMS 那条连接，只需放行一个端口，
         * 部署更省事；代价是报警与信令共用带宽。
         */
        private boolean reuseCmsPort = false;

        /** 协议类型：0-TCP，1-UDP。 */
        private int protocolType = 0;
    }

    /** 图片直传配置。 */
    @Data
    public static class Picture {
        /** 是否启用（启用后设备会把抓图推到平台，平台不必再拉流截帧）。 */
        private boolean enabled = false;

        /** 绑定地址；0.0.0.0 表示所有网卡。 */
        private String bindIp = "0.0.0.0";

        /** 端口。 */
        private int port = 7664;

        /**
         * 存储服务器类型：0-Tomcat，1-VRB，2-云存储，3-KMS，4-ISUP5.0。
         *
         * <p>平台自己收图用 4（ISUP5.0 标准形态）。
         */
        private int serverType = 4;
    }

    /**
     * 注册表过期时间（秒）。
     *
     * <p>ISUP 的心跳由 SDK 内部维持，平台看不到心跳包，只能靠
     * 「重注册 / 地址变更」事件刷新时间戳。所以这个值要给得比设备心跳间隔
     * 宽裕（默认 90 秒），否则会把在线设备误判成离线。
     */
    private int registerExpireSeconds = 90;

    /** 云台控制超时（毫秒）。 */
    private int ptzTimeoutMs = 3000;
}
