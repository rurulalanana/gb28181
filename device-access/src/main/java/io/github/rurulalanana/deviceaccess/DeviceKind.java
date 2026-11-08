package io.github.rurulalanana.deviceaccess;

/**
 * 设备的接入方式（决定用哪套协议栈与设备对话）。
 *
 * <p><b>为什么要区分</b>：同一个平台上，不同厂商、不同批次的设备能力差异很大。
 * 国标设备走 SIP + RTP；大华设备直连时用 NetSDK 才能拿到周边设备发现、
 * 巡航组/巡迹/限位、图像参数这些国标协议里没有的能力；
 * 海康走 ISUP 才能拿到绊线/区域入侵等智能报警与抓图直传。
 * 但<b>业务侧不该感知这个差别</b> —— 设备表、通道表、点播/回放/云台接口
 * 对上层必须是同一套。
 *
 * <p>判据存在 {@code gb_device.source}（历史字段，原本只写死 {@code "device"}）。
 */
public enum DeviceKind {

    /** GB/T 28181（SIP 信令 + RTP 收流）。历史数据也归到这里。 */
    GB28181("gb28181"),

    /** 大华 NetSDK 直连。 */
    DAHUA("dahua"),

    /**
     * 海康 ISUP（设备主动注册到平台，平台当 CMS + SMS）。
     *
     * <p>历史别名 {@code "ehome"}：ISUP 的前身叫 EHome，老部署里可能写成这个。
     */
    HIKVISION("hikvision");

    private final String code;

    DeviceKind(String code) {
        this.code = code;
    }

    /** 落库使用的取值。 */
    public String code() {
        return code;
    }

    /**
     * 解析 {@code gb_device.source}。
     *
     * <p><b>默认必须回落到 GB28181</b>：历史库里的值有 {@code "device"}、
     * 也可能有手工插入留下的 {@code null}。把它们当成未知类型会让这些设备
     * 立刻停止工作 —— 这个字段原本只用来标记「自动注册」，
     * 从来没有被当作协议判据用过，所以任何未知值都只能保守地按国标处理。
     */
    public static DeviceKind of(String source) {
        if (source == null || source.isBlank()) {
            return GB28181;
        }
        String v = source.trim().toLowerCase();
        if (DAHUA.code.equals(v) || "netsdk".equals(v)) {
            return DAHUA;
        }
        // ISUP 的前身叫 EHome，老部署与设备端配置界面都还写作 EHome
        if (HIKVISION.code.equals(v) || "ehome".equals(v) || "isup".equals(v)) {
            return HIKVISION;
        }
        // "device"（历史自动注册）、"gb28181"、"gb"、"sip" 等一律视为国标
        return GB28181;
    }
}
