package io.github.rurulalanana.dahua;

import io.github.rurulalanana.dahua.internal.DahuaPtzCommand;
import io.github.rurulalanana.deviceaccess.ConnectMode;
import io.github.rurulalanana.deviceaccess.DeviceContext;
import io.github.rurulalanana.deviceaccess.DeviceKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设备接入契约与纯逻辑的单测。
 *
 * <h3>为什么这些能脱离原生库跑</h3>
 * 被测的都是<b>不触碰 SDK</b> 的纯函数：枚举解析、通道号反解、时间格式换算。
 * 大华的原生库只在真正调 {@code CLIENT_*} 时才加载，因此本测试类
 * 在没有 SDK 的机器（如 CI）上也能通过。
 *
 * <p>这不是凑数：{@code DeviceKind.of} 的兜底规则和 {@code ConnectMode.of}
 * 的别名映射一旦写错，<b>现有设备会突然全部失效</b>（见各自的类注释），
 * 而这类错误在联调时极难发现 —— 必须靠单测钉住。
 */
class DeviceAccessTest {

    // ==================== DeviceKind.of 的兜底规则 ====================

    /**
     * 未知值必须回落到 GB28181。
     *
     * <p>{@code gb_device.source} 这个字段历史上只用来标记「自动注册」，
     * 库里有 {@code "device"}、也可能有手工插入留下的 {@code null}。
     * 把它们当成未知类型会让这些设备立刻停止工作，
     * 所以任何无法识别的值都只能保守地按国标处理。
     */
    @Test
    @DisplayName("DeviceKind.of：未知值一律回落到 GB28181")
    void deviceKindFallsBackToGb28181() {
        assertEquals(DeviceKind.GB28181, DeviceKind.of(null));
        assertEquals(DeviceKind.GB28181, DeviceKind.of(""));
        assertEquals(DeviceKind.GB28181, DeviceKind.of("   "));
        assertEquals(DeviceKind.GB28181, DeviceKind.of("device"));
        assertEquals(DeviceKind.GB28181, DeviceKind.of("GB28181"));
        assertEquals(DeviceKind.GB28181, DeviceKind.of("gb28181"));
        assertEquals(DeviceKind.GB28181, DeviceKind.of("unknown-vendor"));
    }

    @Test
    @DisplayName("DeviceKind.of：dahua 大小写与空格不敏感")
    void deviceKindParsesDahua() {
        assertEquals(DeviceKind.DAHUA, DeviceKind.of("dahua"));
        assertEquals(DeviceKind.DAHUA, DeviceKind.of("DAHUA"));
        assertEquals(DeviceKind.DAHUA, DeviceKind.of("  Dahua  "));
    }

    @Test
    @DisplayName("DeviceKind.code 与 of 互逆")
    void deviceKindRoundTrips() {
        for (DeviceKind k : DeviceKind.values()) {
            assertEquals(k, DeviceKind.of(k.code()), "code 与 of 必须互逆: " + k);
        }
    }

    // ==================== ConnectMode.of 的别名 ====================

    /**
     * 厂商文档里「主动注册」有多个同义词。
     *
     * <p>设备侧配置与运维笔记里会混用 server / auto / autoregister / active / listen，
     * 只认一个会让用户按文档填了却连不上，且日志里没有任何线索。
     */
    @Test
    @DisplayName("ConnectMode.of：主动注册的各种同义词都能识别")
    void connectModeParsesServerAliases() {
        for (String alias : new String[]{"server", "SERVER", "auto", "autoregister", "active", "listen"}) {
            assertEquals(ConnectMode.SERVER, ConnectMode.of(alias), "应识别为 SERVER: " + alias);
        }
    }

