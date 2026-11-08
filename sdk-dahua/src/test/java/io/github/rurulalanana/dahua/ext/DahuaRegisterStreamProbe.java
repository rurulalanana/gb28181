package io.github.rurulalanana.dahua.ext;

import com.netsdk.lib.NetSDKLib;
import io.github.rurulalanana.dahua.DahuaRegisterTable;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.deviceaccess.ConnectMode;
import io.github.rurulalanana.deviceaccess.DeviceRegisterTable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 在<b>真实的主动注册会话</b>上验证 {@code emDataType} 是否真的无效。
 *
 * <h3>要回答的问题</h3>
 * 我们实测「注册会话上 {@code RealPlayByDataType} 只给 DHAV，不给裸流/PS」，
 * 但另一个项目的代码看起来同样用 {@code emSpecCap=2 + SN} 登录、
 * 同样在注册会话上取流，却能拿到 {@code dwDataType=1004} 的裸流。
 * 差异可能藏在：通道号、{@code emDataType} 具体值、或 SDK 版本。
 *
 * <p>本探针把<b>同一台设备</b>的会话一次建好，然后<b>逐个</b>
 * {@code emDataType} 取流 5 秒、统计各 {@code dwDataType} 的包数与首字节，
 * 一次跑完直接得到对照表，不用反复改主程序重部署。
 *
 * <h3>运行（必须在能接收设备注册的机器上）</h3>
 * <pre>
 * java -cp "sdk-dahua.jar;netsdk.jar;jna.jar;slf4j-api.jar;...;." \
 *      io.github.rurulalanana.dahua.ext.DahuaRegisterStreamProbe 49500 admin ***
 * </pre>
 * 第一个参数是监听端口，第二三个是设备账号密码。
 */
public final class DahuaRegisterStreamProbe {

    private static final int LISTEN_SECONDS = 180;
    private static final long RECORD_MS = 5_000L;

    private static final Map<Integer, AtomicLong> BYTES = new ConcurrentHashMap<>();
    private static final Map<Integer, AtomicLong> COUNT = new ConcurrentHashMap<>();
    private static volatile String headSample = "";
    private static volatile String firstStartCode = "";

    private static final NetSDKLib.fRealDataCallBackEx2 CALLBACK =
            (handle, dataType, buf, bufSize, param, user) -> {
                if (buf == null || bufSize < 4) {
                    return;
                }
                BYTES.computeIfAbsent(dataType, k -> new AtomicLong()).addAndGet(bufSize);
                COUNT.computeIfAbsent(dataType, k -> new AtomicLong()).incrementAndGet();
                if (headSample.isEmpty()) {
                    byte[] h = buf.getByteArray(0, Math.min(16, bufSize));
                    StringBuilder sb = new StringBuilder();
                    for (byte b : h) {
                        sb.append(String.format("%02X ", b));
                    }
                    headSample = "dwDataType=" + dataType + " head=" + sb.toString().trim();
                    firstStartCode = describeHead(h);
                }
            };

    private DahuaRegisterStreamProbe() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 49500;
        String user = args.length > 1 ? args[1] : "admin";
        String pass = args.length > 2 ? args[2] : "";

        DahuaSdk sdk = DahuaSdk.get();
        if (!sdk.init()) {
            say("SDK 初始化失败: " + sdk.loadError());
            return;
        }

        DahuaRegisterTable table = new DahuaRegisterTable(180_000L);
        CountDownLatch registered = new CountDownLatch(1);
        String[] snHolder = new String[1];

        var listener = new io.github.rurulalanana.dahua.DahuaRegisterListener(table,
                (sn, ip, p) -> {
                    say("设备注册: sn=" + sn + " " + ip + ":" + p);
                    snHolder[0] = sn;
                    registered.countDown();
                });
        listener.start("0.0.0.0", port);
        say("已监听 0.0.0.0:" + port + "，等待设备主动注册（最多 " + LISTEN_SECONDS + " 秒）…");

        if (!registered.await(LISTEN_SECONDS, TimeUnit.SECONDS)) {
            say("超时：没有设备注册上来");
            listener.stop();
            return;
        }
        Thread.sleep(1_500L);

