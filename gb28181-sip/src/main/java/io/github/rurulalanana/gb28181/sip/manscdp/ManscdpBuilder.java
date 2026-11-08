package io.github.rurulalanana.gb28181.sip.manscdp;

import io.github.rurulalanana.gb28181.sip.constant.CmdType;
import io.github.rurulalanana.gb28181.sip.constant.ManscdpTypes;
import lombok.SneakyThrows;
import org.apache.commons.collections4.ListUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.Collection;
import java.util.List;

/**
 * MANSCDP 消息构造器。
 *
 * <p>覆盖 GB/T 28181—2022 附录 A 常用命令：Query / Notify / Control / Response。
 *
 * <p><b>字段顺序严格对齐标准 XSD 的 sequence 定义</b>——国标设备的 XML 解析器普遍顺序敏感，
 * 顺序错乱会导致整条消息被丢弃。
 */
public final class ManscdpBuilder {

    private ManscdpBuilder() {
    }

    // ==================== 内部骨架 ====================

    /** 新建文档并挂上根元素。 */
    @SneakyThrows
    private static Element newRoot(String name) {
        Document doc = ManscdpXml.newDocument();
        Element root = doc.createElement(name);
        doc.appendChild(root);
        return root;
    }

    @SneakyThrows
    private static String out(Element root) {
        return ManscdpXml.toXml(root.getOwnerDocument());
    }

    /** 三段式骨架：{@code <CmdType> <SN> <DeviceID>}。 */
    private static Element base(String rootName, CmdType type, int sn, String deviceId) {
        Element root = newRoot(rootName);
        ManscdpXml.addText(root, "CmdType", type.value());
        ManscdpXml.addText(root, "SN", sn);
        ManscdpXml.addText(root, "DeviceID", deviceId);
        return root;
    }

    /** 两段式骨架（无 DeviceID，如广播）。 */
    private static Element baseNoDevice(String rootName, CmdType type, int sn) {
        Element root = newRoot(rootName);
        ManscdpXml.addText(root, "CmdType", type.value());
        ManscdpXml.addText(root, "SN", sn);
        return root;
    }

    /** 仅 CmdType + SN + DeviceID 的三字段查询。 */
    private static String simpleQuery(CmdType type, int sn, String deviceId) {
        return out(base("Query", type, sn, deviceId));
    }

    /** 仅 CmdType + SN + DeviceID + Result 的应答。 */
    private static String simpleResponse(CmdType type, int sn, String deviceId, String result) {
        Element root = base("Response", type, sn, deviceId);
        ManscdpXml.addText(root, "Result", result == null ? ManscdpTypes.RESULT_OK : result);
        return out(root);
    }

    /** 仅 CmdType + SN + DeviceID + 单控制元素的控制命令。 */
    private static String simpleControl(int sn, String deviceId, String elementName, Object value) {
        Element root = base("Control", CmdType.DEVICE_CONTROL, sn, deviceId);
        ManscdpXml.addText(root, elementName, value);
        return out(root);
    }

    // ==================== Query —— A.2.4 查询命令 ====================

    /** 设备目录查询（A.2.4.3）。 */
    public static String catalogQuery(int sn, String deviceId) {
        return catalogQuery(sn, deviceId, null, null);
    }

    /**
     * 设备目录查询或订阅（A.2.4.3）。
     *
     * @param startTime 起始时间，可 {@code null}
     * @param endTime   终止时间，可 {@code null}
     */
    public static String catalogQuery(int sn, String deviceId, String startTime, String endTime) {
        Element root = base("Query", CmdType.CATALOG, sn, deviceId);
        // 时间同样必须是 ISO8601（见 isoTime 的说明）
        ManscdpXml.addText(root, "StartTime", isoTime(startTime));
        ManscdpXml.addText(root, "EndTime", isoTime(endTime));
        return out(root);
    }

    /** 设备信息查询（A.2.4.4）。 */
    public static String deviceInfoQuery(int sn, String deviceId) {
        return simpleQuery(CmdType.DEVICE_INFO, sn, deviceId);
    }

    /** 设备状态查询（A.2.4.2）。 */
    public static String deviceStatusQuery(int sn, String deviceId) {
        return simpleQuery(CmdType.DEVICE_STATUS, sn, deviceId);
    }

