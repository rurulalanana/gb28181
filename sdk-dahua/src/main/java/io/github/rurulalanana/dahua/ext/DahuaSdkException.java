package io.github.rurulalanana.dahua.ext;

/**
 * 大华扩展能力的调用失败。
 *
 * <h3>为什么要带 SDK 错误码</h3>
 * 大华的错误码是 {@code 0x80000xxx} 段的有符号 int（如 {@code -2147483644}），
 * 直接抛给前端等于没说。本异常把它拆成三部分：
 * <ul>
 *   <li>{@code sdkError} —— 原始码，排障时能直接对照大华文档</li>
 *   <li>十六进制形式 —— 与文档/论坛里的写法一致（0x80000443）</li>
 *   <li>{@code hint} —— 一句人能看懂、能动手的原因</li>
 * </ul>
 *
 * <p><b>hint 只在这里拼一次</b>：{@code getMessage()} 已含完整可读文本，
 * 上层直接用 {@code e.getMessage()} 即可。<b>不要</b>再追加 {@link #hint()}，
 * 否则会出现「该通道可能无云台…该通道可能无云台…」这种重复。
 * {@code hint()} 只在需要单独取提示做结构化处理时才用。
 *
 * <p>典型错误码：
 * <ul>
 *   <li>{@code 0x80000004} 句柄无效 —— 会话已断开，重新登录</li>
 *   <li>{@code 0x80000015} 返回数据错误 —— 指令码不被该型号接受</li>
 *   <li>{@code 0x80000017} 不支持 —— 该型号没有这个功能</li>
 *   <li>{@code 0x80000018} 未找到 —— 配置项/录像不存在</li>
 *   <li>{@code 0x80000443} 设备组件工厂获取失败 —— 该型号不支持这个功能</li>
 * </ul>
 */
public class DahuaSdkException extends RuntimeException {

    private final int sdkError;
    private final String hint;

    public DahuaSdkException(String message, int sdkError, String hint) {
        super(build(message, sdkError, hint));
        this.sdkError = sdkError;
        this.hint = hint;
    }

    public DahuaSdkException(String message, String hint) {
        this(message, 0, hint);
    }

    public int sdkError() {
        return sdkError;
    }

    public String hint() {
        return hint;
    }

    private static String build(String message, int sdkError, String hint) {
        StringBuilder sb = new StringBuilder(message);
        if (sdkError != 0) {
            sb.append("（SDK错误码 ").append(sdkError)
                    .append(" / 0x").append(Integer.toHexString(sdkError).toUpperCase());
            String known = describe(sdkError);
            if (known != null) {
                sb.append("，").append(known);
            }
            sb.append('）');
        }
        if (hint != null && !hint.isBlank()) {
            sb.append("；").append(hint);
        }
        return sb.toString();
    }

    /**
     * 错误码 → 中文含义。
     *
     * <p>取自 {@code com.netsdk.lib.LastError} 的常量名，
     * 只翻译现场真会遇到的几个；未知码返回 {@code null}（保留原始码供查手册）。
     */
    private static String describe(int code) {
        return switch (code) {
            case 0x80000004 -> "句柄无效：会话已断开，需重新登录";
            case 0x80000005 -> "句柄无效：会话已断开，需重新登录";
            case 0x80000007 -> "参数不合法：通道号可能超出范围";
            /*
             * 这个码的含义【随指令不同】：预置点场景是「该编号没有预置点」，
             * 其它场景可能是设备不接受参数。所以这里只给中性描述，
             * 具体原因由调用方的 hint 说明 —— 写死成「不支持该指令」会误导排查方向。
             */
            case 0x80000015 -> "返回数据错误";
            case 0x80000017 -> "不支持：该型号没有这个功能";
            case 0x80000018 -> "未找到：配置项或录像不存在";
            case 0x80000443 -> "设备组件工厂获取失败：该型号不支持这个功能";
            default -> null;
        };
    }
}
