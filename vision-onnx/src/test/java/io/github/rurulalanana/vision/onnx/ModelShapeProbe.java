package io.github.rurulalanana.vision.onnx;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 一次性探针：打印模型的输入/输出张量形状。
 *
 * <p>加新模型时先跑它，确认 {@code format} 该填哪个枚举、{@code inputSize} 填多少。
 * 不是常驻测试（没有断言），留着是因为「猜形状」踩过太多次坑。
 */
class ModelShapeProbe {

    @Test
    void printShapes() throws Exception {
        String file = System.getProperty("probe.model", "models/fire_smoke_yolov8n.onnx");
        Path p = Path.of(file);
        assumeTrue(Files.isRegularFile(p), "模型不存在: " + p.toAbsolutePath());

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        try (OrtSession session = env.createSession(p.toString(), new OrtSession.SessionOptions())) {
            System.out.println("=== MODEL: " + p.getFileName() + " ===");
            for (Map.Entry<String, NodeInfo> e : session.getInputInfo().entrySet()) {
                TensorInfo ti = (TensorInfo) e.getValue().getInfo();
                System.out.println("INPUT  " + e.getKey()
                        + " shape=" + java.util.Arrays.toString(ti.getShape())
                        + " type=" + ti.type);
            }
            for (Map.Entry<String, NodeInfo> e : session.getOutputInfo().entrySet()) {
                TensorInfo ti = (TensorInfo) e.getValue().getInfo();
                System.out.println("OUTPUT " + e.getKey()
                        + " shape=" + java.util.Arrays.toString(ti.getShape())
                        + " type=" + ti.type);
            }
        }
    }
}
