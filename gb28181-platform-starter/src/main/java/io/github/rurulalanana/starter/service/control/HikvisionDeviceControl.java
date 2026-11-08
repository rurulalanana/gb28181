package io.github.rurulalanana.starter.service.control;

import io.github.rurulalanana.deviceaccess.DeviceKind;
import io.github.rurulalanana.hikvision.HikvisionDeviceAccessProvider;
import io.github.rurulalanana.hikvision.HikvisionIsapi;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.service.DeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumSet;

/**
 * 海康 ISUP 设备的控制实现。
 *
 * <p>只覆写 ISAPI 真做得到的方法，其余继承默认实现（即抛「不支持」）。
 * 做不到的原因集中在 {@link #unsupportedHint}。
 *
 * <h3>为什么全部走 ISAPI 而不走 SDK 专用接口</h3>
 * ISUP SDK 的 {@code NET_ECMS_RemoteControl} 需要各型号自定义的命令码与结构体，
 * 而 ISAPI（设备 Web 界面背后那套 REST）是海康官方公开、跨型号一致的接口。
 * 走 ISAPI 的好处是「设备 Web 能做的，平台都能做」，且字段名可查。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HikvisionDeviceControl implements DeviceControl {

    private final DeviceService deviceService;

    private final HikvisionIsapi isapi;

    @Override
    public DeviceKind kind() {
        return DeviceKind.HIKVISION;
    }

    /**
     * 支持的能力。
     *
     * <p>只列 ISAPI 确认可用的：云台、变倍/聚焦/光圈、预置位（含查询）、
     * 设备信息/状态查询、远程重启、校时。
     * 巡航/巡迹/限位在海康上分散在多个 ISAPI 资源里，第一版先不做 ——
     * 与其做一个半吊子的，不如明确报「不支持」。
     */
    @Override
    public EnumSet<DeviceCapability> capabilities() {
        return EnumSet.of(
                DeviceCapability.PTZ,
                DeviceCapability.FOCUS_IRIS,
                DeviceCapability.PRESET,
                DeviceCapability.PRESET_QUERY,
                DeviceCapability.QUERY_INFO,
                DeviceCapability.QUERY_STATUS,
                DeviceCapability.REBOOT,
                DeviceCapability.SYNC_TIME,
                DeviceCapability.DEVICE_CONFIG);
    }

    /** 不支持的能力给出具体原因，避免运维对着「不支持」无从下手。 */
    @Override
    public String unsupportedHint(DeviceCapability capability) {
        return switch (capability) {
            case CRUISE, CRUISE_QUERY ->
                    "海康 ISUP 的巡航组/巡迹分散在多个 ISAPI 资源中，当前版本未接入";
            case PTZ_PRECISE, DRAG_ZOOM ->
                    "海康 ISAPI 不支持绝对角度定位与拉框缩放";
            case HOME_POSITION ->
                    "海康 ISAPI 的看守位配置项随型号差异大，当前版本未接入";
            case AUX_SWITCH ->
                    "海康的雨刷/补光灯走 IO 输出控制，型号差异大，当前版本未接入";
            case RECORDING ->
                    "海康的录像启停走录像计划配置，当前版本未接入";
            case GUARD ->
                    "海康的布撤防走报警主机接口，当前版本未接入";
            case FORCE_I_FRAME, FORMAT_SD_CARD, ALARM_RESET, DEVICE_UPGRADE ->
                    "海康 ISAPI 未提供对应的通用接口";
            case TALK ->
                    "海康对讲需 ISUP 语音对讲通道，当前版本未接入";
            default -> "海康 ISUP 接入暂未实现该能力";
        };
    }

    // ==================== 云台 ====================

    @Override
    public void ptz(Ptz cmd) {
        String deviceId = deviceIdOf(cmd.channelId());
        boolean ok = isapi.ptz(
                deviceId,
                HikvisionDeviceAccessProvider.channelNoOf(deviceId, cmd.channelId()),
                cmd.command(),
                cmd.speed() == null ? 128 : cmd.speed());
        if (!ok) {
            throw BizException.badRequest("海康云台控制失败：" + cmd.command());
        }
    }

    /**
     * 变倍/聚焦/光圈。
     *
     * <p>ISAPI 把这三者都放在 {@code /continuous} 的 {@code zoom} 与
     * {@code /focus}、{@code /iris} 子资源里。变倍与云台共用 continuous 接口，
     * 聚焦/光圈是单独的 {@code /focus} 与 {@code /iris} 接口。
     */
    @Override
    public void focusIris(FocusIris cmd) {
        String deviceId = deviceIdOf(cmd.channelId());
        int channelNo = HikvisionDeviceAccessProvider.channelNoOf(deviceId, cmd.channelId());
        String command = cmd.command() == null ? "" : cmd.command().toLowerCase();
        int speed = cmd.speed() == null ? 50 : Math.max(1, Math.min(100, cmd.speed() * 100 / 255));

        String path;
        int value;
        switch (command) {
            case "zoomin" -> {
                // 变倍与云台同一接口：pan/tilt 给 0，只动 zoom
                ptz(new Ptz(cmd.channelId(), "zoomin", cmd.speed()));
                return;
            }
            case "zoomout" -> {
                ptz(new Ptz(cmd.channelId(), "zoomout", cmd.speed()));
                return;
            }
            case "focusnear", "focusin" -> {
                path = "/ISAPI/PTZCtrl/channels/" + channelNo + "/focus";
                value = speed;
            }
            case "focusfar", "focusout" -> {
                path = "/ISAPI/PTZCtrl/channels/" + channelNo + "/focus";
                value = -speed;
            }
            case "irisopen", "irisbig" -> {
                path = "/ISAPI/PTZCtrl/channels/" + channelNo + "/iris";
                value = speed;
            }
            case "irisclose", "irissmall" -> {
                path = "/ISAPI/PTZCtrl/channels/" + channelNo + "/iris";
                value = -speed;
            }
            case "stop" -> {
                // 三者都要停：只停一个会让另一个继续跑（大华踩过同样的坑）
                isapi.put(deviceId, "/ISAPI/PTZCtrl/channels/" + channelNo + "/continuous",
                        "{\"pan\":0,\"tilt\":0,\"zoom\":0}");
                isapi.put(deviceId, "/ISAPI/PTZCtrl/channels/" + channelNo + "/focus",
                        "{\"focus\":0}");
                isapi.put(deviceId, "/ISAPI/PTZCtrl/channels/" + channelNo + "/iris",
                        "{\"iris\":0}");
                return;
            }
            default -> throw BizException.badRequest("海康不支持该变倍/聚焦/光圈命令：" + cmd.command());
        }
        HikvisionIsapi.IsapiResult r = isapi.put(deviceId, path,
                "{\"" + (path.endsWith("focus") ? "focus" : "iris") + "\":" + value + "}");
        if (!r.ok()) {
            throw BizException.badRequest("海康" + cmd.command() + "失败：" + r.error());
        }
    }
    // ==================== 预置位 ====================

    @Override
    public void preset(Preset cmd) {
        String deviceId = deviceIdOf(cmd.channelId());
        int channelNo = HikvisionDeviceAccessProvider.channelNoOf(deviceId, cmd.channelId());
        int presetId = cmd.preset() == null ? 0 : cmd.preset();
        String command = cmd.command() == null ? "" : cmd.command().toLowerCase();
        boolean ok = switch (command) {
            case "set", "add" -> isapi.setPreset(deviceId, channelNo, presetId, null);
            case "goto", "call" -> isapi.gotoPreset(deviceId, channelNo, presetId);
            case "remove", "delete", "del" -> isapi.deletePreset(deviceId, channelNo, presetId);
            default -> throw BizException.badRequest("海康不支持该预置位命令：" + cmd.command());
        };
        if (!ok) {
            throw BizException.badRequest("海康预置位操作失败：" + cmd.command() + " " + presetId);
        }
    }

    /**
     * 触发预置位清单查询。
     *
     * <p>海康是同步接口，这里不做事 —— 真正的读取由
     * {@code PresetService.listBySdk} 通过 {@code DevicePresetList} 契约直接拿。
     */
    @Override
    public void queryPresets(String channelId) {
        // 同步读，无需「下发+等应答」
    }

    // ==================== 查询 ====================

    @Override
    public void queryDeviceInfo(String channelId) {
        String deviceId = deviceIdOf(channelId);
        HikvisionIsapi.DeviceInfo info = isapi.deviceInfo(deviceId);
        if (info == null) {
            throw BizException.badRequest("读不到海康设备信息：" + deviceId);
        }
        log.info("海康设备信息: serial={} 型号={} 固件={} 通道数={}",
                deviceId, info.model(), info.firmware(), info.channelCount());
    }

    @Override
    public void queryDeviceStatus(String channelId) {
        String deviceId = deviceIdOf(channelId);
        HikvisionIsapi.IsapiResult r = isapi.get(deviceId, "/ISAPI/System/status");
        if (!r.ok()) {
            throw BizException.badRequest("读不到海康设备状态：" + r.error());
        }
        log.debug("海康设备状态: serial={} {}", deviceId, r.body());
    }

    // ==================== 维护 ====================

    /** 远程重启。 */
    @Override
    public void reboot(String channelId) {
        String deviceId = deviceIdOf(channelId);
        HikvisionIsapi.IsapiResult r = isapi.put(deviceId, "/ISAPI/System/reboot",
                "<Reboot><mode>full</mode></Reboot>");
        if (!r.ok()) {
            throw BizException.badRequest("海康重启失败：" + r.error());
        }
        log.info("海康设备已下发重启命令: {}", deviceId);
    }

    /** 校时：把平台时间写进设备。 */
    @Override
    public void syncTime(SyncTime cmd) {
        String deviceId = deviceIdOf(cmd.channelId());
        java.time.LocalDateTime at = cmd.time() == null
                ? java.time.LocalDateTime.now() : cmd.time();
        String time = at.format(java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        String body = "<Time><timeMode>manual</timeMode><localTime>" + time
                + "</localTime></Time>";
        HikvisionIsapi.IsapiResult r = isapi.put(deviceId, "/ISAPI/System/time", body);
        if (!r.ok()) {
            throw BizException.badRequest("海康校时失败：" + r.error());
        }
    }

    /** 配置下发（透传 XML）。 */
    @Override
    public void deviceConfig(String channelId, String xml) {
        String deviceId = deviceIdOf(channelId);
        HikvisionIsapi.IsapiResult r = isapi.request(deviceId, "PUT",
                "/ISAPI/System/configurationData", xml);
        if (!r.ok()) {
            throw BizException.badRequest("海康配置下发失败：" + r.error());
        }
    }

    /** 从通道编码里解出设备编号（{@code serial-N} → {@code serial}）。 */
    private String deviceIdOf(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            throw BizException.badRequest("缺少通道编码");
        }
        var channel = deviceService.findChannel(channelId);
        if (channel != null && channel.getDeviceId() != null) {
            return channel.getDeviceId();
        }
        // 通道表里查不到时按「最后一个 - 之前」兜底
        int dash = channelId.lastIndexOf('-');
        return dash > 0 ? channelId.substring(0, dash) : channelId;
    }
}
