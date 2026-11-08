package io.github.rurulalanana.dahua.ext;

import com.netsdk.lib.NetSDKLib;
import io.github.rurulalanana.dahua.internal.DahuaSdk;

import java.time.LocalDateTime;

/**
 * 大华<b>设备级</b>控制能力：重启、校时、报警清除、灯光雨刷、设备信息。
 *
 * <h3>与 {@link DahuaPtzExt} 的分工</h3>
 * <ul>
 *   <li>{@link DahuaPtzExt} —— 全部走 {@code CLIENT_DHPTZControlEx2} 的云台家族</li>
 *   <li>本类 —— 走 {@code CLIENT_ControlDevice} / {@code CLIENT_SetupDeviceTime}
 *       这类<b>整机</b>接口</li>
 * </ul>
 * 分开的理由：云台指令只影响镜头，而重启会让设备断线几十秒、
 * 校时会改动录像时间轴 —— 影响面完全不同，混在一起容易误用。
 *
 * <h3>国标命令 → 大华接口对照</h3>
 * <pre>
 *   TeleBoot    (设备重启)  → CLIENT_ControlDevice(CTRLTYPE_CTRL_REBOOT)
 *   校时                     → CLIENT_SetupDeviceTime
 *   AlarmReset  (报警复位)  → CLIENT_ControlDevice(CTRLTYPE_CTRL_CLEAR_ALARM)
 *   雨刷 / 补光灯            → CLIENT_DHPTZControlEx2(NET_PTZ_LAMP_CONTROL)
 *   设备信息                 → CLIENT_GetDeviceAllInfo
 * </pre>
 *
 * <h3>明确做不到的</h3>
 * <b>强制关键帧</b>（国标 {@code <IFame>}）在大华 NetSDK 里没有独立接口，
 * 也没有对应的 {@code CtrlType}。可行路径只有在推流侧让 ZLM 请求新 GOP，
 * 与设备无关。这里不做假的实现 —— 上层应当如实报「该型号不支持」。
 */
public final class DahuaDeviceCtrl {

    private DahuaDeviceCtrl() {
    }

    // ==================== 重启 ====================

    /**
     * 重启<b>整机</b>（不是只重启云台）。
     *
     * <p><b>调用后设备会断线几十秒到两分钟</b>，期间预览、录像、云台全部中断。
     * 上层应当先确认无人在看画面再下发。
     *
     * <p>与 {@link DahuaPtzExt#reboot} 的区别：那个接口在多数型号上其实是同一件事
     * （大华没有独立的「只重启云台」指令），但语义上本方法更明确。
     */
    public static boolean reboot(DahuaSdk.Session session) {
        NetSDKLib sdk = requireSdk();
        boolean ok = sdk.CLIENT_ControlDevice(session.handle(),
                NetSDKLib.CtrlType.CTRLTYPE_CTRL_REBOOT, null, 10_000);
        if (!ok) {
            throw new DahuaSdkException("设备重启失败",
                    sdk.CLIENT_GetLastError(), "确认账号有重启权限，且设备未被其他客户端锁定");
        }
        return true;
    }

    // ==================== 校时 ====================

    /**
     * 读设备当前时间。
     *
     * <p>设备时钟漂移会让录像的 {@code StartTime/EndTime} 与平台检索区间对不上，
     * 表现为「查昨天下午 3 点什么都查不到，但录像其实存在」。
     *
     * @return 设备时间；读不到返回 {@code null}
     */
    public static LocalDateTime queryTime(DahuaSdk.Session session) {
        NetSDKLib.NET_TIME t = new NetSDKLib.NET_TIME();
        boolean ok = requireSdk().CLIENT_QueryDeviceTime(session.handle(), t, 5_000);
        if (!ok) {
            return null;
        }
        try {
            return LocalDateTime.of(t.dwYear, t.dwMonth, t.dwDay,
                    t.dwHour, t.dwMinute, t.dwSecond);
        } catch (RuntimeException e) {
            // 设备返回全 0 或非法日期时不要抛，让调用方按「读不到」处理
            return null;
        }
    }

    /**
     * 把设备时间设为指定值。
     *
     * @param time 目标时间；{@code null} 表示用平台当前时间
     */
    public static boolean setTime(DahuaSdk.Session session, LocalDateTime time) {
        LocalDateTime t = time == null ? LocalDateTime.now() : time;
        NetSDKLib.NET_TIME sdkTime = new NetSDKLib.NET_TIME();
        sdkTime.dwYear = t.getYear();
        sdkTime.dwMonth = t.getMonthValue();
        sdkTime.dwDay = t.getDayOfMonth();
        sdkTime.dwHour = t.getHour();
        sdkTime.dwMinute = t.getMinute();
        sdkTime.dwSecond = t.getSecond();
        sdkTime.write();
        boolean ok = requireSdk().CLIENT_SetupDeviceTime(session.handle(), sdkTime);
        if (!ok) {
            throw new DahuaSdkException("设备校时失败",
                    requireSdk().CLIENT_GetLastError(), "确认账号有校时权限");
        }
        return true;
    }