    /**
     * 文件目录检索（A.2.4.5）。
     *
     * <p>标准中 {@code StartTime} / {@code EndTime} 为<b>必选</b>（XSD 未给 minOccurs）。
     *
     * <p>默认补 {@code <Secrecy>0</Secrecy>} 与 {@code <Type>all</Type>}，
     * 见 {@link #recordInfoQuery(int, String, String, String, int, String)}。
     */
    public static String recordInfoQuery(int sn, String deviceId, String startTime, String endTime) {
        return recordInfoQuery(sn, deviceId, startTime, endTime, 0, "all");
    }

    /**
     * 文件目录检索（A.2.4.5），可指定涉密属性与录像类型。
     *
     * <h3>为什么要带 {@code Secrecy} / {@code Type}</h3>
     * 标准把这两项列为可选（{@code Type} 元素在 XSD 里就是 minOccurs=0），
     * 但<b>大华 NVR 不带 {@code <Type>} 就不返回任何录像</b>：
     * 实测下发的报文只有 CmdType/SN/DeviceID/StartTime/EndTime 时，
     * 设备回 {@code 400 wait for recordinfo timeout[15s]} ——
     * 它自己的报错文本会把人引向「下级超时」，而真实原因是必备字段缺失。
     *
     * <p>这一条在与 wvp-GB28181-pro 实现对照后才找到，它的
     * {@code SIPCommander#recordInfoQuery} 里就写着
     * 「大华NVR要求必须增加一个值为all的文本元素节点Type」。
     *
     * <p>补上这两个字段对其他厂商没有副作用 —— 标准里的可选字段，
     * 不支持时设备忽略即可。
     *
     * @param secrecy 保密属性：0 不涉密、1 涉密
     * @param type    录像类型：{@code all} / {@code time} / {@code alarm} / {@code manual}
     */
    public static String recordInfoQuery(int sn, String deviceId, String startTime,
                                         String endTime, int secrecy, String type) {
        Element root = base("Query", CmdType.RECORD_INFO, sn, deviceId);
        ManscdpXml.addText(root, "StartTime", isoTime(startTime));
        ManscdpXml.addText(root, "EndTime", isoTime(endTime));
        ManscdpXml.addText(root, "Secrecy", secrecy);
        ManscdpXml.addText(root, "Type",
                (type == null || type.trim().isEmpty()) ? "all" : type.trim());
        return out(root);
    }

    /**
     * 时间串规范化为标准要求的 ISO8601（日期与时间之间用 {@code T}）。
     *
     * <h3>为什么必须做这一步</h3>
     * GB/T 28181—2022 表 45 规定 {@code StartTime/EndTime} 格式为
     * {@code yyyy-MM-ddTHH:mm:ss}，<b>中间是 T 不是空格</b>。
     *
     * <p>我们原来直接下发了数据库/接口里的空格形式
     * （{@code 2026-10-26 12:00:00}），而级联设备（实测 LiveGBS v250103）
     * 严格按 ISO8601 解析：<b>空格形式它一个字节都不回</b>，只是内部等满了
     * 自己的超时后回 {@code 400 wait for recordinfo timeout[15s]} ——
     * 那句报错把人引向「下级超时」，而其真实原因是时间格式不合规。
     *
     * <p>实测对比（同一设备、同一时段，其余字段完全相同）：
     * <pre>
     * StartTime=2026-10-26 12:00:00  → 20 秒内无任何应答
     * StartTime=2026-10-26T12:00:00  → 立即 200 OK，随后收到 SumNum=1 的数据帧
     * </pre>
     *
     * <p>已在带 {@code T} 的输入上幂等：重复调用不会变成 {@code TT}。
     *
     * @param time 形如 {@code 2026-10-26 12:00:00} 或 {@code 2026-10-26T12:00:00}；
     *             已经是带时区/毫秒的串也只会动第一个空格
     * @return 规范化后的时间串；{@code null}/空时原样返回
     */
    static String isoTime(String time) {
        if (time == null) {
            return null;
        }
        String trimmed = time.trim();
        if (trimmed.isEmpty() || trimmed.indexOf('T') > 0) {
            return trimmed;
        }
        int space = trimmed.indexOf(' ');
        if (space < 0) {
            return trimmed;
        }
        return trimmed.substring(0, space) + "T" + trimmed.substring(space + 1).trim();
    }

