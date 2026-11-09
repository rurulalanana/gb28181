package io.github.rurulalanana.vision;

import java.util.List;

/**
 * 视觉检测器（模型层的唯一入口）。
 *
 * <p><b>契约只有一条</b>：给一帧，还一批框。同步、纯函数式 —— 不读数据库、
 * 不发网络、不写文件。实现层（ONNX / TensorRT / 云端 API）只管把这一条做好。
 *
 * <p><b>为什么同步而不是回调</b>：流水线（vision-pipeline）负责调度、节流与背压；
 * 检测器只负责「这一帧算多久」。把调度塞进检测器会让它无法被单独测试与复用。
 *
 * <p><b>为什么返回空列表而不是 null</b>：调用方不用判空；「没检出」与「出错」
 * 用异常区分（出错抛 {@link VisionException}）。
 */
public interface VisionDetector extends AutoCloseable {

    /** 本检测器对应的模型标识（注册表键）。 */
    String modelId();

    /** 本检测器支持的标签（稳定字符串）。 */
    List<String> labels();

    /**
     * 检测一帧。
     *
     * @param frame 待检测帧
     * @return 检测结果（可能为空列表，永不返回 null）
     * @throws VisionException 推理失败（模型缺失、解码失败、引擎异常）
     */
    List<Detection> detect(Frame frame);

    /** 释放原生资源（ONNX 会话等）。默认空实现。 */
    @Override
    default void close() {
    }
}
