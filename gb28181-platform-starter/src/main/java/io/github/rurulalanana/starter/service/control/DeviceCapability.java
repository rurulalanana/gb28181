package io.github.rurulalanana.starter.service.control;

/**
 * 设备控制能力清单（协议无关）。
 *
 * <p>它是「能力名 + 不支持原因 + 前端可用性」的唯一来源：
 * {@link DeviceControl} 的默认实现用它产异常，
 * {@code GET /device/capabilities} 用它产前端置灰表。
 *
 * <p><b>新增能力要动三处</b>：本枚举、{@link DeviceControl} 的 default 方法、
 * 国标实现。漏掉第三处国标设备会变成「不支持」且编译期不报错，
 * 由 {@code DeviceControlCapabilityTest} 兜。
 */
public enum DeviceCapability {

    /** 云台方向控制（上/下/左/右/斜向）。 */
    PTZ("云台方向控制"),
    /** 变倍、聚焦、光圈。 */
    FOCUS_IRIS("变倍/聚焦/光圈"),
    /** 按绝对角度定位（2022 版 PTZPreciseCtrl）。 */
    PTZ_PRECISE("精确云台定位"),
    /** 框选区域放大/缩小。 */
    DRAG_ZOOM("拉框缩放"),
    /** 预置位的设置/调用/删除。 */
    PRESET("预置位控制"),
    /** 读回设备侧预置位清单。 */
    PRESET_QUERY("预置位查询"),
    /** 巡航组的增删点与启停。 */
    CRUISE("巡航组控制"),
    /** 读回巡航轨迹。 */
    CRUISE_QUERY("巡航轨迹查询"),
    /** 看守位（设备自动回位）。 */
    HOME_POSITION("看守位"),
    /** 雨刷、补光灯等辅助开关。 */
    AUX_SWITCH("雨刷/补光灯"),
    /** 设备端录像的启停。 */
    RECORDING("设备录像控制"),
    /** 布防 / 撤防。 */
    GUARD("布防/撤防"),
    /** 设备远程重启。 */
    REBOOT("远程重启"),
    /** 强制 I 帧。 */
    FORCE_I_FRAME("强制关键帧"),
    /** 存储卡格式化。 */
    FORMAT_SD_CARD("存储卡格式化"),
    /** 报警复位。 */
    ALARM_RESET("报警复位"),
    /** 平台向设备下发时间。 */
    SYNC_TIME("设备校时"),
    /** 透传 XML 配置。 */
    DEVICE_CONFIG("配置下发"),
    /** 目录/报警/位置/PTZ 事件订阅。 */
    SUBSCRIBE("事件订阅"),
    /** 设备运行状态查询。 */
    QUERY_STATUS("状态查询"),
    /** 设备信息查询。 */
    QUERY_INFO("信息查询"),
    /** 国标配置项查询（A.2.4.7）。 */
    QUERY_CONFIG("配置项查询"),
    /** 存储卡状态查询。 */
    QUERY_SD_CARD("存储卡状态查询"),
    /** 云台角度查询。 */
    QUERY_PTZ_POSITION("云台位置查询"),
    /** OSD 叠加配置。 */
    OSD_CONFIG("OSD 配置"),
    /** 抓拍参数配置。 */
    SNAPSHOT_CONFIG("抓拍配置"),
    /** 设备软件升级。 */
    DEVICE_UPGRADE("设备升级"),
    /** 目标跟踪（自动/手动/停止）。 */
    TARGET_TRACK("目标跟踪"),
    /** 平台向设备下发报警通知。 */
    ALARM_NOTIFY("报警通知下发"),
    /** 语音喊话（Broadcast）。 */
    TALK("语音喊话");

    private final String label;

    DeviceCapability(String label) {
        this.label = label;
    }

    /** 面向用户的能力名，同时用于「不支持」异常文案。 */
    public String label() {
        return label;
    }
}
