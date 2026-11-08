package io.github.rurulalanana.dahua.ext;

import com.netsdk.lib.NetSDKLib;
import com.sun.jna.Pointer;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.deviceaccess.ConnectMode;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 摸清大华私有 DHAV 帧的布局，为「从 DHAV 里抠出 Annex-B 裸流」提供依据。
 *
 * <h3>为什么要解 DHAV</h3>
 * 设备在 NAT 后时只能用主动注册会话，而<b>注册会话上 SDK 不做转封装</b>：
 * 不管 {@code emDataType} 填 H264 / GBPS，回调里只有 {@code dwDataType=0}
 * 的 DHAV 私有帧（直连会话才会额外投递裸流）。要出图只能自己把 DHAV 拆开。
 *
 * <h3>它打印什么</h3>
 * 每种帧类型各打印一份：完整长度、类型字节、以及<b>第一个 Annex-B 起始码的偏移</b>。
 * 若偏移稳定且落在帧头之后，就说明「跳过帧头、从起始码开始」是可行的取法。
 */
public final class DahuaDhavProbe {

    private static final AtomicInteger SEQ = new AtomicInteger();
    /** 每种类型最多打印几帧。 */
    private static final int PER_TYPE = 3;

    private static final NetSDKLib.fRealDataCallBackEx2 CALLBACK =
            (handle, dataType, buf, bufSize, param, user) -> {
                if (buf == null || bufSize < 16) {
                    return;
                }
                byte[] d = buf.getByteArray(0, bufSize);
                // 只看 DHAV 私有帧
                if (!(d[0] == 'D' && d[1] == 'H' && d[2] == 'A' && d[3] == 'V')) {
                    return;
                }
                int typeByte = d[4] & 0xFF;
                int n = SEQ.incrementAndGet();
                // 每种类型打 PER_TYPE 帧
                if (n > PER_TYPE * 8) {
                    return;
                }
                int sc = firstStartCode(d);
                int lenAt12 = u32(d, 12);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(32, d.length); i++) {
                    sb.append(String.format("%02X ", d[i]));
                }
                System.out.printf("[dhav] #%-3d type=0x%02X len=%-7d 偏移12处=%d 首个起始码=%d head=%s%n",
                        n, typeByte, bufSize, lenAt12, sc, sb.toString().trim());
            };

    private DahuaDhavProbe() {
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "192.168.10.156";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 37777;
        String user = args.length > 2 ? args[2] : "admin";
        String pass = args.length > 3 ? args[3] : "";
        int channel = args.length > 4 ? Integer.parseInt(args[4]) : 0;

        DahuaSdk sdk = DahuaSdk.get();
        if (!sdk.init()) {
            System.out.println("SDK 初始化失败: " + sdk.loadError());
            return;
        }
        DahuaSdk.Session session = sdk.session(host, port, user, pass, ConnectMode.CLIENT, null);
        if (session == null) {
            System.out.println("登录失败");
            return;
        }
        System.out.println("登录成功，开始取流（私有流模式）…");

        NetSDKLib netsdk = sdk.sdk();
        NetSDKLib.NET_IN_REALPLAY_BY_DATA_TYPE in = new NetSDKLib.NET_IN_REALPLAY_BY_DATA_TYPE();
        in.nChannelID = channel;
        // PRIVATE：确保拿到的是 DHAV，验证解析规则对注册会话同样适用
        in.emDataType = NetSDKLib.EM_REAL_DATA_TYPE.EM_REAL_DATA_TYPE_PRIVATE;
        in.cbRealDataEx = CALLBACK;
        in.rType = 0;
        in.write();
        NetSDKLib.NET_OUT_REALPLAY_BY_DATA_TYPE out = new NetSDKLib.NET_OUT_REALPLAY_BY_DATA_TYPE();
        out.write();

        NetSDKLib.LLong play = netsdk.CLIENT_RealPlayByDataType(session.handle(), in, out, 5000);
        if (play == null || play.longValue() == 0) {
            System.out.println("取流失败: " + netsdk.CLIENT_GetLastError());
            return;
        }
        Thread.sleep(6000);
        netsdk.CLIENT_StopRealPlayEx(play);
        System.out.println("完成");
    }

    /** 首个 Annex-B 起始码的下标（3 或 4 字节），找不到返回 -1。 */
    private static int firstStartCode(byte[] d) {
        for (int i = 0; i + 3 < d.length; i++) {
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 0 && d[i + 3] == 1) {
                return i;
            }
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
                return i;
            }
        }
        return -1;
    }

    private static int u32(byte[] d, int off) {
        if (off + 4 > d.length) {
            return -1;
        }
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8)
                | ((d[off + 2] & 0xFF) << 16) | ((d[off + 3] & 0xFF) << 24);
    }
}
