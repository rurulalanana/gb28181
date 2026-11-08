package io.github.rurulalanana.starter.service.control;

import io.github.rurulalanana.deviceaccess.DeviceKind;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.entity.GbDevice;
import io.github.rurulalanana.starter.service.DeviceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 按设备接入方式选 {@link DeviceControl} 实现。
 *
 * <p><b>永不返回 null</b>：未知接入方式一律按国标处理（与 {@code DeviceKind.of} 一致）。
 * 所以调用方只写 {@code resolver.of(channelId).reboot(channelId)}，
 * 不用记「null 表示国标」这种暗号，也不会有判空 NPE。
 */
@Slf4j
@Component
public class DeviceControlResolver {

    private final DeviceService deviceService;
    private final Map<DeviceKind, DeviceControl> index = new EnumMap<>(DeviceKind.class);
    /** 兜底实现：任何未知接入方式都按国标处理。 */
    private final DeviceControl fallback;

    /**
     * @param controls 容器里全部实现。用 {@code List} 注入，新增厂商只需加一个
     *                 {@code @Component}，不用改本类。
     */
    public DeviceControlResolver(DeviceService deviceService, List<DeviceControl> controls) {
        this.deviceService = deviceService;
        for (DeviceControl c : controls) {
            if (c == null || c.kind() == null) {
                continue;
            }
            if (index.putIfAbsent(c.kind(), c) != null) {
                log.warn("接入方式 {} 存在多个控制实现，已保留先注册的 {}",
                        c.kind().code(), index.get(c.kind()).getClass().getSimpleName());
            }
        }
        DeviceControl gb = index.get(DeviceKind.GB28181);
        if (gb == null) {
            // 国标是主链路，缺了它所有控制命令都会失效，必须在启动时就暴露出来
            throw new IllegalStateException(
                    "缺少国标控制实现 Gb28181DeviceControl，平台无法下发任何控制命令");
        }
        this.fallback = gb;
        log.info("已注册的设备控制实现: {}", index.keySet());
    }

    /**
     * 取该通道所属设备的控制实现。
     *
     * @param channelId 通道编码或设备编码（两种都支持，与旧行为一致）
     * @return 永不为 {@code null}
     */
    public DeviceControl of(String channelId) {
        return ofDeviceId(deviceIdOf(channelId));
    }

    /** 取指定设备编码的控制实现，永不为 {@code null}。 */
    public DeviceControl ofDeviceId(String deviceId) {
        GbDevice device = deviceService.findDevice(deviceId);
        if (device == null) {
            // 查不到设备就交给国标实现去报「设备未注册」，比在这里编一句更接近事实
            return fallback;
        }
        return index.getOrDefault(DeviceKind.of(device.getSource()), fallback);
    }

    /** 该设备是否走厂商 SDK 链路（用于日志与少量仍需按协议区分的地方）。 */
    public boolean isSdkDevice(String channelId) {
        return of(channelId).kind() != DeviceKind.GB28181;
    }

    /** 通道编码 → 设备编码；查不到所属设备就当作传进来的就是设备编码。 */
    private String deviceIdOf(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            throw BizException.badRequest("缺少通道编码");
        }
        String owner = deviceService.deviceIdOfChannel(channelId);
        return owner == null ? channelId : owner;
    }
}
