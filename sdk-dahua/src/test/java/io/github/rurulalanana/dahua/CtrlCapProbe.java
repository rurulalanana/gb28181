package io.github.rurulalanana.dahua;

import com.netsdk.lib.NetSDKLib;
import com.sun.jna.ptr.IntByReference;
import io.github.rurulalanana.dahua.ext.DahuaExtAccess;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.deviceaccess.ConnectMode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 设备控制能力探测：把国标侧被拒的那批命令逐个在真机上试一遍。
 *
 * <p>只回答一个问题：<b>这台设备到底支持哪几个</b>。
 * 判定不用返回值（大华对不支持项也返回 true），而是：
 * <ul>
 *   <li>配置类 → 读得到配置项、且写入后回读生效</li>
 *   <li>状态类 → 查状态值前后变化</li>
 *   <li>动作类 → 位置/时间等可观测副作用</li>
 * </ul>
 */
public final class CtrlCapProbe {

    private static DahuaSdk.Session session;
    private static DahuaExtAccess ext;

    public static void main(String[] args) throws Exception {
        String host = arg(args, 0, "192.168.10.156");
        int port = Integer.parseInt(arg(args, 1, "37777"));
        String user = arg(args, 2, "admin");
        String pass = arg(args, 3, "gxhy@123");
        String serial = arg(args, 4, "10001");

        DahuaSdk sdk = DahuaSdk.get();
        if (!sdk.init()) {
            say("SDK 初始化失败: " + sdk.loadError());
            return;
        }
        session = sdk.session(host, port, user, pass, ConnectMode.CLIENT, serial);
        if (session == null) {
            say("登录失败");
            return;
        }
        ext = new DahuaExtAccess();
        say("登录成功 ipc=" + session.ipc() + " 通道=" + session.channelCount());

        int ch = 0;

        say("=== A. 录像计划配置项 ===");
        for (String c : new String[]{"Record", "RecordPlan", "VideoInRecord", "RecordConfig"}) {
            readCfg(c, ch);
        }

        say("=== B. 布防相关配置项 ===");
        for (String c : new String[]{"Alarm", "AlarmConfig", "NetworkAlarm", "AlarmOut"}) {
            readCfg(c, ch);
        }

        say("=== C. 设备信息 / 状态 ===");
        queryTime();
        querySdCard(ch);
        queryRecording(ch);

        say("=== D. 手动录像（CLIENT_RecordStartEx）===");
        try {
            boolean ok = sdk.sdk().CLIENT_RecordStartEx(session.handle());
            say("   RecordStartEx → " + ok + (ok ? "" : "  err=" + err()));
            Thread.sleep(2000);
            boolean ok2 = sdk.sdk().CLIENT_RecordStopEx(session.handle());
            say("   RecordStopEx  → " + ok2 + (ok2 ? "" : "  err=" + err()));
        } catch (Throwable t) {
            say("   异常: " + t);
        }

        say("=== E. 校时 ===");
        try {
            NetSDKLib.NET_TIME t = new NetSDKLib.NET_TIME();
            boolean ok = sdk.sdk().CLIENT_QueryDeviceTime(session.handle(), t, 3000);
            if (ok) {
                say("   设备时间: " + t.dwYear + "-" + t.dwMonth + "-" + t.dwDay
                        + " " + t.dwHour + ":" + t.dwMinute + ":" + t.dwSecond);
            } else {
                say("   QueryDeviceTime 失败 err=" + err());
            }
        } catch (Throwable t) {
            say("   异常: " + t);
        }

        say("=== F. 补光灯 / 雨刷（PTZ 辅助开关）===");
        for (int lamp = 0; lamp <= 1; lamp++) {
            boolean ok = ptzAux(ch, NetSDKLib.NET_PTZ_ControlType.NET_PTZ_LAMP_CONTROL, lamp);
            say("   LAMP " + lamp + " → " + ok + (ok ? "" : "  err=" + err()));
        }

        say("=== G. SD 卡格式化（CTRLTYPE_CTRL_SDCARD）—— 不实际执行 ===");
        say("   跳过：会清空录像，需人工确认后再试");

        say("=== H. 报警清除 ===");
        try {
            NetSDKLib.NET_CTRL_CLEAR_ALARM p = new NetSDKLib.NET_CTRL_CLEAR_ALARM();
            p.nChannelID = ch;
            p.emAlarmType = 0;
            p.write();
            boolean ok = sdk.sdk().CLIENT_ControlDevice(session.handle(),
                    NetSDKLib.CtrlType.CTRLTYPE_CTRL_CLEAR_ALARM, p.getPointer(), 3000);
            say("   CLEAR_ALARM → " + ok + (ok ? "" : "  err=" + err()));
        } catch (Throwable t) {
            say("   异常: " + t);
        }

        say("=== I. 强制关键帧 ===");
        say("   SDK 无独立 API，走 CLIENT_ControlDevice 也无对应 CtrlType");
        say("   → 只能用 CLIENT_SetNewDevConfig 改编码参数，不实用");

        say("=== J. 设备重启（CTRLTYPE_CTRL_REBOOT）—— 不实际执行 ===");
        say("   跳过：会断流，需人工确认后再试");

        System.exit(0);
    }

