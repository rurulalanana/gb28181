package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.deviceaccess.DeviceKind;
import io.github.rurulalanana.deviceaccess.DeviceRegisterTable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 按接入方式分发 {@link DeviceRegisterTable}。
 *
 * <p>大华走 {@code CLIENT_ListenServer}、海康走 ISUP CMS，各有一份内存注册表。
 * 平台按 {@code DeviceKind} 取对应那份 —— 加厂商时只需多一个 {@code @Component}。
 *
 * <p>与 {@code DeviceControlResolver} 的区别：这里允许为空（没有主动注册的部署
 * 本来就不该有注册表），调用方需自行判空。
 */
@Slf4j
@Component
public class DeviceRegisterRegistry {

    private final Map<DeviceKind, DeviceRegisterTable> index = new EnumMap<>(DeviceKind.class);

    public DeviceRegisterRegistry(List<DeviceRegisterTable> tables) {
        for (DeviceRegisterTable t : tables) {
            if (t == null || t.kind() == null) {
                continue;
            }
            if (index.putIfAbsent(t.kind(), t) != null) {
                log.warn("接入方式 {} 存在多个注册表实现，已保留先注册的 {}",
                        t.kind().code(), index.get(t.kind()).getClass().getSimpleName());
            }
        }
        if (!index.isEmpty()) {
            log.info("已注册的设备注册表: {}", index.keySet());
        }
    }

    /** 取该接入方式的注册表；没有时返回 {@code null}。 */
    public DeviceRegisterTable find(DeviceKind kind) {
        return kind == null ? null : index.get(kind);
    }

    /**
     * 按序列号在所有注册表里查找。
     *
     * <p>设备上报的 SN 在厂商之间不会冲突（大华是纯数字、海康带字母前缀），
     * 所以「不知道是哪家」时可以全表扫一遍。用于「只拿到 SN 就要判断在线状态」的场景。
     *
     * @return 命中的记录；都没有时返回 {@code null}
     */
    public DeviceRegisterTable.RegisteredDevice lookupAny(String serial) {
        if (serial == null || serial.isBlank()) {
            return null;
        }
        for (DeviceRegisterTable t : index.values()) {
            DeviceRegisterTable.RegisteredDevice d = t.lookup(serial);
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    /** 全部实现（诊断/清理任务用）。 */
    public List<DeviceRegisterTable> all() {
        return List.copyOf(index.values());
    }
}
