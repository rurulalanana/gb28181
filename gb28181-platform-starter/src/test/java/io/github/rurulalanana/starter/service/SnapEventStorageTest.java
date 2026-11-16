package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.starter.config.Gb28181Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件截图存储：<b>同一通道多次告警必须各存一份</b>。
 *
 * <h3>这个 bug 的现场表现</h3>
 * 早先 AI 事件截图直接复用了「通道当前快照」的 {@code saveBytes}，
 * 文件名固定为 {@code {serial}_{code}.jpg}。于是同一通道每告警一次就覆盖同一张图：
 * <pre>
 *   线上实测：1195 条 AI 告警，snap_path 只有 4 个不同值
 *             其中 1138 条挤在 /api/v1/cloudrecord/snap/10001/10001-1.jpg
 * </pre>
 * 而且那个路径还会被 1 小时过期清理删掉，所以历史告警点开全是 404 ——
 * <b>既看不出是哪次事件的图，图也早就没了。</b>
 *
 * <p>根因不是「路径生成写错了」，而是把「当前状态」的存储原语
 * （要覆盖、要短命）用在了「历史证据」上（要唯一、要跟记录同生共死）。
 */
class SnapEventStorageTest {

    /** 用临时目录当录像根，不碰真实数据。 */
    private static SnapService service(Path root) {
        Gb28181Properties props = new Gb28181Properties();
        props.setRecordRoot(root.toString());
        return new SnapService(props);
    }

