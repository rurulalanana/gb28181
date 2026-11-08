package io.github.rurulalanana.deviceaccess;

/**
 * 厂商扩展能力的标记基类。
 *
 * <p>平台的能力分两类：
 * <ul>
 *   <li><b>核心</b>（{@link DeviceAccessProvider} / {@link DeviceMediaProvider} /
 *       {@link DeviceAlarmSink} / {@link DeviceRegisterTable}）—— 每个厂商必选其一，
 *       各有专用注册表；</li>
 *   <li><b>扩展</b>（本接口的子类型）—— 可选能力，一个厂商可以实现多个，
 *       由 {@code DeviceExtensionRegistry} 按「接口 + kind」统一分发。</li>
 * </ul>
 *
 * <p>扩展能力单独成接口而不是塞进核心契约：实现方只声明自己做得到的，
 * 平台按接口查不到就说明不支持 —— 与 {@code DeviceControl} 的能力表同一思路。
 */
public interface DeviceExtension {

    /** 本实现对应的接入方式。 */
    DeviceKind kind();
}
