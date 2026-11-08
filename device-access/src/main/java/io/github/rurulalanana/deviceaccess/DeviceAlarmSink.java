package io.github.rurulalanana.deviceaccess;

/**
 * 厂商报警实现（SPI）：把厂商原始事件翻译成平台报警。
 *
 * <p>各厂商的事件码体系完全不同（大华 {@code 0x2xxx}、海康 ISUP 另一套），
 * 但平台侧的落库逻辑是通用的：判优先级、写类型名、按「结束」事件关闭同类 ACTIVE。
 * 本接口只负责<b>翻译</b>，不碰数据库。
 *
 * <p>与 {@link DeviceAccessProvider} 同样的边界：不写库、不决定业务规则。
 *
 * <h3>两道必须由实现方做的过滤</h3>
 * <ol>
 *   <li><b>非报警事件</b> —— 厂商回调里混着状态上报（云台位置、订阅完成），
 *       不挡会刷出成千上万条假报警。返回 {@code null} 表示丢弃。</li>
 *   <li><b>「全部已结束」快照</b> —— 订阅瞬间设备常把当前全部状态推一遍，
 *       形如「这些报警都已结束」。要翻成 {@code ended=true}，平台据此关闭同类报警，
 *       而不是新增一堆。</li>
 * </ol>
 */
public interface DeviceAlarmSink {

    /** 本实现对应的接入方式。 */
    DeviceKind kind();

    /**
     * 厂商原始事件 → 平台报警。
     *
     * @return 归一化后的报警；非报警事件（状态上报等）返回 {@code null}
     */
    DeviceAlarm normalize(RawAlarm raw);

    /**
     * 厂商原始事件（只带厂商自己能解读的字段）。
     *
     * @param deviceId 设备编码
     * @param code     厂商事件码
     * @param state    事件结构体首字段（含义随事件码不同，由实现方解读）
     * @param sourceIp 事件上报来源 IP（用于交叉核对）
     */
    record RawAlarm(String deviceId, int code, int state, String sourceIp) {
    }

    /**
     * 平台口径的报警（协议无关）。
     *
     * @param deviceId    设备编码
     * @param channelId   通道编码；厂商事件通常不带通道号，可为 {@code null}
     * @param priority    国标口径 1=一级（最紧急）… 4=提示
     * @param eventType   厂商事件码，落 {@code gb_alarm.event_type}
     * @param typeName    展示用类型名
     * @param description 描述
     * @param ended       {@code true} 表示「该报警已结束」——平台关闭同类 ACTIVE 而不新增
     */
    record DeviceAlarm(String deviceId, String channelId,
                       int priority, int eventType,
                       String typeName, String description,
                       boolean ended) {
    }
}
