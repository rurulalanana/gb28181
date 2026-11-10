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
     * 每个通道用的模型 id（{@code models[].id}）。
     *
     * <p>不配置时用第一个模型。
     *
     * <p><b>注意</b>：这里只是「新建通道时的默认值」。具体某一路用哪个模型，
     * 存在 {@code gb_vision_channel.model_id} 里 —— 通道配置已经全部移到平台界面，
     * 一个通道可以只跑人形、另一个跑车辆，靠的就是那一列。
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

    /**
     * 场景清单（模型 + 标签过滤）。
     *
     * <p>不配置时由 {@link #models} 自动派生：每个模型生成一个「全目标检测」场景。
     * 配了就以配置为准 —— 这样才能用同一份权重派生出
     * 「人形 / 车辆 / 全目标」多个可选项。
     */
    private List<Scenario> scenarios = new ArrayList<>();

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

        /**
         * 展示名（界面上给人看）。
         *
         * <p>不填时用 {@link #id}。{@code id} 是稳定键（存库、存日志），
         * 不该随文案变化；而界面上「yolox-nano」对运维没有意义，
         * 显示成「通用检测（小模型）」才看得懂。
         */
        private String displayName = "";

        /**
         * 用途分类：决定这个模型适合什么场景，界面按它分组。
         *
         * <p>取值见 {@code VisionModelRegistry.CATEGORY_*}：
         * {@code general}（通用目标）/ {@code person}（人形）/ {@code vehicle}（车辆）/
         * {@code face}（人脸）/ {@code helmet}（安全帽）/ {@code fire}（烟火）。
         *
         * <p><b>为什么要有这个字段</b>：模型的差别不只是「快慢」，而是
         * <b>能检出什么</b>。只列一堆文件名，运维不知道该选哪个；
         * 按用途分组后「我要检测安全帽」能直接找到。
         */
        private String category = "general";

        /**
         * 只关心这些标签（空 = 全部）。
         *
         * <p>这是<b>按模型</b>过滤，不是全局过滤。同一个 COCO 模型，
         * 「人形检测」场景配 {@code [person]}、「车辆检测」场景配
         * {@code [car,truck,bus,motorcycle]} —— 用同一份权重得到两种用途，
         * 不需要两个模型文件。
         *
         * <p>所以下面 {@link #getScenarioLabels()} 用「模型 id → 标签」的映射
         * 允许一个模型派生多个「场景」，界面上表现为多个可选项。
         */
        private List<String> onlyLabels = new ArrayList<>();

        /** 单帧推理耗时参考（毫秒，界面上标注，便于运维估算 CPU）。 */
        private int estimatedMsPerFrame = 0;
    }

    /**
     * 「场景」：一个模型 + 一组标签过滤。
     *
     * <h3>为什么要引入这个概念</h3>
     * 用户要的是「人形检测 / 车辆检测 / 人脸检测」这些<b>能力</b>，
     * 而不是「yolox-nano / yolox-tiny」这些<b>文件名</b>。
     * 同一个 COCO 权重，只要换标签过滤就是另一种能力：
     * <pre>
     *   yolox-nano + [person]                        → 人形检测
     *   yolox-nano + [car,truck,bus,motorcycle]      → 车辆检测
     *   yolox-nano + []                              → 全目标检测
     * </pre>
     * 所以「场景」是模型之上的一层薄封装，配置里写几个场景，
     * 界面上就出现几个可选项，<b>不用为每种能力准备一份模型文件</b>。
     *
     * @param id       场景 id（存库用，稳定）
     * @param modelId  用哪个模型
     * @param name     展示名
     * @param category 用途分类
     * @param labels   只保留这些标签（空 = 全部）
     * @param description 说明文字
     * @param estimatedMsPerFrame 单帧推理耗时参考（毫秒）
     */
    public record Scenario(String id, String modelId, String name, String category,
                           List<String> labels, String description, int estimatedMsPerFrame) {
    }
}
