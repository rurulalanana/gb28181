package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.starter.entity.GbChannel;
import io.github.rurulalanana.starter.service.vision.VisionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 「哪些通道要求流常开」的判定。
 *
 * <p>空闲回收有两条独立路径（国标会话与厂商取流），判据必须完全一致：
 * 一边认为该关、另一边认为该留，就会互相抵消 —— 表现为「设了常开还是过一会儿就断」。
 * 抽成独立组件是为了让两边共用同一份逻辑，同时避开
 * {@code StreamService} ↔ {@code DahuaMediaProvider} 的循环依赖。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamKeepAlive {

    private final DeviceService deviceService;
    private final ChannelRecordPolicy recordPolicy;

    /**
     * 视觉检测服务（可选）。
     *
     * <p>用 {@code ObjectProvider} 延迟解析：视觉模块可能未启用
     * （{@code vision.enabled=false}），且它在内部反过来依赖点播链路，
     * 直接注入会形成循环。
     */
    private final ObjectProvider<VisionService> visionProvider;

    /**
     * 找出「要求流常开、不能被空闲回收」的通道编码集合。
     *
     * <p>三类：
     * <ol>
     *   <li>应录云端录像（用户开关 ∪ 录像计划）—— 录像计划不写库，
     *       只读 {@code on_demand} 会让计划刚开录的流被回收；</li>
     *   <li>用户显式设成常开（{@code on_demand=false}）的通道；</li>
     *   <li>正在做视觉检测的通道 —— 检测取帧是 ffmpeg 拉 HTTP-TS，
     *       ZLM 不把这连接算作读者，不并入的话检测会在宽限期后静默停掉。</li>
     * </ol>
     *
     * <p>一次全量查库优于循环里 N 次单条查询（每 20 秒调用一次）；
     * 查库失败返回空集合，退化为全部按需 —— 宁可多回收一次，
     * 也不要因一次抖动把按需通道永久占着设备上行。
     *
     * @return 通道编码集合（不含设备编码，通道编码跨设备可重复）
     */
    public Set<String> channelsRequiringStream() {
        try {
            Set<String> out = new HashSet<>();
            if (recordPolicy != null && recordPolicy.isLoaded()) {
                out.addAll(recordPolicy.enabledChannels());
            }
            for (GbChannel ch : deviceService.allChannels()) {
                if (ch.getChannelId() != null && Boolean.FALSE.equals(ch.getOnDemand())) {
                    out.add(ch.getChannelId());
                }
            }
            VisionService vision = visionProvider.getIfAvailable();
            if (vision != null) {
                out.addAll(vision.runningChannelIds());
            }
            return out;
        } catch (Exception e) {
            log.debug("读取常开通道失败（本次按全部按需处理）: {}", e.getMessage());
            return Set.of();
        }
    }
}