    /**
     * 默认必须是 CLIENT。
     *
     * <p>SERVER 需要额外放开端口并配置设备，不能作为默认值 ——
     * 否则升级后所有大华设备都会突然连不上。
     */
    @Test
    @DisplayName("ConnectMode.of：默认 CLIENT，未知值也回落 CLIENT")
    void connectModeDefaultsToClient() {
        assertEquals(ConnectMode.CLIENT, ConnectMode.of(null));
        assertEquals(ConnectMode.CLIENT, ConnectMode.of(""));
        assertEquals(ConnectMode.CLIENT, ConnectMode.of("client"));
        assertEquals(ConnectMode.CLIENT, ConnectMode.of("随便什么"));
    }

    @Test
    @DisplayName("ConnectMode.code 与 of 互逆")
    void connectModeRoundTrips() {
        for (ConnectMode m : ConnectMode.values()) {
            assertEquals(m, ConnectMode.of(m.code()));
        }
    }

    // ==================== DeviceContext.connectable ====================

    /**
     * 两种模式的判据不同。
     *
     * <p>用同一套判据会让 SERVER 设备永远被判成「信息不全」而拒绝连接 ——
     * SERVER 模式下 host 由注册回调回填，配置里留空是正常的。
     */
    @Test
    @DisplayName("DeviceContext.connectable：CLIENT 要 host+port，SERVER 要 serial 或 host")
    void connectableDiffersByMode() {
        DeviceContext client = new DeviceContext("d1", "192.168.1.10", 37777,
                "admin", "pw", ConnectMode.CLIENT, null, 4);
        assertTrue(client.connectable(), "CLIENT 有 host+port 应可连");

        DeviceContext noHost = new DeviceContext("d1", "", 0,
                "admin", "pw", ConnectMode.CLIENT, null, 4);
        assertFalse(noHost.connectable(), "CLIENT 缺 host 不可连");

        DeviceContext server = new DeviceContext("d1", "", 0,
                "admin", "pw", ConnectMode.SERVER, "SN12345", 4);
        assertTrue(server.connectable(), "SERVER 有 serial 即可连（host 由注册回调回填）");

        DeviceContext serverNoSn = new DeviceContext("d1", "", 0,
                "admin", "pw", ConnectMode.SERVER, null, 4);
        assertFalse(serverNoSn.connectable(), "SERVER 既无 serial 又无 host 不可连");
    }

    @Test
    @DisplayName("DeviceContext.toString 不泄露密码")
    void contextHidesPassword() {
        DeviceContext ctx = new DeviceContext("d1", "192.168.1.10", 37777,
                "admin", "SuperSecret123", ConnectMode.CLIENT, null, 4);
        String text = ctx.toString();
        assertFalse(text.contains("SuperSecret123"), "toString 绝不能包含明文密码: " + text);
        assertTrue(text.contains("***"), "密码位应显示为 ***: " + text);
    }

    @Test
    @DisplayName("DeviceContext.withEndpoint 回填实时地址且保留原有值")
    void contextWithEndpoint() {
        DeviceContext base = new DeviceContext("d1", "", 0,
                "admin", "pw", ConnectMode.SERVER, "SN1", 4);
        DeviceContext filled = base.withEndpoint("10.0.0.5", 9500, "SN2");
        assertEquals("10.0.0.5", filled.host());
        assertEquals(9500, filled.port());
        assertEquals("SN2", filled.serial());

        // 传空值时保留原值，不能被覆盖成 null
        DeviceContext kept = base.withEndpoint(null, 0, null);
        assertEquals("", kept.host());
        assertEquals(0, kept.port());
        assertEquals("SN1", kept.serial());
    }

    // ==================== 云台方向映射 ====================

