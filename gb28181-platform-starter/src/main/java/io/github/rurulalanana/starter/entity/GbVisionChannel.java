package io.github.rurulalanana.starter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 启用 AI 检测的通道（对应表 {@code gb_vision_channel}）。
 *
 * <h3>为什么落库，而不是只写在 {@code application.yml} 的 {@code vision.channels}</h3>
 * 配置文件的通道列表是<b>只读</b>的：运维在界面上勾选了几个通道，重启后
 * 会被 yml 覆盖回去，等于界面上的操作白做。所以：
 * <ul>
 *   <li>yml 的 {@code vision.channels} 降级为<b>初始种子</b> —— 只在表为空时导入一次</li>
 *   <li>之后一律以库为准，界面怎么改就怎么存</li>
 * </ul>
 *
 * <h3>为什么没有 {@code enabled} 列</h3>
 * 「有行即启用，停用就删行」。多一个布尔列就多一个可能忘记同步的状态，
 * 而它没有任何收益：本表只存「要跑的通道」，不需要保留历史。
 */
@Data
@TableName("gb_vision_channel")
public class GbVisionChannel implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 设备编码（SDK 直连设备也有；国标设备是 20 位编号）。 */
    private String deviceId;

    /**
     * 通道编码，同时是 ZLM 的流名。
     *
     * <p>也是检测的 {@code channel} 键 —— 与 {@code /vision/detections?channel=} 一致，
     * 前端不用再做转换。
     */
    private String channelId;

    /**
     * 这一路要跑哪个「场景」（{@code vision.scenarios[].id}）。
     *
     * <h3>为什么要按通道绑定场景</h3>
     * 不同通道的诉求不一样：大门口要看人形，停车场要看车辆，
     * 仓库要看烟火。如果只能全局选一个模型，加模型就失去意义了。
     *
     * <p>为空时退回 {@code vision.default-model}，兼容老数据。
     */
    private String scenarioId;

    /** 备注：为什么开这一路（人工填，便于运维回溯）。 */
    private String remark;

    private LocalDateTime createTime;

    /**
     * 拼出检测键 {@code 设备编码/通道编码}。
     *
     * <p>设备编码缺失时退化成只有通道编码 —— 与 {@code VisionService.splitKey}
     * 的处理一致（那种情况下设备编码与通道编码相同）。
     */
    public String channelKey() {
        if (deviceId == null || deviceId.isBlank()) {
            return channelId;
        }
        return deviceId + "/" + channelId;
    }
}
