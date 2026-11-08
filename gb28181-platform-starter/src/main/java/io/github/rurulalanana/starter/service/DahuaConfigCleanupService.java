package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.dahua.internal.DahuaJson;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 清理设备配置里历史误写留下的 {@code params} 垃圾键。
 *
 * <h3>这些垃圾是怎么来的</h3>
 * 早期写配置时把整包响应（含 {@code id}/{@code result}/{@code params}）原样写回设备，
 * 设备把 {@code params} 当成<b>业务字段</b>存进了配置里。读出来就变成
 * {@code PtzSpeed[0].params.table.PtzSpeed} 这种路径。
 *
 * <h3>为什么删不掉</h3>
 * 大华的写配置是 <b>merge 语义</b>：payload 里没出现的字段保留旧值。
 * 所以「不写 params」不会让它消失，「写回去」只是继续嵌套。
 *
 * <h3>唯一有效的办法（实测）</h3>
 * 显式下发 {@code {"params": null}} —— 设备会把这个键清空。
 * 实测 {@code PtzSpeed} / {@code AutoPan} 都清掉了。
 *
 * <p>清理是幂等的：没有垃圾键时下发 {@code params:null} 也不会产生副作用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DahuaConfigCleanupService {

    private final DahuaCapabilityService capability;

    /**
     * 清理一个配置项里的 {@code params} 垃圾键。
     *
     * @return 清理前的业务层 JSON（便于确认清掉的是什么）；读不到时返回空串
     */
    public String cleanParamsArtifact(String deviceId, String channelCode, String command) {
        DahuaSdk.Session session = capability.requireSession(deviceId);
        int ch = capability.sdkChannel(session, deviceId, channelCode);
        String raw;
        try {
            raw = capability.ext().readConfig(session, command, ch);
        } catch (Exception e) {
            log.info("读取配置项 {} 失败，跳过清理: {}", command, e.getMessage());
            return "";
        }
        if (raw == null || raw.isBlank()) {
            return "";
        }
        com.fasterxml.jackson.databind.JsonNode business = DahuaJson.business(raw);
        if (business == null || !business.isObject() || !business.has("params")) {
            return "";
        }
        String before = DahuaJson.write(business);
        /*
         * 下发 {"params": null}：设备据此清空该键。
         *
         * 用 Map + Jackson 构造而不是拼字符串 —— 保证是合法 JSON，
         * 且不会因为 before 里还有别的转义字符而出错。
         */
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("params", null);
        try {
            capability.ext().writeRaw(session, command, ch, DahuaJson.write(
                    new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(payload)));
        } catch (Exception e) {
            log.warn("清理配置项 {} 的 params 垃圾键失败: {}", command, e.getMessage());
            return before;
        }
        // 回读确认
        try {
            String after = capability.ext().readConfig(session, command, ch);
            com.fasterxml.jackson.databind.JsonNode b2 = DahuaJson.business(after);
            if (b2 != null && b2.isObject() && b2.has("params")
                    && !b2.get("params").isNull()) {
                log.warn("配置项 {} 的 params 垃圾键仍在（设备拒绝清空）", command);
            } else {
                log.info("配置项 {} 的 params 垃圾键已清理: device={} channel={}",
                        command, deviceId, channelCode);
            }
        } catch (Exception ignored) {
            // 回读失败不影响结论，写入已经成功
        }
        return before;
    }
}