    /** 设备预置位查询（A.2.4.8）。 */
    public static String presetQuery(int sn, String deviceId) {
        return simpleQuery(CmdType.PRESET_QUERY, sn, deviceId);
    }

    /** 看守位信息查询（A.2.4.10）。 */
    public static String homePositionQuery(int sn, String deviceId) {
        return simpleQuery(CmdType.HOME_POSITION_QUERY, sn, deviceId);
    }

    /** 巡航轨迹<b>列表</b>查询（A.2.4.11）。 */
    public static String cruiseTrackListQuery(int sn, String deviceId) {
        return simpleQuery(CmdType.CRUISE_TRACK_LIST_QUERY, sn, deviceId);
    }

    /** 存储卡状态查询（A.2.4.14）。 */
    public static String sdCardStatusQuery(int sn, String deviceId) {
        return simpleQuery(CmdType.SD_CARD_STATUS, sn, deviceId);
    }

    /** PTZ 精确状态查询或订阅（A.2.4.13）。 */
    public static String ptzPositionQuery(int sn, String deviceId) {
        return simpleQuery(CmdType.PTZ_POSITION, sn, deviceId);
    }

    /**
     * 巡航轨迹查询（A.2.4.12），{@code Number} 必选。
     *
     * @param number 轨迹编号，0 表示第一条
     */
    public static String cruiseTrackQuery(int sn, String deviceId, int number) {
        Element root = base("Query", CmdType.CRUISE_TRACK_QUERY, sn, deviceId);
        ManscdpXml.addText(root, "Number", number);
        return out(root);
    }

    /**
     * 移动设备位置数据订阅（A.2.4.9）。
     *
     * @param intervalSeconds 上报间隔秒数（标准 default=5），{@code null} 表示不填
     */
    public static String mobilePositionSubscribe(int sn, String deviceId, Integer intervalSeconds) {
        Element root = base("Query", CmdType.MOBILE_POSITION, sn, deviceId);
        ManscdpXml.addText(root, "Interval", intervalSeconds);
        return out(root);
    }

    /**
     * 报警订阅查询（A.2.4.6）。
     * <p><b>注意</b>此处标准定义为 {@code type="string"}，与通知命令（A.2.5.3）中
     * {@code dateTime} 类型的 {@code AlarmTime} 不同。
     */
    public static String alarmSubscribe(int sn, String deviceId, Integer startPriority,
                                        Integer endPriority, Integer alarmMethod, Integer alarmType) {
        Element root = base("Query", CmdType.ALARM, sn, deviceId);
        ManscdpXml.addText(root, "StartAlarmPriority", startPriority);
        ManscdpXml.addText(root, "EndAlarmPriority", endPriority);
        ManscdpXml.addText(root, "AlarmMethod", alarmMethod);
        ManscdpXml.addText(root, "AlarmType", alarmType);
        return out(root);
    }

    /**
     * 设备配置查询（A.2.4.7）。
     * <p>{@code ConfigType} 可含多值用 {@code /} 分隔，每种配置对应一条 Response，SN 相同。
     */
    public static String configDownloadQuery(int sn, String deviceId, String configType) {
        Element root = base("Query", CmdType.CONFIG_DOWNLOAD, sn, deviceId);
        ManscdpXml.addText(root, "ConfigType", configType);
        return out(root);
    }

    // ==================== Notify —— A.2.5 通知命令 ====================

    /**
     * 心跳保活（A.2.5.2）。
     * <p>标准 XSD 中 {@code Status} 为 {@code tg:resultType}，合法值仅 {@code OK} / {@code ERROR}；
     * 部分设备仍发 {@code ON}/{@code OFF}/{@code NOTIFY}，本方法<b>原样透传</b>不归一化。
     *
     * @param status    状态字面量
     * @param faultyIds 故障设备编码列表，可 {@code null}
     */
    public static String keepalive(int sn, String deviceId, String status, Collection<String> faultyIds) {
        Element root = base("Notify", CmdType.KEEPALIVE, sn, deviceId);
        ManscdpXml.addText(root, "Status", status);
        if (faultyIds != null && !faultyIds.isEmpty()) {
            Element info = ManscdpXml.addContainer(root, "Info", faultyIds.size());
            faultyIds.forEach(id -> ManscdpXml.addText(info, "DeviceID", id));
        }
        return out(root);
    }