    // ==================== 报警清除 ====================

    /**
     * 清除通道当前的报警状态（国标 {@code AlarmReset}）。
     *
     * @param channel SDK 通道号（IPC 用 0）
     */
    public static boolean clearAlarm(DahuaSdk.Session session, int channel) {
        NetSDKLib sdk = requireSdk();
        NetSDKLib.NET_CTRL_CLEAR_ALARM param = new NetSDKLib.NET_CTRL_CLEAR_ALARM();
        param.nChannelID = channel;
        param.emAlarmType = 0;
        param.write();
        boolean ok = sdk.CLIENT_ControlDevice(session.handle(),
                NetSDKLib.CtrlType.CTRLTYPE_CTRL_CLEAR_ALARM, param.getPointer(), 5_000);
        if (!ok) {
            throw new DahuaSdkException("报警复位失败",
                    sdk.CLIENT_GetLastError(), "该型号可能没有可复位的报警，或通道号不对");
        }
        return true;
    }

    // ==================== 灯光 / 雨刷 ====================

    /**
     * 灯光 / 雨刷控制（国标 {@code Wiper} / {@code FillLight}）。
     *
     * <p>大华把这两个功能合并成一条 PTZ 辅助指令
     * {@code NET_PTZ_LAMP_CONTROL}（源码注释：「灯光雨刷」），
     * 靠 {@code param2} 区分是哪一个。
     *
     * <p><b>返回值不能证明设备动作了</b>：大华对不支持的指令照样返回 true。
     * 需要确认效果只能看画面 —— 补光灯会让画面变亮，雨刷会让画面抖动。
     *
     * @param on        开 / 关
     * @param lampIndex 灯光序号：0 = 近光/补光，1 = 远光；雨刷通常也用 0
     */
    public static boolean setLamp(DahuaSdk.Session session, int channel,
                                  boolean on, int lampIndex) {
        NetSDKLib sdk = requireSdk();
        boolean ok = sdk.CLIENT_DHPTZControlEx2(session.handle(), channel,
                NetSDKLib.NET_PTZ_ControlType.NET_PTZ_LAMP_CONTROL,
                on ? 1 : 0, lampIndex, 0, 0, null);
        if (!ok) {
            throw new DahuaSdkException("灯光/雨刷控制失败",
                    sdk.CLIENT_GetLastError(), "该型号可能没有补光灯或雨刷");
        }
        return true;
    }

    // ==================== 设备信息 ====================

    /**
     * 设备基本信息。
     *
     * @param serial       序列号
     * @param firmware     软件版本；该型号不支持查询时为 {@code null}
     * @param channelCount 通道数
     * @param deviceType   设备类型码（{@code NET_DEVICEINFO_Ex.byDVRType}，20 = IPC）
     */
    public record DeviceInfo(String serial, String firmware,
                             int channelCount, int deviceType) {

        /** 设备类型是否 IPC（IPC 的云台/抓图/配置通道号是 0，NVR 是 1~N）。 */
        public boolean ipc() {
            return deviceType == NetSDKLib.NET_DEVICE_TYPE.NET_IPC_SERIAL;
        }
    }

    /**
     * 读设备基本信息。
     *
     * <p>版本走 {@code CLIENT_GetSoftwareVersion}；该接口在部分型号上不可用，
     * 此时固件版本返回 {@code null} —— <b>不编造</b>。
     * 序列号 / 通道数 / 设备类型直接取登录会话里的值（登录时设备一定返回过）。
     *
     * @return 恒非 {@code null}
     */
    public static DeviceInfo deviceInfo(DahuaSdk.Session session) {
        String firmware = null;
        try {
            var in = new com.netsdk.lib.structure.NET_IN_GET_SOFTWAREVERSION_INFO();
            in.write();
            var out = new com.netsdk.lib.structure.NET_OUT_GET_SOFTWAREVERSION_INFO();
            out.write();
            boolean ok = requireSdk().CLIENT_GetSoftwareVersion(session.handle(),
                    in.getPointer(), out.getPointer(), 5_000);
            if (ok) {
                out.read();
                firmware = readCString(out.szVersion);
            }
        } catch (Throwable ignored) {
            // 型号不支持或 SDK 结构不匹配都不影响其余字段，静默退回
        }
        return new DeviceInfo(session.serial(), firmware,
                session.channelCount(), session.deviceType());
    }

    // ==================== 内部 ====================

    private static String readCString(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        int end = 0;
        while (end < bytes.length && bytes[end] != 0) {
            end++;
        }
        String s = new String(bytes, 0, end, java.nio.charset.StandardCharsets.UTF_8).trim();
        return s.isEmpty() ? null : s;
    }

    private static NetSDKLib requireSdk() {
        NetSDKLib sdk = DahuaSdk.get().sdk();
        if (sdk == null) {
            throw new DahuaSdkException("大华 SDK 不可用", DahuaSdk.get().loadError());
        }
        return sdk;
    }
}
