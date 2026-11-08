package io.github.rurulalanana.dahua.ext;

import com.netsdk.lib.NetSDKLib;
import com.sun.jna.Pointer;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.deviceaccess.ConnectMode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 验证「SDK 能否直接拿到 PS 码流」（设计文档 §5.3 的 V3 门禁，开发期手工运行）。
 *
 * <h3>它要回答什么</h3>
 * 设备在 NAT 后时，平台反向连它的 RTSP(554) 是不通的 —— 只有设备主动连出来的
 * 那条注册连接可用。要让播放也走这条连接，就必须用 SDK 取流而不是 RTSP。
 * 而 SDK 取流是否可行，取决于回调里给的是不是<b>能直接喂给 ZLM 的 PS 流</b>：
 * <ul>
 *   <li>是 PS（{@code 00 00 01 BA} 开头）→ 打 RTP 即可，ZLM 侧零改动；</li>
 *   <li>是裸 ES → 要自己拼 PS 头，成本高且各型号不一致。</li>
 * </ul>
 *
 * <h3>为什么放在 src/test</h3>
 * 需要长期占用设备码流、依赖真机，与「测试必须快速返回」冲突。
 *
 * <h3>运行</h3>
 * <pre>
 * mvn -o -q test-compile
 * java -cp "target/classes;target/test-classes;$(cat target/cp.txt)" \
 *      io.github.rurulalanana.dahua.ext.DahuaSdkStreamProbe 192.168.10.156 37777 admin ***  1
 * </pre>
 */
public final class DahuaSdkStreamProbe {

    /** 回调必须被强引用持有，否则被 GC 后就是野指针。 */
    private static final Map<Integer, AtomicLong> TYPE_BYTES = new ConcurrentHashMap<>();
    private static final Map<Integer, AtomicLong> TYPE_COUNT = new ConcurrentHashMap<>();

    private static volatile boolean sawPs = false;
    private static volatile boolean sawAnnexB = false;
    private static volatile boolean sawH264 = false;
    private static volatile boolean sawH265 = false;
    private static volatile String firstHead = "";
    private static volatile String secondHead = "";
    private static final AtomicLong total = new AtomicLong();

    private static final NetSDKLib.fRealDataCallBackEx2 CALLBACK =
            (handle, dataType, buf, bufSize, param, user) -> {
                if (buf == null || bufSize <= 0) {
                    return;
                }
                TYPE_BYTES.computeIfAbsent(dataType, k -> new AtomicLong()).addAndGet(bufSize);
                TYPE_COUNT.computeIfAbsent(dataType, k -> new AtomicLong()).incrementAndGet();

                int n = Math.min(bufSize, 32);
                byte[] head = buf.getByteArray(0, n);
                String hex = hex(head);
                if (firstHead.isEmpty()) {
                    firstHead = "type=" + dataType + " len=" + bufSize + " " + hex;
                } else if (secondHead.isEmpty() && !hex.equals(firstHead)) {
                    secondHead = "type=" + dataType + " len=" + bufSize + " " + hex;
                }
                // Annex-B 起始码：00 00 01 / 00 00 00 01 → 裸流（H264/H265）
                int nalAt = -1;
                if (n >= 4 && head[0] == 0 && head[1] == 0 && head[2] == 0 && head[3] == 1) {
                    nalAt = 4;
                } else if (n >= 3 && head[0] == 0 && head[1] == 0 && head[2] == 1) {
                    nalAt = 3;
                }
                if (nalAt > 0 && nalAt < n) {
                    sawAnnexB = true;
                    int b = head[nalAt] & 0xFF;
                    int h264Type = b & 0x1F;
                    int h265Type = (b >> 1) & 0x3F;
                    // SPS/PPS/VPS 才能定编码：H264 SPS=7 PPS=8；H265 VPS=32 SPS=33 PPS=34
                    if (h265Type == 32 || h265Type == 33 || h265Type == 34) {
                        sawH265 = true;
                    } else if (h264Type == 7 || h264Type == 8) {
                        sawH264 = true;
                    }
                }
                // PS 起始码：00 00 01 BA（Program Stream pack header）
                if (n >= 4 && (head[0] & 0xFF) == 0 && (head[1] & 0xFF) == 0
                        && (head[2] & 0xFF) == 1 && (head[3] & 0xFF) == 0xBA) {
                    sawPs = true;
                }
                total.addAndGet(bufSize);
            };