    /** 简化心跳，状态固定 {@code OK}。 */
    public static String keepalive(int sn, String deviceId) {
        return keepalive(sn, deviceId, ManscdpTypes.RESULT_OK, null);
    }

    /**
     * 语音广播通知（A.2.5.5）。
     * <p>字段是 {@code SourceID} / {@code TargetID}，<b>不是</b> {@code DeviceID}。
     */
    public static String broadcast(int sn, String sourceId, String targetId) {
        Element root = baseNoDevice("Notify", CmdType.BROADCAST, sn);
        ManscdpXml.addText(root, "SourceID", sourceId);
        ManscdpXml.addText(root, "TargetID", targetId);
        return out(root);
    }

    // ==================== Response —— A.2.6 应答命令 ====================

    /** 报警通知应答（A.2.6.3）。设备需收到回执才会继续上报新报警。 */
    public static String alarmResponse(int sn, String deviceId, String result) {
        return simpleResponse(CmdType.ALARM, sn, deviceId, result);
    }

    /**
     * 设备控制应答（A.2.6.2）。
     * 2022 版将播放/回放等控制应答统一到 {@code DeviceControl}。
     */
    public static String deviceControlResponse(int sn, String deviceId, String result) {
        return simpleResponse(CmdType.DEVICE_CONTROL, sn, deviceId, result);
    }

    /** 设备配置应答（A.2.6.8）。 */
    public static String deviceConfigResponse(int sn, String deviceId, String result) {
        return simpleResponse(CmdType.DEVICE_CONFIG, sn, deviceId, result);
    }

    /**
     * 设备目录查询应答（A.2.6.4）。
     * <p>{@code SumNum} <b>必选</b>，语义为本次返回的目录项总数，须与
     * {@code DeviceList/@Num} 一致；即使列表为空也必须出现（此时取 0）。
     *
     * @param deviceId 目标编码，<b>必须与查询请求相同</b>
     * @param items    目录项列表，可为空
     */
    public static String catalogResponse(int sn, String deviceId, Collection<CatalogItem> items) {
        List<CatalogItem> list = items == null ? java.util.Collections.emptyList()
                : new java.util.ArrayList<>(items);
        Element root = base("Response", CmdType.CATALOG, sn, deviceId);
        ManscdpXml.addText(root, "SumNum", list.size());
        Element deviceList = ManscdpXml.addContainer(root, "DeviceList", list.size());
        list.forEach(it -> it.appendTo(deviceList));
        return out(root);
    }

    /** 空目录应答（SumNum=0）。 */
    public static String emptyCatalogResponse(int sn, String deviceId) {
        return catalogResponse(sn, deviceId, null);
    }

    /**
     * 目录变更<b>通知</b>（A.2.5，{@code <Notify>} 根节点）。
     *
     * <p><b>与 {@link #catalogResponse} 的区别是「有没有人在等」</b>：
     * <ul>
     *   <li>{@code <Response>} 是<b>对某个查询的答复</b>，对端按
     *       {@code (SN, DeviceID)} 去匹配它自己发出过的查询；
     *       匹配不上就直接丢弃 —— 它没有任何理由相信这个 SN 属于自己。</li>
     *   <li>{@code <Notify>} 是<b>主动通知</b>，不需要有人提问，
     *       对端按「目录变化」处理（新增/更新/删除）。</li>
     * </ul>
     *
     * <p><b>这个区别踩过</b>：级联时本级通道有变化，用 {@code <Response>} 推给上级，
     * 上级（实测 LiveGBS）收到后既不报错也不落库 —— 因为它没在等那个 SN。
     * 现象是「日志显示推送成功、对端通道数一直是 0」，而且双方都没有任何错误日志，
     * 属于最难排查的一类。
     *
     * @param sn       消息序号
     * @param deviceId 本级平台编码
     * @param items    变化的目录项（{@code Item/Event} 决定是新增还是删除）
     */
    public static String catalogNotify(int sn, String deviceId, Collection<CatalogItem> items) {
        List<CatalogItem> list = items == null ? java.util.Collections.emptyList()
                : new java.util.ArrayList<>(items);
        Element root = base("Notify", CmdType.CATALOG, sn, deviceId);
        ManscdpXml.addText(root, "SumNum", list.size());
        Element deviceList = ManscdpXml.addContainer(root, "DeviceList", list.size());
        list.forEach(it -> it.appendTo(deviceList));
        return out(root);
    }

