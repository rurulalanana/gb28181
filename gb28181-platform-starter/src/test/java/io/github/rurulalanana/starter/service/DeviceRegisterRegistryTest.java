package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.deviceaccess.DeviceKind;
import io.github.rurulalanana.deviceaccess.DeviceRegisterTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注册表按接入方式分发。
 *
 * <p>接第二个厂商时容器里会有两份 {@code DeviceRegisterTable}，
 * 按接口注入会报「multiple beans」—— 这个测试保证按 kind 取是对的。
 */
class DeviceRegisterRegistryTest {

    @Test
    @DisplayName("按 kind 取到对应实现，未注册的返回 null")
    void findByKind() {
        var dahua = new FakeTable(DeviceKind.DAHUA);
        var registry = new DeviceRegisterRegistry(List.of(dahua));

        assertSame(dahua, registry.find(DeviceKind.DAHUA));
        // 海康还没接，取不到
        assertNull(registry.find(DeviceKind.GB28181));
        assertNull(registry.find(null));
    }

    @Test
    @DisplayName("多厂商共存时各取各的")
    void multipleVendors() {
        var dahua = new FakeTable(DeviceKind.DAHUA);
        var other = new FakeTable(DeviceKind.GB28181);
        var registry = new DeviceRegisterRegistry(List.of(dahua, other));

        assertSame(dahua, registry.find(DeviceKind.DAHUA));
        assertSame(other, registry.find(DeviceKind.GB28181));
        assertEquals(2, registry.all().size());
    }

    @Test
    @DisplayName("lookupAny 跨厂商按 SN 找，SN 不冲突")
    void lookupAnyAcrossVendors() {
        var dahua = new FakeTable(DeviceKind.DAHUA);
        var other = new FakeTable(DeviceKind.GB28181);
        dahua.register("10001", "1.1.1.1", 1000, null);
        other.register("DS-2CD3T46", "2.2.2.2", 2000, null);

        var registry = new DeviceRegisterRegistry(List.of(dahua, other));

        assertEquals("1.1.1.1", registry.lookupAny("10001").ip());
        assertEquals("2.2.2.2", registry.lookupAny("DS-2CD3T46").ip());
        assertNull(registry.lookupAny("不存在"));
        assertNull(registry.lookupAny(""));
    }

    @Test
    @DisplayName("同 kind 重复注册时保留先注册的，不抛异常")
    void duplicateKindKeepsFirst() {
        var first = new FakeTable(DeviceKind.DAHUA);
        var second = new FakeTable(DeviceKind.DAHUA);
        var registry = new DeviceRegisterRegistry(List.of(first, second));

        assertSame(first, registry.find(DeviceKind.DAHUA),
                "重复实现应保留先注册的，否则启动会因 bean 冲突失败");
        assertTrue(registry.all().size() == 1);
    }

    @Test
    @DisplayName("空列表不报错（没有主动注册的部署）")
    void emptyIsOk() {
        var registry = new DeviceRegisterRegistry(List.of());
        assertNull(registry.find(DeviceKind.DAHUA));
        assertTrue(registry.all().isEmpty());
        assertNull(registry.lookupAny("x"));
    }

    /** 最小实现，只关心分发逻辑。 */
    private static final class FakeTable implements DeviceRegisterTable {
        private final DeviceKind kind;
        private final Map<String, RegisteredDevice> bySerial = new ConcurrentHashMap<>();

        FakeTable(DeviceKind kind) {
            this.kind = kind;
        }

        @Override
        public DeviceKind kind() {
            return kind;
        }

        @Override
        public void register(String serial, String ip, int port, String extra) {
            long now = System.currentTimeMillis();
            bySerial.put(serial, new RegisteredDevice(serial, ip, port, now, now, extra));
        }

        @Override
        public RegisteredDevice lookup(String serial) {
            return bySerial.get(serial);
        }

        @Override
        public List<RegisteredDevice> list() {
            return List.copyOf(bySerial.values());
        }

        @Override
        public void unregister(String serial) {
            bySerial.remove(serial);
        }

        @Override
        public int pruneExpired() {
            return 0;
        }
    }
}
