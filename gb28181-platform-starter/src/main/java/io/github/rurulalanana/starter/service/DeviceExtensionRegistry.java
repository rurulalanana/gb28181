package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.deviceaccess.DeviceExtension;
import io.github.rurulalanana.deviceaccess.DeviceKind;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 厂商扩展能力的统一分发器：按「接口 + kind」找实现。
 *
 * <p>为什么不像 {@code DeviceMediaRegistry} 那样每个契约一个注册表：
 * 扩展能力会越来越多（抓图、录像检索、预置位、配置、对讲…），
 * 每个都写一份注册表是纯样板。这里用一个泛型索引代替。
 *
 * <p>用法：
 * <pre>
 *   extensions.find(kind, DeviceSnapshot.class)
 *             .ifPresent(s -&gt; s.jpeg(deviceId, channelId));
 * </pre>
 * 查不到 = 该厂商不支持这项能力，调用方回落到通用路径或明确报不支持。
 */
@Slf4j
@Component
public class DeviceExtensionRegistry {

    /** 接口类型 → (kind → 实现)。 */
    private final Map<Class<?>, Map<DeviceKind, Object>> index = new HashMap<>();

    public DeviceExtensionRegistry(List<DeviceExtension> extensions) {
        for (DeviceExtension e : extensions) {
            if (e == null || e.kind() == null) {
                continue;
            }
            /*
             * 一个实现可能同时实现多个扩展接口（例如大华既做抓图又做录像检索），
             * 所以要把它的每个扩展接口都登记一次。
             */
            for (Class<?> contract : contractsOf(e.getClass())) {
                Map<DeviceKind, Object> byKind =
                        index.computeIfAbsent(contract, k -> new EnumMap<>(DeviceKind.class));
                if (byKind.putIfAbsent(e.kind(), e) != null) {
                    log.warn("{} 在接入方式 {} 下存在多个实现，已保留先注册的 {}",
                            contract.getSimpleName(), e.kind().code(),
                            byKind.get(e.kind()).getClass().getSimpleName());
                }
            }
        }
        if (!index.isEmpty()) {
            log.info("已注册的厂商扩展能力: {}", index.keySet().stream()
                    .map(Class::getSimpleName).sorted().toList());
        }
    }

    /**
     * 取实现。
     *
     * @return 该厂商不支持这项能力时返回 {@link Optional#empty()}
     */
    public <T> Optional<T> find(DeviceKind kind, Class<T> contract) {
        if (kind == null || contract == null) {
            return Optional.empty();
        }
        Map<DeviceKind, Object> byKind = index.get(contract);
        if (byKind == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(contract.cast(byKind.get(kind)));
    }

    /** 该厂商是否支持某项扩展能力。 */
    public boolean supports(DeviceKind kind, Class<?> contract) {
        return find(kind, contract).isPresent();
    }

    /**
     * 某项能力的全部实现（不区分厂商）。
     *
     * <p>用于「不针对具体设备、问所有厂商一遍」的场景，例如局域网发现 ——
     * 调用方并不知道要找哪家的设备，只能挨个问。
     */
    public <T> List<T> all(Class<T> contract) {
        Map<DeviceKind, Object> byKind = index.get(contract);
        if (byKind == null || byKind.isEmpty()) {
            return List.of();
        }
        List<T> out = new java.util.ArrayList<>(byKind.size());
        for (Object impl : byKind.values()) {
            out.add(contract.cast(impl));
        }
        return out;
    }

    /** 找出实现类声明的所有扩展接口（不含 {@link DeviceExtension} 本身）。 */
    private static List<Class<?>> contractsOf(Class<?> impl) {
        List<Class<?>> out = new java.util.ArrayList<>(4);
        for (Class<?> c : impl.getInterfaces()) {
            if (c != DeviceExtension.class && DeviceExtension.class.isAssignableFrom(c)) {
                out.add(c);
            }
        }
        return out;
    }
}
