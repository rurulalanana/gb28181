package io.github.rurulalanana.dahua.ext;

import com.netsdk.lib.NetSDKLib;
import com.sun.jna.Memory;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.deviceaccess.ConnectMode;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 喊话 / 对讲能力的真机探针（开发期手工运行，不进产品）。
 *
 * <p><b>为什么必须先跑探针再写 UI</b>：大华对讲有三处「调了不报错但没效果」的坑 ——
 * ① 不设 {@code NET_TALK_ENCODE_TYPE} 时设备按 G711 解我们的 PCM，现场全是杂音；
 * ② {@code CLIENT_RecordStart} 是采集<b>运行程序这台 PC</b> 的声卡，服务端必失败；
 * ③ 设备回传音频（{@code flag==1}）只有在 listener 非 null 时才拿得到，
 * 传 null 就静默丢弃 —— 表现为「能喊话但听不到设备那头的声音」。
 *
 * <p>本探针把三步 {@code SetDeviceMode} 的返回值、{@code StartTalkEx} 句柄、
 * 每帧发送结果、设备回传帧数/字节数全部打出来，用来判断「这台设备到底能不能双工」。
 *
 * <h3>运行</h3>
 * <pre>
 * mvn -o -q test-compile
 * mvn -o -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
 * java -cp "target/classes;target/test-classes;$(cat target/cp.txt)" \
 *      io.github.rurulalanana.dahua.ext.DahuaTalkProbe
 * </pre>
 *
 * <p>可覆盖的系统属性：{@code -Ddahua.host=} {@code -Ddahua.port=}
 * {@code -Ddahua.user=} {@code -Ddahua.pass=} {@code -Dtalk.seconds=}
 */
public final class DahuaTalkProbe {

    private static final String HOST = System.getProperty("dahua.host", "192.168.10.156");
    private static final int PORT = Integer.getInteger("dahua.port", 37777);
    private static final String USER = System.getProperty("dahua.user", "admin");
    private static final String PASS = System.getProperty("dahua.pass", "gxhy@123");
    /** 监听设备回传音频的秒数。 */
    private static final int LISTEN_SECONDS = Integer.getInteger("talk.seconds", 5);

    /** 一帧 25 ms 的 8 kHz/16 bit 单声道 PCM 字节数。 */
    private static final int FRAME_BYTES =
            DahuaExtAccess.TALK_SAMPLE_RATE * DahuaExtAccess.TALK_AUDIO_BIT / 8
                    * DahuaExtAccess.TALK_PACKET_PERIOD_MS / 1000;

