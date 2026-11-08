package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.deviceaccess.DeviceKind;
import io.github.rurulalanana.deviceaccess.DeviceMediaProvider;
import io.github.rurulalanana.starter.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 按接入方式选 {@link DeviceMediaProvider}。
 *
 * <p>与 {@code DeviceControlResolver} 同样的写法：{@code List} 注入、按 kind 索引，
 * 新增厂商只需加一个 {@code @Component}，不用改本类。
 *
 * <p>与那个 resolver 的区别：这里<b>会返回 null</b>。国标走 SIP 收流，
 * 没有也不需要 provider —— 调用方（{@code StreamService}）只在非国标分支用它。
 */
@Slf4j
@Component
public class DeviceMediaRegistry {

    private final Map<DeviceKind, DeviceMediaProvider> index = new EnumMap<>(DeviceKind.class);

    public DeviceMediaRegistry(List<DeviceMediaProvider> providers) {
        for (DeviceMediaProvider p : providers) {
            if (p == null || p.kind() == null) {
                continue;
            }
            if (index.putIfAbsent(p.kind(), p) != null) {
                log.warn("接入方式 {} 存在多个取流实现，已保留先注册的 {}",
                        p.kind().code(), index.get(p.kind()).getClass().getSimpleName());
            }
        }
        if (!index.isEmpty()) {
            log.info("已注册的取流实现: {}", index.keySet());
        }
    }

    /** 取该接入方式的取流实现；国标或未安装对应 SDK 时返回 {@code null}。 */
    public DeviceMediaProvider find(DeviceKind kind) {
        return kind == null ? null : index.get(kind);
    }

    /** 取实现，否则给出可操作的错误信息。 */
    public DeviceMediaProvider require(DeviceKind kind) {
        DeviceMediaProvider p = find(kind);
        if (p == null) {
            throw BizException.badRequest("接入方式 " + kind.code()
                    + " 不可用：未安装对应的 SDK 模块");
        }
        return p;
    }

    /**
     * 让所有实现做一次空闲回收。
     *
     * <p>厂商取流没有 RtpSession，平台那个按会话的巡检扫不到它们，
     * 所以由调度器统一驱动 —— 厂商不必自带 {@code @Scheduled}。
     */
    public void reapAll() {
        for (DeviceMediaProvider p : index.values()) {
            try {
                p.reapIdle();
            } catch (Exception e) {
                log.debug("取流回收失败（{}）: {}", p.kind().code(), e.getMessage());
            }
        }
    }
}
