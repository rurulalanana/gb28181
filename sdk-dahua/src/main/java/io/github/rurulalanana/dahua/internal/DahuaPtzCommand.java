package io.github.rurulalanana.dahua.internal;

import com.netsdk.lib.NetSDKLib;

import java.util.Locale;

/**
 * 统一的云台方向词 → 大华 SDK 指令码。
 *
 * <h3>为什么单独一个枚举</h3>
 * 契约（{@code DeviceAccessProvider#ptz}）用的是<b>协议无关的方向词</b>
 * （{@code up} / {@code upright} / {@code zoomin} / {@code stop} …），
 * 平台侧的 {@code ControlService} 已经按国标语义把它们归一化过。
 * 厂商指令码是纯数字常量，散落在实现里会变成一堆魔法数字，
 * 而且没法单测 —— 这里收敛成枚举，映射关系可以脱离原生库直接测。
 *
 * <h3>为什么不用 SDK 的枚举类</h3>
 * 那些常量定义在 {@code NetSDKLib} 的嵌套类里（{@code NET_PTZ_ControlType} /
 * {@code NET_EXTPTZ_ControlType}），且都是 {@code public static final int}
 * 的<b>编译期常量</b> —— javac 会把值直接内联进本枚举的字节码，
 * 运行时既不加载嵌套类、也不会触发 {@code NetSDKLib} 的静态初始化块。
 *
 * <p>这一点很关键：{@code NetSDKLib} 的静态块会 {@code Native.load} 原生库。
 * 若本枚举引用了非编译期常量，那么「解析方向词」这个纯逻辑动作
 * 也会把原生库拖进来，导致没有 SDK 的机器上连单测都跑不了。
 */
public enum DahuaPtzCommand {

    UP(NetSDKLib.NET_PTZ_ControlType.NET_PTZ_UP_CONTROL),
    DOWN(NetSDKLib.NET_PTZ_ControlType.NET_PTZ_DOWN_CONTROL),
    LEFT(NetSDKLib.NET_PTZ_ControlType.NET_PTZ_LEFT_CONTROL),
    RIGHT(NetSDKLib.NET_PTZ_ControlType.NET_PTZ_RIGHT_CONTROL),

    /*
     * 四角方向不在 NET_PTZ_ControlType 里，而在 NET_EXTPTZ_ControlType。
     * 这是大华的划分方式：基础方向用 0~n，扩展方向从 0x20 起。
     * 混用会导致「按左上，画面往上走」这类难查的偏差。
     */
    UP_LEFT(NetSDKLib.NET_EXTPTZ_ControlType.NET_EXTPTZ_LEFTTOP),
    UP_RIGHT(NetSDKLib.NET_EXTPTZ_ControlType.NET_EXTPTZ_RIGHTTOP),
    DOWN_LEFT(NetSDKLib.NET_EXTPTZ_ControlType.NET_EXTPTZ_LEFTDOWN),
    DOWN_RIGHT(NetSDKLib.NET_EXTPTZ_ControlType.NET_EXTPTZ_RIGHTDOWN),

    ZOOM_IN(NetSDKLib.NET_PTZ_ControlType.NET_PTZ_ZOOM_ADD_CONTROL),
    ZOOM_OUT(NetSDKLib.NET_PTZ_ControlType.NET_PTZ_ZOOM_DEC_CONTROL),

    FOCUS_NEAR(NetSDKLib.NET_PTZ_ControlType.NET_PTZ_FOCUS_ADD_CONTROL),
    FOCUS_FAR(NetSDKLib.NET_PTZ_ControlType.NET_PTZ_FOCUS_DEC_CONTROL),
    IRIS_OPEN(NetSDKLib.NET_PTZ_ControlType.NET_PTZ_APERTURE_ADD_CONTROL),
    IRIS_CLOSE(NetSDKLib.NET_PTZ_ControlType.NET_PTZ_APERTURE_DEC_CONTROL);

    private final int code;

    DahuaPtzCommand(int code) {
        this.code = code;
    }

    /** SDK 指令码（{@code CLIENT_DHPTZControlEx2} 的 dwPTZCommand）。 */
    public int code() {
        return code;
    }

    /** 是否为变倍指令（速度口径与方向不同）。 */
    public boolean zoom() {
        return this == ZOOM_IN || this == ZOOM_OUT;
    }

    /**
     * 解析契约里的方向词。
     *
     * <p>大小写与分隔符都不敏感：{@code upLeft} / {@code up-left} /
     * {@code UPLEFT} 都能识别 —— 前端与国标侧的历史写法并不统一。
     *
     * @param command 方向词
     * @return 对应指令；{@code stop} 或无法识别时返回 {@code null}
     *         （{@code stop} 由调用方单独处理，它不是一个指令码，而是「停」这个动作）
     */
    public static DahuaPtzCommand parse(String command) {
        if (command == null) {
            return null;
        }
        String v = command.trim().toUpperCase(Locale.ROOT)
                .replace("-", "").replace("_", "").replace(" ", "");
        return switch (v) {
            case "UP" -> UP;
            case "DOWN" -> DOWN;
            case "LEFT" -> LEFT;
            case "RIGHT" -> RIGHT;
            case "UPLEFT", "LEFTUP" -> UP_LEFT;
            case "UPRIGHT", "RIGHTUP" -> UP_RIGHT;
            case "DOWNLEFT", "LEFTDOWN" -> DOWN_LEFT;
            case "DOWNRIGHT", "RIGHTDOWN" -> DOWN_RIGHT;
            case "ZOOMIN" -> ZOOM_IN;
            case "ZOOMOUT" -> ZOOM_OUT;
            case "FOCUSNEAR" -> FOCUS_NEAR;
            case "FOCUSFAR" -> FOCUS_FAR;
            case "IRISOPEN", "IRISIN" -> IRIS_OPEN;
            case "IRISCLOSE", "IRISOUT" -> IRIS_CLOSE;
            // "stop" 与未知值都返回 null：前者语义是「停止」不是指令，
            // 后者必须让调用方明确报错，不能静默当成某个方向
            default -> null;
        };
    }

    /** 是否为「停止」指令。 */
    public static boolean isStop(String command) {
        return command != null && "stop".equalsIgnoreCase(command.trim());
    }
}
