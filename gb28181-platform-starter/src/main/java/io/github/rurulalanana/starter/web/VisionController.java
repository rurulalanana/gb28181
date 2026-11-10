package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.config.VisionProperties;
import io.github.rurulalanana.starter.service.vision.DbDetectionSink;
import io.github.rurulalanana.starter.service.vision.VisionModelRegistry;
import io.github.rurulalanana.starter.service.vision.VisionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 视觉识别接口。
 *
 * <h3>接口风格</h3>
 * 与平台其它接口一致：统一 {@code {code,msg,data}}，参数 snake_case。
 * 非 GET 一律要求写权限（{@code WritePermissionInterceptor} 默认拒绝）。
 *
 * <h3>为什么「开启/关闭」是 POST 且带 channel</h3>
 * 检测是<b>有状态、有资源</b>的操作（每路一个常驻 ffmpeg + 一份推理配额），
 * 不能做成「查询即开启」。用显式 POST 才能让运维知道自己在花 CPU。
 */
@Slf4j
@RestController
@Tag(name = "AI 视觉识别", description = "人形/车辆等目标检测的开关与结果查询")
@RequestMapping("/api/v1/vision")
@RequiredArgsConstructor
public class VisionController {

    private final VisionProperties props;
    private final VisionService visionService;
    private final VisionModelRegistry registry;
    private final DbDetectionSink sink;

    /** 视觉识别总览：开关状态、模型、正在检测的通道。 */
    @Operation(summary = "视觉识别状态")
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        StpUtil.checkLogin();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", props.isEnabled());
        data.put("available", registry.available());
        data.put("defaultModel", registry.defaultModelId());
        data.put("declaredModels", registry.declaredModelIds());
        data.put("loadedModels", registry.loadedModelIds());
        data.put("configuredChannels", props.getChannels());
        data.put("runningCount", visionService.runningCount());
        data.put("running", visionService.status());
        data.put("fps", props.getFps());
        data.put("confidenceThreshold", props.getConfidenceThreshold());
        return ApiResponse.ok(data);
    }

    /**
     * 某路当前画面的最新检测框。
     *
     * <p>框坐标是<b>归一化</b>的（0~1），前端按 canvas 实际尺寸乘即可 ——
     * 这样分辨率变化（主码流/子码流切换）不会让框错位。
     */
    @Operation(summary = "查询某路最新检测结果")
    @GetMapping("/detections")
    public ApiResponse<Map<String, Object>> detections(@RequestParam String channel) {
        StpUtil.checkLogin();
        DbDetectionSink.Latest latest = sink.latestOf(channel);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channel", channel);
        data.put("running", visionService.isRunning(channel));
        if (latest == null) {
            data.put("at", 0);
            data.put("counts", Map.of());
            data.put("detections", List.of());
            return ApiResponse.ok(data);
        }
        data.put("at", latest.at());
        data.put("width", latest.width());
        data.put("height", latest.height());
        data.put("counts", latest.counts());
        data.put("detections", latest.detections().stream().map(d -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", d.label());
            row.put("confidence", d.confidence());
            row.put("box", Map.of(
                    "x", d.box().x(), "y", d.box().y(),
                    "w", d.box().w(), "h", d.box().h()));
            return row;
        }).toList());
        return ApiResponse.ok(data);
    }

    /**
     * 开启一路检测。
     *
     * @param channel 通道键：{@code 设备编码/通道编码}，或只有通道编码（设备直连）
     */
    @Operation(summary = "开启某路 AI 检测")
    @PostMapping("/start")
    public ApiResponse<Map<String, Object>> start(@RequestParam String channel) {
        StpUtil.checkLogin();
        if (!props.isEnabled()) {
            throw BizException.badRequest("视觉识别未启用，请先在配置中打开 vision.enabled");
        }
        if (!registry.available()) {
            throw BizException.badRequest("没有可用的视觉模型，请检查 vision.models 与模型文件");
        }
        boolean ok = visionService.start(channel);
        if (!ok) {
            throw BizException.badRequest("开启失败：取不到该通道的流，请确认通道编码正确且设备在线");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channel", channel);
        data.put("running", true);
        return ApiResponse.ok(data);
    }

    /** 关闭一路检测。 */
    @Operation(summary = "关闭某路 AI 检测")
    @PostMapping("/stop")
    public ApiResponse<Map<String, Object>> stop(@RequestParam String channel) {
        StpUtil.checkLogin();
        boolean ok = visionService.stop(channel);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channel", channel);
        data.put("running", false);
        data.put("wasRunning", ok);
        return ApiResponse.ok(data);
    }
}