        DeviceRegisterTable.RegisteredDevice dev = table.lookup(snHolder[0]);
        if (dev == null) {
            say("注册表里查不到设备（可能已过期）");
            listener.stop();
            return;
        }

        // 用注册连接登录（emSpecCap=2 + SN），与主程序完全一致
        DahuaSdk.Session session = sdk.session(dev.ip(), dev.port(), user, pass,
                ConnectMode.SERVER, snHolder[0]);
        if (session == null) {
            say("注册会话登录失败");
            listener.stop();
            return;
        }
        say("注册会话登录成功: ipc=" + session.ipc() + " 通道数=" + session.channelCount()
                + " handle=" + session.handle().longValue());

        // 逐个 emDataType 试
        int[] types = {
                NetSDKLib.EM_REAL_DATA_TYPE.EM_REAL_DATA_TYPE_PRIVATE,
                NetSDKLib.EM_REAL_DATA_TYPE.EM_REAL_DATA_TYPE_H264,
                NetSDKLib.EM_REAL_DATA_TYPE.EM_REAL_DATA_TYPE_GBPS,
        };
        String[] names = {"PRIVATE(0)", "H264(4)", "GBPS(1)"};

        // 通道也逐个试：IPC 可能是 0 也可能是 1
        for (int channel : new int[]{0, 1}) {
            for (int i = 0; i < types.length; i++) {
                tryType(sdk, session, channel, types[i], names[i]);
                Thread.sleep(800L);   // 给设备释放上一路取流
            }
        }

        listener.stop();
        say("");
        say("========== 对照表已打印完毕 ==========");
    }

    private static void tryType(DahuaSdk sdk, DahuaSdk.Session session,
                                int channel, int emDataType, String name) {
        BYTES.clear();
        COUNT.clear();
        headSample = "";
        firstStartCode = "";

        NetSDKLib netsdk = sdk.sdk();
        NetSDKLib.NET_IN_REALPLAY_BY_DATA_TYPE in = new NetSDKLib.NET_IN_REALPLAY_BY_DATA_TYPE();
        in.nChannelID = channel;
        in.emDataType = emDataType;
        in.cbRealDataEx = CALLBACK;
        in.rType = 0;
        in.emAudioType = 0;
        in.nMP4Type = 0;

        NetSDKLib.NET_OUT_REALPLAY_BY_DATA_TYPE out = new NetSDKLib.NET_OUT_REALPLAY_BY_DATA_TYPE();
        NetSDKLib.LLong handle = netsdk.CLIENT_RealPlayByDataType(session.handle(), in, out, 5000);

        String label = "通道=" + channel + " emDataType=" + name;
        if (handle == null || handle.longValue() == 0) {
            say(label + " → 取流失败 lastError=" + netsdk.CLIENT_GetLastError());
            return;
        }
        try {
            Thread.sleep(RECORD_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        netsdk.CLIENT_StopRealPlayEx(handle);

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, AtomicLong> e : BYTES.entrySet()) {
            sb.append(String.format("  dwDataType=%d 包=%d 字节=%d",
                    e.getKey(), COUNT.get(e.getKey()).get(), e.getValue().get()));
        }
        say(label + " → 收到:" + (sb.length() == 0 ? " 无数据" : ""));
        if (sb.length() > 0) {
            say(sb.toString());
            say("  首包: " + headSample);
            say("  起始码: " + firstStartCode);
        }
    }

    private static String describeHead(byte[] h) {
        if (h.length >= 4 && h[0] == 'D' && h[1] == 'H' && h[2] == 'A' && h[3] == 'V') {
            return "DHAV（大华私有）";
        }
        if (h.length >= 4 && h[0] == 0 && h[1] == 0 && h[2] == 0 && h[3] == 1) {
            return "Annex-B 裸流（4 字节起始码）";
        }
        if (h.length >= 3 && h[0] == 0 && h[1] == 0 && h[2] == 1) {
            return "Annex-B 裸流（3 字节起始码）";
        }
        if (h.length >= 4 && h[0] == 0 && h[1] == 0 && h[2] == 1 && (h[3] & 0xFF) == 0xBA) {
            return "国标 PS";
        }
        return "未知";
    }

    private static void say(String s) {
        System.out.println("[probe] " + s);
    }
}
