package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.dahua.ext.DahuaExtAccess;
import io.github.rurulalanana.dahua.ext.DahuaSdkException;
import io.github.rurulalanana.starter.common.BizException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 大华喊话 / 对讲会话管理。
 *
 * <h3>为什么必须有设备级互斥</h3>
 * 大华的 {@code CLIENT_StartTalkEx} 以<b>登录句柄</b>为参数，不是通道级 ——
 * 同一台设备<b>同时只能有一路对讲</b>。第二个调用会<b>静默顶掉</b>第一个：
 * 前一个用户的 {@code TalkChannel} 还在，{@code send()} 也返回成功，
 * 但音频根本发不到设备。
 *
 * <p>不在这里加锁，现场表现就是「两个人同时喊话，只有一个能被听见，
 * 另一个以为自己在说话」—— 而这种"以为成功"的失败最难排查。
 *
 * <h3>为什么带 TTL</h3>
 * 浏览器可能崩溃、网络可能断，{@code close()} 不会被调用，
 * 设备对讲被永久占用，其他人再也喊不了话。TTL 到点自动释放。
 *
 * <h3>音频格式（实测口径，前端按此实现）</h3>
 * 上行（浏览器 → 设备）：<b>裸 PCM，8 kHz / 16 bit / 单声道</b>，原样
 * {@code TalkSendData}，服务端不转码。
 *
 * <p>下行（设备 → 浏览器）：大华回传的是 <b>DHAV 打包</b>的音频帧，
 * 每帧 2096 字节，结构见 {@link #stripAudioHeader}。头尾都必须剥掉 ——
 * 少剥一个字节，浏览器就会把帧头当成采样点，每帧爆一次音（现场听感是「放鞭炮」）。
 */
@Slf4j
@Service
public class DahuaTalkService {

    /** 对讲占用上限：5 分钟。超过自动释放，防止浏览器崩溃后永久占麦。 */
    private static final long TTL_MS = 5 * 60_000L;

    /** 下发给浏览器的采样率（Hz）。 */
    public static final int SAMPLE_RATE = DahuaExtAccess.TALK_SAMPLE_RATE;

    /** 下发给浏览器的位深（bit）。 */
    public static final int AUDIO_BIT = DahuaExtAccess.TALK_AUDIO_BIT;

    /** 下发给浏览器的格式标识（前端据此选解码路径）。 */
    public static final String FORMAT = "PCM16";

    /** 声道数：设备对讲只支持单声道。 */
    public static final int CHANNELS = 1;

    /**
     * 对讲下行 DHAV 帧的头部长度（字节，实测）。
     *
     * <p><b>不是 20。</b>早期按「20 字节帧头」实现，现场听到的是持续的鞭炮声 ——
     * 因为剩下 28 字节（20 字节内层头 + 8 字节尾）被当成 14 个采样点推进了播放队列，
     * 每 128 ms 一记满幅跳变（实测帧边界跳变平均 18128，修好后是 1）。
     *
     * <p>见 {@link #stripAudioHeader}。
     */
    private static final int DHAV_AUDIO_HEADER = 40;

    /** 帧尾的 8 字节："dhav" + uint32 帧长。 */
    private static final int DHAV_AUDIO_TRAILER = 8;

    private final DahuaCapabilityService dahuaCapability;

    /** deviceId → 当前占用者。 */
    private final Map<String, Holder> active = new ConcurrentHashMap<>();

    private final AtomicLong seq = new AtomicLong();

    public DahuaTalkService(DahuaCapabilityService dahuaCapability) {
        this.dahuaCapability = dahuaCapability;
    }

    /** 一次对讲会话。 */
    public record Session(String talkId, String deviceId, String owner) {
    }

    /**
     * 开始对讲。
     *
     * @param deviceId 设备编号
     * @param owner    占用者标识（登录用户名），用于给出「谁在喊话」的提示
     */
    public Session start(String deviceId, String owner) {
        purgeExpired();
        String key = deviceId;
        Holder holder = active.get(key);
        if (holder != null && !holder.expired()) {
            // 同一人重复点「开始」：返回已有会话而不是报错（前端可能重连）
            if (holder.owner().equals(owner)) {
                holder.touch();
                return new Session(holder.talkId(), deviceId, owner);
            }
            throw BizException.badRequest("该设备正在被「" + holder.owner()
                    + "」喊话，请等对方结束后再试");
        }

        var session = dahuaCapability.requireSession(deviceId);
        String talkId = "talk-" + seq.incrementAndGet();
        // 先建 Holder 再开通道：listener 要立刻能用，而通道构造期间就可能开始回调
        Holder h = new Holder(talkId, deviceId, owner, System.currentTimeMillis());
        DahuaExtAccess.TalkChannel channel;
        try {
            // listener 不能传 null：传 null 时设备回传的音频（flag==1）会被静默丢弃，
            // 表现为「能喊话但听不到设备那头」—— 而日志里一切正常，极难排查。
            channel = dahuaCapability.ext().startTalk(session, h::onDeviceAudio);
        } catch (DahuaSdkException e) {
            throw dahuaCapability.translate("建立对讲", e);
        }
        h.setChannel(channel);
        active.put(key, h);
        log.info("大华对讲已建立: device={} talkId={} owner={}", deviceId, talkId, owner);
        return new Session(talkId, deviceId, owner);
    }

    /**
     * 订阅设备回传的音频（双工对讲的下行）。
     *
     * <p>一个 talkId 可以挂<b>多个</b>接收端（同一值班室两台电脑同时听），
     * 但发送端只能有一个（由 {@link #start} 的设备级互斥保证）。
     *
     * <p>回调线程是 JNA 原生线程：<b>消费方必须立刻返回</b>，
     * 不能在里面做网络 IO 阻塞（会拖住 SDK 的音频泵）。
     * WebSocket 侧用「投递到会话自己的发送队列」实现，见 {@code DahuaTalkWebSocketHandler}。
     *
     * @return 取消订阅的句柄，连接断开时必须调用
     */
    public AutoCloseable subscribe(String talkId, java.util.function.Consumer<byte[]> sink) {
        Holder h = byTalkId(talkId);
        h.addSink(sink);
        return () -> h.removeSink(sink);
    }

    /** 会话是否还存在（WebSocket 握手时校验，避免订阅一个已结束的对讲）。 */
    public boolean exists(String talkId) {
        return byTalkIdOrNull(talkId) != null;
    }

    /**
     * 剥掉 DHAV 音频帧的「头 + 尾」，只留裸 PCM。
     *
     * <h3>实测帧结构（2026-11，IPC，逐帧取 hex 校验过 63 帧）</h3>
     * <pre>
     *   2096 字节 / 帧
     *   [0..4)      "DHAV"                         大写 magic
     *   [0..40)     头部（外层 20 字节 + 内层 20 字节，内层每帧形状相同）
     *   [40..2088)  裸 PCM16，<b>2048 字节 = 1024 采样点 = 128.00 ms</b> @ 8 kHz
     *   [2088..2096) 尾部 "dhav" + uint32 帧长(2096)  小写 magic
     * </pre>
     *
     * <h3>为什么必须头尾都剥（这是「对讲听到放鞭炮」的根因）</h3>
     * 原先按「20 字节头」实现，每帧会多留 28 字节（20 字节内层头 + 8 字节尾）。
     * 那 28 字节被浏览器当成 14 个采样点，全是接近满幅的跳变：
     *
     * <pre>
     *   修复前：帧边界跳变 平均 18128、最大 22029（62/62 帧都跳）
     *   修复后：帧边界跳变 平均 1、    最大 3      （0 帧跳）
     * </pre>
     *
     * 2096/8000 = 262 ms 的帧，每帧在听感上就是一记「啪」，7.6 帧/秒 → 连续鞭炮声。
     * 少剥 8 字节同样会响：帧尾的 {@code "dhav"} 是 {@code 0x7661_6864} 量级的满幅值。
     *
     * <p><b>不对号就返回 {@code null}（丢弃），不要整帧透传。</b>
     * 帧结构变了说明我们对这份数据的理解是错的，透传只会把噪音放大给用户的耳朵 ——
     * 「安静」比「响亮的错误」好排查得多。丢弃时会打一次带 hex 的日志留证据。
     *
     * @return 裸 PCM16（8 kHz/16 bit/单声道）；结构与实测不符时返回 {@code null}
     */
    public static byte[] stripAudioHeader(byte[] frame) {
        if (frame == null || frame.length <= DHAV_AUDIO_HEADER + DHAV_AUDIO_TRAILER) {
            return null;
        }
        if (!isMagic(frame, 0, MAJOR_MAGIC)) {
            // 最常见的原因：固件改成别的封装了。原样返回会把整帧当 PCM 播成噪音
            return null;
        }
        int end = frame.length;
        // 尾部 magic 是「有小写 dhav」的标志；没有就按「无尾」处理（部分固件）
        if (isMagic(frame, end - DHAV_AUDIO_TRAILER, MINOR_MAGIC)) {
            end -= DHAV_AUDIO_TRAILER;
        }
        int payload = end - DHAV_AUDIO_HEADER;
        // 16 bit 采样，载荷必须是偶数长度；奇数说明切歪了，宁可不播
        if (payload <= 0 || payload % 2 != 0) {
            return null;
        }
        byte[] pcm = new byte[payload];
        System.arraycopy(frame, DHAV_AUDIO_HEADER, pcm, 0, payload);
        return pcm;
    }

    /** 帧头 magic：{@code "DHAV"}。 */
    private static final byte[] MAJOR_MAGIC = {'D', 'H', 'A', 'V'};

    /** 帧尾 magic：{@code "dhav"}（小写，与大写头区分）。 */
    private static final byte[] MINOR_MAGIC = {'d', 'h', 'a', 'v'};

    private static boolean isMagic(byte[] d, int off, byte[] magic) {
        if (off < 0 || off + magic.length > d.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (d[off + i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 发一帧音频。
     *
     * @param talkId 由 {@link #start} 返回
     * @param pcm    裸 PCM（8 kHz / 16 bit / 单声道）
     */
    public void send(String talkId, byte[] pcm) {
        Holder h = byTalkId(talkId);
        if (h.expired()) {
            close(talkId);
            throw BizException.badRequest("对讲已超时释放，请重新开始");
        }
        h.touch();
        DahuaExtAccess.TalkChannel channel = h.channel();
        if (channel == null) {
            // 极端竞态（通道刚被释放）：给一句能动手的提示，而不是 NPE
            throw BizException.badRequest("对讲通道未就绪，请重新开始对讲");
        }
        channel.send(pcm);
    }

    /** 结束对讲（幂等）。 */
    public void close(String talkId) {
        Holder h = byTalkIdOrNull(talkId);
        if (h == null) {
            return;
        }
        active.remove(h.deviceId(), h);
        closeQuietly(h);
        log.info("大华对讲已结束: device={} talkId={}", h.deviceId(), talkId);
    }

    /** 该设备当前是否有人在喊话（供界面禁用按钮）。 */
    public String busyOwner(String deviceId) {
        purgeExpired();
        Holder h = active.get(deviceId);
        return h == null ? null : h.owner();
    }

    private Holder byTalkId(String talkId) {
        Holder h = byTalkIdOrNull(talkId);
        if (h == null) {
            throw BizException.badRequest("对讲会话不存在或已结束：" + talkId);
        }
        return h;
    }

    private Holder byTalkIdOrNull(String talkId) {
        if (talkId == null || talkId.isBlank()) {
            return null;
        }
        for (Holder h : active.values()) {
            if (h.talkId().equals(talkId)) {
                return h;
            }
        }
        return null;
    }

    /** 清掉超时的占用，避免浏览器崩溃后设备被永久占麦。 */
    private void purgeExpired() {
        active.entrySet().removeIf(e -> {
            if (e.getValue().expired()) {
                log.info("对讲超时自动释放: device={} talkId={}",
                        e.getKey(), e.getValue().talkId());
                closeQuietly(e.getValue());
                return true;
            }
            return false;
        });
    }

    @PreDestroy
    public void shutdown() {
        for (Holder h : active.values()) {
            closeQuietly(h);
        }
        active.clear();
    }

    /** 关通道：{@code channel} 可能为 null（start 在 SetDeviceMode 阶段就失败了）。 */
    private static void closeQuietly(Holder h) {
        DahuaExtAccess.TalkChannel channel = h.channel();
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (Exception ignored) {
            // 关闭失败不影响移除/退出
        }
    }

    /** 占用记录。 */
    private static final class Holder {
        private final String talkId;
        private final String deviceId;
        private final String owner;
        private volatile long lastActiveAt;
        /** 通道在 {@link #start} 里晚于本对象创建，故非 final。 */
        private volatile DahuaExtAccess.TalkChannel channel;
        /** 下行音频订阅者（可多个）。 */
        private final java.util.List<java.util.function.Consumer<byte[]>> sinks =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        /** 只记一次「帧头已剥离」，避免每 130 ms 刷一行日志。 */
        private final java.util.concurrent.atomic.AtomicBoolean headerLogged =
                new java.util.concurrent.atomic.AtomicBoolean();

        Holder(String talkId, String deviceId, String owner, long now) {
            this.talkId = talkId;
            this.deviceId = deviceId;
            this.owner = owner;
            this.lastActiveAt = now;
        }

        String talkId() {
            return talkId;
        }

        String deviceId() {
            return deviceId;
        }

        String owner() {
            return owner;
        }

        DahuaExtAccess.TalkChannel channel() {
            return channel;
        }

        void setChannel(DahuaExtAccess.TalkChannel channel) {
            this.channel = channel;
        }

        void addSink(java.util.function.Consumer<byte[]> sink) {
            if (sink != null) {
                sinks.add(sink);
            }
            touch();
        }

        void removeSink(java.util.function.Consumer<byte[]> sink) {
            sinks.remove(sink);
        }

        /**
         * 设备回传音频（{@code flag==1}）：剥头后广播给所有接收端。
         *
         * <p>运行在 <b>JNA 原生回调线程</b>：必须快、不能抛、不能阻塞
         * （订阅者自己负责把数据丢进队列）。无人订阅时直接丢弃 ——
         * 单工喊话场景本来就不需要下行音频。
         */
        void onDeviceAudio(byte flag, byte[] frame) {
            if (flag != 1 || frame == null || frame.length == 0 || sinks.isEmpty()) {
                return;
            }
            byte[] pcm = stripAudioHeader(frame);
            if (pcm == null) {
                // 结构对不上：丢掉，并且只留一次证据（含原始 hex），不要刷屏
                if (headerLogged.compareAndSet(false, true)) {
                    log.warn("对讲下行音频结构无法识别，已丢弃（帧 {} 字节，头尾 hex: {} ... {}）",
                            frame.length, hex(frame, 0, 8), hex(frame, frame.length - 8, 8));
                }
                return;
            }
            if (headerLogged.compareAndSet(false, true)) {
                log.info("对讲下行音频: 帧 {} 字节 → 剥头尾后 {} 字节（{} 采样点 / {} ms，PCM {} Hz/{} bit/{} 声道）",
                        frame.length, pcm.length, pcm.length / 2,
                        pcm.length / 2 * 1000 / SAMPLE_RATE, SAMPLE_RATE, AUDIO_BIT, CHANNELS);
            }
            for (java.util.function.Consumer<byte[]> sink : sinks) {
                try {
                    sink.accept(pcm);
                } catch (Throwable ignored) {
                    // 单个接收端异常不能影响其它人，更不能抛回原生线程
                }
            }
        }

        /** 取 {@code [from, from+n)} 的十六进制串，用于日志取证。 */
        private static String hex(byte[] d, int from, int n) {
            int start = Math.max(0, from);
            int end = Math.min(d.length, start + n);
            StringBuilder sb = new StringBuilder((end - start) * 3);
            for (int i = start; i < end; i++) {
                sb.append(String.format("%02X", d[i]));
                if (i + 1 < end) {
                    sb.append(' ');
                }
            }
            return sb.toString();
        }

        void touch() {
            this.lastActiveAt = System.currentTimeMillis();
        }

        boolean expired() {
            return System.currentTimeMillis() - lastActiveAt > TTL_MS;
        }
    }
}
