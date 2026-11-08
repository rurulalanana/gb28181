package io.github.rurulalanana.deviceaccess;

/**
 * 主动注册监听服务。
 *
 * <p>SERVER 模式下，provider 需要先起一个监听端口，设备才会连上来。
 * 把它抽成接口而不是让 provider 自己在构造器里开端口，有三个原因：
 * <ol>
 *   <li><b>端口是全局资源</b> —— 同一个端口只能被一个 provider 占用，
 *       生命周期必须由平台统一管理（启动/优雅停止/重启）</li>
 *   <li><b>失败要能上报</b> —— 端口被占用是部署期最常见的错误，
 *       必须能明确告诉运维「哪个端口、什么原因」，而不是静默不工作</li>
 *   <li><b>可观测</b> —— 平台要能回答「监听起来了没有、收到多少注册」</li>
 * </ol>
 */
public interface RegisterListener {

    /** 监听是否已启动。 */
    boolean running();

    /** 实际监听端口（配置为 0 时由系统分配，启动后可读到真实值）。 */
    int port();

    /**
     * 启动监听。
     *
     * @param bindIp 绑定地址；{@link ConnectMode#SERVER} 公网部署时一般填 {@code 0.0.0.0}
     * @param port   监听端口
     * @throws IllegalStateException 端口被占用或 SDK 拒绝（消息里要说明原因）
     */
    void start(String bindIp, int port);

    /** 停止监听（幂等）。 */
    void stop();

    /** 已收到的注册次数（累计，诊断用）。 */
    long registeredCount();
}