    private DahuaSdkStreamProbe() {
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "192.168.10.156";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 37777;
        String user = args.length > 2 ? args[2] : "admin";
        String pass = args.length > 3 ? args[3] : "";
        int channel = args.length > 4 ? Integer.parseInt(args[4]) : 1;

        DahuaSdk sdk = DahuaSdk.get();
        if (!sdk.init()) {
            say("SDK 初始化失败: " + sdk.loadError());
            return;
        }
        say("SDK 初始化成功");

        DahuaSdk.Session session = sdk.session(host, port, user, pass, ConnectMode.CLIENT, null);
        if (session == null) {
            say("登录失败");
            return;
        }
        say("登录成功: " + host + ":" + port + " 通道数=" + session.channelCount());

        NetSDKLib netsdk = sdk.sdk();

        /*
         * 用 CLIENT_RealPlayByDataType 并显式指定 emDataType=EM_REAL_DATA_TYPE_H264。
         *
         * 【为什么不是 GBPS】
         * 实测 GBPS 只在直连会话上给 PS，主动注册会话上只给 DHAV ——
         * 而 NAT 场景只有注册会话可用。H264 裸流两种会话都正常，
         * 也是官方 demo（RealPlayModule）的默认用法。
         */
        NetSDKLib.NET_IN_REALPLAY_BY_DATA_TYPE in = new NetSDKLib.NET_IN_REALPLAY_BY_DATA_TYPE();
        in.nChannelID = channel;
        in.emDataType = NetSDKLib.EM_REAL_DATA_TYPE.EM_REAL_DATA_TYPE_H264;
        in.cbRealDataEx = CALLBACK;
        in.rType = 0;             // 0 = 实时
        in.emAudioType = 0;
        in.nMP4Type = 0;
        in.write();

        NetSDKLib.NET_OUT_REALPLAY_BY_DATA_TYPE out = new NetSDKLib.NET_OUT_REALPLAY_BY_DATA_TYPE();
        out.write();

        NetSDKLib.LLong play = netsdk.CLIENT_RealPlayByDataType(
                session.handle(), in, out, 5000);
        if (play == null || play.longValue() == 0) {
            say("取流失败: lastError=" + netsdk.CLIENT_GetLastError());
            return;
        }
        say("取流已开始(GBPS): handle=" + play.longValue() + "，采集 10 秒…");

        Thread.sleep(10_000);

        netsdk.CLIENT_StopRealPlayEx(play);
        say("已停止取流");

        say("");
        say("========== 结果 ==========");
        say("总字节: " + total.get());
        for (Map.Entry<Integer, AtomicLong> e : TYPE_BYTES.entrySet()) {
            int t = e.getKey();
            say(String.format("  dwDataType=%d  %s  包数=%d  字节=%d",
                    t, typeName(t), TYPE_COUNT.get(t).get(), e.getValue().get()));
        }
        say("首包: " + firstHead);
        say("次包: " + secondHead);
        say("看到 PS 起始码(00 00 01 BA): " + sawPs);
        say("看到 Annex-B 起始码（裸流）: " + sawAnnexB
                + "  H264(SPS/PPS)=" + sawH264 + "  H265(VPS/SPS/PPS)=" + sawH265);
        say(sawAnnexB
                ? "→ 结论：SDK 给的是裸流，可直接 mk_media_input_h264/265 喂 ZLM"
                : "→ 结论：没有裸流，需要进一步确认 emDataType");
    }

    private static String typeName(int t) {
        return switch (t) {
            case 0 -> "原始音视频混合数据（大华为 PS）";
            case 1 -> "标准视频数据";
            case 2 -> "YUV";
            case 3 -> "PCM 音频";
            case 4 -> "原始音频";
            default -> "未知";
        };
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (byte x : b) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", x));
        }
        return sb.toString();
    }

    private static void say(String s) {
        System.out.println("[probe] " + s);
    }
}