    /**
     * 设备状态查询应答（A.2.6.6）。
     * <p><b>四个状态字段取值域互不相同，切勿混用</b>：
     * {@code result} → OK/ERROR；{@code online} → ONLINE/OFFLINE；
     * {@code status} → OK/ERROR；{@code encode}/{@code record} → ON/OFF。
     *
     * @param online {@code "ONLINE"} / {@code "OFFLINE"}
     * @param status {@code "OK"} / {@code "ERROR"}
     * @param encode {@code "ON"} / {@code "OFF"}
     * @param record {@code "ON"} / {@code "OFF"}
     */
    public static String deviceStatusResponse(int sn, String deviceId, String result, String online,
                                              String status, String reason, String encode, String record) {
        Element root = base("Response", CmdType.DEVICE_STATUS, sn, deviceId);
        ManscdpXml.addText(root, "Result", result == null ? ManscdpTypes.RESULT_OK : result);
        ManscdpXml.addText(root, "Online", online == null ? ManscdpTypes.ONLINE : online);
        ManscdpXml.addText(root, "Status", status == null ? ManscdpTypes.RESULT_OK : status);
        ManscdpXml.addText(root, "Reason", reason);
        ManscdpXml.addText(root, "Encode", encode);
        ManscdpXml.addText(root, "Record", record);
        return out(root);
    }

    /** 设备状态查询应答（正常在线的简版）。 */
    public static String onlineStatusResponse(int sn, String deviceId) {
        return deviceStatusResponse(sn, deviceId, ManscdpTypes.RESULT_OK, ManscdpTypes.ONLINE,
                ManscdpTypes.RESULT_OK, null, ManscdpTypes.STATUS_ON, ManscdpTypes.STATUS_ON);
    }

    /**
     * 设备信息查询应答（A.2.6.5）。
     * <p>注意 {@code Result} 位于 {@code DeviceName} <b>之后</b>。
     */
    public static String deviceInfoResponse(int sn, String deviceId, String result, String deviceName,
                                            String manufacturer, String model, String firmware, Integer channel) {
        Element root = base("Response", CmdType.DEVICE_INFO, sn, deviceId);
        ManscdpXml.addText(root, "DeviceName", deviceName);
        ManscdpXml.addText(root, "Result", result == null ? ManscdpTypes.RESULT_OK : result);
        ManscdpXml.addText(root, "Manufacturer", manufacturer);
        ManscdpXml.addText(root, "Model", model);
        ManscdpXml.addText(root, "Firmware", firmware);
        ManscdpXml.addText(root, "Channel", channel);
        return out(root);
    }

    // ==================== Control —— A.2.3 控制命令 ====================

    /**
     * PTZ 云台控制（A.2.3.1.2）。
     * <p><b>2022 版变化</b>：控制码不再是 {@code Info} 下的数值，而是 {@code <PTZCmd>} 的
     * <b>8 字符定长字符串</b>。请用 {@link io.github.rurulalanana.gb28181.sip.constant.PtzCommand} 生成。
     *
     * @param ptzCmd 8 字符控制码
     */
    public static String ptzControl(int sn, String deviceId, String ptzCmd) {
        return ptzControl(sn, deviceId, ptzCmd, null, null);
    }

