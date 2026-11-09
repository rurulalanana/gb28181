package io.github.rurulalanana.vision;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 检测器注册表：按 {@code modelId} 取实现。
 *
 * <p><b>业务层只按 modelId 取，不 import 具体类</b> —— 换模型只改配置，
 * 不改代码。实现用 {@link Supplier} 懒加载：没用到的模型不初始化
 * （ONNX 会话初始化要读模型文件，启动时全量加载会拖慢启动）。
 *
 * <p>线程安全：注册在启动期完成，查询在运行期并发发生。
 */
public final class DetectorRegistry {

    private final Map<String, Supplier<VisionDetector>> factories = new ConcurrentHashMap<>();
    private final Map<String, VisionDetector> instances = new ConcurrentHashMap<>();

    /** 注册一个模型（重复注册以最后一次为准）。 */
    public void register(String modelId, Supplier<VisionDetector> factory) {
        if (modelId == null || modelId.isBlank() || factory == null) {
            return;
        }
        factories.put(modelId, factory);
        instances.remove(modelId);
    }

    /** 按 modelId 取检测器（懒初始化，单例）。未注册返回 {@code null}。 */
    public VisionDetector get(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return null;
        }
        Supplier<VisionDetector> factory = factories.get(modelId);
        if (factory == null) {
            return null;
        }
        return instances.computeIfAbsent(modelId, k -> factory.get());
    }

    /** 是否已注册。 */
    public boolean contains(String modelId) {
        return modelId != null && factories.containsKey(modelId);
    }

    /** 已注册的 modelId 列表。 */
    public List<String> modelIds() {
        return List.copyOf(factories.keySet());
    }

    /** 关闭全部已初始化的检测器（进程退出时调用）。 */
    public void closeAll() {
        for (VisionDetector d : instances.values()) {
            try {
                d.close();
            } catch (RuntimeException ignored) {
                // 关闭失败不影响退出
            }
        }
        instances.clear();
    }
}