    /**
     * 四角方向必须落到扩展指令码（0x20 起），不能与基础方向混用。
     *
     * <p>混用会导致「按左上，画面往上走」这类难查的偏差。
     */
    @Test
    @DisplayName("DahuaPtzCommand.parse：八个方向 + 变倍 + 焦点光圈")
    void ptzParsesAllDirections() {
        assertEquals(DahuaPtzCommand.UP, DahuaPtzCommand.parse("up"));
        assertEquals(DahuaPtzCommand.DOWN, DahuaPtzCommand.parse("down"));
        assertEquals(DahuaPtzCommand.LEFT, DahuaPtzCommand.parse("left"));
        assertEquals(DahuaPtzCommand.RIGHT, DahuaPtzCommand.parse("right"));
        assertEquals(DahuaPtzCommand.UP_LEFT, DahuaPtzCommand.parse("upleft"));
        assertEquals(DahuaPtzCommand.UP_RIGHT, DahuaPtzCommand.parse("upright"));
        assertEquals(DahuaPtzCommand.DOWN_LEFT, DahuaPtzCommand.parse("downleft"));
        assertEquals(DahuaPtzCommand.DOWN_RIGHT, DahuaPtzCommand.parse("downright"));
        assertEquals(DahuaPtzCommand.ZOOM_IN, DahuaPtzCommand.parse("zoomin"));
        assertEquals(DahuaPtzCommand.ZOOM_OUT, DahuaPtzCommand.parse("zoomout"));
        assertEquals(DahuaPtzCommand.FOCUS_NEAR, DahuaPtzCommand.parse("focusnear"));
        assertEquals(DahuaPtzCommand.FOCUS_FAR, DahuaPtzCommand.parse("focusfar"));
    }

    @Test
    @DisplayName("DahuaPtzCommand.parse：分隔符与顺序都不敏感")
    void ptzParsesVariants() {
        assertEquals(DahuaPtzCommand.UP_LEFT, DahuaPtzCommand.parse("up-left"));
        assertEquals(DahuaPtzCommand.UP_LEFT, DahuaPtzCommand.parse("UP_LEFT"));
        assertEquals(DahuaPtzCommand.UP_LEFT, DahuaPtzCommand.parse("leftup"), "leftup 是 upleft 的同义写法");
        assertEquals(DahuaPtzCommand.DOWN_RIGHT, DahuaPtzCommand.parse("rightdown"));
    }

    /**
     * stop 与未知值都返回 null。
     *
     * <p>stop 的语义是「停止」这个动作，不是一个指令码，由调用方单独处理；
     * 未知值必须让调用方明确报错，不能静默当成某个方向。
     */
    @Test
    @DisplayName("DahuaPtzCommand.parse：stop 与未知值返回 null")
    void ptzRejectsStopAndUnknown() {
        assertNull(DahuaPtzCommand.parse("stop"));
        assertNull(DahuaPtzCommand.parse("nonexistent"));
        assertNull(DahuaPtzCommand.parse(null));
        assertNull(DahuaPtzCommand.parse(""));
    }

    @Test
    @DisplayName("DahuaPtzCommand.isStop：大小写与空格不敏感")
    void ptzIsStop() {
        assertTrue(DahuaPtzCommand.isStop("stop"));
        assertTrue(DahuaPtzCommand.isStop("STOP"));
        assertTrue(DahuaPtzCommand.isStop("  Stop  "));
        assertFalse(DahuaPtzCommand.isStop("up"));
        assertFalse(DahuaPtzCommand.isStop(null));
    }

    @Test
    @DisplayName("DahuaPtzCommand.zoom 只对变倍为真")
    void ptzZoomFlag() {
        assertTrue(DahuaPtzCommand.ZOOM_IN.zoom());
        assertTrue(DahuaPtzCommand.ZOOM_OUT.zoom());
        assertFalse(DahuaPtzCommand.UP.zoom());
        assertFalse(DahuaPtzCommand.FOCUS_NEAR.zoom(), "焦点不是变倍，速度口径不同");
    }

