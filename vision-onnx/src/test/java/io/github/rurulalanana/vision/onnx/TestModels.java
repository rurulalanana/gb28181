package io.github.rurulalanana.vision.onnx;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 测试用的模型文件定位器。
 *
 * <h3>为什么需要它（这里踩过一个「静默失效」的坑）</h3>
 * 模型统一放在<b>仓库根目录</b>的 {@code models/}（见 {@code models/MODELS.md}：
 * 「模型是随部署拷贝的产物，不放进模块」）。而 surefire 的 {@code user.dir}
 * 是<b>模块目录</b> {@code vision-onnx/}。
 *
 * <p>早先测试直接写 {@code Path.of("models", "yolox_nano.onnx")}，
 * 解析成 {@code vision-onnx/models/yolox_nano.onnx} —— 那个路径不存在，
 * 于是 {@code assumeTrue} 让<b>全部 3 个用例静默跳过</b>。
 * 构建一直是绿的，但那组测试从来没真正执行过，
 * 「真实模型跑通」这件事实际上没有任何自动化保护。
 *
 * <p>所以统一从这里取路径：按顺序尝试模块内、仓库根，任一命中即可。
 * 这样无论从模块目录还是仓库根跑测试都能找到模型。
 */
final class TestModels {

    private TestModels() {
    }

    /**
     * 定位一个模型文件。
     *
     * @param fileName 文件名（如 {@code yolox_nano.onnx}）
     * @return 存在的路径；都找不到时返回 {@code null}（调用方用
     *         {@code assumeTrue} 跳过，而不是判失败 ——
     *         允许「只 clone 代码、没拉部署模型」的场合）
     */
    static Path locate(String fileName) {
        Path[] candidates = {
                Path.of("models", fileName),          // 模块内（若有人放在这里）
                Path.of("..", "models", fileName),    // 仓库根（实际位置）
        };
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }
}