    /**
     * PTZ 云台控制（带预置位/巡航参数）。
     *
     * @param presetName      预置位名称
     * @param cruiseTrackName 巡航轨迹名称（最长 32 字节）
     */
    public static String ptzControl(int sn, String deviceId, String ptzCmd,
                                    String presetName, String cruiseTrackName) {
        Element root = base("Control", CmdType.DEVICE_CONTROL, sn, deviceId);
        ManscdpXml.addText(root, "PTZCmd", ptzCmd);
        if (presetName != null || cruiseTrackName != null) {
            Element params = ManscdpXml.addContainer(root, "PTZCmdParams", null);
            ManscdpXml.addText(params, "PresetName", presetName);
            ManscdpXml.addText(params, "CruiseTrackName", cruiseTrackName);
        }
        return out(root);
    }

    /**
     * 把上级下发的 {@code <Control>} 指令转成下发给设备的 {@code <Control>}，<b>仅替换 SN</b>。
     *
     * <p><b>为什么要原样转发而不是逐字段重新构造</b>：
     * <ol>
     *   <li>控制命令族有十几种（{@code PTZCmd} / {@code PresetCmd} / {@code FiCmd} /
     *       {@code RecordCmd} / {@code GuardCmd} / {@code DragZoomIn} / {@code HomePosition}
     *       / {@code FormatSDCard} …），逐个字段映射既冗长又必然漏项；</li>
     *   <li>{@code PTZCmd} 是<b>8 字节不透明十六进制串</b>（A.5 附录），各厂商对保留位的
     *       用法不同（有的塞预置位号、有的塞绝对位置），解码再编码会破坏对方语义；</li>
     *   <li>上级看到的通道编码就是我们自己的通道编码（目录是我们发给它的），
     *       所以 {@code <DeviceID>} 无需改写。</li>
     * </ol>
     *
     * <p><b>为什么必须替换 SN</b>：SN 是 MANSCDP 的请求序号，由发起方持有并用来匹配应答。
     * 上级的 SN 是上级的事务号，我们向设备发起的是新事务，必须用自己的 SN，
     * 否则设备的应答会与上级的等待队列撞号。
     *
     * <p>字段顺序按原报文顺序保留（标准 XSD 的 sequence 顺序敏感，国标设备普遍
     * 顺序严格校验，重排会导致整条消息被丢弃）。
     *
     * @param sn  本级新的 SN
     * @param src 上级的原始 {@code <Control>} 消息
     * @return 可下发给设备的 XML
     */
    public static String relinkControl(int sn, ManscdpMessage src) {
        Document doc = ManscdpXml.newDocument();
        Element root = doc.createElement("Control");
        doc.appendChild(root);
        boolean snWritten = false;
        // children 是 LinkedHashMap，按原报文首次出现顺序遍历，保证顺序不变
        for (java.util.Map.Entry<String, List<Element>> e : src.getChildren().entrySet()) {
            if ("SN".equals(e.getKey())) {
                ManscdpXml.addText(root, "SN", sn);
                snWritten = true;
                continue;
            }
            for (Element child : ListUtils.emptyIfNull(e.getValue())) {
                root.appendChild(doc.importNode(child, true));
            }
        }
        if (!snWritten) {
            // 原报文无 SN（不合规，但真实设备出现过）：补在末尾，至少不丢消息体
            ManscdpXml.addText(root, "SN", sn);
        }
        return out(root);
    }

    /** PTZ 精准控制（A.2.3.1.11）。{@code pan} 0~360，{@code tilt} -90~90，{@code zoom} >1。 */
    public static String ptzPreciseControl(int sn, String deviceId, Double pan, Double tilt, Double zoom) {
        Element root = base("Control", CmdType.DEVICE_CONTROL, sn, deviceId);        Element ctl = ManscdpXml.addContainer(root, "PTZPreciseCtrl", null);
        ManscdpXml.addText(ctl, "Pan", pan);
        ManscdpXml.addText(ctl, "Tilt", tilt);
        ManscdpXml.addText(ctl, "Zoom", zoom);
        return out(root);
    }

    /** 远程启动控制（A.2.3.1.3）。 */
    public static String teleBoot(int sn, String deviceId) {
        return simpleControl(sn, deviceId, "TeleBoot", "Boot");
    }

    /** 强制关键帧（A.2.3.1.7），设备应立刻发送一个 IDR 帧。 */
    public static String forceIFrame(int sn, String deviceId) {
        return simpleControl(sn, deviceId, "IFrameCmd", "Send");
    }

