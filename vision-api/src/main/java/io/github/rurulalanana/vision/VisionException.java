package io.github.rurulalanana.vision;

/**
 * 视觉推理异常（模型缺失、解码失败、引擎异常）。
 *
 * <p>用运行时异常：检测是流水线里高频调用的路径，检查异常会让每个调用点
 * 都要写样板代码；流水线在线程边界统一捕获即可。
 */
public class VisionException extends RuntimeException {

    public VisionException(String message) {
        super(message);
    }

    public VisionException(String message, Throwable cause) {
        super(message, cause);
    }
}
