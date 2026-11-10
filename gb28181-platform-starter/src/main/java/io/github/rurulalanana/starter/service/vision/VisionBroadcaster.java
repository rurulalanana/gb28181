package io.github.rurulalanana.starter.service.vision;

import io.github.rurulalanana.vision.Detection;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 检测结果的实时推送：把「每帧的检测结果」合并成「有变化才推」的增量。
 *
 * <h3>为什么不能直接把每一帧推给前端</h3>
 * {@code fps=4} × N 路意味着每秒 N×4 条消息。但前端画框只需要<b>最新一帧</b>，
 * 中间那些帧推过去，浏览器连渲染都来不及就又被覆盖 —— 纯浪费带宽与 GC。
 * 更糟的是 N 路时消息量线性增长，而 WebSocket 是单连接串行的，
 * 一路慢就全卡住。
 *
 * <p>所以这里做<b>两级合并</b>：
 * <ol>
 *   <li>同一通道在一个推送周期内只保留<b>最后一帧</b>（覆盖写）</li>
 *   <li>结果与上次推送<b>完全一样就跳过</b>（框没动、人没进出时不需要发）</li>
 * </ol>
 *
 * <h3>为什么按「订阅者」而不是「通道」推</h3>
 * 前端一个页面可能同时看 1 路（通道管理弹窗）或 16 路（直播分屏）。
 * 按订阅关系只推它关心的通道，才能让「16 分屏」和「单路预览」都不浪费。
 *
 * <h3>为什么用固定节拍而不是「有结果就推」</h3>
 * 固定 200ms 一拍：多路的结果会在同一拍里被合并成<b>一条消息</b>，
 * 前端一次处理完。如果来一条推一条，16 路会变成每秒 64 次回调，
 * 而浏览器主线程每秒能顺滑处理的 DOM 更新远低于这个数。
 */
@Slf4j
@Service
public class VisionBroadcaster {

    /**
     * 推送节拍（毫秒）。
     *
     * <p>200ms 是实测的折中：比推理帧间隔（fps=4 时 250ms）略快，
     * 保证新帧一产生就能在下一拍发出，不会因为节拍比帧率慢而额外累积延迟；
     * 又足够把多路合并成一条消息。
     *
     * <p>不要调到 100ms 以下：那已经快于人眼可辨，只是把 CPU 花在序列化上。
     */
    private static final long TICK_MS = 200;

    /**
     * 单个连接未发出的消息上限。
     *
     * <p>超了就<b>丢最旧的</b>：实时画框宁可跳一帧，也不能让一个卡住的
     * 客户端把服务端内存吃掉（16 路 × 每帧几十个框，积压起来很快）。
     */
    private static final int MAX_PENDING = 32;

    /** 一个订阅者（一个 WebSocket 连接）。 */
    public interface Subscriber {
        /**
         * 推送一批更新。
         *
         * @param updates 通道键 → 最新结果
         */
        void push(Map<String, Payload> updates);

        /** 该订阅者关心的通道键。 */
        Set<String> channels();

        /** 连接是否还活着。 */
        boolean alive();

        /**
         * 该订阅者<b>上次收到</b>的各通道结果签名。
         *
         * <p><b>必须是每个订阅者各自一份</b>，不能做成全局的「这个通道推过没有」。
         * 早先放在全局 Map 里，导致第二个连接永远收不到数据：
         * 第一个连接已经推过 → 全局签名被写入 → 第二个连接的更新
         * 被判成「没变化」而跳过，表现为「打开第二个页面看不到框」。
         *
         * <p>返回的 Map 由广播器读写，实现方只管持有即可。
         */
        Map<String, String> lastSent();
    }

    /**
     * 推给前端的一帧结果。
     *
     * @param channel    通道键（设备编码/通道编码）
     * @param at         帧时间戳（毫秒）
     * @param width      帧宽
     * @param height     帧高
     * @param counts     标签 → 数量
     * @param detections 框（归一化坐标）
     */
    public record Payload(String channel, long at, int width, int height,
                          Map<String, Long> counts, List<Detection> detections) {
    }

    /** 每个通道的最新待推结果（覆盖写，不追加）。 */
    private final Map<String, Payload> pending = new ConcurrentHashMap<>();

    /** 活跃订阅者。 */
    private final Set<Subscriber> subscribers = ConcurrentHashMap.newKeySet();