    /** 报警复位（A.2.3.1.6）。 */
    public static String alarmReset(int sn, String deviceId) {
        return simpleControl(sn, deviceId, "AlarmCmd", "ResetAlarm");
    }

    /**
     * 设备校时（标准 A.2.3.1.9 / 9.12）。
     *
     * <p>字段名是 {@code DeviceTime}，取值格式 <b>严格为
     * {@code yyyy-MM-dd'T'HH:mm:ss}</b>（ISO 8601，无时区后缀）。
     * 写成毫秒或带 {@code Z} 会被多数设备判为非法而整条丢弃 ——
     * 而设备不会回错误应答，表现为「发了校时但时间没变」。
     *
     * <p><b>为什么必须做校时</b>：设备时钟漂移后，它上报录像时用的
     * {@code StartTime/EndTime} 与平台侧的检索区间对不上，
     * 用户按「昨天下午 3 点」查录像会什么都查不到，而录像其实存在。
     * 这个问题的排查成本极高（看起来像设备没录像）。
     *
     * @param deviceTime {@code yyyy-MM-dd'T'HH:mm:ss}（本地时间）
     */
    public static String timeSync(int sn, String deviceId, String deviceTime) {
        Element root = base("Control", CmdType.DEVICE_CONTROL, sn, deviceId);
        ManscdpXml.addText(root, "DeviceTime", deviceTime);
        return out(root);
    }

    /** 存储卡格式化（A.2.3.1.13），{@code sdCardId=0} 表示全部。 */
    public static String formatSdCard(int sn, String deviceId, int sdCardId) {
        return simpleControl(sn, deviceId, "FormatSDCard", sdCardId);
    }

    /**
     * 拉框放大（A.2.3.1.8）/ 拉框缩小（A.2.3.1.9）。
     *
     * <p>2022 版把拉框定义为<b>独立 XML 元素</b>，与 A.3 的 8 字节 {@code PTZCmd}
     * 是两条完全不同的通路：指令表 A.4/A.5 里没有拉框指令码。
     *
     * <p>坐标为<b>像素值</b>：{@code length}/{@code width} 是平台播放窗口像素尺寸，
     * 中心点与框大小同样以像素计。平台与设备画面比例不同，换算由设备完成。
     *
     * @param elementName {@code DragZoomIn} 或 {@code DragZoomOut}
     */
    private static String dragZoom(int sn, String deviceId, String elementName,
                                   int length, int width, int midX, int midY,
                                   int lenX, int lenY) {
        Element root = base("Control", CmdType.DEVICE_CONTROL, sn, deviceId);
        Element box = ManscdpXml.newElement(root, elementName);
        ManscdpXml.addText(box, "Length", length);
        ManscdpXml.addText(box, "Width", width);
        ManscdpXml.addText(box, "MidPointX", midX);
        ManscdpXml.addText(box, "MidPointY", midY);
        ManscdpXml.addText(box, "LengthX", lenX);
        ManscdpXml.addText(box, "LengthY", lenY);
        return out(root);
    }

    /** 拉框放大（A.2.3.1.8）。 */
    public static String dragZoomIn(int sn, String deviceId, int length, int width,
                                    int midX, int midY, int lenX, int lenY) {
        return dragZoom(sn, deviceId, "DragZoomIn", length, width, midX, midY, lenX, lenY);
    }

    /** 拉框缩小（A.2.3.1.9）。 */
    public static String dragZoomOut(int sn, String deviceId, int length, int width,
                                     int midX, int midY, int lenX, int lenY) {
        return dragZoom(sn, deviceId, "DragZoomOut", length, width, midX, midY, lenX, lenY);
    }

    /** 报警布防/撤防（A.2.3.1.5）：{@code SetGuard} / {@code ResetGuard}。 */
    public static String guardControl(int sn, String deviceId, String guardCmd) {
        return simpleControl(sn, deviceId, "GuardCmd", guardCmd);
    }

