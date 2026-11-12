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

    /**
     * 检测区域（归一化 {@code x,y,w,h}，逗号分隔，取值 0~1）；为空表示全画面。
     *
     * <h3>为什么需要「裁剪后才能检测」</h3>
     * 模型输入固定 416×416。1920×1080 的画面缩到 416 是 <b>0.217 倍</b>，
     * 远景里 33px 高的人只剩 <b>7px</b> —— 实测同一帧检出 <b>0 个</b>。
     * 而把画面裁到 480×270 后再缩到 416（0.867 倍），人变成 28px，
     * <b>同一帧能检出 10 个</b>。所以「裁哪里」是能不能检出的决定性因素。
     *
     * <p>用归一化坐标而不是像素：换码流分辨率（主/子码流切换、设备改配置）
     * 时不用重配。ffmpeg 侧用 {@code iw*}/{@code ih*} 表达式，天然跟随输入尺寸。
     *
     * <p>为什么不改成「自动切片」：实测 2×2 / 3×3 切片都仍是 0 检出
     * （每块缩到 416 后人还是 14~21px），要达到 28px 得 4×4 切片，
     * 即单帧 <b>16 次推理</b> —— 4fps × 2 路就是 128 次/秒，这台共享机器扛不住。
     */
    private String cropRegion;

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