    /**
     * 指令码不能重复。
     *
     * <p>重复说明映射写错了 —— 例如把光圈接到变倍上，
     * 现场表现就是「按光圈，画面缩放」（本项目在国标侧踩过这个坑）。
     */
    @Test
    @DisplayName("DahuaPtzCommand：指令码互不重复")
    void ptzCodesAreDistinct() {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (DahuaPtzCommand c : DahuaPtzCommand.values()) {
            assertTrue(seen.add(c.code()),
                    "指令码重复: " + c + " -> " + c.code());
        }
    }

    // ==================== 通道编号反解 ====================

    @Test
    @DisplayName("channelCodeOf / channelIndexOf 互逆")
    void channelCodeRoundTrips() {
        String deviceId = "34020000001310000001";
        for (int i = 1; i <= 8; i++) {
            String code = DahuaDeviceAccessProvider.channelCodeOf(deviceId, i);
            assertEquals(i, DahuaDeviceAccessProvider.channelIndexOf(deviceId, code),
                    "通道编号应能反解: " + code);
        }
    }

    @Test
    @DisplayName("channelIndexOf：纯数字直接当通道号")
    void channelIndexAcceptsPlainNumber() {
        assertEquals(3, DahuaDeviceAccessProvider.channelIndexOf("d1", "3"));
        assertEquals(12, DahuaDeviceAccessProvider.channelIndexOf("d1", "12"));
    }

    /**
     * 通道 0 必须抬到 1。
     *
     * <p>大华的通道 0 是设备自身，云台和取流都用不了 ——
     * 传 0 会得到一个「无效果但返回成功」的调用，比失败更难查。
     */
    @Test
    @DisplayName("channelIndexOf：0 抬到 1，未知值回落到 1")
    void channelIndexClampsToOne() {
        assertEquals(1, DahuaDeviceAccessProvider.channelIndexOf("d1", "0"));
        assertEquals(1, DahuaDeviceAccessProvider.channelIndexOf("d1", "abc"));
        assertEquals(1, DahuaDeviceAccessProvider.channelIndexOf("d1", null));
        assertEquals(1, DahuaDeviceAccessProvider.channelIndexOf("d1", ""));
        assertEquals(1, DahuaDeviceAccessProvider.channelIndexOf("d1", "34020000001320000001"));
    }

    @Test
    @DisplayName("channelIndexOf：别的设备的通道编号不被误认")
    void channelIndexRejectsForeignDevice() {
        // 通道编号属于另一台设备时不能取到它的通道号
        assertEquals(1, DahuaDeviceAccessProvider.channelIndexOf("devA", "devB-5"),
                "设备前缀不匹配时应回落，而不是返回 5");
    }

    // ==================== provider 元信息 ====================

    @Test
    @DisplayName("provider.kind 为 DAHUA，且声明支持 SERVER 模式")
    void providerMetadata() {
        DahuaDeviceAccessProvider p = new DahuaDeviceAccessProvider();
        assertEquals(DeviceKind.DAHUA, p.kind());
        assertTrue(p.supportsServerMode(), "DahuaRegisterListener 已实现主动注册");
    }

    /**
     * 缺少上下文时探测必须返回 false，而不是抛异常。
     *
     * <p>契约要求 {@code listChannels} 在设备不可达时返回空列表而非抛异常，
     * {@code probe} 同理 —— 抛异常会让调用方（批量巡检）中断整轮。
     */
    @Test
    @DisplayName("probe / listChannels：入参不完整时安全降级")
    void providerDegradesGracefully() {
        DahuaDeviceAccessProvider p = new DahuaDeviceAccessProvider();
        assertFalse(p.probe(null));
        assertNotNull(p.listChannels(null));
        assertTrue(p.listChannels(null).isEmpty());

        DeviceContext blank = new DeviceContext("d1", "", 0,
                "admin", "pw", ConnectMode.CLIENT, null, 0);
        assertFalse(p.probe(blank), "地址不完整应返回 false 而非抛异常");
        assertTrue(p.listChannels(blank).isEmpty());
    }

