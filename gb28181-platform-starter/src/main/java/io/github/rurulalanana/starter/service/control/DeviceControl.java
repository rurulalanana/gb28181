package io.github.rurulalanana.starter.service.control;

import io.github.rurulalanana.deviceaccess.DeviceKind;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.vo.ControlResultVo;

import java.time.LocalDateTime;
import java.util.EnumSet;

/**
 * 设备控制的统一契约：一套方法签名，每个协议一份实现。
 *
 * <p>每个方法都有默认实现，默认抛「不支持」。实现类只覆写做得到的方法，
 * 所以「不支持就直接报错」是结构保证的，不用在每个调用点写 if。
 *
 * <p>与 {@code DeviceAccessProvider} 的分工：那个管<b>接入</b>（探测、目录、拉流地址），
 * 本接口管<b>下发命令</b>（双方都有的语义）。
 *
 * <p>厂商私有能力（大华 cameraconfig、巡航组配置项、主动注册表、私有下载）
 * 不进本接口 —— 塞进来只会造出一堆必然抛异常的方法。
 */
public interface DeviceControl {

    // ==================== 元信息 ====================

    /** 本实现对应的接入方式。 */
    DeviceKind kind();

    /**
     * 本实现支持的能力集合。
     *
     * <p>同时供 {@link #supports} 判断与 {@code GET /device/capabilities} 下发给前端。
     */
    EnumSet<DeviceCapability> capabilities();

    /** 是否支持某项能力。 */
    default boolean supports(DeviceCapability capability) {
        return capabilities().contains(capability);
    }

    /**
     * 某项能力做不到的具体原因（面向用户）。
     *
     * <p>同一能力在不同厂商那边缺失的理由不同，所以由实现类给。返回空串则只报能力名。
     */
    default String unsupportedHint(DeviceCapability capability) {
        return "";
    }

    /** 构造「不支持」异常，文案统一在这里产出。 */
    default BizException unsupported(DeviceCapability capability) {
        String hint = unsupportedHint(capability);
        return BizException.badRequest("该设备通过 " + kind().code() + " 接入，不支持「"
                + capability.label() + "」" + (hint == null || hint.isBlank() ? "" : "；" + hint));
    }

    // ==================== 云台 ====================

    /** 云台方向控制。{@code speed} 0~255，默认 129。 */
    default void ptz(Ptz cmd) {
        throw unsupported(DeviceCapability.PTZ);
    }

    /** 变倍 / 聚焦 / 光圈。 */
    default void focusIris(FocusIris cmd) {
        throw unsupported(DeviceCapability.FOCUS_IRIS);
    }

    /** 按绝对角度定位（2022 版）。 */
    default void ptzPrecise(PtzPrecise cmd) {
        throw unsupported(DeviceCapability.PTZ_PRECISE);
    }

    /** 框选区域放大 / 缩小。 */
    default ControlResultVo dragZoom(DragZoom cmd) {
        throw unsupported(DeviceCapability.DRAG_ZOOM);
    }

    /** 预置位设置 / 调用 / 删除。 */
    default void preset(Preset cmd) {
        throw unsupported(DeviceCapability.PRESET);
    }

    /**
     * 触发一次预置位清单查询。
     *
     * <p>国标是异步的（下发查询 → 等应答）；SDK 多为同步读，实现类可覆写成空操作。
     */
    default void queryPresets(String channelId) {
        throw unsupported(DeviceCapability.PRESET_QUERY);
    }

    /** 巡航组：增删点、设速度/停留、启停、查询。 */
    default void cruise(Cruise cmd) {
        throw unsupported(DeviceCapability.CRUISE);
    }

    /** 查询巡航轨迹。 */
    default void queryCruiseTrack(String channelId, Integer number) {
        throw unsupported(DeviceCapability.CRUISE_QUERY);
    }

    /** 查询看守位。 */
    default void queryHomePosition(String channelId) {
        throw unsupported(DeviceCapability.HOME_POSITION);
    }

    /** 设置看守位。 */
    default void homePosition(HomePosition cmd) {
        throw unsupported(DeviceCapability.HOME_POSITION);
    }

    /** 雨刷开关。 */
    default void wiper(String channelId, String command) {
        throw unsupported(DeviceCapability.AUX_SWITCH);
    }

    /** 补光灯开关。 */
    default void fillLight(String channelId, String command, Integer index) {
        throw unsupported(DeviceCapability.AUX_SWITCH);
    }

    // ==================== 设备控制 ====================

    /** 设备端录像启停。 */
    default void recording(Recording cmd) {
        throw unsupported(DeviceCapability.RECORDING);
    }

    /** 布防 / 撤防。 */
    default void guard(Arming cmd) {
        throw unsupported(DeviceCapability.GUARD);
    }

    /** 远程重启。 */
    default void reboot(String channelId) {
        throw unsupported(DeviceCapability.REBOOT);
    }

    /** 强制 I 帧。 */
    default void forceIFrame(String channelId) {
        throw unsupported(DeviceCapability.FORCE_I_FRAME);
    }

    /** 存储卡格式化。{@code sdCardId = 0} 表示全部。 */
    default void formatSdCard(String channelId, Integer sdCardId) {
        throw unsupported(DeviceCapability.FORMAT_SD_CARD);
    }

    /** 报警复位。 */
    default void alarmReset(String channelId) {
        throw unsupported(DeviceCapability.ALARM_RESET);
    }

