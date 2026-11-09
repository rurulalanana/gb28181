package io.github.rurulalanana.dahua.ext;

import com.fasterxml.jackson.databind.JsonNode;
import com.netsdk.lib.NetSDKLib;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import io.github.rurulalanana.dahua.internal.DahuaJson;
import io.github.rurulalanana.dahua.internal.DahuaSdk;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 大华 NetSDK 的<b>扩展能力</b>封装（PRD 七模块里 SPI 契约没覆盖的部分）。
 *
 * <h3>为什么单独一层，不塞进 {@code DeviceAccessProvider}</h3>
 * 契约层是<b>厂商无关</b>的：只有通道、流地址、云台方向这些各家都有的概念。
 * 预置位号段、JSON 配置项名、报警事件码全是大华私有语义，塞进去会让契约
 * 变成"大华 SDK 的形状"，将来接海康时只能再复制一套。
 *
 * <p>所以分工是：
 * <ul>
 *   <li>{@code DeviceAccessProvider} —— 跨厂商的最小公共集（已有）</li>
 *   <li>本类 —— 大华专属能力，调用方明知自己在用大华时才用</li>
 *   <li>平台 Service 层 —— 把本类的返回翻译成统一 DTO、落库、做业务规则</li>
 * </ul>
 *
 * <h3>能力边界（实测口径）</h3>
 * 本类只保证"发得出去、错误能读懂"，<b>不保证型号支持</b>：
 * 设备不支持时 SDK 返回 {@code false}，这里统一抛
 * {@link DahuaSdkException} 并在消息里带 SDK 错误码，
 * 由上层决定"提示不支持"还是"报故障"。
 */
public final class DahuaExtAccess {

    private static final Charset GBK = Charset.forName("GBK");

    /** 单次抓图允许的最大字节数（4K JPEG 通常 < 2 MB）。 */
    private static final int SNAP_MAX_BYTES = 8 * 1024 * 1024;

    /** JSON 配置读取缓冲。大华配置项 JSON 普遍在 10 KB 量级，64 KB 足够。 */
    private static final int CFG_BUF_BYTES = 64 * 1024;

