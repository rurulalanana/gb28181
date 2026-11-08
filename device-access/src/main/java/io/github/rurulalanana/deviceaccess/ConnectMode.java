package io.github.rurulalanana.deviceaccess;

/**
 * 设备连接方向。
 *
 * <h3>为什么要显式建模</h3>
 * 厂商 SDK 通常支持两种连法，<b>部署形态完全不同</b>：
 * <ul>
 *   <li>{@link #CLIENT} —— 平台主动连设备。要求设备有固定可达地址
 *       （或至少平台能路由过去）。适合内网、专线、设备有公网 IP 的场景。</li>
 *   <li>{@link #SERVER} —— 平台监听端口，设备主动连上来注册。
 *       <b>设备只需要能出网</b>，不需要固定 IP、不需要端口映射。
 *       这是公网部署、4G 摄像机、NAT 后设备的唯一可行方式。</li>
 * </ul>
 *
 * <p>两者的先后顺序不可互换：SERVER 模式下平台要先知道「设备连上来了」，
 * 才能拿到它的实际地址并建立会话；CLIENT 模式则直接用配置的地址。
 */
public enum ConnectMode {

    /** 平台主动连接设备（用配置里的 host/port）。 */
    CLIENT("client"),

    /**
     * 平台监听端口，设备主动注册。
     *
     * <p>也叫「主动注册」「自动注册」「服务器模式」——
     * 三个说法在厂商文档里混用，指的都是这个方向。
     */
    SERVER("server");

    private final String code;

    ConnectMode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /**
     * 解析配置值。
     *
     * <p>默认 {@link #CLIENT}：这是行为最可预测的一种（地址由配置决定），
     * 也是在已有部署里能立刻工作的方式。SERVER 需要额外放开端口并配置设备，
     * 不能作为默认值 —— 否则升级后所有大华设备都会突然连不上。
     */
    public static ConnectMode of(String value) {
        if (value == null || value.isBlank()) {
            return CLIENT;
        }
        String v = value.trim().toLowerCase();
        // 厂商文档里的同义词
        if (SERVER.code.equals(v) || "auto".equals(v) || "autoregister".equals(v)
                || "active".equals(v) || "listen".equals(v)) {
            return SERVER;
        }
        return CLIENT;
    }
}
