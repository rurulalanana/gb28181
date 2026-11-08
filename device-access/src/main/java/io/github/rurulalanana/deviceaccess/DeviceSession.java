package io.github.rurulalanana.deviceaccess;

/**
 * 设备会话的释放（SPI，可选能力）。
 *
 * <p>平台主动连出去的设备（私有协议长连接）在删除档案前必须先断开，
 * 否则设备侧一直显示「有客户端登录」，实现方的原生句柄也会泄漏。
 * 国标是设备连平台，不存在这个问题，因此不需要实现本接口。
 *
 * <p>实现方<b>不得</b>因释放失败而抛异常：设备可能已离线，
 * 但删除档案仍然应当成功。
 */
public interface DeviceSession extends DeviceExtension {

    /** 释放该设备占用的会话；没有会话或释放失败时静默返回。 */
    void release(String deviceId);
}