    private static byte[] jpeg(String marker) {
        // 内容不重要，只需要能区分「哪一张」
        return ("JPEG:" + marker).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("同一通道两次告警：存两份、路径不同、内容不互相覆盖")
    void sameChannelKeepsSeparateFiles(@TempDir Path root) {
        SnapService svc = service(root);
        String serial = "34020000001310000001";
        String code = "34020000001310000001";

        String p1 = svc.saveEventBytes(serial, code, "aaaa1111bbbb2222", jpeg("第一次"));
        String p2 = svc.saveEventBytes(serial, code, "cccc3333dddd4444", jpeg("第二次"));

        assertNotNull(p1);
        assertNotNull(p2);
        assertNotEquals(p1, p2, "同一通道的两次告警必须有各自的路径");

        // 第一张没被覆盖 —— 这是原 bug 的核心症状
        assertArrayEquals(jpeg("第一次"), svc.readEvent(serial, "aaaa1111bbbb2222"),
                "第二次写入不能覆盖第一张");
        assertArrayEquals(jpeg("第二次"), svc.readEvent(serial, "cccc3333dddd4444"));
    }

    @Test
    @DisplayName("事件截图路径形如 /snap/alarm/{serial}/{key}.jpg，且与通道快照路径不重叠")
    void urlShape(@TempDir Path root) {
        SnapService svc = service(root);
        String url = svc.saveEventBytes("10001", "10001-1", "abc123", jpeg("x"));

        assertEquals("/api/v1/cloudrecord/snap/alarm/10001/abc123.jpg", url);
        // 通道快照是 /snap/{serial}/{code}.jpg —— 两者形状必须能区分开，
        // 否则清理时会把「当前状态图」当成「历史证据图」
        String channelSnap = svc.saveBytes("10001", "10001-1", jpeg("y"));
        assertEquals("/api/v1/cloudrecord/snap/10001/10001-1.jpg", channelSnap);
        assertFalse(channelSnap.contains("/alarm/"));
    }

    @Test
    @DisplayName("通道快照仍是覆盖语义（两种用途的差异是刻意的）")
    void channelSnapshotStillOverwrites(@TempDir Path root) {
        SnapService svc = service(root);
        String a = svc.saveBytes("10001", "10001-1", jpeg("旧"));
        String b = svc.saveBytes("10001", "10001-1", jpeg("新"));

        assertEquals(a, b, "通道快照就该是同一个路径");
        assertArrayEquals(jpeg("新"), svc.read("10001", "10001-1"), "后写的覆盖先写的");
    }

    @Test
    @DisplayName("按 URL 删除：只删事件截图，绝不碰通道快照")
    void deleteOnlyTouchesEventFiles(@TempDir Path root) {
        SnapService svc = service(root);
        String serial = "10001";
        String channelSnap = svc.saveBytes(serial, "10001-1", jpeg("通道当前"));
        String eventUrl = svc.saveEventBytes(serial, "10001-1", "deadbeef", jpeg("证据"));

        svc.deleteEventByUrl(eventUrl);

        assertNull(svc.readEvent(serial, "deadbeef"), "事件截图应被删除");
        assertNotNull(svc.read(serial, "10001-1"), "通道快照不该被误删");

        // 再拿通道快照的 URL 去调删除，必须无副作用
        svc.deleteEventByUrl(channelSnap);
        assertNotNull(svc.read(serial, "10001-1"), "拿通道快照 URL 调用也必须无效");
    }

    @Test
    @DisplayName("脏 URL / 空值不抛异常（清理路径上不能因一条脏数据中断）")
    void deleteTolerateSloppyInput(@TempDir Path root) {
        SnapService svc = service(root);
        for (String bad : new String[]{null, "", "   ", "/", "no-slash",
                "/api/v1/cloudrecord/snap/alarm/onlyserial", "/api/v1/cloudrecord/snap/alarm/"}) {
            svc.deleteEventByUrl(bad);   // 不抛异常即通过
        }
    }

    @Test
    @DisplayName("特殊字符被安全化，不能穿越目录")
    void pathTraversalBlocked(@TempDir Path root) {
        SnapService svc = service(root);
        // 用带路径分隔符的 key 尝试穿越
        String url = svc.saveEventBytes("../../etc", "x", "../../../evil", jpeg("z"));
        assertNotNull(url);
        assertFalse(url.contains(".."), "路径里不该出现 ..：" + url);

        // 文件必须落在 alarm 目录下
        Path alarmDir = root.resolve("snap").resolve("alarm");
        assertTrue(Files.isDirectory(alarmDir), "应在 snap/alarm 下");
    }

    @Test
    @DisplayName("孤儿清理：保留被引用的，删掉没被引用的")
    void purgeOrphans(@TempDir Path root) {
        SnapService svc = service(root);
        String serial = "10001";
        svc.saveEventBytes(serial, "c1", "keep0001", jpeg("留"));
        svc.saveEventBytes(serial, "c1", "drop0002", jpeg("删"));

        int removed = svc.purgeEventOrphans(java.util.Set.of("keep0001.jpg"));

        assertEquals(1, removed);
        assertNotNull(svc.readEvent(serial, "keep0001"), "被引用的必须留着");
        assertNull(svc.readEvent(serial, "drop0002"), "没被引用的应删掉");
    }

    @Test
    @DisplayName("孤儿清理不碰通道快照（它靠 1 小时过期自清）")
    void purgeOrphansLeavesChannelSnaps(@TempDir Path root) {
        SnapService svc = service(root);
        svc.saveBytes("10001", "10001-1", jpeg("当前"));
        svc.saveEventBytes("10001", "10001-1", "somekey1", jpeg("证据"));

        // keep 传空 = 所有事件截图都该删，但通道快照不在扫描范围
        svc.purgeEventOrphans(java.util.Set.of());

        assertNotNull(svc.read("10001", "10001-1"), "通道快照不属于事件截图，不能被清掉");
    }

    @Test
    @DisplayName("非方形 / 多设备：各设备一个子目录")
    void perDeviceSubdirectory(@TempDir Path root) {
        SnapService svc = service(root);
        String u1 = svc.saveEventBytes("devA", "ch1", "k1", jpeg("A"));
        String u2 = svc.saveEventBytes("devB", "ch2", "k2", jpeg("B"));

        assertEquals("/api/v1/cloudrecord/snap/alarm/devA/k1.jpg", u1);
        assertEquals("/api/v1/cloudrecord/snap/alarm/devB/k2.jpg", u2);
        assertArrayEquals(jpeg("A"), svc.readEvent("devA", "k1"));
        assertArrayEquals(jpeg("B"), svc.readEvent("devB", "k2"));
    }

    @Test
    @DisplayName("空字节 / 空 key 返回 null，不建空文件")
    void rejectEmptyInput(@TempDir Path root) {
        SnapService svc = service(root);
        assertNull(svc.saveEventBytes("10001", "c1", "k1", null));
        assertNull(svc.saveEventBytes("10001", "c1", "k1", new byte[0]));
        assertNull(svc.saveEventBytes("10001", "c1", "", jpeg("x")));
        assertNull(svc.saveEventBytes("10001", "c1", null, jpeg("x")));
        assertNull(svc.readEvent("10001", null));
    }

    @Test
    @DisplayName("通道快照的 1 小时过期不删事件截图（靠目录隔离，不靠文件名判断）")
    void purgeExpiredSkipsEventDir(@TempDir Path root) throws Exception {
        SnapService svc = service(root);
        svc.saveEventBytes("10001", "c1", "oldkey01", jpeg("很久以前的证据"));

        // 把事件截图的时间戳改成 2 小时前
        Path f = root.resolve("snap").resolve("alarm").resolve("10001").resolve("oldkey01.jpg");
        assertTrue(Files.isRegularFile(f));
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - 2 * 60 * 60 * 1000L));

        svc.purgeExpired();

        assertNotNull(svc.readEvent("10001", "oldkey01"),
                "事件截图是历史证据，保留期跟报警（默认 180 天），不能被 1 小时清理删掉");
    }

    @Test
    @DisplayName("eventUrl 与 saveEventBytes 返回的路径完全一致")
    void eventUrlMatchesSave(@TempDir Path root) {
        SnapService svc = service(root);
        List<String> cases = List.of("10001", "34020000001310000001");
        for (String serial : cases) {
            String saved = svc.saveEventBytes(serial, "chan", "key12345", jpeg("x"));
            assertEquals(SnapService.eventUrl(serial, "key12345"), saved,
                    "两处各拼一遍迟早不同步，必须共用同一个方法");
        }
    }
}
