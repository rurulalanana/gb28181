package io.github.rurulalanana.starter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 视觉识别（AI）配置（{@code vision.*}）。
 *
 * <h3>为什么是顶层 {@code vision} 而不是 {@code gb28181.vision}</h3>
 * 视觉识别是<b>与国标链路并列的能力</b>：它读的是 ZLM 的输出流，
 * 不认识 SIP、不用 MANSCDP、也不需要平台 20 位编码。
 * 挂在 {@code gb28181} 下会暗示「这是国标子项」，语义是错的。
 *
 * <h3>默认关闭</h3>
 * 默认 {@code enabled=false}。原因不是「功能不完整」，而是<b>资源</b>：
 * 每路检测要起一个常驻 ffmpeg + 跑 ONNX 推理，8 核机器上 6~8 路就到上限。
 * 让运维显式打开，比默认跑满 CPU 更安全。
 */
@Data
@ConfigurationProperties(prefix = "vision")
public class VisionProperties {

    /**
     * 总开关。
     *
     * <p>关闭时不会加载任何模型、不会起任何线程，也不影响国标/大华/海康链路。
     */
    private boolean enabled = false;

    /**
     * 模型文件目录。
     *
     * <p>默认 {@code models}（相对于进程工作目录，线上是 {@code /opt/gb28181/models}）。
     * 模型<b>随包分发</b>，禁止运行期联网下载 —— 服务器通常不出网。
     */
    private String modelDir = "models";

    /**
     * 启用的模型清单（按 {@code id} 引用）。
     *
     * <p><b>模型独立维护</b>：这里只写「用哪个模型、文件叫什么、什么输出格式」，
     * 代码里不出现任何具体模型类。换模型 = 改这段配置 + 放文件，
     * 不需要重新编译。
     */
    private List<Model> models = new ArrayList<>();

    /**
     * 启用检测的通道（按通道编码，如 {@code 10001-1}）。
     *
     * <p>不配置时不检测任何通道。之所以不用「全部通道自动开启」：
     * 每个通道都要常驻一路 ffmpeg，无脑全开会在几分钟内把 CPU 打满。
     */
    private List<String> channels = new ArrayList<>();

    /**
     * 每个通道用的模型 id（{@code models[].id}）。
     *
     * <p>不配置时用第一个模型。
     */
    private String defaultModel = "";

    /**
     * 推理目标帧率（每路）。
     *
     * <h3>为什么是 4 而不是 1 或 2</h3>
     * 这个值直接决定「框跟随画面」的跟手程度：
     * <ul>
     *   <li>1 fps —— 人走动时框明显「跳」，间隔一秒像在逐格播放</li>
     *   <li>2 fps —— 勉强跟手，快速移动仍有可感知的滞后</li>
     *   <li>4 fps —— 框贴合得比较自然，平均延迟约 125ms</li>
     * </ul>
     *
     * <p>实测代价很小：8 核机器跑 3 路 1280×720，推理线程只占 15% CPU
     * （load average 0.66）。关键在于取帧侧已经在 ffmpeg 里缩到 416 宽
     * （见 {@code ZlmFrameSource}），单帧推理只要 60~90ms。
     *
     * <p>再往上加收益递减：前端轮询周期是按这个值算的
     * （{@code 1000/fps} 毫秒），fps=4 时轮询 250ms 一次，
     * 已经接近「人眼不易察觉」的边界。要更低延迟得改成 WebSocket 推送。
     */
    private double fps = 4.0;

    /** 单路队列容量：满了丢最旧的帧，不堆积延迟。 */
    private int queueCapacity = 4;

    /** 推理线程数。CPU 推理建议不超过核数的一半，否则会拖慢信令与转发。 */
    private int workerThreads = 2;

    /** 置信度阈值（0~1）。低于此值的检测直接丢弃。 */
    private float confidenceThreshold = 0.45f;

    /** 事件触发所需的连续帧数（去抖）。 */
    private int minEventFrames = 3;

    /** 事件触发所需的最短持续时长（毫秒，去抖）。 */
    private long minEventMillis = 2000;

    /** 事件冷却时长（毫秒）：同一通道同一标签触发后，多久内不重复告警。 */
    private long eventCooldownMillis = 30_000;

    /** 是否把事件写入 {@code gb_alarm}（告警中心可见）。 */
    private boolean raiseAlarm = true;

    /** 是否保存事件截图。 */
    private boolean saveSnapshot = true;

    /** 只关心这些标签（空表示全部）。用于「只检测人形」。 */
    private List<String> labels = new ArrayList<>();

    /**
     * 一个模型的声明。
     *
     * <p>字段刻意与 {@code DetectorConfig} 对应但<b>不直接复用它</b>：
     * 配置类是 Spring 的（可变、可热更新），契约是模型的（不可变 record）。
     * 直接复用会让「配置文件格式」和「模型接口」绑死。
     */
    @Data
    public static class Model {

        /** 模型标识（注册表键，也是 {@code defaultModel} 引用的名字）。 */
        private String id;

        /** 模型文件名（相对 {@code vision.model-dir}）。 */
        private String file;

        /**
         * 输出格式：{@code YOLO_CHANNEL_FIRST} / {@code YOLO_ANCHOR_FIRST} / {@code YOLOX}。
         *
         * <p>见 {@code DetectorConfig.OutputFormat} 的表格 —— 选错会导致
         * 「框全塌到左上角」或「置信度大于 1」。
         */
        private String format = "YOLOX";

        /** 模型输入边长（正方形）。YOLOX-nano 是 416，YOLOv8n 是 640。 */
        private int inputSize = 416;

        /**
         * 预处理口径：{@code yolox} 或 {@code yolo}。
         *
         * <p>必须与 {@link #format} 配对：YOLOX 用「0~255 + BGR + letterbox」，
         * YOLOv8 用「/255 + RGB + 拉伸」。配错的症状是分数全挤在 0.31~0.34。
         */
        private String preprocess = "yolox";

        /** 类别表。留空时用内置的 COCO 80 类。 */
        private List<String> labels = new ArrayList<>();
    }
}
