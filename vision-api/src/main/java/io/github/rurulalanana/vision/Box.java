package io.github.rurulalanana.vision;

/**
 * 检测框（归一化坐标）。
 *
 * <p><b>为什么归一化（0~1）而不是像素</b>：模型输入尺寸与原始画面尺寸通常不同，
 * 像素坐标必须带上「是哪张图、什么尺寸」才有意义；归一化坐标与分辨率解耦，
 * 任何尺寸的画面都能直接叠框，前端 canvas 也只需按容器尺寸换算一次。
 *
 * @param x 左上角横坐标（0~1）
 * @param y 左上角纵坐标（0~1）
 * @param w 宽（0~1）
 * @param h 高（0~1）
 */
public record Box(float x, float y, float w, float h) {

    /** 框中心横坐标（ROI 判定用）。 */
    public float centerX() {
        return x + w / 2f;
    }

    /** 框中心纵坐标（ROI 判定用）。 */
    public float centerY() {
        return y + h / 2f;
    }

    /** 框面积（占画面比例，过滤小目标用）。 */
    public float area() {
        return w * h;
    }

    /** 与另一框的交并比（NMS 与目标跟踪用）。 */
    public float iou(Box other) {
        if (other == null) {
            return 0f;
        }
        float ix = Math.max(0f, Math.min(x + w, other.x + other.w) - Math.max(x, other.x));
        float iy = Math.max(0f, Math.min(y + h, other.y + other.h) - Math.max(y, other.y));
        float inter = ix * iy;
        float union = area() + other.area() - inter;
        return union <= 0f ? 0f : inter / union;
    }
}