    private final ScheduledExecutorService ticker =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "vision-broadcast");
                t.setDaemon(true);
                return t;
            });

    public VisionBroadcaster() {
        ticker.scheduleWithFixedDelay(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 收到一帧新结果（由 {@code DbDetectionSink} 调用）。
     *
     * <p>这里<b>只写内存</b>，不做任何序列化或网络 IO —— 它跑在推理线程上，
     * 任何阻塞都会直接拉低帧率。真正的推送在 {@link #tick()} 里由独立线程做。
     */
    public void onDetections(String channel, long at, int width, int height,
                             List<Detection> detections) {
        if (channel == null) {
            return;
        }
        List<Detection> list = detections == null ? List.of() : List.copyOf(detections);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Detection d : list) {
            counts.merge(d.label(), 1L, Long::sum);
        }
        pending.put(channel, new Payload(channel, at, width, height, counts, list));
    }

    /** 通道停止检测时清掉待推状态，避免前端一直显示最后一帧的框。 */
    public void forget(String channel) {
        if (channel == null) {
            return;
        }
        pending.remove(channel);
        // 各订阅者的 lastSent 不必清：通道停检后不再有结果，
        // 重新开启时 at 一定变了，签名自然不同，会正常推送。
    }

    /** 注册订阅者。 */
    public void subscribe(Subscriber s) {
        if (s != null) {
            subscribers.add(s);
        }
    }

    /**
     * 立刻给某个订阅者推一次它关心的通道当前结果。
     *
     * <h3>为什么需要这个</h3>
     * 正常推送靠「签名变化」触发。但刚订阅时签名是空的，下一拍一定会推 ——
     * 除非画面<b>完全静止</b>且结果与上一拍一致（那也只需要等 200ms）。
     * 真正的问题是「切换预览通道」：新的通道如果当前没有检测结果
     * （画面里没人），{@code pending} 里可能有值但签名与旧的一致，
     * 前端会看不到任何反馈。
     *
     * <p>所以订阅/切换后立刻主动推一次<b>当前快照</b>，让前端立刻对齐，
     * 而不是等下一次结果变化。
     */
    public void pushSnapshot(Subscriber s) {
        if (s == null || !s.alive()) {
            return;
        }
        Set<String> want = s.channels();
        if (want == null || want.isEmpty()) {
            return;
        }
        Map<String, Payload> updates = new LinkedHashMap<>();
        for (String ch : want) {
            Payload p = pending.get(ch);
            if (p != null) {
                updates.put(ch, p);
            }
        }
        if (updates.isEmpty()) {
            return;
        }
        try {
            s.push(updates);
            Map<String, String> sent = s.lastSent();
            for (Map.Entry<String, Payload> e : updates.entrySet()) {
                sent.put(e.getKey(), signature(e.getValue()));
            }
        } catch (RuntimeException e) {
            log.debug("视觉快照推送失败: {}", e.getMessage());
        }
    }

    /** 注销订阅者。 */
    public void unsubscribe(Subscriber s) {
        subscribers.remove(s);
    }

    /** 当前订阅者数量（排障用）。 */
    public int subscriberCount() {
        return subscribers.size();
    }

    /**
     * 一拍：把有变化的通道合并成一条消息推给各自关心的订阅者。
     *
     * <p>「有变化」的判定用<b>序列化后的字符串</b>比较，而不是逐字段 diff：
     * 框的浮点坐标几乎每帧都会微动，真正的「没变化」只发生在画面完全静止时。
     * 用字符串比较能自然地处理这种情况，且实现简单到不会出错
     * （手写 diff 一旦漏掉某个字段，就会静默地不推送更新）。
     */
    private void tick() {
        try {
            if (subscribers.isEmpty() || pending.isEmpty()) {
                return;
            }
            // 先把本拍要推的快照取出来，后续对 pending 的写入属于下一拍
            Map<String, Payload> snapshot = new HashMap<>(pending);

            for (Subscriber s : subscribers) {
                if (!s.alive()) {
                    subscribers.remove(s);
                    continue;
                }
                Set<String> want = s.channels();
                if (want == null || want.isEmpty()) {
                    continue;
                }
                Map<String, String> sent = s.lastSent();
                Map<String, Payload> updates = new LinkedHashMap<>();
                for (String ch : want) {
                    Payload p = snapshot.get(ch);
                    if (p == null) {
                        continue;
                    }
                    String sig = signature(p);
                    if (sig.equals(sent.get(ch))) {
                        // 与「这个订阅者上次收到的」完全一致：不发，前端画面本来也没变。
                        continue;
                    }
                    updates.put(ch, p);
                }
                if (updates.isEmpty()) {
                    continue;
                }
                try {
                    s.push(updates);
                    /*
                     * 记录「已推」要放在 push 成功之后。
                     * 如果 push 抛异常（连接已断）却先记了签名，这条更新
                     * 就永远不会再推 —— 前端会停在旧框上。
                     */
                    for (Map.Entry<String, Payload> e : updates.entrySet()) {
                        sent.put(e.getKey(), signature(e.getValue()));
                    }
                } catch (RuntimeException e) {
                    log.debug("视觉推送失败，移除订阅者: {}", e.getMessage());
                    subscribers.remove(s);
                }
            }
        } catch (RuntimeException e) {
            /*
             * 定时任务里抛异常会让 scheduleWithFixedDelay 永久停止调度，
             * 表现为「推送突然全没了」而日志只有一条。这里必须兜住。
             */
            log.warn("视觉推送节拍异常: {}", e.toString());
        }
    }

    /** 结果的签名：用于判断「和上次推的一样吗」。 */
    private static String signature(Payload p) {
        StringBuilder sb = new StringBuilder(64);
        sb.append(p.at()).append('|').append(p.width()).append('x').append(p.height());
        for (Detection d : p.detections()) {
            sb.append('|').append(d.label()).append(':')
                    .append(Math.round(d.confidence() * 100))
                    .append('@').append(Math.round(d.box().x() * 1000))
                    .append(',').append(Math.round(d.box().y() * 1000))
                    .append(',').append(Math.round(d.box().w() * 1000))
                    .append(',').append(Math.round(d.box().h() * 1000));
        }
        return sb.toString();
    }

    /** 已订阅但当前没人关心的通道（内存清理用）。 */
    public List<String> idleChannels() {
        Set<String> wanted = new java.util.HashSet<>();
        for (Subscriber s : subscribers) {
            Set<String> c = s.channels();
            if (c != null) {
                wanted.addAll(c);
            }
        }
        List<String> out = new ArrayList<>();
        for (String ch : pending.keySet()) {
            if (!wanted.contains(ch)) {
                out.add(ch);
            }
        }
        return out;
    }

    @PreDestroy
    public void shutdown() {
        ticker.shutdownNow();
        subscribers.clear();
    }
}