    /** 设备校时。{@code time} 为 {@code null} 表示用平台当前时间。 */
    default void syncTime(SyncTime cmd) {
        throw unsupported(DeviceCapability.SYNC_TIME);
    }

    /** 透传 XML 配置（2022 版 DeviceConfig）。 */
    default void deviceConfig(String channelId, String xml) {
        throw unsupported(DeviceCapability.DEVICE_CONFIG);
    }

    /** 事件订阅（目录 / 报警 / 位置 / PTZ）。 */
    default void subscribe(Subscribe cmd) {
        throw unsupported(DeviceCapability.SUBSCRIBE);
    }

    // ==================== 查询 ====================

    /** 设备运行状态查询。 */
    default void queryDeviceStatus(String channelId) {
        throw unsupported(DeviceCapability.QUERY_STATUS);
    }

    /** 设备信息查询。 */
    default void queryDeviceInfo(String channelId) {
        throw unsupported(DeviceCapability.QUERY_INFO);
    }

    /** 配置项查询（A.2.4.7）。 */
    default void queryConfig(String channelId, String configType) {
        throw unsupported(DeviceCapability.QUERY_CONFIG);
    }

    /** 存储卡状态查询。 */
    default void querySdCardStatus(String channelId) {
        throw unsupported(DeviceCapability.QUERY_SD_CARD);
    }

    /** 云台角度查询。 */
    default void queryPtzPosition(String channelId) {
        throw unsupported(DeviceCapability.QUERY_PTZ_POSITION);
    }

    // ==================== 配置 / 升级 ====================

    /** OSD 叠加配置。 */
    default void osdConfig(Osd cmd) {
        throw unsupported(DeviceCapability.OSD_CONFIG);
    }

    /** 抓拍参数配置。 */
    default void snapshotConfig(Snapshot cmd) {
        throw unsupported(DeviceCapability.SNAPSHOT_CONFIG);
    }

    /** 设备软件升级。最终结果由设备另行通知。 */
    default ControlResultVo deviceUpgrade(Upgrade cmd) {
        throw unsupported(DeviceCapability.DEVICE_UPGRADE);
    }

    /** 目标跟踪：{@code Auto} / {@code Manual} / {@code Stop}。 */
    default ControlResultVo targetTrack(TargetTrack cmd) {
        throw unsupported(DeviceCapability.TARGET_TRACK);
    }

    // ==================== 报警 / 喊话 ====================

    /** 平台向设备下发报警通知（A.2.5.9）。 */
    default void alarmNotify(AlarmNotify cmd) {
        throw unsupported(DeviceCapability.ALARM_NOTIFY);
    }

    /** 语音喊话（A.2.3.1.11 Broadcast）。 */
    default void talk(String channelId, String sourceId) {
        throw unsupported(DeviceCapability.TALK);
    }

    // ==================== 参数对象 ====================
    /*
     * 为什么用 record 而不是照搬长参数列表：dragZoom 8 个、targetTrack 9 个、
     * osdConfig 10 个、cruise 6 个。默认实现只需要「忽略参数并抛异常」，
     * 长参数列表会让 32 个 default 方法变成一堵墙，无法一眼扫出有哪些能力。
     */

    /** 云台方向控制。 */
    record Ptz(String channelId, String command, Integer speed) {
    }

    /** 变倍 / 聚焦 / 光圈。 */
    record FocusIris(String channelId, String command, Integer speed) {
    }

    /** 精确云台定位：角度 + 变焦倍数。 */
    record PtzPrecise(String channelId, Double pan, Double tilt, Double zoom) {
    }

    /** 拉框缩放：坐标均为<b>像素值</b>，{@code length}/{@code width} 是播放窗口尺寸。 */
    record DragZoom(String channelId, boolean zoomIn, int length, int width,
                    int midpointX, int midpointY, int lengthX, int lengthY) {
    }

    /** 预置位：{@code set} / {@code goto} / {@code remove}。 */
    record Preset(String channelId, String command, Integer preset) {
    }

    /** 巡航组控制。 */
    record Cruise(String channelId, String command, Integer groupId, Integer preset,
                  Integer speed, Integer stay, String name) {
    }

    /** 看守位。 */
    record HomePosition(String channelId, Boolean enabled, Integer resetTime,
                        Integer presetIndex) {
    }

    /** 设备端录像启停。 */
    record Recording(String channelId, String command, Integer streamNumber) {
    }

    /** 布防 / 撤防。 */
    record Arming(String channelId, String command) {
    }

    /** 校时。 */
    record SyncTime(String channelId, LocalDateTime time) {
    }

    /** 事件订阅。 */
    record Subscribe(String channelId, String type, Integer expires, Integer interval) {
    }

    /** OSD 配置。 */
    record Osd(String channelId, Integer timeEnable, Integer timeType,
               Integer timeX, Integer timeY, Integer textEnable, String text,
               Integer textX, Integer textY, Integer width, Integer length) {
    }

    /** 抓拍配置。 */
    record Snapshot(String channelId, Integer snapNum, Integer interval, String uploadUrl) {
    }

    /** 设备升级。 */
    record Upgrade(String channelId, String firmware, String fileUrl, String manufacturer) {
    }

    /** 目标跟踪。 */
    record TargetTrack(String channelId, String mode, String deviceId2,
                       Integer length, Integer width,
                       Integer midpointX, Integer midpointY,
                       Integer lengthX, Integer lengthY) {
    }

    /** 报警通知下发。 */
    record AlarmNotify(String channelId, Integer priority, Integer method, Integer type,
                       String description, Double longitude, Double latitude) {
    }
}