    @Test
    @DisplayName("liveStreamUri：地址缺失时返回 null 而非拼出 null:554")
    void liveStreamUriRequiresHost() {
        DahuaDeviceAccessProvider p = new DahuaDeviceAccessProvider();
        DeviceContext noHost = new DeviceContext("d1", "", 0,
                "admin", "pw", ConnectMode.CLIENT, null, 0);
        assertNull(p.liveStreamUri(noHost, "d1-1", 0),
                "没有可达地址时应返回 null，不能拼出 rtsp://...@null:554");
    }

    @Test
    @DisplayName("liveStreamUri：主/子码流映射到 subtype 0/1")
    void liveStreamUriSubtype() {
        DahuaDeviceAccessProvider p = new DahuaDeviceAccessProvider();
        DeviceContext ctx = new DeviceContext("d1", "192.168.1.10", 37777,
                "admin", "pw", ConnectMode.CLIENT, null, 4);

        String main = p.liveStreamUri(ctx, "d1-2", 0);
        assertNotNull(main);
        assertTrue(main.contains("channel=2"), "通道号应写进 URL: " + main);
        assertTrue(main.contains("subtype=0"), "主码流 subtype=0: " + main);

        String sub = p.liveStreamUri(ctx, "d1-2", 1);
        assertNotNull(sub);
        assertTrue(sub.contains("subtype=1"), "子码流 subtype=1: " + sub);

        // 未指定时按主码流
        String def = p.liveStreamUri(ctx, "d1-2", null);
        assertNotNull(def);
        assertTrue(def.contains("subtype=0"), "未指定应按主码流: " + def);
    }

    @Test
    @DisplayName("playbackStreamUri：时间用下划线格式，与国标的 T 分隔不同")
    void playbackStreamUriTimeFormat() {
        DahuaDeviceAccessProvider p = new DahuaDeviceAccessProvider();
        DeviceContext ctx = new DeviceContext("d1", "192.168.1.10", 37777,
                "admin", "pw", ConnectMode.CLIENT, null, 4);

        String uri = p.playbackStreamUri(ctx, "d1-1",
                "2026-10-28 08:00:00", "2026-10-28 09:00:00");
        assertNotNull(uri);
        assertTrue(uri.contains("starttime=2026_10_28_08_00_00"),
                "大华要求下划线分隔的时间格式: " + uri);
        assertTrue(uri.contains("endtime=2026_10_28_09_00_00"), uri);
    }

    @Test
    @DisplayName("playbackStreamUri：时间格式非法时返回 null")
    void playbackStreamUriRejectsBadTime() {
        DahuaDeviceAccessProvider p = new DahuaDeviceAccessProvider();
        DeviceContext ctx = new DeviceContext("d1", "192.168.1.10", 37777,
                "admin", "pw", ConnectMode.CLIENT, null, 4);

        assertNull(p.playbackStreamUri(ctx, "d1-1", "2026-10-28T08:00:00", "2026-10-28T09:00:00"),
                "ISO 的 T 分隔不是大华格式，应拒绝而不是猜");
        assertNull(p.playbackStreamUri(ctx, "d1-1", null, null));
        assertNull(p.playbackStreamUri(ctx, "d1-1", "垃圾", "垃圾"));
    }

    @Test
    @DisplayName("流地址里的账号密码做 URL 编码")
    void streamUriEncodesCredentials() {
        DahuaDeviceAccessProvider p = new DahuaDeviceAccessProvider();
        DeviceContext ctx = new DeviceContext("d1", "192.168.1.10", 37777,
                "ad@min", "p@ss:word", ConnectMode.CLIENT, null, 4);
        String uri = p.liveStreamUri(ctx, "d1-1", 0);
        assertNotNull(uri);
        // @ 和 : 不编码会破坏 URL 结构，导致解析出错误的 host
        assertFalse(uri.contains("ad@min"), "账号中的 @ 必须编码: " + uri);
        assertTrue(uri.contains("%40"), "@ 应编码为 %40: " + uri);
    }
}
