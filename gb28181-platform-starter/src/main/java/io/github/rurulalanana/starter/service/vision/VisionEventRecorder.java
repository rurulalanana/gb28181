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

    /**
     * 「通道编码 + 标签」→ 仍在 ACTIVE 的告警。
     *
     * <p>结束事件只带 {@code sourceId + label}，不带告警 ID
     * （{@code EventAggregator} 不知道业务层的告警 ID，也不该知道）。
     * 所以在这里记一份映射，结束时才能找到要回写的那条告警。
     *
     * <p>同时缓存<b>开始时的描述</b>：结束时只有「持续了多久」这一个新信息，
     * 而描述里的标签/数量/置信度在结束事件里拿不到（那一帧没有检测框）。
     * 不缓存的话只能覆盖成残缺描述，或者干脆不改 —— 前者丢信息，后者用户
     * 永远看不到真实时长。
     *
     * <p>不持久化：进程重启后丢失，那些告警就停在 ACTIVE 且没有结束时间。
     * 可以接受 —— 重启不影响「报警发生过」这个事实，为它加一张表不值得。
     */
    private final java.util.Map<String, ActiveAlarm> activeAlarms =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 一条仍在等待结束信号的告警。 */
    private record ActiveAlarm(String alarmId, String baseDescription) {
    }

    @Override
    public void handle(VisionEvent event) {
        if (event.ended()) {
            finishAlarm(event);
            return;
        }
        /*
         * 先定告警 ID，再用它当截图的文件名。
         *
         * 【为什么必须复用同一个 ID】库里 {@code snap_path} 与文件名必须能对上，
         * 否则清理时只能靠时间戳猜。而告警 ID 本来就是这张图的唯一身份 ——
         * 一次事件一张图，天然一一对应。
         *
         * 【创建顺序】ID → 存图 → 落库。反过来（先落库再存图）会出现
         * 「记录里有路径、文件没写成功」的悬空引用；而这个顺序下即使
         * 存图失败也只是 {@code snap_path} 为 null，记录本身仍完整。
         */
        String alarmId = newAlarmId();
        String snapPath = props.isSaveSnapshot() ? saveSnapshot(event, alarmId) : null;
        if (props.isRaiseAlarm()) {
            String desc = raiseAlarm(event, alarmId, snapPath);
            if (desc != null) {
                // 只有真的落库了才登记，否则结束事件会去更新一条不存在的记录
                activeAlarms.put(activeKey(event), new ActiveAlarm(alarmId, desc));
            }
        }
    }

    /**
     * 回写「事件结束」：补上结束时间与真实持续时长，状态置为已复位。
     *
     * <p>为什么不在一开始就写时长：那一刻只知道「它刚跨过去抖阈值」，
     * 写出来的值恒等于阈值（实测永远是 2.0 秒）。
     * <b>真实时长必须等它消失</b>。
     */
    private void finishAlarm(VisionEvent event) {
        ActiveAlarm active = activeAlarms.remove(activeKey(event));
        if (active == null) {
            // 没报过警的短暂出现（或进程重启过），没有可回写的记录
            return;
        }
        try {
            long seconds = Math.max(1, Math.round(event.sustainedMs() / 1000.0));
            /*
             * 用条件更新而不是 updateById：{@code @TableId} 是自增的 {@code id}，
             * 而这里只有业务 ID（alarmId）。传 updateById 会因为 id 为 null 而失败。
             */
            int rows = alarmMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers
                    .<GbAlarm>lambdaUpdate()
                    .eq(GbAlarm::getAlarmId, active.alarmId())
                    .set(GbAlarm::getEndTime, LocalDateTime.ofInstant(
                            java.time.Instant.ofEpochMilli(event.firedAt()),
                            java.time.ZoneId.systemDefault()))
                    .set(GbAlarm::getState, "RESET")
                    // 补上真实时长；标签/数量/置信度沿用开始时的描述
                    .set(GbAlarm::getAlarmDescription,
                            active.baseDescription() + "，持续 " + seconds + " 秒"));
            if (rows > 0) {
                log.info("视觉告警已结束: alarm={} label={} 真实持续={}秒",
                        active.alarmId(), event.label(), seconds);
            }
        } catch (RuntimeException e) {
            log.warn("视觉告警结束回写失败: alarm={} {}", active.alarmId(), e.getMessage());
        }
    }

    /** 活跃告警的键：通道编码 + 标签（同一通道同一标签同时只可能有一条 ACTIVE）。 */
    private static String activeKey(VisionEvent event) {
        return event.sourceId() + '\u0000' + event.label();
    }

    /** 告警 ID：16 位十六进制，同时用作截图文件名。 */
    private static String newAlarmId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * 存事件截图。失败只记日志，不影响告警。
     *
     * <p><b>必须走 {@code saveEventBytes} 而不是 {@code saveBytes}</b>：
     * 后者按「设备_通道」命名，同一通道每告警一次就覆盖同一张图 ——
     * 实测线上 1195 条告警只对应 4 个路径，其中 1138 条挤在同一个文件上，
     * 而且那个文件还会被 1 小时过期规则删掉，历史告警点开全是 404。
     */
    private String saveSnapshot(VisionEvent event, String alarmId) {
        if (event.frame() == null || event.frame().jpeg() == null) {
            return null;
        }
        try {
            String[] ids = splitKey(event.sourceId());
            /*
             * 先把检测框画到图上再存。
             *
             * 只存原始帧的话，运维看到的是浓烟一片 —— 既不知道 AI 认为哪里是火，
             * 也没法判断这次是真报警还是误检。误报的形态（比如把夕阳当火）
             * 画上框后一眼就能认出来，这对调阈值、判断要不要保留该场景是决定性的。
             *
             * 画框失败会自动回退成原图（见 DetectionAnnotator），
             * 所以这里不用额外 try/catch。
             */
            byte[] image = DetectionAnnotator.annotate(event.frame().jpeg(), event.detections());
            return snapService.saveEventBytes(ids[0], ids[1], alarmId, image);
        } catch (RuntimeException e) {
            log.warn("视觉事件截图保存失败: source={} alarm={} {}",
                    event.sourceId(), alarmId, e.getMessage());
            return null;
        }
    }

    /**
     * 写一条报警记录。
     *
     * @return 落库成功时返回本次写入的描述（供结束时追加真实时长）；失败返回 {@code null}
     */
    private String raiseAlarm(VisionEvent event, String alarmId, String snapPath) {
        try {
            String[] ids = splitKey(event.sourceId());
            GbAlarm row = new GbAlarm();
            row.setAlarmId(alarmId);
            row.setDeviceId(ids[0]);
            row.setChannelId(ids[1]);
            row.setMethod(METHOD_OTHER);
            row.setMethodName("其他");
            row.setPriority(PRIORITY_NOTICE);
            row.setPriorityName("三级");
            row.setAlarmTypeName(event.label());
            /*
             * 描述里<b>不写持续时长</b>。
             *
             * 早先这里写的是 `持续 %d 秒`，取 event.sustainedMs() / 1000，
             * 于是界面上永远是「持续 2 秒」—— 因为触发条件就是「连续出现 ≥ 2 秒」，
             * 它必然在刚跨过阈值的那一帧报出，那个值恒等于去抖配置。
             * 看着像数据，实际是配置值的回声。
             *
             * 真实时长要等目标消失，由 finishAlarm() 追加到这条描述末尾。
             */
            String desc = String.format("AI 识别：%s × %d（最高置信度 %.0f%%）",
                    event.label(), event.count(), event.maxConfidence() * 100);
            row.setAlarmDescription(desc);
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
            return desc;
        } catch (RuntimeException e) {
            log.warn("视觉告警落库失败: source={} {}", event.sourceId(), e.getMessage());
            return null;
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
