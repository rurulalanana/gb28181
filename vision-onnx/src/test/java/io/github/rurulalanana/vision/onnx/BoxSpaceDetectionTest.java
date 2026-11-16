package io.github.rurulalanana.vision.onnx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 框坐标量纲判定（像素 vs 归一化）。
 *
 * <h3>为什么这段逻辑要单独测</h3>
 * 它决定 W/H 乘不乘 {@code inputSize}。判错时的症状不是「报错」，而是
 * <b>整屏一个大框 {@code [1,1,1,1]}</b>：
 * <pre>
 *   真实像素坐标 cx=201.2 当成 0~1 用 → ×320 = 64384
 *   → 除缩放比与原图宽高 → 远超 1 → clamp01 → 1.0
 * </pre>
 * 而且它<b>确实检出了东西</b>（只有位置不对），所以既不像崩溃也不像完全失效，
 * 从日志和界面都很难看出是 bug —— 实测就是接火焰模型时才暴露。
 *
 * <h3>数值来自真实模型</h3>
 * 这里的像素样本是 {@code fire_smoke_yolov8n.onnx}（imgsz=320）对真实图片
 * 最高分候选的原始输出：{@code 201.206, 116.741, 51.866, 69.463}。
 * 归一化样本是「如果模型导出成 0~1」时的等价形态。
 */
class BoxSpaceDetectionTest {

    /** 造一个 {@code [4+C][N]} 的假张量；框部分填给定值，类别部分填给定分数。 */
    private static float[][] tensor(int count, float[] cx, float[] cy, float[] cw, float[] ch,
                                    int classes, float classScore) {
        float[][] out = new float[4 + classes][count];
        System.arraycopy(cx, 0, out[0], 0, Math.min(cx.length, count));
        System.arraycopy(cy, 0, out[1], 0, Math.min(cy.length, count));
        System.arraycopy(cw, 0, out[2], 0, Math.min(cw.length, count));
        System.arraycopy(ch, 0, out[3], 0, Math.min(ch.length, count));
        for (int c = 4; c < out.length; c++) {
            for (int i = 0; i < count; i++) {
                out[c][i] = classScore;
            }
        }
        return out;
    }

    @Test
    @DisplayName("真实 YOLOv8 导出的像素坐标：判成像素（否则框会贴满全屏）")
    void pixelSpaceFromRealModel() {
        // fire_smoke_yolov8n.onnx 实测最高分候选的原始输出
        float[][] t = tensor(3,
                new float[]{201.206f, 88.4f, 310.1f},
                new float[]{116.741f, 45.2f, 290.7f},
                new float[]{51.866f, 30.5f, 12.9f},
                new float[]{69.463f, 40.1f, 8.3f},
                2, 0.53f);

        assertFalse(OnnxVisionDetector.isNormalizedBoxSpace(t, 3),
                "坐标到 310 明显是 inputSize 尺度的像素，不能当归一化用");
    }

    @Test
    @DisplayName("归一化坐标：判成归一化")
    void normalizedSpace() {
        float[][] t = tensor(3,
                new float[]{0.63f, 0.28f, 0.97f},
                new float[]{0.36f, 0.14f, 0.91f},
                new float[]{0.16f, 0.10f, 0.04f},
                new float[]{0.22f, 0.13f, 0.03f},
                2, 0.88f);

        assertTrue(OnnxVisionDetector.isNormalizedBoxSpace(t, 3),
                "全部 ≤1，是归一化坐标");
    }

    @Test
    @DisplayName("归一化但轻微越界（退化预测）：仍判归一化，不因 1.05 就翻脸")
    void normalizedSlightlyOverOne() {
        float[][] t = tensor(2,
                new float[]{1.05f, 0.5f},
                new float[]{0.98f, 0.5f},
                new float[]{0.30f, 0.2f},
                new float[]{0.40f, 0.2f},
                2, 0.6f);

        assertTrue(OnnxVisionDetector.isNormalizedBoxSpace(t, 2),
                "留了余量到 " + OnnxVisionDetector.NORMALIZED_BOX_MAX
                        + "，轻微越界不该被误判成像素");
    }

    @Test
    @DisplayName("全零输出（模型什么都没预测）：判归一化，不会影响结果")
    void allZero() {
        float[][] t = tensor(2, new float[]{0f, 0f}, new float[]{0f, 0f},
                new float[]{0f, 0f}, new float[]{0f, 0f}, 2, 0f);
        // 判成哪个都无所谓：分数全 0，所有候选都会被阈值挡掉
        assertTrue(OnnxVisionDetector.isNormalizedBoxSpace(t, 2));
    }

    @Test
    @DisplayName("只有一个候选越界也算像素（取全量最大值，不是抽样）")
    void singleOutlierMakesItPixel() {
        float[][] t = tensor(100,
                new float[]{0.2f}, new float[]{0.2f}, new float[]{0.1f}, new float[]{0.1f},
                2, 0.5f);
        // 第 99 个候选的 cx 是 250（像素），其余都像归一化
        t[0][99] = 250f;

        assertFalse(OnnxVisionDetector.isNormalizedBoxSpace(t, 100),
                "只要有一个候选是像素量级，整体就是像素空间");
    }

    @Test
    @DisplayName("负坐标取绝对值参与判定（cx 可以为负的退化预测）")
    void negativeValuesUseAbs() {
        float[][] t = tensor(1,
                new float[]{-0.3f}, new float[]{0.5f}, new float[]{0.2f}, new float[]{0.2f},
                1, 0.7f);
        assertTrue(OnnxVisionDetector.isNormalizedBoxSpace(t, 1), "-0.3 绝对值仍小于阈值");

        float[][] pixel = tensor(1,
                new float[]{-180f}, new float[]{160f}, new float[]{40f}, new float[]{40f},
                1, 0.7f);
        assertFalse(OnnxVisionDetector.isNormalizedBoxSpace(pixel, 1),
                "-180 的绝对值是 180，属于像素空间");
    }
}
