package io.github.rurulalanana.deviceaccess;

import java.util.List;

/**
 * 设备侧录像检索（SPI，可选能力）。
 *
 * <p>国标走 MANSCDP {@code RecordInfo}，厂商走各自的 SDK 查询接口。
 * 平台只关心「有哪些录像段」，不关心怎么查出来的。
 */
public interface DeviceRecordSearch extends DeviceExtension {

    /** 该设备此刻能否检索（通常等于「SDK 会话是否有效」）。 */
    boolean available(String deviceId);

    /**
     * 查某通道在时间区间内的录像段。
     *
     * @param startTime 起始时间，格式 {@code yyyy-MM-dd HH:mm:ss}
     * @param endTime   结束时间，同上
     * @return 录像段列表；没有录像时返回空列表
     * @throws IllegalStateException 设备拒绝（型号不支持 / 未装存储卡 / 会话失效）
     */
    List<FoundRecord> search(String deviceId, String channelId,
                             String startTime, String endTime);

    /**
     * 一段录像。
     *
     * @param fileName 设备侧文件名（多数设备会提供；为空时平台用起止时间当标题）
     * @param start    开始时间，格式 {@code yyyy-MM-dd HH:mm:ss}
     * @param end      结束时间，同上
     * @param bytes    文件大小（字节）；未知时给 0
     */
    record FoundRecord(String fileName, String start, String end, int bytes) {
    }
}
