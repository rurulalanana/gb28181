package io.github.rurulalanana.deviceaccess;

/**
 * 一次设备操作的上下文（协议无关）。
 *
 * <p>把厂商 SDK 需要的东西都收敛在这里，避免每个方法都传一长串参数，
 * 也避免 provider 实现去读数据库 —— <b>provider 只做协议适配，不做持久化</b>，
 * 这样它才可以被单元测试/桩替换，不依赖 Spring 容器与数据源。
 *
 * @param deviceId    平台内设备唯一编号（{@code gb_device.device_id}）
 * @param host        设备地址（IP 或域名）。{@link ConnectMode#SERVER} 模式下
 *                    由「设备主动注册」回调回填，配置里的值可能为空
 * @param port        设备 SDK 端口（大华默认 37777）
 * @param username    登录用户名
 * @param password    登录密码
 * @param connectMode 连接方向
 * @param serial      设备序列号（SERVER 模式下设备注册时上报，登录时要用）
 * @param channelCount 设备上报的通道数（仅诊断用，可为 0）
 */
public record DeviceContext(
        String deviceId,
        String host,
        int port,
        String username,
        String password,
        ConnectMode connectMode,
        String serial,
        int channelCount) {

    /** 兼容旧签名：未显式指定方向时按 CLIENT。 */
    public DeviceContext(String deviceId, String host, int port,
                         String username, String password, int channelCount) {
        this(deviceId, host, port, username, password, ConnectMode.CLIENT, null, channelCount);
    }

    /**
     * 设备是否具备建立连接的最小信息。
     *
     * <p><b>两种模式判据不同</b>：
     * <ul>
     *   <li>CLIENT —— 必须有 host + port（配置里填的）</li>
     *   <li>SERVER —— 必须有 serial（设备注册时上报的序列号），
     *       host/port 由注册回调给出，配置里留空是正常的</li>
     * </ul>
     * 用同一套判据会让 SERVER 设备永远被判成「信息不全」而拒绝连接。
     */
    public boolean connectable() {
        if (notBlank(deviceId) == false) {
            return false;
        }
        if (connectMode == ConnectMode.SERVER) {
            return notBlank(serial) || notBlank(host);
        }
        return notBlank(host) && port > 0;
    }

    /** 派生一个「回填了实际地址」的副本（SERVER 模式收到注册事件后使用）。 */
    public DeviceContext withEndpoint(String ip, int p, String deviceSerial) {
        return new DeviceContext(deviceId,
                ip != null && !ip.isBlank() ? ip : host,
                p > 0 ? p : port,
                username, password, connectMode,
                deviceSerial != null && !deviceSerial.isBlank() ? deviceSerial : serial,
                channelCount);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 便于日志输出：不打印密码。 */
    @Override
    public String toString() {
        return deviceId + "@" + host + ":" + port
                + " mode=" + connectMode.code()
                + (serial == null ? "" : " sn=" + serial)
                + " (" + username + "/***)";
    }
}