    public static void main(String[] args) {
        DahuaSdk sdk = DahuaSdk.get();
        if (!sdk.init()) {
            log("SDK 初始化失败: " + sdk.loadError());
            return;
        }
        DahuaSdk.Session session = sdk.session(HOST, PORT, USER, PASS, ConnectMode.CLIENT, null);
        if (session == null) {
            log("登录失败 " + HOST + ":" + PORT);
            return;
        }
        log("登录成功: " + HOST + ":" + PORT + " 通道数=" + session.channelCount());

        NetSDKLib api = sdk.sdk();
        NetSDKLib.LLong login = session.handle();

        // ==================== 阶段一：裸调 SDK，逐项打印（对齐官方 demo 顺序）====================
        log("──────── 阶段一：编码协商三步（官方 demo 顺序）────────");

        NetSDKLib.NETDEV_TALKDECODE_INFO encode = new NetSDKLib.NETDEV_TALKDECODE_INFO();
        encode.encodeType = NetSDKLib.NET_TALK_CODING_TYPE.NET_TALK_PCM;
        encode.dwSampleRate = DahuaExtAccess.TALK_SAMPLE_RATE;
        encode.nAudioBit = DahuaExtAccess.TALK_AUDIO_BIT;
        encode.nPacketPeriod = DahuaExtAccess.TALK_PACKET_PERIOD_MS;
        encode.write();
        report("① NET_TALK_ENCODE_TYPE  PCM/8000/16/25ms",
                api.CLIENT_SetDeviceMode(login, NetSDKLib.EM_USEDEV_MODE.NET_TALK_ENCODE_TYPE,
                        encode.getPointer()), api);

        NetSDKLib.NET_SPEAK_PARAM speak = new NetSDKLib.NET_SPEAK_PARAM();
        speak.nMode = 0;
        speak.bEnableWait = 0;
        speak.nSpeakerChannel = 0;
        speak.dwSize = speak.size();
        speak.write();
        report("② NET_TALK_SPEAK_PARAM   nMode=0 bEnableWait=0 speaker=0",
                api.CLIENT_SetDeviceMode(login, NetSDKLib.EM_USEDEV_MODE.NET_TALK_SPEAK_PARAM,
                        speak.getPointer()), api);

        NetSDKLib.NET_TALK_TRANSFER_PARAM transfer = new NetSDKLib.NET_TALK_TRANSFER_PARAM();
        transfer.bTransfer = 0;
        transfer.dwSize = transfer.size();
        transfer.write();
        report("③ NET_TALK_TRANSFER_MODE bTransfer=0（非转发）",
                api.CLIENT_SetDeviceMode(login, NetSDKLib.EM_USEDEV_MODE.NET_TALK_TRANSFER_MODE,
                        transfer.getPointer()), api);

        // ==================== 阶段二：StartTalkEx + 送静音 + 统计回传 ====================
        log("──────── 阶段二：StartTalkEx / TalkSendData / 设备回传统计 ────────");
        AtomicInteger downFrames = new AtomicInteger();
        AtomicLong downBytes = new AtomicLong();
        AtomicInteger upFrames = new AtomicInteger();
        // 抓前几帧的原始字节：用来判断设备回传的封装（实测是 "DHAV" 打包的 PCM）
        java.util.List<byte[]> samples = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        NetSDKLib.pfAudioDataCallBack callback = (handle, buf, len, flag, user) -> {
            if (buf == null || len <= 0) {
                return;
            }
            if (flag == 1) {
                downFrames.incrementAndGet();
                downBytes.addAndGet(len);
                if (samples.size() < 8) {
                    samples.add(buf.getByteArray(0, len));
                }
            } else {
                upFrames.incrementAndGet();
            }
        };
        NetSDKLib.LLong talk = api.CLIENT_StartTalkEx(login, callback, null);
        if (talk == null || talk.longValue() == 0) {
            log("StartTalkEx 失败 err=0x" + Integer.toHexString(api.CLIENT_GetLastError()));
            return;
        }
        log("StartTalkEx 句柄=" + talk.longValue());

        byte[] silence = new byte[FRAME_BYTES];
        log("送 1 秒静音 PCM: " + FRAME_BYTES + " 字节/帧 × 10 帧（8 kHz/16 bit/单声道）");
        int sentFrames = 0;
        for (int i = 0; i < 10; i++) {
            Memory memory = new Memory(silence.length);
            memory.write(0, silence, 0, silence.length);
            NetSDKLib.LLong ret = api.CLIENT_TalkSendData(talk, memory, silence.length);
            long v = ret == null ? 0 : ret.longValue();
            log("  TalkSendData#" + (i + 1) + " 返回=" + v
                    + (v > 0 ? "（已送出）" : "（失败 err=0x"
                    + Integer.toHexString(api.CLIENT_GetLastError()) + "）"));
            if (v > 0) {
                sentFrames++;
            }
            sleep(DahuaExtAccess.TALK_PACKET_PERIOD_MS);
        }
        log("TalkSendData 成功 " + sentFrames + "/10 帧");

        log("监听设备回传音频 " + LISTEN_SECONDS + " 秒…（双工能力的关键证据）");
        sleep(LISTEN_SECONDS * 1000L);
        log("设备回传（flag==1）: " + downFrames.get() + " 帧 / " + downBytes.get() + " 字节");
        log("本机采集（flag==0）: " + upFrames.get() + " 帧（服务端无声卡，正常应为 0）");
        logDownFormat(samples);
        log("CLIENT_StopTalkEx = " + api.CLIENT_StopTalkEx(talk));

        // ==================== 阶段三：走生产封装（DahuaExtAccess#startTalk）====================
        log("──────── 阶段三：生产封装 DahuaExtAccess#startTalk（含编码协商）────────");
        DahuaExtAccess ext = new DahuaExtAccess();
        AtomicInteger wrappedDown = new AtomicInteger();
        AtomicLong wrappedBytes = new AtomicLong();
        try (DahuaExtAccess.TalkChannel channel = ext.startTalk(session,
                (flag, pcm) -> {
                    if (flag == 1) {
                        wrappedDown.incrementAndGet();
                        wrappedBytes.addAndGet(pcm.length);
                    }
                })) {
            log("封装层对讲句柄=" + channel.id());
            long ret = channel.send(silence);
            log("封装层 send() 返回=" + ret);
            sleep(Math.min(LISTEN_SECONDS, 3) * 1000L);
            log("封装层收到设备回传: " + wrappedDown.get() + " 帧 / " + wrappedBytes.get() + " 字节");
        } catch (Throwable t) {
            log("封装层对讲失败: " + brief(t));
        }

        log("──────── 结论提示 ────────");
        log("设备回传帧数 > 0 → 支持双工（可把音频下发给浏览器）；");
        log("= 0 → 该型号只支持单工喊话（浏览器只送不听），前端「收听」应置灰。");
        Runtime.getRuntime().halt(0);
    }

    private static void report(String what, boolean ok, NetSDKLib api) {
        if (ok) {
            log(what + "  → true");
        } else {
            log(what + "  → false  err=0x"
                    + Integer.toHexString(api.CLIENT_GetLastError()));
        }
    }

    /**
     * 打印设备回传帧的原始字节结构，判断封装与编码。
     *
     * <p><b>实测结论（这台 IPC）</b>：每帧以 ASCII {@code "DHAV"} 开头 —— 大华语音
     * 对讲的「音频打包模式」默认是 DHAV（见 {@code NET_ENCODE_AUDIO_INFO.nFrameType}，
     * 0-DHAV / 1-PS），不是裸 PCM。所以浏览器端 <b>不能</b> 把整帧当 PCM 播：
     * 头部那 16 字节会被当成 8 个采样点，每 130 ms 插一次爆音。
     * 平台侧在下发前把头部剥掉（见 {@code DahuaTalkService#stripAudioHeader}）。
     */
    private static void logDownFormat(java.util.List<byte[]> samples) {
        if (samples == null || samples.isEmpty()) {
            log("设备回传帧: 未取到（可能是静音抑制，设备没在采音）");
            return;
        }
        for (int i = 0; i < samples.size(); i++) {
            byte[] frame = samples.get(i);
            StringBuilder hex = new StringBuilder();
            for (int b = 0; b < Math.min(32, frame.length); b++) {
                hex.append(String.format("%02X ", frame[b]));
            }
            log("帧#" + (i + 1) + " 长度=" + frame.length + "，前 32 字节 = " + hex.toString().trim());
        }
        byte[] first = samples.get(0);
        String magic = first.length >= 4
                ? new String(first, 0, 4, java.nio.charset.StandardCharsets.US_ASCII) : "";
        log("封装识别: magic=\"" + magic + "\""
                + ("DHAV".equals(magic) ? " → DHAV 打包，平台侧剥掉头部再下发" : " → 视为裸数据"));
    }

    private static String brief(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void log(String s) {
        System.out.println("[talk-probe] " + s);
    }
}
