package io.github.rurulalanana.starter.service.vision;

import io.github.rurulalanana.starter.config.VisionProperties;
import io.github.rurulalanana.starter.entity.GbAlarm;
import io.github.rurulalanana.starter.mapper.GbAlarmMapper;
import io.github.rurulalanana.starter.service.SnapService;
import io.github.rurulalanana.vision.VisionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 把视觉事件落成告警：存截图 + 写 {@code gb_alarm}。
 *
 * <h3>为什么复用 {@code gb_alarm} 而不新建 {@code gb_ai_event} 表</h3>
 * 报警中心已经有一套完整的「列表 / 分页 / 复位 / 保留期清理」，
 * 前端也已经在用。AI 识别出的「有人闯入」本质上就是一条报警，
 * 单独开一张表会让用户必须在两个页面里找「到底有没有报警」，
 * 而两套清理策略迟早会不一致。
 *
 * <p>区分方式：{@code alarm_method=7}（其他）+ {@code alarm_type_name} 写标签名，
 * {@code alarm_description} 写「AI 识别：person × 2（置信度 0.78）」。
 *
 * <h3>为什么不用 {@code @Transactional}</h3>
 * 这个类跑在推理线程里，不是请求线程。事务边界在这里没有意义
 * （单条 insert），反而会因为长事务持有连接。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisionEventRecorder implements DbDetectionSink.EventHandler {

    /** 报警方式：7 = 其他（国标 GA/T 1400 里 1~6 是电话/设备/短信/GPS/视频/故障）。 */
    private static final int METHOD_OTHER = 7;

    /** 报警级别：3 = 三级（AI 识别属于提示性，不应压过设备的真实报警）。 */
    private static final int PRIORITY_NOTICE = 3;

    private final VisionProperties props;
    private final SnapService snapService;
    private final GbAlarmMapper alarmMapper;

    @Override
    public void handle(VisionEvent event) {
        String snapPath = props.isSaveSnapshot() ? saveSnapshot(event) : null;
        if (props.isRaiseAlarm()) {
            raiseAlarm(event, snapPath);
        }
    }

    /** 存事件截图。失败只记日志，不影响告警。 */
    private String saveSnapshot(VisionEvent event) {
        if (event.frame() == null || event.frame().jpeg() == null) {
            return null;
        }
        try {
            String[] ids = splitKey(event.sourceId());
            return snapService.saveBytes(ids[0], ids[1], event.frame().jpeg());
        } catch (RuntimeException e) {
            log.warn("视觉事件截图保存失败: source={} {}", event.sourceId(), e.getMessage());
            return null;
        }
    }

    /** 写一条报警记录。 */
    private void raiseAlarm(VisionEvent event, String snapPath) {
        try {
            String[] ids = splitKey(event.sourceId());
            GbAlarm row = new GbAlarm();
            row.setAlarmId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            row.setDeviceId(ids[0]);
            row.setChannelId(ids[1]);
            row.setMethod(METHOD_OTHER);
            row.setMethodName("其他");
            row.setPriority(PRIORITY_NOTICE);
            row.setPriorityName("三级");
            row.setAlarmTypeName(event.label());
            row.setAlarmDescription(String.format("AI 识别：%s × %d（置信度 %.2f，持续 %d 秒）",
                    event.label(), event.count(), event.maxConfidence(),
                    event.sustainedMs() / 1000));
            // 用「首次出现」的时间而不是触发时刻：告警应记录事情开始的时间
            row.setStartTime(LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(event.triggeredAt()),
                    java.time.ZoneId.systemDefault()));
            row.setState("ACTIVE");
            row.setSnapPath(snapPath);
            row.setCreateTime(LocalDateTime.now());
            alarmMapper.insert(row);
            log.info("视觉告警已记录: device={} channel={} label={} snap={}",
                    ids[0], ids[1], event.label(), snapPath);
        } catch (RuntimeException e) {
            log.warn("视觉告警落库失败: source={} {}", event.sourceId(), e.getMessage());
        }
    }

    /** 拆 {@code 设备编码/通道编码}；只有一段时两段相同。 */
    private static String[] splitKey(String key) {
        if (key == null) {
            return new String[]{"", ""};
        }
        int slash = key.indexOf('/');
        if (slash < 0) {
            return new String[]{key, key};
        }
        return new String[]{key.substring(0, slash), key.substring(slash + 1)};
    }
}
