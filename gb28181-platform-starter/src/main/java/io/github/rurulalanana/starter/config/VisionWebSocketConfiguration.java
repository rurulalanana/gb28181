package io.github.rurulalanana.starter.config;

import io.github.rurulalanana.starter.web.VisionWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册 AI 检测结果的 WebSocket 端点。
 *
 * <p>路径放在 {@code /api/v1/vision/ws}：与 REST 同一前缀，反代只需为
 * {@code /api/v1/**} 打开 {@code Upgrade} 头，不用额外维护规则
 * （与大华对讲端点同一思路）。
 *
 * <p><b>为什么 {@code setAllowedOriginPatterns("*")}</b>：WebSocket 握手不受 CORS 保护，
 * Spring 默认只允许同源；平台既有「jar 内前端同源部署」也有「nginx 反代 + 自定义域」
 * 两种形态，写死域名会让后者连不上。安全性由 handler 里的 token 校验承担，
 * 不放行任何匿名连接。
 *
 * <p>注意：{@code @EnableWebSocket} 已经在 {@code DahuaTalkWebSocketConfiguration}
 * 上标注过，这里<b>不再重复</b> —— 重复标注虽然 Spring 能容忍，
 * 但会让「到底哪个类负责开启」变得不清楚。
 */
@Configuration
@RequiredArgsConstructor
public class VisionWebSocketConfiguration implements WebSocketConfigurer {

    private final VisionWebSocketHandler visionWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(visionWebSocketHandler, "/api/v1/vision/ws")
                .setAllowedOriginPatterns("*");
    }
}