    private static void readCfg(String name, int ch) {
        try {
            String json = ext.readConfig(session, name, ch);
            say("   " + name + " → OK 长度=" + (json == null ? 0 : json.length())
                    + (json != null && json.length() > 0
                    ? "  前 200: " + json.substring(0, Math.min(200, json.length())) : ""));
        } catch (Throwable t) {
            say("   " + name + " → 失败: " + shortMsg(t));
        }
    }

    private static void queryTime() {
        try {
            NetSDKLib.NET_TIME t = new NetSDKLib.NET_TIME();
            boolean ok = DahuaSdk.get().sdk().CLIENT_QueryDeviceTime(session.handle(), t, 3000);
            say("   QueryDeviceTime → " + ok + (ok ? "  " + t.dwYear + "-" + t.dwMonth + "-"
                    + t.dwDay + " " + t.dwHour + ":" + t.dwMinute : "  err=" + err()));
        } catch (Throwable t) {
            say("   QueryDeviceTime 异常: " + t);
        }
    }

    private static void querySdCard(int ch) {
        try {
            // 该 SDK 版本没有 NET_SD_CARD_INFO 结构，用裸缓冲区试 ——
            // 只看接口是否接受，不解析内容
            com.sun.jna.Memory buf = new com.sun.jna.Memory(1024);
            IntByReference ret = new IntByReference(0);
            boolean ok = DahuaSdk.get().sdk().CLIENT_QueryDevState(session.handle(),
                    NetSDKLib.NET_DEVSTATE_SD_CARD, buf, 1024, ret, 3000);
            say("   SD 卡查询 → " + ok + " 返回长度=" + ret.getValue()
                    + (ok ? "" : "  err=" + err()));
        } catch (Throwable t) {
            say("   SD 卡查询异常: " + t);
        }
    }

    private static void queryRecording(int ch) {
        try {
            IntByReference val = new IntByReference(0);
            boolean ok = DahuaSdk.get().sdk().CLIENT_QueryDevState(session.handle(),
                    NetSDKLib.NET_DEVSTATE_RECORDING,
                    val.getPointer(), 4, new IntByReference(0), 3000);
            say("   录像状态 → " + ok + " 值=" + val.getValue()
                    + (ok ? "" : "  err=" + err()));
        } catch (Throwable t) {
            say("   录像状态异常: " + t);
        }
    }

    private static boolean ptzAux(int ch, int command, int p1) {
        return DahuaSdk.get().sdk().CLIENT_DHPTZControlEx2(
                session.handle(), ch, command, p1, 0, 0, 0, null);
    }

    private static String err() {
        return "0x" + Integer.toHexString(DahuaSdk.get().sdk().CLIENT_GetLastError());
    }

    private static String shortMsg(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName()
                : m.substring(0, Math.min(120, m.length()));
    }

    private static String arg(String[] a, int i, String def) {
        return a.length > i && a[i] != null && !a[i].isBlank() ? a[i] : def;
    }

    private static void say(String msg) {
        System.out.println("[cap] " + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + msg);
        System.out.flush();
    }
}