    private static final AtomicInteger CMD_SERIAL = new AtomicInteger(1);

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DahuaExtAccess.class);

    // ==================== 模块一：局域网设备发现 ====================

    /**
     * 局域网内发现到的一台设备。
     *
     * @param ip       设备 IP
     * @param sdkPort  SDK 端口（默认 37777）
     * @param httpPort Web 端口（默认 80）
     * @param serial   序列号 —— 与主动注册回调里的 SN 是同一个值，可用于绑定
     * @param model    型号
     * @param firmware 固件版本
     * @param channels 视频输入通道数
     */
    public record Discovered(String ip, int sdkPort, int httpPort, String serial,
                             String model, String firmware, int channels) {
    }

    /**
     * 组播/广播搜索局域网设备。
     *
     * <p><b>只在同一广播域有效</b>：跨三层网络收不到应答，这是协议本身决定的，
     * 不是实现缺陷。跨网段请改用「按 IP 段搜索」或让设备主动注册。
     *
     * @param localIp   本机用于搜索的网卡地址；{@code null} 表示由 SDK 自选
     * @param timeoutMs 收集时长（一般 3~5 秒；太短会漏掉慢设备）
     * @return 发现列表（可能为空，不返回 {@code null}）
     */
    public List<Discovered> discoverLan(String localIp, long timeoutMs) {
        NetSDKLib sdk = DahuaSdk.get().sdk();
        List<Discovered> found = new ArrayList<>();
        if (sdk == null) {
            return found;
        }

        NetSDKLib.fSearchDevicesCB callback = (pDevNetInfo, pUserData) -> {
            try {
                NetSDKLib.DEVICE_NET_INFO_EX info = new NetSDKLib.DEVICE_NET_INFO_EX();
                copyInto(info, pDevNetInfo);
                // 大华的字符串字段是定长 GBK，未填满的部分是 0
                String serial = text(info.szSerialNo);
                if (serial.isEmpty()) {
                    return;
                }
                synchronized (found) {
                    for (Discovered d : found) {
                        if (d.serial().equals(serial)) {
                            return; // 同一台设备会重复应答，按 SN 去重
                        }
                    }
                    found.add(new Discovered(
                            text(info.szIP),
                            info.nPort,
                            info.nHttpPort & 0xFFFF,
                            serial,
                            text(info.szDetailType),
                            text(info.szDevSoftVersion),
                            info.wVideoInputCh & 0xFFFF));
                }
            } catch (Throwable ignored) {
                // 回调线程里绝不能抛：JNA 会把异常吞掉并可能中断后续回调
            }
        };

        NetSDKLib.LLong handle = sdk.CLIENT_StartSearchDevices(callback, null, localIp);
        if (handle == null || handle.longValue() == 0) {
            throw new DahuaSdkException("局域网搜索启动失败",
                    sdk.CLIENT_GetLastError(), "检查本机防火墙是否放行组播/广播");
        }
        try {
            Thread.sleep(Math.max(500L, timeoutMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            sdk.CLIENT_StopSearchDevices(handle);
        }
        synchronized (found) {
            return new ArrayList<>(found);
        }
    }

    // ==================== 模块四：抓图 ====================

    /**
     * 请求设备抓一张图（同步，直接拿到 JPEG 字节）。
     *
     * <p>与 {@code CLIENT_SnapPictureEx} 的区别：那个是"发指令 + 等回调"，
     * 回调是全局的、要靠序列号配对；本接口同步返回，调用方不用管回调线程。
     * 支持并发调用。
     *
     * @param session 已登录会话
     * @param channel 视频通道号（从 1 起）
     * @return JPEG 字节
     * @throws DahuaSdkException 设备拒绝（多为通道不支持抓图）或超时
     */
    public byte[] snapshot(DahuaSdk.Session session, int channel) {
        NetSDKLib sdk = requireSdk();
        NetSDKLib.NET_IN_MANUAL_SNAP in = new NetSDKLib.NET_IN_MANUAL_SNAP();
        in.nChannel = channel;
        in.nCmdSerial = nextSerial();
        in.write();

        Memory buffer = new Memory(SNAP_MAX_BYTES);
        NetSDKLib.NET_OUT_MANUAL_SNAP out = new NetSDKLib.NET_OUT_MANUAL_SNAP();
        out.nMaxBufLen = SNAP_MAX_BYTES;
        out.pRcvBuf = buffer;
        out.write();

        boolean ok = sdk.CLIENT_ManualSnap(session.handle(), in, out, 10_000);
        if (!ok) {
            throw new DahuaSdkException("抓图失败（通道 " + channel + "）",
                    sdk.CLIENT_GetLastError(), "确认通道号是视频通道且型号支持抓图");
        }
        out.read();
        if (out.nRetBufLen <= 0 || out.pRcvBuf == null) {
            throw new DahuaSdkException("抓图返回空数据", 0, "设备未产生图像");
        }
        return out.pRcvBuf.getByteArray(0, out.nRetBufLen);
    }

    /**
     * 抓图并落盘。
     *
     * @param dir     目标目录（不存在会自动创建）
     * @param channel 视频通道号
     * @return 写入的文件路径
     */
    public Path snapshotToFile(DahuaSdk.Session session, int channel, Path dir) {
        byte[] jpeg = snapshot(session, channel);
        try {
            Files.createDirectories(dir);
            String name = "snap-" + session.serial() + "-ch" + channel + "-"
                    + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
                    + ".jpg";
            Path file = dir.resolve(name);
            Files.write(file, jpeg);
            return file;
        } catch (Exception e) {
            throw new DahuaSdkException("抓图落盘失败: " + e.getMessage(), 0, "检查目录权限与磁盘空间");
        }
    }

    // ==================== 模块六：配置读写（19 项图像参数的通用入口）====================

    /**
     * 读一个 JSON 配置项，返回<b>完整响应包</b>。
     *
     * <p>大华的图像参数没有 19 个独立接口 —— 全部落在少数几个 JSON 配置项里
     * （如 {@code "VideoInOptions"}、{@code "VideoInExposure"}）。
     *
     * <p>设备返回的是完整响应包，形如：
     * <pre>
     * {"id":3348,"params":{"table":{业务字段}},"result":true,"session":1014386800}
     * </pre>
     * 业务字段在 {@code params.table} 里。想只拿业务层用
     * {@link #readConfigBusiness}。
     *
     * @param command 配置项命令名，如 {@code "VideoInOptions"}
     * @param channel 通道号
     * @return 完整响应包 JSON（设备返回空时为空串）
     */
    public String readConfig(DahuaSdk.Session session, String command, int channel) {
        NetSDKLib sdk = requireSdk();
        byte[] buffer = new byte[CFG_BUF_BYTES];
        IntByReference error = new IntByReference(0);
        boolean ok = sdk.CLIENT_GetNewDevConfig(session.handle(), command, channel,
                buffer, buffer.length, error, 8_000, null);
        if (!ok) {
            throw new DahuaSdkException("读取配置失败: " + command,
                    error.getValue(), "该型号可能不支持此配置项");
        }
        return trimNul(buffer);
    }

    /**
     * 读配置并直接返回<b>业务层</b>（{@code params.table} 的内容）。
     *
     * <p>与 {@link #readConfig} 的区别就是少了一层外壳，读→改→写时更不容易搞错：
     * 拿到什么就喂回 {@link #writeConfig}，不会把 {@code id}/{@code session}
     * 一起写进设备。
     *
     * @return 业务层 JSON 对象文本；读不到时为空串
     */
    public String readConfigBusiness(DahuaSdk.Session session, String command, int channel) {
        return businessLayer(readConfig(session, command, channel));
    }

    /**
     * 读配置项并展平成<b>与 CGI 完全相同的键路径</b>（{@code PtzSpeed[0].PtzSpeed=3}）。
     *
     * <h3>为什么要这个「多余」的格式转换</h3>
     * 平台解析云台配置的代码（{@code DahuaPtzConfigService} 的 12 个 parse 方法）
     * 全部按 CGI 的扁平键路径写的。走 SDK 读回来是 JSON，
     * 直接改解析逻辑要动 12 处、且「CGI 能读 / SDK 能读」两种部署会各自暴露不同 bug。
     *
     * <p>把 SDK 的 JSON <b>转成 CGI 的形状</b>只需要一处转换，
     * 解析逻辑零改动，两条通路的结果天然一致 —— 这是本方法的全部价值。
     *
     * <h3>键路径规则（与设备 CGI 实测一致）</h3>
     * <pre>
     *   {"PtzSpeed":[{"PtzSpeed":3}]}   → PtzSpeed[0].PtzSpeed=3
     *   {"PtzTour":[{"Presets":[[..]]}]} → PtzTour[0].Presets[0][0]=-1
     * </pre>
     * 即：<b>对象键在前、数组下标紧随其后</b>。业务层若是纯数组（没有外层名字），
     * 用 {@code command} 作名字包一层，保证键始终以配置项名开头。
     *
     * <h3>⚠️ SDK 比 CGI 少一层「通道」维度</h3>
     * {@code CLIENT_GetNewDevConfig} 传入 {@code channel} 后，设备返回的 JSON
     * <b>已经按通道过滤</b>，数组下标直接就是业务序号。实测对照（同一台 IPC）：
     * <pre>
     *   CGI : PtzTour[0][0].Name=巡航群組1    AutoScan[0][0].LeftEnable=true
     *   SDK : PtzTour[0].Name=巡航群組1       AutoScan[0].LeftEnable=true
     *   CGI : PtzSpeed[0].PtzSpeed=1          AutoPan[0].PanSpeed=3
     *   SDK : PtzSpeed=1（裸字段）            PanSpeed=3（裸字段）
     * </pre>
     * 而 {@code DahuaPtzConfigService} 的 12 个 parse 方法按 <b>CGI 形状</b>写，
     * 于是 SDK 通路「读到了 3168 个键却解析出空列表」—— 界面整片空白且无任何报错。
     * 这里把通道维度<b>补回去</b>，两条通路结果才真正等价。
     *
     * @param command 配置项命令名（同时用作纯数组业务层的外层名字）
     * @param channel 通道号
     * @return 扁平键路径 → 值；读不到时返回空 Map（不抛异常）
     */
    public java.util.Map<String, String> readConfigFlat(DahuaSdk.Session session,
                                                       String command, int channel) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        String raw;
        try {
            raw = readConfig(session, command, channel);
        } catch (DahuaSdkException e) {
            /*
             * 型号不支持该配置项：交给调用方决定是否回退 HTTP。
             * 记一条日志而不是静默返回空 —— 否则上层只看到「读到 0 个键」，
             * 分不清「设备没有这项」和「读失败了」。
             */
            log.info("SDK 读配置项 {} 失败: err=0x{} {}", command,
                    String.format("%08X", e.sdkError()), e.getMessage());
            return out;
        }
        if (raw == null || raw.isBlank()) {
            log.info("SDK 读配置项 {} 返回空串", command);
            return out;
        }
        com.fasterxml.jackson.databind.JsonNode business =
                io.github.rurulalanana.dahua.internal.DahuaJson.business(raw);
        if (business == null || business.isMissingNode() || business.isNull()) {
            log.info("SDK 读配置项 {} 的 params.table 为空: {}", command, abbreviate(raw));
            return out;
        }
        // 纯数组业务层没有外层名字，用配置项名包一层，与 CGI 的 PtzSpeed[0] 对齐
        boolean arrayShape = business.isArray();
        String root = arrayShape ? command : "";
        flatten(root, business, out);
        /*
         * 形状诊断：设备返回的 params.table 有时是 {"PtzTour":[...]}、
         * 有时是裸数组 [...]、有时还多包一层 {"PtzTour":{"PtzTour":[...]}}。
         * 包错一层，parse* 的键路径就全对不上，界面整片空白且无任何报错。
         */
        if (out.isEmpty()) {
            log.info("SDK 读配置项 {} 展平后 0 个键，原始形状={} 内容={}",
                    command, business.getNodeType(), abbreviate(raw));
            return out;
        }
        return withChannelDimension(out, command, channel, arrayShape);
    }

    /**
     * 把 SDK 的键路径补回「通道」维度，使其与 CGI 形状一致。
     *
     * <h3>为什么必须补</h3>
     * {@code CLIENT_GetNewDevConfig} 传入 {@code channel} 后，设备返回的 JSON
     * <b>已经按通道过滤</b>，比 CGI 少一层。实测对照（同一台 IPC，fw 3.201.0000007.1.R）：
     * <pre>
     *   CGI : PtzTour[0][0].Name=巡航群組1     SDK : PtzTour[0].Name=巡航群組1
     *   CGI : AutoScan[0][0].LeftEnable=true   SDK : AutoScan[0].LeftEnable=true
     *   CGI : PtzSpeed[0].PtzSpeed=1           SDK : PtzSpeed=1（对象，裸字段）
     *   CGI : AutoPan[0].PanSpeed=3            SDK : PanSpeed=3（对象，裸字段）
     * </pre>
     *
     * <h3>为什么用「JSON 节点类型」而不是「猜键名」</h3>
     * 曾经试过看首段数字是否等于通道号来判断「是不是已经带通道维度」，
     * 结果是<b>歧义无解</b>：{@code PtzTour[0].Name} 里的 {@code 0} 是<b>巡航线号</b>，
     * 恰好等于通道号 0，就被误判成「已带通道」而跳过补维 ——
     * 于是<b>第 1 条巡航组整个消失</b>，第 2 条及以后都正常
     * （它们的首段 1、2… 不等于通道号，照常补维）。
     * 这种「只有第一条丢」的现象极难从界面看出来。
     *
     * <p>{@code params.table} 的节点类型是<b>确定的</b>：
     * 数组型（{@code [{...}]}）说明外层名字由 {@code flatten} 的 root 补上，
     * 首段是业务序号；对象型（{@code {"PtzSpeed":1}}）说明展平后只剩字段名。
     * 两者缺的维度不同，按类型分派即可，不需要任何猜测。
     *
     * @param arrayShape {@code params.table} 是否为数组
     */
    public static java.util.Map<String, String> withChannelDimension(
            java.util.Map<String, String> flat, String command, int channel, boolean arrayShape) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>(flat.size() + 8);
        String at = "[" + channel + "]";
        if (arrayShape) {
            // 数组型：Name[idx]... → Name[ch][idx]...
            String head = command + "[";
            for (java.util.Map.Entry<String, String> e : flat.entrySet()) {
                String key = e.getKey();
                if (key.startsWith(head)) {
                    out.put(command + at + key.substring(command.length()), e.getValue());
                } else {
                    // 不该出现（数组型展平后一定以配置项名开头），原样保留便于排查
                    out.put(key, e.getValue());
                }
            }
            return out;
        }
        // 对象型：裸字段 → Name[ch].Field
        for (java.util.Map.Entry<String, String> e : flat.entrySet()) {
            out.put(command + at + "." + e.getKey(), e.getValue());
        }
        return out;
    }

    /** 日志用的截断。 */
    private static String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 400 ? s : s.substring(0, 400) + "...(共 " + s.length() + " 字符)";
    }

    /**
     * 递归展平 JSON：对象键在前、数组下标紧随其后。
     *
     * <p>包级可见是为了单测（{@code ConfigFlatTest}）能直接喂 JSON 校验键路径 ——
     * 这套键路径必须与 CGI 的输出逐字符一致，否则 12 个 parse 方法会集体读不到值。
     */
    public static void flatten(String path, com.fasterxml.jackson.databind.JsonNode node,
                        java.util.Map<String, String> out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            java.util.Iterator<java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>>
                    it = node.fields();
            while (it.hasNext()) {
                java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> e = it.next();
                flatten(path.isEmpty() ? e.getKey() : path + "." + e.getKey(), e.getValue(), out);
            }
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flatten(path + "[" + i + "]", node.get(i), out);
            }
            return;
        }
        // 叶子：布尔统一成 true/false，与 CGI 一致
        out.put(path, node.asText());
    }

    /**
     * 写一个 JSON 配置项。
     *
     * <p>参数可以是<b>两种形态之一</b>，本方法会自动归一：
     * <ul>
     *   <li>完整响应包（{@link #readConfig} 的返回值）—— 自动剥出 {@code params.table}</li>
     *   <li>业务层对象 —— 直接使用</li>
     * </ul>
     * 内部统一包装成设备要求的 {@code {"params":{"table":<业务层>}}} 再下发。
     *
     * <h3>为什么必须做这个归一（血的教训）</h3>
     * 设备只认 {@code params.table} 那一层。把整包（含 {@code id}/{@code result}/
     * {@code session}）原样写回去，设备会把这些字段<b>当成业务字段存进 table</b>，
     * 于是每写一次就多嵌一层 —— 实测连续误写后 {@code TimeSection} 在 JSON 里
     * 出现 5 次，配置彻底不可用，最后只能 {@code CLIENT_DeleteDevConfig} 删掉重建。
     *
     * <p><b>而且写入是 merge 语义</b>：payload 里没出现的字段保留旧值，
     * 所以嵌进去的垃圾字段<b>写不回去也删不掉</b>。这个坑只能靠不写错来避免。
     *
     * <p>另外大华是<b>整段覆盖</b>业务字段，只传改动的字段会把其余字段清零。
     * 正确姿势是「读全量 → 改字段 → 写回」。
     *
     * @param command 配置项命令名
     * @param channel 通道号
     * @param json    完整响应包 或 业务层对象
     * @return 是否需要重启设备才生效
     */
    public boolean writeConfig(DahuaSdk.Session session, String command, int channel, String json) {
        return writeRaw(session, command, channel, wrapForWrite(json));
    }

    /**
     * 把一段字符串<b>原样</b>下发给 {@code CLIENT_SetNewDevConfig}，不做任何包装。
     *
     * <p><b>为什么需要它</b>：不同配置项对 payload 形状的要求并不一致 ——
     * 对象型（{@code PtzSpeed} / {@code AutoPan}）与数组型（{@code PtzTour} /
     * {@code PatternPlayDetail}）实测接受的外壳不同，
     * 多包一层 {@code params.table} 时设备<b>照样返回 true</b>，
     * 但值没变、而且把 {@code params} 当成业务字段存下来（成立
     * {@code PtzSpeed[0].params.table.PtzSpeed} 这种垃圾键，merge 语义下删不掉）。
     *
     * <p>所以「哪种形状才对」必须靠实测逐个确认，
     * 而实测需要一个不做假设的入口。业务代码请用 {@link #writeConfig}。
     *
     * @param payload 已是最终下发的字节内容（本方法不再包装）
     * @return 是否需要重启设备才生效
     */
    public boolean writeRaw(DahuaSdk.Session session, String command, int channel, String payload) {
        NetSDKLib sdk = requireSdk();
        /*
         * 必须写 UTF-8，不能写 GBK。
         *
         * 实测（DH-SD49425DB-HNY-GQ-EAU / fw 3.201.0000007.1.R）：
         * 设备自己的 Web 界面写巡航组名走 configManager.setTemporaryConfig，
         * 落盘的字节是 UTF-8 —— 读 PtzTour[0][2].Name 得到
         *   E5 B7 A1 E8 88 AA E7 BE A4 E7 B5 84  ("巡航群組3")
         * 而平台早期这里用 GBK 写，同一字段落盘成
         *   D1 B2 BA BD C8 BA BD                    ("巡航群組1" 的 GBK)
         * 设备界面按 UTF-8 解，于是显示成「Ѳ��Ⱥ�M1」这种乱码。
         *
         * 判据很硬：GBK 字节几乎不可能通过严格 UTF-8 校验，
         * 而 UTF-8 字节按 GBK 解一定得到乱码。所以「设备里存的是 UTF-8」
         * 与「设备界面按 UTF-8 显示」是同一件事，写入必须对齐。
         *
         * 对纯 ASCII（绝大多数字段）两种编码字节完全相同，不受影响。
         */
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        IntByReference error = new IntByReference(0);
        IntByReference restart = new IntByReference(0);
        boolean ok = sdk.CLIENT_SetNewDevConfig(session.handle(), command, channel,
                data, data.length, error, restart, 8_000);
        if (!ok) {
            throw new DahuaSdkException("写入配置失败: " + command,
                    error.getValue(), "确认字段名与取值范围符合该型号");
        }
        return restart.getValue() != 0;
    }

    /**
     * 从完整响应包里剥出 {@code params.table} 的业务层对象文本。
     *
     * <p>已经传入业务层时按语义原样返回（重新序列化，内容不变），
     * 所以这个方法是幂等的，可以无脑套用。实现见 {@link DahuaJson#businessLayer}。
     *
     * @param json 完整响应包或业务层
     * @return 业务层对象文本；输入为空或非法时返回空串
     */
    public static String businessLayer(String json) {
        return DahuaJson.businessLayer(json);
    }

    /** 把「完整响应包 或 业务层」统一包成设备要的 {@code {"params":{"table":…}}}。 */
    private static String wrapForWrite(String json) {
        try {
            return DahuaJson.wrapForWrite(json);
        } catch (IllegalArgumentException e) {
            throw new DahuaSdkException("写入配置失败：内容为空", 0,
                    "先读配置再改字段，不要凭空构造");
        }
    }

    // ==================== 模块三：预置点 ====================

    /** {@code NET_DEVSTATE_PTZ_PRESET_LIST}：获取当前云台的预置点列表。 */
    private static final int NET_DEVSTATE_PTZ_PRESET_LIST = 0x0057;

    /** 一次最多读多少个预置点。大华上限通常是 255/256，取 300 留余量。 */
    private static final int PTZ_PRESET_MAX = 300;

    /**
     * 预置点条目。
     *
     * @param index    预置点号（大华从 1 起）
     * @param name     设备上的名称；读不到时为 {@code null}
     * @param pan      水平坐标（0.1 度，0~3599）；读不到时 {@code null}
     * @param tilt     垂直坐标（0.1 度，-1800~1800）；读不到时 {@code null}
     * @param zoom     变倍位置（0~128）；读不到时 {@code null}
     * @param staySeconds 到位停留时间（秒）；仅 {@code PtzPreset} 配置项有此项
     */
    public record Preset(int index, String name, Integer pan, Integer tilt,
                         Integer zoom, int staySeconds) {

        /** 是否带坐标（能用于平台侧绝对定位兜底）。 */
        public boolean hasPosition() {
            return pan != null && tilt != null && zoom != null;
        }
    }

    /**
     * 列出设备上的预置点。
     *
     * <h3>三条路，按可靠性排序</h3>
     * <ol>
     *   <li><b>{@code CLIENT_QueryDevState(NET_DEVSTATE_PTZ_PRESET_LIST=0x57)}</b> ——
     *       真机验证可用，带<b>名称与坐标</b>。这是首选</li>
     *   <li>{@code CLIENT_GetConfig(NET_EM_CFG_PTZ_PRESET=1011)} —— 本机返回
     *       {@code 0x80000007}，部分型号支持</li>
     *   <li>{@code "PtzPreset"} JSON 配置项 —— 本机返回 {@code 0x80000018}（配置项不存在）</li>
     * </ol>
     *
     * <h3>为什么必须三条都试</h3>
     * 早期只用第 3 条，读不到就得出「设备不支持预置点」的<b>错误结论</b> ——
     * 而设备 Web 界面明明有「預置點1」，第 1 条也能读到。三条接口属于完全不同的体系，
     * 型号之间实现哪条完全不可预测。
     *
     * @return 预置点列表；三条都读不到时返回空列表（不抛异常，调用方按「无清单」处理）
     */
    public List<Preset> listPresets(DahuaSdk.Session session, int channel) {
        List<Preset> byState = listPresetsByState(session, channel);
        if (!byState.isEmpty()) {
            return byState;
        }
        List<Preset> byCfg = listPresetsByCfg(session, channel);
        if (!byCfg.isEmpty()) {
            return byCfg;
        }
        // 两条都空：可能是设备真的没有预置点，也可能是都不支持。由上层决定怎么呈现
        return List.of();
    }

    /**
     * 主路径：{@code CLIENT_QueryDevState(0x57)} 读预置点列表。
     *
     * <p>结构体数组必须分配在<b>原生内存</b>里（{@code Memory}），
     * SDK 会往那块内存写数据；直接把 Java 对象数组传进去拿不到结果。
     *
     * @return 预置点列表；接口不可用时返回空列表
     */
    public List<Preset> listPresetsByState(DahuaSdk.Session session, int channel) {
        NetSDKLib sdk = requireSdk();
        try {
            NetSDKLib.NET_PTZ_PRESET proto = new NetSDKLib.NET_PTZ_PRESET();
            proto.write();
            int unit = proto.size();
            Memory array = new Memory((long) unit * PTZ_PRESET_MAX);

            NetSDKLib.NET_PTZ_PRESET_LIST out = new NetSDKLib.NET_PTZ_PRESET_LIST();
            out.dwMaxPresetNum = PTZ_PRESET_MAX;
            out.dwRetPresetNum = 0;
            out.pstuPtzPorsetList = array;
            out.write();

            IntByReference ret = new IntByReference(0);
            boolean ok = sdk.CLIENT_QueryDevState(session.handle(),
                    NET_DEVSTATE_PTZ_PRESET_LIST,
                    out.getPointer(), out.size(), ret, 5_000);
            if (!ok) {
                int err = sdk.CLIENT_GetLastError();
                log.info("QueryDevState(0x57) 读取预置点失败: 通道={} 错误码=0x{}",
                        channel, String.format("%08X", err));
                return List.of();
            }
            out.read();
            int n = Math.min(out.dwRetPresetNum, PTZ_PRESET_MAX);
            if (n <= 0) {
                log.info("QueryDevState(0x57) 返回 0 个预置点: 通道={} dwRetPresetNum={}",
                        channel, out.dwRetPresetNum);
                return List.of();
            }

            List<Preset> result = new ArrayList<>(n);
            int skipped = 0;
            for (int i = 0; i < n; i++) {
                /*
                 * JNA 的 Structure.useMemory 是 protected，改走
                 * 「从大块内存拷出这段字节 → 写进单个结构体自己的内存 → read()」。
                 * 效果等价，且不依赖受保护 API。
                 */
                NetSDKLib.NET_PTZ_PRESET one = new NetSDKLib.NET_PTZ_PRESET();
                one.write();
                byte[] raw = array.getByteArray((long) i * unit, unit);
                one.getPointer().write(0, raw, 0, unit);
                one.read();
                /*
                 * 诊断：把 SDK 实际返回的索引与名称原始字节打出来。
                 *
                 * 「预置位读不到」与「预置位名称乱码」两个问题都只能靠它定性：
                 * 前者看 nIndex 是否为 0（部分固件 0-based，会被下面的 <=0 过滤掉），
                 * 后者看 szName 的字节能被哪种编码解出来。
                 */
                if (DIAG_PRESET && i < DIAG_LIMIT) {
                    log.info("预置点诊断 #{}/{}: nIndex={} szName 原始字节={} 判定={}",
                            i + 1, n, one.nIndex, hexOf(one.szName), decodeName(one.szName));
                }
                if (one.nIndex <= 0) {
                    skipped++;
                    continue;
                }
                int[] pos = one.nPosition;
                result.add(new Preset(one.nIndex,
                        /*
                         * 名称编码随固件而变，**不能写死**：
                         * 实测 IPC-HFW 系列返回 UTF-8（「預置點1」的字节是
                         * E9 A0 90 E7 BD AE E9 BB 9E 31），按 GBK 解就是「闋愮疆榛�1」；
                         * 而部分老固件返回 GBK。用 decodeName 自动判定。
                         */
                        decodeName(one.szName),
                        pos != null && pos.length > 0 ? pos[0] : null,
                        pos != null && pos.length > 1 ? pos[1] : null,
                        pos != null && pos.length > 2 ? pos[2] : null,
                        0));
            }
            if (skipped > 0) {
                log.info("QueryDevState(0x57) 有 {} 个预置点因 nIndex<=0 被跳过（共 {} 个）",
                        skipped, n);
            }
            return result;
        } catch (Throwable t) {
            // 型号不支持这个 DevState 时会抛或返回 false，都按「读不到」处理
            log.info("QueryDevState(0x57) 异常: 通道={} {}", channel, t.toString());
            return List.of();
        }
    }

    /** 预置点诊断日志开关（排查读不到 / 名称乱码时临时打开）。 */
    private static final boolean DIAG_PRESET = false;

    /** 字节数组转十六进制串（诊断用）。 */
    private static String hexOf(byte[] bytes) {
        if (bytes == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            if (b == 0) {
                break;
            }
            sb.append(String.format("%02X ", b));
        }
        return sb.length() == 0 ? "(空)" : sb.toString().trim();
    }

    /**
     * 定长字节数组 → 字符串，<b>编码自动判定</b>。
     *
     * <p>大华的字符串字段编码<b>不统一</b>，同一个 SDK 的不同接口、不同固件都不一样：
     * <ul>
     *   <li>预置点名称（{@code NET_PTZ_PRESET.szName}）：IPC-HFW 系列实测是 <b>UTF-8</b></li>
     *   <li>部分老固件的设备名/通道名：GBK</li>
     *   <li>序列号/型号/IP：纯 ASCII，两种解都一样</li>
     * </ul>
     *
     * <p>所以不能写死一个字符集。判据是「<b>严格 UTF-8 能否解通</b>」：
     * UTF-8 的结构约束很强（多字节序列的首字节与后续字节都有合法范围），
     * GBK 字节碰巧构成合法 UTF-8 的概率极低；反过来 GBK 文本几乎不可能通过 UTF-8 校验。
     * 因此「严格 UTF-8 通过 → 用 UTF-8，否则 GBK」在两种设备上都能解对。
     *
     * <p>用 {@code CodingErrorAction.REPORT} 而不是 {@code new String(bytes, UTF_8)}：
     * 后者会把非法字节静默替换成 {@code U+FFFD}，永远"成功"，判不出编码。
     *
     * <p>包级可见是为了单测（{@code PresetNameCharsetTest}）能直接喂真实字节校验。
     *
     * @param bytes 定长字段，尾部以 0 填充
     * @return 解码结果；内容为空时返回 {@code null}
     */
    static String decodeName(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        int end = 0;
        while (end < bytes.length && bytes[end] != 0) {
            end++;
        }
        if (end == 0) {
            return null;
        }
        String s;
        try {
            s = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, end))
                    .toString();
        } catch (CharacterCodingException e) {
            // 不是合法 UTF-8 → 按 GBK（老固件）
            s = new String(bytes, 0, end, GBK);
        }
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 备选路径：{@code "PtzPreset"} JSON 配置项。
     *
     * <p>能读到停留时间（{@code StayTime}），但没有名称与坐标。
     *
     * <p>字段名随机型不同（{@code Index} 或 {@code nIndex}），而树模型
     * 本来就不要求固定字段名，直接逐个候选名试即可。
     *
     * @return 预置点列表；配置项读不到时返回空列表
     */
    public List<Preset> listPresetsByCfg(DahuaSdk.Session session, int channel) {
        String raw;
        try {
            raw = readConfig(session, "PtzPreset", channel);
        } catch (DahuaSdkException e) {
            return List.of();
        }
        JsonNode business = DahuaJson.business(raw);
        if (business == null) {
            return List.of();
        }
        /*
         * 设备可能返回对象（单个）、数组（多个）、或包在一层对象里。
         * 统一归结为「遍历所有带 Index 字段的对象」，三种形态都能覆盖。
         */
        List<Preset> result = new ArrayList<>();
        for (JsonNode item : indexedObjects(business)) {
            Integer index = DahuaJson.intValue(item.get("Index"));
            if (index == null) {
                index = DahuaJson.intValue(item.get("nIndex"));
            }
            if (index == null || index <= 0) {
                continue;
            }
            Integer stay = DahuaJson.intValue(item.get("StayTime"));
            result.add(new Preset(index, null, null, null, null, stay == null ? 0 : stay));
        }
        return result;
    }

    /**
     * 找出所有「带 Index/nIndex 字段」的对象。
     *
     * <p>不按固定层级取，是因为不同型号把列表放在 {@code table} 直接下、
     * 或者 {@code table} 内的某个数组里，两种都出现过。
     */
    private static List<JsonNode> indexedObjects(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        collectIndexed(node, out);
        return out;
    }

    private static void collectIndexed(JsonNode node, List<JsonNode> out) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            if (node.has("Index") || node.has("nIndex")) {
                out.add(node);
                // 命中的对象内部不会再嵌列表，不必继续往下
                return;
            }
            for (JsonNode child : node) {
                collectIndexed(child, out);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectIndexed(child, out);
            }
        }
    }

    /**
     * 转到预置点。
     *
     * <p>异步动作：指令返回成功只代表设备收到了，球机转到位还要几秒
     * （实测大角度转动约 2~3 秒）。
     *
     * <h3>调用方式（真机验证）</h3>
     * {@code CLIENT_DHPTZControlEx2(GOTOPRESET=71)} + {@link NetSDKLib.PTZ_CONTROL_GOTOPRESET} 结构体，
     * <b>预置点号从 1 开始</b>，{@code dwStop} 必须为 0。
     *
     * <h3>三个必须遵守的细节（都是踩过的坑）</h3>
     * <ol>
     *   <li><b>{@code szReserve} 必须显式清零</b>：JNA 的 {@code byte[]} 字段默认 {@code null}，
     *       {@code write()} 时原生侧会读到无效内存，设备返回
     *       {@code 0x80000015 NET_RETURN_DATA_ERROR} —— 看起来像「型号不支持」，
     *       实际只是结构体没填干净</li>
     *   <li><b>预置点号必须是 1 基</b>：传 0 返回 {@code 0x80000007}（参数不合法）</li>
     *   <li><b>{@code dwStop} 必须是 0</b>：传 1 同样返回 {@code 0x80000007}</li>
     * </ol>
     *
     * <h3>{@code 0x80000015} 的真实含义</h3>
     * 在预置点场景下它是「<b>该编号没有预置点</b>」，不是「不支持预置点」。
     * 实测：设备有预置点 1 时 GOTO 1 成功且精确回位（连做 3 次误差 0）；
     * GOTO 一个不存在的 5 号则返回这个错误。
     *
     * @param preset 预置点号（1 基）
     */
    public boolean gotoPreset(DahuaSdk.Session session, int channel, int preset) {
        NetSDKLib sdk = requireSdk();
        NetSDKLib.PTZ_CONTROL_GOTOPRESET param = new NetSDKLib.PTZ_CONTROL_GOTOPRESET();
        param.nPresetIndex = preset;
        param.stuSpeed.fPositionX = 1.0f;
        param.stuSpeed.fPositionY = 1.0f;
        param.stuSpeed.fZoom = 1.0f;
        if (param.szReserve != null) {
            java.util.Arrays.fill(param.szReserve, (byte) 0);
        }
        param.write();
        boolean ok = sdk.CLIENT_DHPTZControlEx2(session.handle(), channel,
                NetSDKLib.NET_EXTPTZ_ControlType.NET_EXTPTZ_GOTOPRESET,
                0, 0, 0, 0, param.getPointer());
        if (!ok) {
            int err = sdk.CLIENT_GetLastError();
            throw new DahuaSdkException("转到预置点 " + preset + " 失败", err,
                    err == 0x80000015
                            ? "设备上没有这个编号的预置点，请先「设置」预置点 " + preset
                            : "预置点号需从 1 开始，且该通道要有云台");
        }
        return true;
    }

    /**
     * 把当前位置保存为预置点（编号不存在则创建）。
     *
     * <p>走 {@code NET_PTZ_POINT_SET_CONTROL}(11)，<b>预置点号传 param2</b>。
     * 详见 {@link #ptzPointCommand}。
     */
    public boolean setPreset(DahuaSdk.Session session, int channel, int preset) {
        return ptzPointCommand(session, channel,
                NetSDKLib.NET_PTZ_ControlType.NET_PTZ_POINT_SET_CONTROL, preset, "设置预置点");
    }

    /** 删除预置点（走 {@code NET_PTZ_POINT_DEL_CONTROL}(12)，预置点号在 param2）。 */
    public boolean deletePreset(DahuaSdk.Session session, int channel, int preset) {
        return ptzPointCommand(session, channel,
                NetSDKLib.NET_PTZ_ControlType.NET_PTZ_POINT_DEL_CONTROL, preset, "删除预置位");
    }

    /**
     * 云台绝对坐标。
     *
     * <p>单位与 {@link #position} 完全一致（{@code pan 0~3600}、{@code tilt -1800~1800}），
     * 可以直接把 {@code position()} 的读数原样喂给 {@link #moveAbsolutely}。
     *
     * @param pan  水平角度（0~3600，0.1 度）
     * @param tilt 垂直角度（-1800~1800，0.1 度）
     * @param zoom 变倍（型号相关，通常是 1~N 的整数档）
     */
    public record PtzPosition(int pan, int tilt, int zoom) {
    }

    /**
     * 读云台当前绝对位置。
     *
     * <p>这是判断「云台到底动没动」的<b>唯一可信证据</b> ——
     * 大华对不支持的指令照样返回 {@code true}，返回值不能作为成功依据。
     *
     * @return 位置；设备不返回时为 {@code null}
     */
    public PtzPosition position(DahuaSdk.Session session, int channel) {
        NetSDKLib sdk = requireSdk();
        NetSDKLib.NET_PTZ_LOCATION_INFO info = new NetSDKLib.NET_PTZ_LOCATION_INFO();
        info.nChannelID = channel;
        info.write();
        IntByReference ret = new IntByReference(0);
        boolean ok = sdk.CLIENT_QueryDevState(session.handle(),
                NetSDKLib.NET_DEVSTATE_PTZ_LOCATION,
                info.getPointer(), info.size(), ret, 3_000);
        if (!ok) {
            return null;
        }
        info.read();
        return new PtzPosition(info.nPTZPan, info.nPTZTilt, info.nPTZZoom);
    }

    /**
     * 云台绝对定位（{@code NET_EXTPTZ_MOVE_ABSOLUTELY}）。
     *
     * <p>把云台转到指定角度。与「转到预置点」的区别是直接给坐标，
     * 不依赖设备上存了哪个预置点 —— 适合「按图上点击的位置转向」这类场景。
     *
     * <p><b>不要拿它替代预置点</b>：早期因为误判「设备不支持预置点」，
     * 曾用「平台存坐标 + 本方法回位」做兜底。后来确认设备完全支持预置点
     * （{@code QueryDevState(0x57)} 能读到名称与坐标，GOTO 精确回位），
     * 那套平台副本已移除 —— 设备能做的不该由平台替它做。
     *
     * <p>注意：{@code EXACTGOTO}(67) 传 {@code null} 结构体会被当成全零坐标，
     * 表现为「跳到原点」，不要拿它做绝对定位。
     */
    public boolean moveAbsolutely(DahuaSdk.Session session, int channel, PtzPosition target) {
        NetSDKLib sdk = requireSdk();
        NetSDKLib.PTZ_CONTROL_ABSOLUTELY param = new NetSDKLib.PTZ_CONTROL_ABSOLUTELY();
        param.stuPosition.nPositionX = target.pan();
        param.stuPosition.nPositionY = target.tilt();
        param.stuPosition.nZoom = target.zoom();
        param.stuSpeed.fPositionX = 1.0f;
        param.stuSpeed.fPositionY = 1.0f;
        param.stuSpeed.fZoom = 1.0f;
        param.write();
        boolean ok = sdk.CLIENT_DHPTZControlEx2(session.handle(), channel,
                NetSDKLib.NET_EXTPTZ_ControlType.NET_EXTPTZ_MOVE_ABSOLUTELY,
                0, 0, 0, 0, param.getPointer());
        if (!ok) {
            throw new DahuaSdkException(
                    "绝对定位到 (" + target.pan() + "," + target.tilt() + "," + target.zoom() + ") 失败",
                    sdk.CLIENT_GetLastError(), "该通道可能无云台");
        }
        return true;
    }

    /**
     * 预置点号传在哪个参数上 —— <b>param2</b>。
     *
     * <h3>这是本模块最难发现的坑</h3>
     * {@code NET_PTZ_POINT_SET_CONTROL}(11) / {@code DEL_CONTROL}(12) /
     * {@code MOVE_CONTROL}(10) 的预置点号在 <b>{@code lParam2}</b>，不是 {@code lParam1}。
     *
     * <p><b>传错参数不会报错</b>：指令返回 {@code true}，但设备实际在操作「预置点 0」——
     * 一个不存在的编号，于是什么都不做。现象就是「设置预置点说成功了，设备上什么都没有」，
     * 而且同一套代码对「已存在的编号」看起来也是成功的（其实只是回了原位），
     * 极易误判成「型号不支持预置点」。
     *
     * <p>真机对比（{@code QueryDevState(0x57)} 读清单验证）：
     * <pre>
     *   SET param1=30 param2=0   → 返回 true，清单里没有 30
     *   SET param1=0  param2=31  → 返回 true，清单出现 31  ★
     *   GOTO param1=31 param2=0  → 返回 true，位置不变
     *   GOTO param1=0  param2=31 → 返回 true，转到预置点 31 的位置  ★
     * </pre>
     *
     * <p>旁证：SDK Demo 里变倍的光标速度也是传 param2
     * （{@code CLIENT_DHPTZControlEx(..., NET_PTZ_ZOOM_ADD_CONTROL, 0, lParam2, 0, 0)}），
     * 而 {@code NET_PTZ_POINT_LOOP_CONTROL} 的注释写了「param2 = 预置位号」。
     *
     * @param preset 预置点号（1 基）
     */
    private boolean ptzPointCommand(DahuaSdk.Session session, int channel, int command,
                                    int preset, String action) {
        NetSDKLib sdk = requireSdk();
        boolean ok = sdk.CLIENT_DHPTZControlEx2(session.handle(), channel, command,
                0, preset, 0, 0, null);
        if (!ok) {
            throw new DahuaSdkException(action + " " + preset + " 失败",
                    sdk.CLIENT_GetLastError(), "该通道可能无云台，或预置点号超出设备支持范围");
        }
        return true;
    }

    /** 点间巡航（在若干预置位之间按顺序巡游）。 */
    public boolean pointTour(DahuaSdk.Session session, int channel, boolean start,
                             int tourNo, int preset) {
        /*
         * 直接复用 DahuaPtzExt 的实现，不在这里再写一遍命令码 ——
         * 巡航的开始/停止是最容易搞反的一对（CLOSELOOP 是「清除」不是「开始」），
         * 两处各写一份必然会分叉：早先这里用 dwStop 停止，实测报 0x80000007 参数不合法。
         */
        if (start) {
            return DahuaPtzExt.cruiseRun(session, channel, tourNo, true);
        }
        return DahuaPtzExt.cruiseRun(session, channel, tourNo, false);
    }

    // ==================== 模块八：SDK 取码流（NAT 场景下替代 RTSP） ====================

    /**
     * SDK 取流的回调。
     *
     * <p>实现方运行在 SDK 原生线程上，<b>必须快速返回</b>：这里只做「把裸流帧
     * 交给发布器」，绝不能写库或做网络阻塞（会拖垮整条码流）。
     */
    @FunctionalInterface
    public interface StreamListener {
        /**
         * @param es       一段 <b>Annex-B 裸流</b>（H264/H265，含起始码）。
         *                 编码类型由上层从 NAL 头判定 —— 实测大华 H265 流也会
         *                 上报 {@code dwDataType=1004}（名义上是 H264），不能信。
         * @param dataType SDK 上报的 {@code dwDataType}，仅供诊断
         */
        void onFrame(byte[] es, int dataType);
    }

    /** 一路 SDK 取流。{@code close()} 幂等。 */
    public static final class RealPlay implements AutoCloseable {

        private final DahuaExtAccess owner;
        private final NetSDKLib.LLong handle;
        private final String key;
        private volatile boolean closed;

        RealPlay(DahuaExtAccess owner, NetSDKLib.LLong handle, String key) {
            this.owner = owner;
            this.handle = handle;
            this.key = key;
        }

        public String key() {
            return key;
        }

        public boolean active() {
            return !closed && handle != null && handle.longValue() != 0;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            owner.stopRealPlay(this);
        }
    }

    /**
     * 取流回调表：{@code 取流句柄 → 目标}。
     *
     * <p><b>为什么按取流句柄索引</b>：{@code CLIENT_RealPlayByDataType} 的回调把
     * <b>取流句柄</b>作为第一个参数传回来，不是登录句柄 —— 实测把回调绑在登录句柄上
     * 连续 10 秒一个字节都收不到。多通道并发取流时必须靠它区分。
     */
    private static final Map<Long, StreamListener> STREAM_LISTENERS = new ConcurrentHashMap<>();

    /**
     * 前若干条数据的诊断日志条数。
     *
     * <p>把「到底收到了什么」落在日志里：排查「有数据但出不了图」时，
     * 「没收到数据」与「收到但不是编码数据」在 ZLM 侧的表现一模一样（都只是不出图）。
     */
    private static final int DIAG_LIMIT = 8;

    private static final Map<Long, java.util.concurrent.atomic.AtomicInteger> SEEN =
            new ConcurrentHashMap<>();

    private static final byte[] DHAV_MAGIC = {'D', 'H', 'A', 'V'};

    /**
     * 已经收到过<b>转封装裸流</b>的取流句柄。
     *
     * <p><b>为什么需要它</b>：转封装库（StreamConvertor）就位时，SDK 对同一帧会
     * <b>投递两份</b> —— 一份 {@code dwDataType=1004} 的 Annex-B 裸流，
     * 一份 {@code dwDataType=0} 的私有 DHAV。两份都要就只能选一份，
     * 否则每帧重复推两遍，码率翻倍、时间戳往前跑。
     *
     * <p>判据放在「已经成功解出视频帧」之后加（见回调），而不是看到非 0 类型就加：
     * 音频帧（G.711）也会带非 0 类型，若被噪声帧抢先标记，
     * 后面的私有视频帧会被误丢，结果就是一片黑。
     */
    private static final java.util.Set<Long> RAW_HANDLES = ConcurrentHashMap.newKeySet();

    /** 回调对象必须被强引用持有，否则被 GC 后就是野指针。 */
    private static final NetSDKLib.fRealDataCallBackEx2 REAL_DATA_CALLBACK =
            (playHandle, dataType, buf, bufSize, param, user) -> {
                if (playHandle == null || buf == null || bufSize < 4) {
                    return;
                }
                long id = playHandle.longValue();
                StreamListener target = STREAM_LISTENERS.get(id);
                try {
                    // ---- 诊断：前几条数据无条件记录类型与首字节 ----
                    int seen = SEEN.computeIfAbsent(id,
                            k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
                    if (seen <= DIAG_LIMIT) {
                        int n = Math.min(bufSize, 8);
                        byte[] h = buf.getByteArray(0, n);
                        StringBuilder sb = new StringBuilder();
                        for (byte x : h) {
                            sb.append(String.format("%02X ", x));
                        }
                        log.info("SDK 取流数据诊断 #{}/{} handle={} dwDataType={} len={} head={}",
                                seen, DIAG_LIMIT, id, dataType, bufSize, sb.toString().trim());
                    }
                    byte[] raw = buf.getByteArray(0, bufSize);
                    /*
                     * 已有裸流副本时丢掉私有副本 —— 见 RAW_HANDLES 注释。
                     * 放在转码之前，丢帧不付额外代价。
                     */
                    if (dataType == 0 && RAW_HANDLES.contains(id)) {
                        return;
                    }
                    byte[] es = toAnnexB(raw);
                    if (es == null) {
                        return;
                    }
                    if (dataType != 0) {
                        RAW_HANDLES.add(id);
                    }
                    target.onFrame(es, dataType);
                } catch (Throwable ignored) {
                    // 消费方异常不能抛回原生线程，否则整个 SDK 回调链会崩
                }
            };

    /**
     * 把回调数据转成 Annex-B 裸流；不是视频数据时返回 {@code null}。
     *
     * <h3>为什么需要这一步</h3>
     * 回调拿到的可能是裸流，也可能是大华私有 DHAV 封装：要了 {@code H264}
     * 正常就是裸流，但「转封装库 StreamConvertor 缺失」或部分固件不支持转封装时，
     * SDK 不会报错，而是静默退化成 DHAV。两种都要能处理，所以统一过这里
     * （裸流原样返回，DHAV 才拆帧头）—— 这样补不补转封装库都能出图。
     *
     * <h3>DHAV 布局（实测，2026-11，IPC fw 3.201）</h3>
     * <pre>
     *   0~3    "DHAV"
     *   4      帧类型：0xFD=I 帧 / 0xFC=P 帧 / 0xF0、0xF1=音频或信息帧
     *   8~11   保留
     *   12~15  本帧长度（小端）—— 实测恒等于 dwBufSize，可作完整性校验
     *   16~39  帧头其余部分
     *   40~    Annex-B 裸流（SPS/PPS/VPS + 切片）
     * </pre>
     * 探测数据：连续 20+ 个视频帧的首个起始码<b>全部落在偏移 40</b>；
     * 音频/信息帧（0xF0/0xF1）在整帧内找不到起始码。
     *
     * <p>因此判据不用枚举类型字节，而是「帧头之后能否找到 Annex-B 起始码」——
     * 换型号/换固件时帧头长度可能变，但「能解出 NAL 就说明位置对了」这条不会失效。
     *
     * @param raw SDK 回调的原始字节
     * @return 从起始码开始的裸流；非视频帧返回 {@code null}
     */
    private static byte[] toAnnexB(byte[] raw) {
        if (raw == null || raw.length < 20) {
            return null;
        }
        // 已经是裸流（直连会话会这样给）
        if (nalStartAt(raw, 0) >= 0) {
            return raw;
        }
        // DHAV 私有封装
        if (raw[0] == DHAV_MAGIC[0] && raw[1] == DHAV_MAGIC[1]
                && raw[2] == DHAV_MAGIC[2] && raw[3] == DHAV_MAGIC[3]) {
            // 从帧头之后找起始码：不写死 40，避免固件改帧头长度后失效
            int limit = Math.min(raw.length - 4, 128);
            for (int i = DHAV_HEADER_SCAN_FROM; i < limit; i++) {
                if (nalStartAt(raw, i) >= 0) {
                    return java.util.Arrays.copyOfRange(raw, i, raw.length);
                }
            }
            return null;   // 音频/信息帧：没有 NAL，丢弃
        }
        return null;
    }

    /** 从 DHAV 帧头之后开始扫起始码（16 = DHAV 头长度，实测帧头共 40 字节）。 */
    private static final int DHAV_HEADER_SCAN_FROM = 16;

    /** {@code i} 处是起始码则返回 NAL 头下标，否则 -1。 */
    private static int nalStartAt(byte[] d, int i) {
        if (i + 3 < d.length && d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 0 && d[i + 3] == 1) {
            return i + 4;
        }
        if (i + 2 < d.length && d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
            return i + 3;
        }
        return -1;
    }

    /**
     * 按 SDK 取码流，拿到的可能是裸流或 DHAV 私有帧（由 {@link #toAnnexB} 统一成裸流）。
     *
     * <h3>为什么这条路径存在</h3>
     * RTSP 那条路要平台<b>反向连设备</b>（{@code rtsp://<ip>:554/...}）。
     * 设备在 NAT / 4G 后面时，554 从平台侧根本不可达 —— 实测同一台设备
     * 554 / 37777 / 80 三个端口从公网全部连不上，只有设备主动连出来的
     * 注册连接是通的。此时 RTSP 播放必然一直重试、永远出不了图。
     *
     * <p>SDK 取流走的是<b>已建立的登录会话</b>，方向与主动注册一致，天然穿透 NAT。
     *
     * <h3>为什么不能指望 SDK 直接给裸流 / PS</h3>
     * 三种 {@code emDataType} 全试过，实测结论：
     * <table>
     *   <tr><th>取值</th><th>直连会话</th><th>主动注册会话</th></tr>
     *   <tr><td>PRIVATE</td><td>DHAV</td><td>DHAV</td></tr>
     *   <tr><td>H264</td><td>DHAV + 裸流(1004)</td><td><b>只有 DHAV</b></td></tr>
     *   <tr><td>GBPS</td><td>DHAV + 国标 PS(1001)</td><td><b>只有 DHAV</b></td></tr>
     * </table>
     * <p>
     * <b>上表是「StreamConvertor 缺失」造成的，不是会话类型的属性。</b>
     * 补回 {@code libStreamConvertor.so} 后，注册会话同样能拿到裸流。
     * 因此这里仍然要 {@code H264}，DHAV 拆帧只作为兜底保留。
     *
     * <p>RTSP 那条路要平台<b>反向连设备</b>，NAT 后必然不可达；SDK 取流走已建立的
     * 登录会话，方向与主动注册一致，天然穿透 NAT —— 这一条不受上面影响。
     *
     * @param session 已登录会话
     * @param channel SDK 通道号。<b>IPC 自身是 0</b>，NVR 下挂摄像机是 1~N
     *                （用 1 取 IPC 会报 {@code 0x80000007}，踩过）
     * @param key     调用方的键（日志用）
     * @param listener 裸流消费者
     * @return 取流句柄；失败抛异常（带 SDK 错误码与常见原因）
     */
    public RealPlay startRealPlay(DahuaSdk.Session session, int channel, String key,
                                  StreamListener listener) {
        NetSDKLib sdk = requireSdk();
        NetSDKLib.NET_IN_REALPLAY_BY_DATA_TYPE in = new NetSDKLib.NET_IN_REALPLAY_BY_DATA_TYPE();
        in.nChannelID = channel;
        /*
         * 要 H264 —— SDK 会把数据转成 Annex-B 裸流（dwDataType=1004）。
         *
         * 转封装由原生库 StreamConvertor 完成，而 libdhnetsdk.so 是用 dlopen
         * 加载它的（Java 侧零引用）。历史上本项目把 StreamConvertor 当「用不到的库」
         * 裁掉了，结果 emDataType 静默失效 —— 不报错，只退化成私有 DHAV。
         * Windows 侧没有独立的 StreamConvertor.dll（转封装内置在 dhnetsdk.dll 里），
         * 所以这个坑只在 Linux 上暴露，表现为「本地正常、部署就不行」。
         *
         * 即使这里要到了裸流，回调仍统一过 {@link #toAnnexB}：
         * 它遇裸流原样返回、遇 DHAV 才拆帧头，两条路径都能出图。
         */
        in.emDataType = NetSDKLib.EM_REAL_DATA_TYPE.EM_REAL_DATA_TYPE_H264;
        /*
         * 用 cbRealDataEx（fRealDataCallBackEx2）而不是 cbRealData：
         * 官方 demo（RealPlayModule.realPlayByDataType）用的就是 Ex2 版本。
         */
        in.cbRealDataEx = REAL_DATA_CALLBACK;
        in.rType = 0;        // 0 = 实时
        in.emAudioType = 0;  // 0 = 默认音频（与官方 demo 一致）
        in.nMP4Type = 0;     // 非 MP4 必须给 0，否则部分固件拒绝
        in.write();

        NetSDKLib.NET_OUT_REALPLAY_BY_DATA_TYPE out = new NetSDKLib.NET_OUT_REALPLAY_BY_DATA_TYPE();
        out.write();

        // 第 4 个参数是等待应答的毫秒数：设备在 NAT 后，握手比局域网慢，给 5 秒
        NetSDKLib.LLong handle = sdk.CLIENT_RealPlayByDataType(session.handle(), in, out, 5000);
        if (handle == null || handle.longValue() == 0) {
            int err = sdk.CLIENT_GetLastError();
            throw new DahuaSdkException("SDK 取流失败",
                    err, "通道 " + channel + "，" + realPlayErrHint(err));
        }
        long id = handle.longValue();
        STREAM_LISTENERS.put(id, listener);
        log.info("SDK 取流已开始: {} 通道={} handle={}", key, channel, id);
        return new RealPlay(this, handle, key);
    }

    void stopRealPlay(RealPlay play) {
        STREAM_LISTENERS.remove(play.handle.longValue());
        RAW_HANDLES.remove(play.handle.longValue());
        SEEN.remove(play.handle.longValue());
        try {
            requireSdk().CLIENT_StopRealPlayEx(play.handle);
            log.info("SDK 取流已停止: {}", play.key());
        } catch (Throwable t) {
            // 停止失败不影响调用方（设备可能已断开）
            log.debug("停止 SDK 取流失败: {} {}", play.key(), t.toString());
        }
    }

    /** 取流失败原因提示。未知码返回空串，调用方仍有原始码可查。 */
    private static String realPlayErrHint(int err) {
        return switch (err) {
            case 0x80000004, 0x80000005 -> "登录句柄无效，请重新连接设备";
            case 0x80000007 -> "参数不合法：通道号可能不对（IPC 自身是通道 0）";
            case 0x8000001B -> "设备已达最大取流路数";
            default -> String.format("错误码 0x%08X", err);
        };
    }

    // ==================== 模块七：报警订阅 ====================

    /**
     * 标准化后的报警事件。
     *
     * <p>{@code command} 是 SDK 原始事件码（{@code 0x2xxx} 段），
     * 语义映射放在平台层做 —— 事件码上千个，硬编码在这层会随 SDK 升级失配。
     *
     * @param command    SDK 事件类型（对应 {@code CLIENT_StartListenEx} 的 lCommand）
     * @param deviceIp   上报设备 IP
     * @param devicePort 设备端口
     * @param rawBytes   事件结构体字节数（用于判断信息完整度）
     * @param state      结构体首 4 字节按 int 读出，{@code -1} 表示结构体不足 4 字节。
     *                   <b>用途是区分「报警发生」与「报警结束」</b>：大华老式事件
     *                   （{@code 0x2101}~{@code 0x2107} 这一组）的结构体首字段就是
     *                   {@code dwState}，0 = 结束、非 0 = 触发。不区分会把「录像丢失已恢复」
     *                   也当成一条新报警落库。
     * @param head       结构体前若干字节的十六进制（最多 32 字节，大写无分隔）。
     *                   保留它是为了<b>事后还能认出未定义事件码的结构体</b> ——
     *                   SDK 3.061 里 {@code 0x3169}/{@code 0x21AA}/{@code 0x31DD} 等码
     *                   查不到常量定义，只能靠字段布局反推。
     * @param receivedAt 平台收到时间（毫秒）
     */
    public record AlarmEvent(int command, String deviceIp, int devicePort,
                             int rawBytes, int state, String head, long receivedAt) {
    }

    /** 报警回调。实现方必须快速返回 —— 回调运行在 SDK 的原生线程上。 */
    @FunctionalInterface
    public interface AlarmListener {
        void onAlarm(AlarmEvent event);
    }

    /**
     * 订阅设备报警。
     *
     * <p><b>回调线程约束</b>：它跑在 SDK 的原生线程里，不能碰数据库、不能做长耗时操作，
     * 否则会阻塞后续事件（表现为"报警丢了一半"）。正确做法是往队列里丢，
     * 由业务线程池消费。
     *
     * <p>回调是<b>进程级全局</b>的：一个进程只能有一个报警回调，
     * 事件里用 {@code lLoginID} 区分设备。所以本类用单例监听器 + 登录句柄路由。
     *
     * @param session  已登录会话
     * @param listener 事件消费者
     */
    public void subscribeAlarm(DahuaSdk.Session session, AlarmListener listener) {
        NetSDKLib sdk = requireSdk();
        alarmListener = listener;
        synchronized (ALARM_LOCK) {
            if (!alarmCallbackInstalled) {
                sdk.CLIENT_SetDVRMessCallBack(ALARM_CALLBACK, null);
                alarmCallbackInstalled = true;
            }
        }
        if (!sdk.CLIENT_StartListenEx(session.handle())) {
            throw new DahuaSdkException("报警订阅失败",
                    sdk.CLIENT_GetLastError(), "确认设备未被其他客户端独占、且已布防");
        }
    }

    /** 退订（幂等）。 */
    public void unsubscribeAlarm(DahuaSdk.Session session) {
        try {
            requireSdk().CLIENT_StopListen(session.handle());
        } catch (Throwable ignored) {
            // 退订失败不影响调用方
        }
    }

    private static final Object ALARM_LOCK = new Object();
    private static volatile boolean alarmCallbackInstalled;
    private static volatile AlarmListener alarmListener;

    /** 原生回调必须被强引用持有，否则被 GC 后就是野指针。 */
    private static final NetSDKLib.fMessCallBack ALARM_CALLBACK =
            (command, loginId, pStuEvent, bufLen, deviceIp, devicePort, user) -> {
                AlarmListener target = alarmListener;
                if (target != null) {
                    try {
                        // 首 4 字节 = 状态位（0x2101~0x2107 这组老式事件的结构体首字段就是
                        // dwState）。结构体不足 4 字节时给 -1，表示"该事件没有状态语义"。
                        int state = -1;
                        String head = "";
                        if (pStuEvent != null && bufLen >= 4) {
                            state = pStuEvent.getInt(0);
                            /*
                             * 取前 128 字节而不是 32：SDK 3.061 里有若干事件码在 Java 侧
                             * 没有常量定义（实测 0x3169 / 0x21AA / 0x31DD / 0x349A / 0x3517），
                             * 唯一能认出它们的办法就是看结构体布局 —— 前 32 字节往往全是
                             * 数字字段，真正的线索（字符串、IP、用户名）在后面。
                             */
                            int n = Math.min(bufLen, 128);
                            byte[] raw = pStuEvent.getByteArray(0, n);
                            StringBuilder sb = new StringBuilder(n * 2);
                            for (byte b : raw) {
                                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                                  .append(Character.forDigit(b & 0xF, 16));
                            }
                            head = sb.toString().toUpperCase();
                        }
                        target.onAlarm(new AlarmEvent(command, deviceIp,
                                devicePort.intValue(), bufLen, state, head,
                                System.currentTimeMillis()));
                    } catch (Throwable ignored) {
                        // 消费方异常不能抛回原生线程
                    }
                }
                return true;
            };

    // ==================== 模块二：录像下载 ====================

    /** 下载进度（字节）。 */
    public record DownloadProgress(long totalBytes, long downloadedBytes) {
        public int percent() {
            return totalBytes <= 0 ? 0 : (int) Math.min(100, downloadedBytes * 100 / totalBytes);
        }
    }

    /** 下载进度回调；实现方不要做长耗时操作（跑在 SDK 线程上）。 */
    @FunctionalInterface
    public interface DownloadListener {
        void onProgress(DownloadProgress progress);
    }

    /**
     * 按时间段把设备录像下载到服务器本地文件。
     *
     * <p><b>与"回放"是两条路</b>：回放是取实时流给前端看；下载是 SDK 把设备上的
     * 录像文件按块拉下来落盘，用于取证、归档。<b>不能用拉流+录制代替</b> ——
     * 那样拿到的是重新编码的流，时间戳与画质都会变。
     *
     * @param channel 通道号
     * @param start   起始时间，格式 {@code yyyy-MM-dd HH:mm:ss}（设备本地时区）
     * @param end     结束时间，同格式
     * @param target  落盘文件（父目录需存在）
     * @param timeoutMs 最长等待时间；超时会停止下载并抛异常
     * @return 实际写入的字节数
     */
    public long downloadRecord(DahuaSdk.Session session, int channel, String start, String end,
                               Path target, long timeoutMs, DownloadListener listener) {
        NetSDKLib sdk = requireSdk();
        NetSDKLib.NET_TIME startTime = parseTime(start);
        NetSDKLib.NET_TIME endTime = parseTime(end);
        startTime.write();
        endTime.write();

        ProgressState state = new ProgressState();
        /* 回调必须被强引用持有到下载结束，否则 JNA 回调被 GC 就是野指针。 */
        NetSDKLib.fTimeDownLoadPosCallBack callback =
                (handle, total, downloaded, index, recordInfo, user) -> {
                    state.total = total;
                    state.downloaded = downloaded;
                    if (listener != null) {
                        try {
                            listener.onProgress(new DownloadProgress(total, downloaded));
                        } catch (Throwable ignored) {
                            // 消费方异常不能抛回原生线程
                        }
                    }
                };
        this.downloadCallback = callback;

        NetSDKLib.LLong handle = sdk.CLIENT_DownloadByTimeEx(
                session.handle(), channel, 0, startTime, endTime,
                target.toString(), callback, null, null, null, null);
        if (handle == null || handle.longValue() == 0) {
            throw new DahuaSdkException("录像下载启动失败",
                    sdk.CLIENT_GetLastError(), "确认该时段设备上确实有录像");
        }

        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (state.total > 0 && state.downloaded >= state.total) {
                    break;
                }
                Thread.sleep(200L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                sdk.CLIENT_StopDownload(handle);
            } catch (Throwable ignored) {
                // 停止失败不影响已下载内容
            }
            this.downloadCallback = null;
        }
        if (state.total > 0 && state.downloaded < state.total) {
            throw new DahuaSdkException("录像下载超时（已下载 " + state.downloaded
                    + " / " + state.total + " 字节）", 0, "可缩小时间段后重试");
        }
        return state.downloaded;
    }

    private static final class ProgressState {
        volatile int total;
        volatile int downloaded;
    }

    /** 强引用槽：见下载/抓图回调的说明。 */
    private volatile NetSDKLib.fTimeDownLoadPosCallBack downloadCallback;

    /** 解析 {@code yyyy-MM-dd HH:mm:ss} 为 SDK 时间结构。 */
    private static NetSDKLib.NET_TIME parseTime(String text) {
        try {
            String[] dateAndTime = text.trim().split(" ");
            String[] ymd = dateAndTime[0].split("-");
            String[] hms = dateAndTime[1].split(":");
            NetSDKLib.NET_TIME t = new NetSDKLib.NET_TIME();
            t.dwYear = Integer.parseInt(ymd[0]);
            t.dwMonth = Integer.parseInt(ymd[1]);
            t.dwDay = Integer.parseInt(ymd[2]);
            t.dwHour = Integer.parseInt(hms[0]);
            t.dwMinute = Integer.parseInt(hms[1]);
            t.dwSecond = Integer.parseInt(hms[2]);
            return t;
        } catch (Exception e) {
            throw new DahuaSdkException("时间格式非法: " + text, 0,
                    "要求 yyyy-MM-dd HH:mm:ss");
        }
    }

    // ==================== 模块二：录像检索（回放/下载的前置依赖）====================

    /**
     * 设备上的一段录像。
     *
     * @param fileName 设备侧文件名（可用于按文件下载）
     * @param channel  通道号
     * @param bytes    文件大小（字节）
     * @param frames   帧数
     * @param start    起始时间 {@code yyyy-MM-dd HH:mm:ss}
     * @param end      结束时间
     */
    public record RecordFile(String fileName, int channel, int bytes, int frames,
                             String start, String end) {
    }

    /** 单次检索最多取多少段：大华单次上限 4000，超过会被截断，取 1000 够一天用。 */
    private static final int RECORD_QUERY_MAX = 1000;

    /**
     * 按时间段检索设备上的录像文件。
     *
     * <p><b>这是回放与下载的前置条件</b>：没有它就只能"猜某个时间点有录像"，
     * 用户界面连"这段时间有哪些段落"都列不出来。
     *
     * <p>{@code nRecordFileType=0} 表示不限录像类型（定时/报警/动检全要）。
     * 部分型号只认具体类型，若返回空可改成 0xFF（全部）再试。
     *
     * @param channel 通道号
     * @param start   起始时间 {@code yyyy-MM-dd HH:mm:ss}（设备本地时区）
     * @param end     结束时间
     * @return 录像段落列表，按开始时间升序；没有录像返回空列表
     */
    public List<RecordFile> queryRecordFile(DahuaSdk.Session session, int channel,
                                            String start, String end) {
        NetSDKLib sdk = requireSdk();
        NetSDKLib.NET_TIME startTime = parseTime(start);
        NetSDKLib.NET_TIME endTime = parseTime(end);
        startTime.write();
        endTime.write();

        /*
         * 必须用 Structure#toArray 分配连续内存。
         *
         * 直接 new NET_RECORDFILE_INFO[n] 只是 n 个 Java 引用，
         * JNA 会把它们当成指针数组传下去，原生侧按结构体数组解析 →
         * 内存错乱（轻则读到垃圾，重则崩溃）。toArray 一次性分配 n*size 的连续块。
         */
        NetSDKLib.NET_RECORDFILE_INFO[] files =
                (NetSDKLib.NET_RECORDFILE_INFO[]) new NetSDKLib.NET_RECORDFILE_INFO()
                        .toArray(RECORD_QUERY_MAX);
        IntByReference count = new IntByReference(0);
        boolean ok = sdk.CLIENT_QueryRecordFile(session.handle(), channel, 0,
                startTime, endTime, null, files, RECORD_QUERY_MAX, count, 10_000, true);
        if (!ok) {
            throw new DahuaSdkException("录像检索失败（通道 " + channel + "）",
                    sdk.CLIENT_GetLastError(), "确认该时段有录像且通道号正确");
        }
        int found = Math.max(0, Math.min(count.getValue(), RECORD_QUERY_MAX));
        List<RecordFile> result = new ArrayList<>(found);
        for (int i = 0; i < found; i++) {
            NetSDKLib.NET_RECORDFILE_INFO f = files[i];
            if (f.starttime == null || f.endtime == null) {
                continue;
            }
            result.add(new RecordFile(
                    text(f.filename),
                    f.ch,
                    f.size,
                    f.framenum,
                    formatTime(f.starttime),
                    formatTime(f.endtime)));
        }
        result.sort(Comparator.comparing(RecordFile::start));
        return result;
    }

    /** {@code NET_TIME} → {@code yyyy-MM-dd HH:mm:ss}。 */
    private static String formatTime(NetSDKLib.NET_TIME t) {
        return String.format("%04d-%02d-%02d %02d:%02d:%02d",
                t.dwYear, t.dwMonth, t.dwDay, t.dwHour, t.dwMinute, t.dwSecond);
    }

    // ==================== 模块三（其余子功能）：通用云台指令入口 ====================

    /**
     * 发一条原始云台指令。
     *
     * <p><b>为什么留这个"逃生口"</b>：云台有 11 个子功能，但 SDK 只有
     * 一个入口 {@code CLIENT_DHPTZControlEx2}，差异全在命令码与参数含义上
     * （预置位用 param1 当号、线扫用 param2 当速度…）。逐个包成方法会得到
     * 11 个签名相似、极易传错参数的函数。
     *
     * <p>命名方法（{@link #gotoPreset} 等）覆盖常用场景，长尾功能
     * （巡迹、限位、开机/空闲动作）由上层按需传命令码 —— 命令码取自
     * {@code NET_PTZ_ControlType} / {@code NET_EXTPTZ_ControlType}，
     * 每个都在 SDK 里有注释说明参数含义。
     *
     * @param command 命令码
     * @param p1      param1（含义随命令变化）
     * @param p2      param2
     * @param p3      param3
     * @param stop    {@code true}=停止动作
     * @param extra   需要传结构体的命令（如 GOTOPRESET）传结构指针，否则 {@code null}
     */
    public boolean rawPtz(DahuaSdk.Session session, int channel, int command,
                          int p1, int p2, int p3, boolean stop, Pointer extra) {
        NetSDKLib sdk = requireSdk();
        boolean ok = sdk.CLIENT_DHPTZControlEx2(session.handle(), channel, command,
                p1, p2, p3, stop ? 1 : 0, extra);
        if (!ok) {
            throw new DahuaSdkException("云台指令 0x" + Integer.toHexString(command) + " 失败",
                    sdk.CLIENT_GetLastError(), "该型号可能无云台或不支持该功能");
        }
        return true;
    }

    // ==================== 模块五：喊话 / 对讲 ====================

    /**
     * 对讲音频口径：8 kHz / 16 bit / 单声道 / 每包 25 ms。
     *
     * <p><b>这几个数字必须让前端也知道</b>，否则送来的字节与设备期望的口径对不上，
     * 现场听到的就是变调噪音（8 kHz 的缓冲按 44.1 kHz 播 = 快放，反过来 = 慢放）。
     * 平台通过 {@code /talk/start} 的应答把它们回给浏览器，前端按此重采样。
     */
    public static final int TALK_SAMPLE_RATE = 8000;

    /** 对讲位深（bit）。 */
    public static final int TALK_AUDIO_BIT = 16;

    /** 大华建议的打包周期（毫秒），决定 TalkSendData 推帧的节奏。 */
    public static final int TALK_PACKET_PERIOD_MS = 25;

    /**
     * 对讲通道。
     *
     * <p><b>同一台设备同时只能有一路</b>：大华的 {@code CLIENT_StartTalkEx} 以
     * <b>登录句柄</b>为参数，不是通道级。第二个调用会静默顶掉第一个，
     * 所以多值班员抢麦必须在平台侧加互斥（设备级锁 + TTL）。
     *
     * <p>音频编码在 {@link #startTalk} 里用 {@code CLIENT_SetDeviceMode} 固定成
     * {@link #TALK_SAMPLE_RATE} 的裸 PCM —— 不指定时由设备自选（常见是 G711A），
     * 而浏览器只能产出 PCM，协商成 G711 就得在服务端再做一层编码。
     */
    public final class TalkChannel implements AutoCloseable {

        private final NetSDKLib.LLong handle;

        private TalkChannel(NetSDKLib.LLong handle) {
            this.handle = handle;
        }

        /** 对讲句柄；{@code 0} 表示未建立。 */
        public long id() {
            return handle.longValue();
        }

        /**
         * 发一帧音频。
         *
         * <p>数据格式必须与设备协商结果一致 —— 传 MP3/AAC 原始帧是发不出去的，
         * 大华要的是裸 PCM 或 G711 帧。调用方负责重采样到 8 kHz 单声道。
         *
         * @return SDK 返回值（非 0 表示成功，具体语义按 SDK 版本）
         */
        public long send(byte[] pcm) {
            if (pcm == null || pcm.length == 0) {
                return 0;
            }
            Memory memory = new Memory(pcm.length);
            memory.write(0, pcm, 0, pcm.length);
            return requireSdk().CLIENT_TalkSendData(handle, memory, pcm.length).longValue();
        }

        @Override
        public void close() {
            try {
                requireSdk().CLIENT_StopTalkEx(handle);
            } catch (Throwable ignored) {
                // 关闭失败不影响上层
            }
        }
    }

    /**
     * 建立对讲（单工喊话也走这条）。
     *
     * <p><b>必须先协商编码再 StartTalkEx</b>：不设 {@code NET_TALK_ENCODE_TYPE} 时
     * 设备按自己的默认值（常见 G711A/G711U）解我们送去的裸 PCM，听到的是持续噪音；
     * 而设备回传到 {@code flag==1} 的帧也是那种编码，浏览器解不了。
     * 官方 demo（{@code TalkModule#startTalk}）的顺序就是三步 SetDeviceMode → StartTalkEx。
     *
     * <p><b>任何一步 SetDeviceMode 失败都必须抛</b>，不能带着"编码没设上"继续
     * 去开对讲 —— 那样错误会推迟到"用户听到杂音"才暴露，现场根本查不出来。
     *
     * <p>不调用 {@code CLIENT_RecordStart}：那一步是采集<b>运行 demo 的那台 PC 的麦克风</b>，
     * 服务端没有声卡，必失败；音频从浏览器经 {@code /talk/send} 进来，
     * 由 {@link TalkChannel#send} 推到设备。
     *
     * @param session 已登录会话
     * @param listener 设备回传的音频帧（{@code flag==1}）；单工可传 {@code null}
     * @return 对讲通道，用完必须 {@link TalkChannel#close()}
     */
    public TalkChannel startTalk(DahuaSdk.Session session, AudioFrameListener listener) {
        NetSDKLib sdk = requireSdk();
        if (session == null || session.handle() == null) {
            throw new DahuaSdkException("对讲建立失败", "登录会话已失效，请重新登录设备");
        }
        applyTalkModes(sdk, session.handle());

        NetSDKLib.pfAudioDataCallBack callback = (talkHandle, buf, len, flag, user) -> {
            if (listener == null || buf == null || len <= 0) {
                return;
            }
            try {
                listener.onAudio(flag, buf.getByteArray(0, len));
            } catch (Throwable ignored) {
                // 消费方异常不能抛回原生线程
            }
        };
        this.talkCallback = callback; // 强引用，见回调说明
        NetSDKLib.LLong handle = sdk.CLIENT_StartTalkEx(session.handle(), callback, null);
        if (handle == null || handle.longValue() == 0) {
            throw new DahuaSdkException("对讲建立失败",
                    sdk.CLIENT_GetLastError(), "设备可能不支持对讲，或已被其他客户端占用");
        }
        return new TalkChannel(handle);
    }

    /**
     * 对讲前的三项设备模式设置（顺序与官方 demo 一致）。
     *
     * <ol>
     *   <li>{@code NET_TALK_ENCODE_TYPE}：把编码固定成 8 kHz/16 bit 裸 PCM；</li>
     *   <li>{@code NET_TALK_SPEAK_PARAM}：喊话参数（{@code nMode=0} 普通喊话，
     *       {@code bEnableWait=0} 不排队等待，{@code nSpeakerChannel=0} 默认喇叭）；</li>
     *   <li>{@code NET_TALK_TRANSFER_MODE}：{@code bTransfer=0} 非转发模式
     *       （转发模式才需要再设 {@code NET_TALK_TALK_CHANNEL}）。</li>
     * </ol>
     */
    private void applyTalkModes(NetSDKLib sdk, NetSDKLib.LLong login) {
        NetSDKLib.NETDEV_TALKDECODE_INFO encode = new NetSDKLib.NETDEV_TALKDECODE_INFO();
        encode.encodeType = NetSDKLib.NET_TALK_CODING_TYPE.NET_TALK_PCM;
        encode.dwSampleRate = TALK_SAMPLE_RATE;
        encode.nAudioBit = TALK_AUDIO_BIT;
        encode.nPacketPeriod = TALK_PACKET_PERIOD_MS;
        encode.write();
        setMode(sdk, login, NetSDKLib.EM_USEDEV_MODE.NET_TALK_ENCODE_TYPE, encode.getPointer(),
                "对讲编码格式(PCM " + TALK_SAMPLE_RATE + "Hz/" + TALK_AUDIO_BIT + "bit)",
                "该型号可能不支持 PCM 对讲编码");

        NetSDKLib.NET_SPEAK_PARAM speak = new NetSDKLib.NET_SPEAK_PARAM();
        speak.nMode = 0;              // 0=普通喊话（1=对讲，需要对方先呼叫）
        speak.bEnableWait = 0;        // 0=不等待对方空闲，避免占用期间静默卡住
        speak.nSpeakerChannel = 0;    // 0=设备默认喇叭
        // dwSize 由 JNA 算不出「本结构该报多少」，却又是设备校验的第一个字段，显式赋上
        speak.dwSize = speak.size();
        speak.write();
        setMode(sdk, login, NetSDKLib.EM_USEDEV_MODE.NET_TALK_SPEAK_PARAM, speak.getPointer(),
                "喊话参数", "该型号可能不支持语音喊话");

        NetSDKLib.NET_TALK_TRANSFER_PARAM transfer = new NetSDKLib.NET_TALK_TRANSFER_PARAM();
        transfer.bTransfer = 0;       // 0=非转发：音频直接进设备喇叭，不进转发通道
        transfer.dwSize = transfer.size();
        transfer.write();
        setMode(sdk, login, NetSDKLib.EM_USEDEV_MODE.NET_TALK_TRANSFER_MODE, transfer.getPointer(),
                "对讲转发模式", "该型号可能不支持对讲转发模式");
    }

    /** {@code CLIENT_SetDeviceMode} 的失败统一翻译成带错误码的异常。 */
    private static void setMode(NetSDKLib sdk, NetSDKLib.LLong login, int mode,
                               Pointer value, String what, String hint) {
        if (!sdk.CLIENT_SetDeviceMode(login, mode, value)) {
            throw new DahuaSdkException("设置" + what + "失败",
                    sdk.CLIENT_GetLastError(), hint);
        }
    }

    /**
     * 设备回传的音频帧。
     *
     * @param audioFlag 0=本机采集，1=设备发来的
     * @param pcm       音频字节（编码由设备协商决定）
     */
    @FunctionalInterface
    public interface AudioFrameListener {
        void onAudio(byte audioFlag, byte[] pcm);
    }

    /** 强引用槽：JNA 回调必须一直被持有。 */
    private volatile NetSDKLib.pfAudioDataCallBack talkCallback;

    // ==================== 内部工具 ====================

    private static NetSDKLib requireSdk() {
        NetSDKLib sdk = DahuaSdk.get().sdk();
        if (sdk == null) {
            throw new DahuaSdkException("大华 SDK 不可用",
                    DahuaSdk.get().loadError());
        }
        return sdk;
    }

    private static int nextSerial() {
        int v = CMD_SERIAL.getAndIncrement();
        return v & 0xFFFF; // SDK 要求 0~65535
    }

    /**
     * 把原生内存拷进 JNA 结构体。
     *
     * <p>大华的 {@code SdkStructure} 没有 {@code (Pointer)} 构造函数，
     * 所以只能「先分配 → 拷字节 → read()」。
     */
    private static void copyInto(NetSDKLib.SdkStructure target, Pointer source) {
        if (source == null) {
            return;
        }
        int size = target.size();
        target.write();
        target.getPointer().write(0, source.getByteArray(0, size), 0, size);
        target.read();
    }

    /** 定长字节数组 → 字符串（截到第一个 0）。纯 ASCII 字段（SN/IP/型号）两种解都一样。 */
    private static String text(byte[] field) {
        String s = decodeName(field);
        return s == null ? "" : s;
    }

    /**
     * 配置项 JSON 字节 → 字符串。
     *
     * <p>与 {@code DahuaCgiAccess} 里实测的结论一致：设备 {@code configManager}
     * 返回的是 <b>UTF-8</b>（{@code PtzTour[0][0].Name} 按 GBK 解就是「宸¤埅缇ょ祫1」）。
     * 早先这里硬用 GBK，配置里的中文名（巡航线名、预置点分组名）全变乱码。
     */
    private static String trimNul(byte[] buffer) {
        int end = 0;
        while (end < buffer.length && buffer[end] != 0) {
            end++;
        }
        if (end == 0) {
            return "";
        }
        return decodeJsonDocument(buffer, end);
    }

    /**
     * 包级可见：让单测能直接喂真实字节校验编码判定（{@code MixedCharsetDocumentTest}）。
     *
     * @param buffer 含尾部 0 填充的配置项字节
     */
    static String decodeForTest(byte[] buffer) {
        return trimNul(buffer);
    }

    /**
     * 解码一份 JSON 配置文档，<b>逐字段</b>判定编码。
     *
     * <h3>为什么不能整份文档判一次</h3>
     * 设备上的数据可能<b>同时含两种编码</b>。实测这台球机
     * （DH-SD49425DB-HNY-GQ-EAU / fw 3.201.0000007.1.R）读 {@code PtzTour}：
     * <pre>
     *   PtzTour[0][0].Name  = D1 B2 BA BD C8 BA BD 4D 31   ← GBK（平台早期写入）
     *   PtzTour[0][2].Name  = E5 B7 A1 E8 88 AA …  33     ← UTF-8（设备界面写入）
     * </pre>
     * 整份文档做一次严格 UTF-8 校验时，只要有一个 GBK 字节就整体失败，
     * 于是<b>全篇退化成 GBK</b>，把本来正确的 UTF-8 字段解成「宸¤埅缇ょ祫3」。
     * 反过来先试 GBK 也一样会毁掉另一批字段 —— 一份文档里两种编码并存时，
     * 任何「全局选一个字符集」的做法都必错一半。
     *
     * <p>所以按 JSON 字符串字面量切段，每段单独判：严格 UTF-8 通过就用 UTF-8，
     * 否则按 GBK。判据本身没变（见 {@link #decodeName}），只是作用域从
     * 「整份文档」缩小到「单个字符串值」。
     *
     * <p>结构字符（{@code {}[]:,"}、数字、true/false）都是 ASCII，
     * 两种编码字节相同，逐段解码后原样拼回去，JSON 结构不受影响。
     */
    private static String decodeJsonDocument(byte[] buffer, int length) {
        StringBuilder out = new StringBuilder(length);
        int i = 0;
        while (i < length) {
            if (buffer[i] != '"') {
                // 非字符串区间：ASCII 结构字符，直接按字节追加
                out.append((char) (buffer[i] & 0xFF));
                i++;
                continue;
            }
            out.append('"');
            i++;
            int start = i;
            boolean escaped = false;
            while (i < length) {
                byte b = buffer[i];
                if (b == '\\' && i + 1 < length) {
                    escaped = true;
                    i += 2;
                    continue;
                }
                if (b == '"') {
                    break;
                }
                i++;
            }
            byte[] seg = new byte[i - start];
            System.arraycopy(buffer, start, seg, 0, seg.length);
            /*
             * 含反斜杠转义的段原样按 ASCII 追加：转义序列本身是 ASCII
             * （反斜杠 u 加四位十六进制），非 ASCII 不会以转义形式出现。
             */
            out.append(escaped ? new String(seg, StandardCharsets.ISO_8859_1)
                    : decodeSegment(seg));
            if (i < length) {
                out.append('"');
                i++;
            }
        }
        return out.toString();
    }

    /** 单个字符串值：严格 UTF-8 优先，不通过则按 GBK。 */
    private static String decodeSegment(byte[] seg) {
        if (seg.length == 0) {
            return "";
        }
        boolean ascii = true;
        for (byte b : seg) {
            if (b < 0) {
                ascii = false;
                break;
            }
        }
        if (ascii) {
            return new String(seg, StandardCharsets.US_ASCII);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(seg))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(seg, GBK);
        }
    }
}
