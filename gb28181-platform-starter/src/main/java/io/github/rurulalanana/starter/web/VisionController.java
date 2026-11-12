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

import java.util.ArrayList;
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
        data.put("configuredChannels", visionService.activeChannels());
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

    // ==================== 通道配置（多选管理） ====================

    /**
     * 已配置的检测通道列表（含运行态）。
     *
     * <p>与 {@code /status} 的区别：{@code /status} 只报<b>正在跑</b>的通道，
     * 这个接口报<b>配置了</b>的通道 —— 包括「配置了但设备离线没跑起来」。
     * 管理页面需要后者，否则用户看不到自己配过什么。
     */
    @Operation(summary = "已配置的检测通道列表")
    @GetMapping("/channels")
    public ApiResponse<Map<String, Object>> channels() {
        StpUtil.checkLogin();
        List<Map<String, Object>> rows = visionService.listConfigured();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", props.isEnabled());
        data.put("available", registry.available());
        data.put("defaultModel", registry.defaultModelId());
        data.put("defaultScenario", registry.defaultScenarioId());
        data.put("fps", props.getFps());
        data.put("confidenceThreshold", props.getConfidenceThreshold());
        data.put("channels", rows);
        data.put("count", rows.size());
        data.put("runningCount", visionService.runningCount());
        data.put("scenarios", scenarioViews());
        return ApiResponse.ok(data);
    }

    /**
     * 可选场景列表（界面「添加通道」时的下拉项）。
     *
     * <p>每项带上「模型 + 标签过滤 + 用途分类」，让运维能按能力选，
     * 而不是按文件名猜。{@code available=false} 表示模型文件缺失，
     * 界面应置灰 —— 提前告诉用户「这个选不了」比让他选了再报错好。
     */
    private List<Map<String, Object>> scenarioViews() {
        List<Map<String, Object>> out = new ArrayList<>();
        List<String> loaded = registry.loadedModelIds();
        List<String> declared = registry.declaredModelIds();
        for (VisionProperties.Scenario s : registry.allScenarios()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.id());
            row.put("modelId", s.modelId());
            row.put("name", s.name());
            row.put("category", s.category());
            row.put("description", s.description());
            List<String> labels = registry.scenarioLabels(s.id());
            row.put("labels", labels);
            row.put("estimatedMsPerFrame", s.estimatedMsPerFrame());
            /*
             * 可用性：模型必须被声明（文件存在）才可能加载。
             * 已加载是更强的信号，但懒加载意味着「没被用过就还没加载」，
             * 所以只要声明了就算可用，界面不必区分「已加载 / 可加载」。
             */
            row.put("available", declared.contains(s.modelId()));
            row.put("loaded", loaded.contains(s.modelId()));
            out.add(row);
        }
        return out;
    }

    /**
     * 新增一路检测通道。
     *
     * <p>参数用 snake_case（与平台其它接口一致）：
     * {@code device_id} / {@code channel_id} / {@code scenario_id}。
     */
    @Operation(summary = "新增检测通道")
    @PostMapping("/channels/add")
    public ApiResponse<Map<String, Object>> addChannel(
            @RequestParam(required = false) String device_id,
            @RequestParam String channel_id,
            @RequestParam(required = false) String scenario_id,
            @RequestParam(required = false) String remark) {
        StpUtil.checkLogin();
        if (!props.isEnabled()) {
            throw BizException.badRequest("视觉识别未启用，请先在配置中打开 vision.enabled");
        }
        if (!registry.available()) {
            throw BizException.badRequest("没有可用的视觉模型，请检查 vision.models 与模型文件");
        }
        if (channel_id == null || channel_id.isBlank()) {
            throw BizException.badRequest("通道编码不能为空");
        }
        if (scenario_id != null && !scenario_id.isBlank()
                && registry.scenario(scenario_id) == null) {
            throw BizException.badRequest("场景不存在: " + scenario_id);
        }
        boolean inserted = visionService.addChannel(device_id, channel_id, scenario_id, remark);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channel_id", channel_id);
        data.put("inserted", inserted);
        data.put("msg", inserted ? "已添加" : "该通道已在列表中");
        return ApiResponse.ok(data);
    }

    /**
     * 修改某通道的场景。
     *
     * <p>会重启这一路检测（场景决定模型与标签过滤，必须重新装配流水线）。
     */
    @Operation(summary = "修改检测通道的场景")
    @PostMapping("/channels/scenario")
    public ApiResponse<Map<String, Object>> updateScenario(
            @RequestParam String channel_id,
            @RequestParam String scenario_id) {
        StpUtil.checkLogin();
        if (registry.scenario(scenario_id) == null) {
            throw BizException.badRequest("场景不存在: " + scenario_id);
        }
        boolean ok = visionService.updateChannelScenario(channel_id, scenario_id);
        if (!ok) {
            throw BizException.badRequest("该通道不在检测列表中");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channel_id", channel_id);
        data.put("scenario_id", scenario_id);
        data.put("updated", true);
        return ApiResponse.ok(data);
    }

    /**
     * 移除一路检测通道。
     *
     * <p>按<b>通道编码</b>删除（不是「设备/通道」键）：界面上的删除按钮
     * 针对的就是通道本身，不该因为设备编码传错而删不掉。
     */
    /**
     * 移除检测通道（<b>幂等</b>）。
     *
     * <p>不在配置里时返回 {@code removed=false} 而不报错：播放弹窗里的「停止检测」
     * 对「临时开的一次性检测」和「配置了长期检测的通道」都要能用，
     * 让调用方按 {@code removed} 决定要不要再补一次本地停止。
     */
    @Operation(summary = "移除检测通道（幂等）")
    @PostMapping("/channels/remove")
    public ApiResponse<Map<String, Object>> removeChannel(@RequestParam String channel_id) {
        StpUtil.checkLogin();
        boolean removed = visionService.removeChannel(channel_id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channel_id", channel_id);
        data.put("removed", removed);
        return ApiResponse.ok(data);
    }
}
