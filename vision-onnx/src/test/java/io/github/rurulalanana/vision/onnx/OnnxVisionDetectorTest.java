package io.github.rurulalanana.vision.onnx;

import io.github.rurulalanana.vision.DetectorConfig;
import io.github.rurulalanana.vision.VisionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ONNX 检测器单测：只覆盖「配置/模型缺失时快速失败」。
 *
 * <p>真实推理的端到端验证需要模型文件（yolox-nano.onnx，随包分发），
 * 见 docs/AI视觉识别.md 的集成测试说明；这里不联网、不依赖模型文件。
 */
class OnnxVisionDetectorTest {

    @Test
    void missingModelFailsFast(@TempDir Path tmp) {
        DetectorConfig config = new DetectorConfig("missing", tmp.resolve("nope.onnx").toString(),
                0.5f, 64, List.of("person"));
        assertThrows(VisionException.class, () -> new OnnxVisionDetector(config));
    }

    @Test
    void blankConfigFailsFast() {
        assertThrows(VisionException.class,
                () -> new OnnxVisionDetector(new DetectorConfig("", "", 0.5f, 0, List.of())));
    }

    @Test
    void nullConfigFailsFast() {
        assertThrows(VisionException.class, () -> new OnnxVisionDetector(null));
    }
}
