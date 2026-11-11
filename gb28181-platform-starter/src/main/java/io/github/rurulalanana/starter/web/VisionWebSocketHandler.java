package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rurulalanana.starter.service.vision.VisionBroadcaster;
import io.github.rurulalanana.vision.Detection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * AI 检测结果的 WebSocket 推送端点。
 *
 * <p>地址：{@code ws(s)://<host>/api/v1/vision/ws?token=xxx}
 *
 * <h3>为什么要换掉 REST 轮询</h3>
 * 轮询的延迟下限是「轮询周期」，与后端帧率无关：
 * <ul>
 *   <li>后端 4fps（250ms 一帧），前端也 250ms 轮一次 —— 平均仍有 125ms 空等</li>
 *   <li>轮询周期必须 ≥ 帧间隔，否则同一帧被反复取回；两者一旦不匹配就白付延迟</li>
 *   <li>16 分屏时是每秒 16 个 HTTP 请求，每个都要过鉴权 + 操作日志拦截器</li>
 * </ul>
 * WebSocket 把这三件事一次解决：帧产生即推送、无需对齐周期、单连接承载所有通道。
 *
 * <h3>为什么鉴权走 query 里的 token</h3>
 * 浏览器 {@code new WebSocket()} <b>不能带自定义请求头</b>，而平台登录态在
 * {@code Authorization} 头里。只能把 token 放 query —— 与
 * {@code DahuaTalkWebSocketHandler} 完全相同的做法。
 *
 * <p>因此 {@code SaTokenConfiguration} 要把该路径加进 {@code excludePathPatterns}
 * （否则握手就被 MVC 拦截器挡掉），而这里<b>不信任</b>「已经放行」，
 * 在 {@link #afterConnectionEstablished} 里用 {@code StpUtil.getLoginIdByToken}
 * 自己再校验一次，无效直接 {@code close(1008)}。
 *
 * <h3>订阅协议</h3>
 * 连接建立后，客户端发一条文本消息声明要看哪些通道：
 * <pre>
 *   {"action":"watch","channels":["10001/10001-1","dev/ch"]}
 *   {"action":"ping"}                      ← 心跳，服务端回 {"type":"pong"}
 * </pre>
 * 服务端推送：
 * <pre>
 *   {"type":"detections","data":{"10001/10001-1":{...}}}
 * </pre>
 * 不用「连接即订阅全部」：一个只预览 1 路的页面不该收 16 路的数据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisionWebSocketHandler extends TextWebSocketHandler {

    /**
     * 空闲超时（毫秒）：超过这么久没收到客户端任何消息就断开。
     *
     * <p>前端每 20 秒发一次 ping，给 90 秒的宽限足以容忍网络抖动，
     * 又能及时回收「用户直接关掉标签页」这类没有 close 帧的死连接。
     */
    private static final long IDLE_TIMEOUT_MS = 90_000;

    private final VisionBroadcaster broadcaster;
    private final ObjectMapper objectMapper;

    /** sessionId → 会话状态。 */
    private final Map<String, Conn> conns = new ConcurrentHashMap<>();

    /** 清理死连接的定时器。 */
    private final ScheduledExecutorService reaper =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "vision-ws-reaper");
                t.setDaemon(true);
                return t;
            });

    /** 一个连接的订阅状态。 */
    private final class Conn implements VisionBroadcaster.Subscriber {
        final WebSocketSession session;
        volatile Set<String> channels = Set.of();
        volatile long lastSeen = System.currentTimeMillis();

        /**
         * 这个连接上次收到的结果签名（通道键 → 签名）。
         *
         * <p>用并发 Map：写入在推送节拍线程，读取也主要在那里，
         * 但 {@link #handleWatch} 在 WebSocket 线程上可能同时触发清理。
         */
        final Map<String, String> sent = new ConcurrentHashMap<>();
        /**
         * 发送锁。
         *
         * <p>Spring 的 WebSocketSession <b>不是线程安全的</b>：推送节拍线程和
         * 心跳应答可能同时写，会抛 "TEXT_PARTIAL_WRITING"。
         * 这里用对象锁串行化，而不是把发送丢进线程池 ——
         * 消息本身很小（几百字节），锁竞争可以忽略。
         */
        /**
         * 该连接的发送锁：{@code WebSocketSession} 不允许并发发送。
         *
         * <p>用 {@link ReentrantLock} 而不是 {@code synchronized}：
         * {@code ping}/{@code pong}、建连时的 {@code hello} 都跑在 Tomcat 请求线程上，
         * 开启虚拟线程后那是虚拟线程；锁内是阻塞的网络写，
         * {@code synchronized} 会把这个虚拟线程<b>钉住</b>在载体线程上，
         * 一个慢客户的发送就能拖住整个平台的处理能力。
         */
        final ReentrantLock sendLock = new ReentrantLock();

        Conn(WebSocketSession session) {
            this.session = session;
        }

        @Override
        public void push(Map<String, VisionBroadcaster.Payload> updates) {
            Map<String, Object> data = new LinkedHashMap<>();
            for (Map.Entry<String, VisionBroadcaster.Payload> e : updates.entrySet()) {
                data.put(e.getKey(), toWire(e.getValue()));
            }
            sendJson(Map.of("type", "detections", "data", data));
        }

        @Override
        public Set<String> channels() {
            return channels;
        }

        /**
         * 这个连接上次收到的各通道结果签名。
         *
         * <p>必须每个连接一份：做成全局的会让「第二个打开的页面」永远收不到
         * 数据（第一个页面已经推过，全局签名被占用）。
         */
        @Override
        public Map<String, String> lastSent() {
            return sent;
        }

        @Override
        public boolean alive() {
            return session.isOpen();
        }

        void sendJson(Map<String, Object> msg) {
            if (!session.isOpen()) {
                return;
            }
            try {
                String text = objectMapper.writeValueAsString(msg);
                sendLock.lock();
                try {
                    session.sendMessage(new TextMessage(text));
                } finally {
                    sendLock.unlock();
                }
            } catch (IOException | IllegalStateException e) {
                // 连接已断：交给 reaper 回收，这里不抛
                log.debug("视觉 WS 发送失败: {}", e.getMessage());
            }
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String token = queryParam(session, "token");
        Object loginId = null;
        if (token != null && !token.isBlank()) {
            try {
                loginId = StpUtil.getLoginIdByToken(token);
            } catch (Exception e) {
                log.debug("视觉 WS 校验 token 异常: {}", e.toString());
            }
        }
        if (loginId == null) {
            closeQuietly(session, CloseStatus.POLICY_VIOLATION, "登录已失效，请重新登录");
            return;
        }

        Conn conn = new Conn(session);
        conns.put(session.getId(), conn);
        broadcaster.subscribe(conn);
        // 先回一条 hello：让前端知道连接可用，不必等第一帧检测结果
        conn.sendJson(Map.of("type", "hello",
                "subscribers", broadcaster.subscriberCount()));
        log.debug("视觉 WS 已连接: session={} 当前订阅者={}",
                session.getId(), broadcaster.subscriberCount());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Conn conn = conns.get(session.getId());
        if (conn == null) {
            return;
        }
        conn.lastSeen = System.currentTimeMillis();
        String payload = message.getPayload();
        if (payload == null || payload.isBlank()) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = objectMapper.readValue(payload, Map.class);
            String action = String.valueOf(msg.get("action"));
            switch (action) {
                case "watch" -> handleWatch(conn, msg.get("channels"));
                case "ping" -> conn.sendJson(Map.of("type", "pong"));
                default -> log.debug("视觉 WS 未知动作: {}", action);
            }
        } catch (Exception e) {
            log.debug("视觉 WS 消息解析失败: {}", e.getMessage());
        }
    }

    /** 更新订阅的通道集合。 */
    private void handleWatch(Conn conn, Object raw) {
        Set<String> next = new LinkedHashSet<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o == null) {
                    continue;
                }
                String ch = String.valueOf(o).trim();
                if (!ch.isEmpty()) {
                    next.add(ch);
                }
            }
        }
        conn.channels = Set.copyOf(next);
        /*
         * 清掉不再关心的通道签名。
         *
         * 不清也能工作，但会随「切换预览的通道」慢慢积累；
         * 而且更重要：重新订阅同一个通道时，如果旧签名还在，
         * 而结果恰好与上次一致，就会不推送 —— 前端以为没数据。
         * 清掉后重新订阅一定能拿到当前帧。
         */
        conn.sent.keySet().retainAll(next);
        conn.sendJson(Map.of("type", "watching",
                "channels", new ArrayList<>(next)));
        /*
         * 立刻推一次当前快照：否则前端要等下一帧结果<b>变化</b>才看到框。
         * 画面里没人时结果可能长时间不变，用户会以为检测没生效。
         */
        broadcaster.pushSnapshot(conn);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Conn conn = conns.remove(session.getId());
        if (conn != null) {
            broadcaster.unsubscribe(conn);
        }
        log.debug("视觉 WS 已断开: session={} status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("视觉 WS 传输错误: session={} {}", session.getId(), exception.getMessage());
        Conn conn = conns.remove(session.getId());
        if (conn != null) {
            broadcaster.unsubscribe(conn);
        }
    }

    /**
     * 回收空闲/失效连接。
     *
     * <p>浏览器直接关闭标签页时不一定发 close 帧，连接会挂在服务端
     * 直到 TCP 超时。不主动清理的话，每个关过的页面都留一个订阅者，
     * 推送节拍要遍历它们 —— 时间一长就明显变慢。
     */
    public void reapIdle() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Conn> e : conns.entrySet()) {
            Conn c = e.getValue();
            if (!c.session.isOpen()) {
                conns.remove(e.getKey());
                broadcaster.unsubscribe(c);
                continue;
            }
            if (now - c.lastSeen > IDLE_TIMEOUT_MS) {
                closeQuietly(c.session, CloseStatus.SESSION_NOT_RELIABLE, "空闲超时");
                conns.remove(e.getKey());
                broadcaster.unsubscribe(c);
            }
        }
    }

    /** 启动清理定时器（构造后由 Spring 调用一次）。 */
    @jakarta.annotation.PostConstruct
    public void startReaper() {
        reaper.scheduleWithFixedDelay(this::reapIdle, 30, 30, TimeUnit.SECONDS);
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        reaper.shutdownNow();
        for (Conn c : conns.values()) {
            broadcaster.unsubscribe(c);
            closeQuietly(c.session, CloseStatus.SERVICE_RESTARTED, "服务重启");
        }
        conns.clear();
    }

    // ==================== 内部 ====================

    /**
     * 转成前端用的结构。
     *
     * <p>字段名与 REST 的 {@code /vision/detections} <b>完全一致</b>，
     * 这样前端两套传输方式可以共用同一个渲染逻辑 —— 换传输不该换数据结构。
     */
    private static Map<String, Object> toWire(VisionBroadcaster.Payload p) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("channel", p.channel());
        row.put("at", p.at());
        row.put("width", p.width());
        row.put("height", p.height());
        row.put("counts", p.counts());
        List<Map<String, Object>> dets = new ArrayList<>(p.detections().size());
        for (Detection d : p.detections()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", d.label());
            item.put("confidence", d.confidence());
            Map<String, Object> box = new LinkedHashMap<>();
            box.put("x", d.box().x());
            box.put("y", d.box().y());
            box.put("w", d.box().w());
            box.put("h", d.box().h());
            item.put("box", box);
            dets.add(item);
        }
        row.put("detections", dets);
        return row;
    }

    private static String queryParam(WebSocketSession session, String name) {
        if (session.getUri() == null) {
            return null;
        }
        return UriComponentsBuilder.fromUri(session.getUri()).build()
                .getQueryParams().getFirst(name);
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status, String reason) {
        try {
            if (session.isOpen()) {
                session.close(new CloseStatus(status.getCode(), reason));
            }
        } catch (IOException ignored) {
            // 已经关了
        }
    }
}
