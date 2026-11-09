package io.github.rurulalanana.vision.pipeline;

import io.github.rurulalanana.vision.Box;
import io.github.rurulalanana.vision.Detection;

import java.util.List;

/**
 * 区域（ROI）判定：检测框中心是否落在多边形内。
 *
 * <p><b>为什么在流水线层做</b>：ROI 是业务概念（哪块区域算入侵），
 * 模型层不该认识它。流水线在事件聚合时用它过滤。
 *
 * <p>坐标为归一化（0~1），与 {@link Box} 一致。
 */
public final class Roi {

    private final List<Point> polygon;

    /** 归一化坐标点。 */
    public record Point(float x, float y) {
    }

    public Roi(List<Point> polygon) {
        this.polygon = polygon == null ? List.of() : List.copyOf(polygon);
    }

    /** 是否配置了区域（未配置时视为全画面）。 */
    public boolean configured() {
        return polygon.size() >= 3;
    }

    /**
     * 框中心是否在区域内。
     *
     * <p>未配置区域时返回 {@code true}（全画面都算）。
     */
    public boolean contains(Box box) {
        if (!configured() || box == null) {
            return true;
        }
        return inside(box.centerX(), box.centerY());
    }

    /** 点在多边形内（射线法）。 */
    private boolean inside(float x, float y) {
        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            Point a = polygon.get(i);
            Point b = polygon.get(j);
            boolean intersect = (a.y() > y) != (b.y() > y)
                    && x < (b.x() - a.x()) * (y - a.y()) / (b.y() - a.y()) + a.x();
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** 按 ROI 过滤检测结果（未配置时原样返回）。 */
    public List<Detection> filter(List<Detection> detections) {
        if (!configured() || detections == null || detections.isEmpty()) {
            return detections == null ? List.of() : detections;
        }
        return detections.stream().filter(d -> contains(d.box())).toList();
    }
}
