package io.github.rurulalanana.deviceaccess;

import java.util.List;

/**
 * 设备侧预置位清单（SPI，可选能力）。
 *
 * <p>国标走 {@code PresetQuery} 异步查询，厂商走 SDK 同步读。
 * 平台只关心「有哪些预置位、叫什么、在什么位置」。
 */
public interface DevicePresetList extends DeviceExtension {

    /** 该设备此刻能否读取（通常等于「SDK 会话是否有效」）。 */
    boolean available(String deviceId);

    /**
     * 读预置位清单。
     *
     * @return 预置位列表；设备不支持或读不到时返回空列表（不抛异常）
     */
    List<PresetInfo> list(String deviceId, String channelId);

    /**
     * 一个预置位。
     *
     * @param index       编号（1-based，设备口径）
     * @param name        设备侧名称；为空时平台显示「预置点 N」
     * @param pan         水平坐标；设备不提供时为 {@code null}
     * @param tilt        垂直坐标；同上
     * @param zoom        变倍；同上
     * @param staySeconds 停留时间（秒）；0 表示设备不提供
     */
    record PresetInfo(int index, String name,
                      Integer pan, Integer tilt, Integer zoom,
                      int staySeconds) {

        /** 设备是否给出了坐标（决定前端能否显示「设备坐标」）。 */
        public boolean hasPosition() {
            return pan != null && tilt != null && zoom != null;
        }
    }
}
