package io.github.rurulalanana.starter.web;

import io.github.rurulalanana.starter.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 存活探针。
 *
 * <pre>
 * GET /api/v1/health   进程与数据库是否可用（免登录）
 * </pre>
 *
 * <h3>为什么必须有这个接口</h3>
 * {@code scripts/restart-local.ps1} 从 1.0.0 起就在轮询 {@code /api/v1/health}，
 * 但这个接口<b>从来没实现过</b>。由于平台的 HTTP 状态码恒为 200（真实结果在 body 的
 * {@code code} 里），脚本拿到 200 就打印「已启动」—— 实际上它只验证了端口已被监听，
 * 应用是否初始化完成、数据库是否连得上完全没验证。
 *
 * <p>也不能依赖「不存在的路径会返回非 200」：{@code GlobalExceptionHandler} 会把
 * 404 也包成 200，所以探测脚本永远看不出区别。
 *
 * <h3>为什么免登录</h3>
 * 部署脚本、负载均衡、外部监控都拿不到账号。响应里只有状态与耗时时长，
 * 不泄露任何设备/账号/配置信息。
 */
@RestController
@Tag(name = "存活探针", description = "进程与数据库健康状态（免登录）")
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
public class HealthController {

    /** 进程启动时间，用于算 uptime。 */
    private static final Instant STARTED_AT = Instant.now();

    private final JdbcTemplate jdbcTemplate;

    /**
     * 健康检查。
     *
     * <p>数据库不可用时返回 {@code status=DOWN} 且 HTTP 仍是 200 ——
     * 与全站约定保持一致（真实结果看 {@code code}）。
     * 需要 HTTP 语义的监控请读 body 的 {@code status} 字段。
     */
    @Operation(summary = "健康检查")
    @GetMapping
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("uptimeSeconds", Duration.between(STARTED_AT, Instant.now()).getSeconds());
        body.put("time", Instant.now().toString());

        long t0 = System.nanoTime();
        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            body.put("database", one != null && one == 1 ? "UP" : "DOWN");
        } catch (Exception e) {
            /*
             * 只回 DOWN + 异常类名，不回 message：
             * 数据库异常的原文里常带账号、主机、库名，这个接口是免登录的。
             */
            body.put("database", "DOWN");
            body.put("databaseError", e.getClass().getSimpleName());
            body.put("status", "DOWN");
        }
        body.put("databaseMs", (System.nanoTime() - t0) / 1_000_000);
        return ApiResponse.ok(body);
    }
}