    /**
     * 目标跟踪控制（A.2.3.1.14）。
     *
     * <ul>
     *   <li>{@code Auto} 自动跟踪：设备按已配置参数自行跟踪，<b>不带坐标</b></li>
     *   <li>{@code Manual} 手动跟踪（指哪打哪）：<b>必须带 {@code <TargetArea>}</b></li>
     *   <li>{@code Stop} 停止跟踪</li>
     * </ul>
     *
     * <p>坐标是<b>像素值</b>不是百分比：{@code length}/{@code width} 是平台端
     * {@code 播放窗口}的像素尺寸，{@code midX}/{@code midY} 是跟踪框中心，
     * {@code lenX}/{@code lenY} 是跟踪框长宽。标准明确说明平台与设备画面比例不同、
     * 需设备自行换算，所以平台必须提供自己的窗口尺寸。
     *
     * <p>6 个坐标必须嵌在 {@code <TargetArea>} 里。平铺到 {@code <Control>} 下
     * 不符合 Schema，设备解析不到框选区域，表现为「命令收了但不跟踪」。
     *
     * <p>{@code deviceId} 是<b>球机通道</b>（执行跟踪的一方），
     * {@code deviceId2} 是<b>全景通道</b>（提供画面的另一方），可空。
     */
    public static String targetTrack(int sn, String deviceId, String mode, String deviceId2,
                                     Integer length, Integer width,
                                     Integer midX, Integer midY,
                                     Integer lenX, Integer lenY) {
        Element root = base("Control", CmdType.DEVICE_CONTROL, sn, deviceId);
        ManscdpXml.addText(root, "TargetTrack", mode);
        ManscdpXml.addText(root, "DeviceID2", deviceId2);
        if (midX != null) {
            Element area = ManscdpXml.newElement(root, "TargetArea");
            ManscdpXml.addText(area, "Length", length);
            ManscdpXml.addText(area, "Width", width);
            ManscdpXml.addText(area, "MidPointX", midX);
            ManscdpXml.addText(area, "MidPointY", midY);
            ManscdpXml.addText(area, "LengthX", lenX);
            ManscdpXml.addText(area, "LengthY", lenY);
        }
        return out(root);
    }

    /**
     * 设备软件升级（A.2.3.1.12）。
     *
     * <p>字段包在 {@code <DeviceUpgrade>} 容器里，且必须带 {@code <SessionID>}
     * （32~128 字节）—— 设备后续用它在升级结果通知里关联本次会话。
     */
    public static String deviceUpgrade(int sn, String deviceId, String firmware,
                                       String fileUrl, String manufacturer, String sessionId) {
        Element root = base("Control", CmdType.DEVICE_CONTROL, sn, deviceId);
        Element up = ManscdpXml.newElement(root, "DeviceUpgrade");
        ManscdpXml.addText(up, "Firmware", firmware);
        ManscdpXml.addText(up, "FileURL", fileUrl);
        ManscdpXml.addText(up, "Manufacturer", manufacturer);
        ManscdpXml.addText(up, "SessionID", sessionId);
        return out(root);
    }

    /**
     * 录像控制（A.2.3.1.4）。
     *
     * @param record      {@code Record} / {@code StopRecord}
     * @param streamNumber 码流编号，0=主码流；{@code null} 表示缺省
     */
    public static String recordControl(int sn, String deviceId, String record, Integer streamNumber) {
        Element root = base("Control", CmdType.DEVICE_CONTROL, sn, deviceId);
        ManscdpXml.addText(root, "RecordCmd", record);
        ManscdpXml.addText(root, "StreamNumber", streamNumber);
        return out(root);
    }

    /**
     * 媒体流播放控制。
     *
     * @param operation 操作码，如 {@code Play} / {@code Stop} / {@code Pause} / {@code Resume}
     * @param streamId  流标识，{@code null} 表示不携带
     * @deprecated <b>GB/T 28181—2022 已删除 MediaControl 命令</b>，为兼容 2016 版设备保留。
     */
    @Deprecated
    public static String mediaControl(int sn, String deviceId, String operation, String streamId) {
        Element root = base("Control", CmdType.MEDIA_CONTROL, sn, deviceId);
        Element info = ManscdpXml.addContainer(root, "Info", null);
        ManscdpXml.addText(info, "MediaType", "Video");
        ManscdpXml.addText(info, "CmdType", operation);
        ManscdpXml.addText(info, "StreamID", streamId);
        return out(root);
    }
}