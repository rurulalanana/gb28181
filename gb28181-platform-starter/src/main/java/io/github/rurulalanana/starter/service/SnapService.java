package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.starter.config.Gb28181Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 通道快照的存储与读取（存图、取图、清过期）。
 *
 * <p>快照只能由服务端产生：现场推 H265，必须真解码才能出 JPEG；浏览器抓帧依赖 wasm 解码器、
 * 跨域 canvas 读像素受限、页面一关就没了，已全部移除。现在由 {@link FfmpegSnapService}
 * 用外部 ffmpeg 拉 ZLM 的流解码（不引 JavaCV，jar 增量为 0），编排见 {@link SnapCaptureService}。
 * 不能用 ZLM 的 Web API：ZLM4J 1.12.0 内嵌的 ZLMediaKit 不含 Web API（{@code nm -D} 查不到
 * {@code mk_web_api}），{@code /index/api/getSnap} 不可用。
 *
 * <p>存储布局 {@code <recordRoot>/snap/<serial>_<code>.jpg}，与录像同根便于整体备份 / 清理。
 */
@Slf4j
/**
 * 快照存储。两种用途，<b>存储策略完全不同，不要混用</b>：
 *
 * <table border="1">
 *   <tr><th></th><th>通道当前快照</th><th>AI 事件截图</th></tr>
 *   <tr><td>语义</td><td>「这个通道现在什么样」</td><td>「这条告警发生时的画面」</td></tr>
 *   <tr><td>文件名</td><td>{@code {serial}_{code}.jpg} 固定</td><td>{@code alarm/{serial}/{alarmId}.jpg} 唯一</td></tr>
 *   <tr><td>同通道多次</td><td><b>覆盖</b>（只留最新）</td><td><b>各存一份</b>（每条告警一张）</td></tr>
 *   <tr><td>保留期</td><td>1 小时（当前状态，过期即无意义）</td><td>跟报警保留期（默认 180 天）</td></tr>
 * </table>
 *
 * <h3>为什么必须分成两套（这里踩过坑）</h3>
 * 早先 AI 事件截图直接复用了 {@link #saveBytes}，于是同一通道每告警一次就覆盖同一张
 * {@code {serial}_{code}.jpg} —— 实测 1195 条 AI 告警的 {@code snap_path}
 * 只有 4 个不同值，其中 1138 条挤在同一个路径上。
 * 更糟的是那个路径还会被 1 小时过期清理掉，所以历史告警点开全是 404：
 * <b>既看不出是哪次事件的图，图也早就没了。</b>
 *
 * <p>根因不是「路径生成写错了」，而是把「当前状态」的存储原语用在了
 * 「历史证据」上。这两类数据的生命周期和唯一性要求是对立的：
 * 前者要覆盖、要短命；后者要唯一、要跟记录同生共死。
 */
@Service
@RequiredArgsConstructor
public class SnapService {

    /** 快照子目录名（放在录像根目录下，与录像同根便于备份）。 */
    private static final String SUBDIR = "snap";

    /**
     * 事件截图的子目录名（在 {@link #SUBDIR} 之下）。
     *
     * <p>放子目录而不是平铺，是为了让 {@link #purgeExpired()} 的
     * {@code Files.list}（<b>不递归</b>）天然跳过它们 ——
     * 「1 小时过期」只该作用于通道当前快照，绝不能删历史告警的证据图。
     * 若平铺在同一层，就得在清理时逐个判断文件名格式，一改格式就可能误删。
     */
    private static final String EVENT_SUBDIR = "alarm";

    /** 快照最长保留时间（毫秒），默认 1 小时。 */
    private static final long MAX_AGE_MS = 60 * 60 * 1000L;

    private final Gb28181Properties props;

    /** 快照根目录。 */
    private Path root() {
        return Path.of(props.resolvedRecordRoot(), SUBDIR);
    }

    /** 事件截图根目录：{@code <recordRoot>/snap/alarm/}。 */
    private Path eventRoot() {
        return root().resolve(EVENT_SUBDIR);
    }

    /**
     * 保存服务端抓拍产生的 JPEG 字节（ffmpeg 已在内存里产出，无需再 Base64）。
     *
     * <p><b>只用于「通道当前快照」</b>：文件名固定，同通道会覆盖。
     * 历史证据图请用 {@link #saveEventBytes}。
     *
     * @return 快照的访问路径（相对平台 HTTP 根）；失败返回 {@code null}
     */
    public String saveBytes(String serial, String code, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            Path dir = root();
            Files.createDirectories(dir);
            // 文件名只允许安全字符：编码是 20 位数字，天然安全，但仍做一次过滤
            Path file = dir.resolve(safe(serial) + "_" + safe(code) + ".jpg");
            // 先写临时文件再原子替换，避免前端同时请求时读到半张图
            Path tmp = dir.resolve(file.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            return "/api/v1/cloudrecord/snap/" + safe(serial) + "/" + safe(code) + ".jpg";
        } catch (IOException e) {
            log.warn("保存快照失败: serial={} code={} {}", serial, code, e.getMessage());
            return null;
        }
    }

    /**
     * 保存一条<b>告警的事件截图</b>：文件名带唯一键，同通道多次告警各存一份。
     *
     * @param serial    设备编码
     * @param code      通道编码（<b>只用于日志定位</b>，不进路径 ——
     *                  唯一键已经能定位到具体告警，再加通道段只是让路径更长）
     * @param uniqueKey 唯一键（用告警 ID）。同时充当文件名，必须只含安全字符
     * @param bytes     JPEG 字节
     * @return 访问路径（相对平台 HTTP 根）；失败返回 {@code null}
     */
    public String saveEventBytes(String serial, String code, String uniqueKey, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || uniqueKey == null || uniqueKey.isBlank()) {
            return null;
        }
        try {
            String dev = safe(serial);
            String key = safe(uniqueKey);
            Path dir = eventRoot().resolve(dev);
            Files.createDirectories(dir);
            Path file = dir.resolve(key + ".jpg");
            Path tmp = dir.resolve(key + ".jpg.tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            return eventUrl(serial, uniqueKey);
        } catch (IOException e) {
            log.warn("保存事件截图失败: serial={} code={} key={} {}", serial, code, uniqueKey, e.getMessage());
            return null;
        }
    }

    /**
     * 事件截图的访问 URL。
     *
     * <p>抽成方法而不是各处拼串：{@link VisionEventRecorder} 落库要和
     * {@link #saveEventBytes} 返回的路径完全一致，两处各拼一遍迟早不同步。
     */
    public static String eventUrl(String serial, String uniqueKey) {
        return "/api/v1/cloudrecord/snap/" + EVENT_SUBDIR + "/"
                + safe(serial) + "/" + safe(uniqueKey) + ".jpg";
    }

    /**
     * 读取事件截图。
     *
     * @return 图像字节；不存在时返回 {@code null}
     */
    public byte[] readEvent(String serial, String uniqueKey) {
        if (uniqueKey == null || uniqueKey.isBlank()) {
            return null;
        }
        try {
            Path file = eventRoot().resolve(safe(serial)).resolve(safe(uniqueKey) + ".jpg");
            if (!Files.exists(file)) {
                return null;
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            log.debug("读取事件截图失败: serial={} key={} {}", serial, uniqueKey, e.getMessage());
            return null;
        }
    }

    /**
     * 删除一条事件截图（跟随告警记录一起清理）。
     *
     * <p>{@code snapPath} 传的就是落库的 URL，这里按同样的形状反解。
     * 解析不出文件名时直接返回 —— 宁可留一张孤儿图，也不要因为一条脏数据
     * 把清理任务打断（孤儿图有独立的目录级清理兜底）。
     */
    public void deleteEventByUrl(String snapPath) {
        if (snapPath == null || snapPath.isBlank()) {
            return;
        }
        // 通道当前快照不在本方法的职责内（它靠 1 小时过期自清）
        String marker = "/" + EVENT_SUBDIR + "/";
        int markerAt = snapPath.indexOf(marker);
        if (markerAt < 0) {
            return;
        }
        int devStart = markerAt + marker.length();
        int devEnd = snapPath.indexOf('/', devStart);
        if (devEnd < 0) {
            return;
        }
        String dev = snapPath.substring(devStart, devEnd);
        String fileName = snapPath.substring(devEnd + 1);
        if (fileName.isEmpty()) {
            return;
        }
        try {
            Files.deleteIfExists(eventRoot().resolve(safe(dev)).resolve(fileName));
        } catch (IOException e) {
            log.debug("删除事件截图失败: path={} {}", snapPath, e.getMessage());
        }
    }

    /**
     * 清理事件截图的孤儿文件（目录里存在但 {@code keep} 里没有）。
     *
     * <p>为什么需要它：{@link #deleteEventByUrl} 依赖「每条告警都能被遍历到」，
     * 但用户批量删报警、数据库手工清理、写入过程中进程被杀等情形都会留下孤儿。
     * 定期按「当前库里的 snap_path 集合」扫一遍最省心 ——
     * 判据只有一份（库），不需要另记状态。
     *
     * @param keep 仍需保留的文件名集合（{@code <alarmId>.jpg}）
     * @return 删除的文件数
     */
    public int purgeEventOrphans(java.util.Set<String> keep) {
        Path dir = eventRoot();
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int removed = 0;
        try (var devDirs = Files.list(dir)) {
            for (Path devDir : devDirs.filter(Files::isDirectory).toList()) {
                try (var files = Files.list(devDir)) {
                    for (Path f : files.toList()) {
                        String name = f.getFileName().toString();
                        if (!name.endsWith(".jpg") || keep.contains(name)) {
                            continue;
                        }
                        try {
                            Files.deleteIfExists(f);
                            removed++;
                        } catch (IOException ignore) {
                            // 单个文件删不掉不影响其它
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.debug("清理孤儿事件截图失败: {}", e.getMessage());
        }
        return removed;
    }

    /**
     * 读取快照。
     *
     * @return 图像字节；不存在时返回 {@code null}
     */
    public byte[] read(String serial, String code) {
        try {
            // 先清理再读：若放在检查之后，会出现「刚确认存在就被自己删了」的 NoSuchFileException 竞态
            purgeExpired();
            Path file = root().resolve(safe(serial) + "_" + safe(code) + ".jpg");
            if (!Files.exists(file)) {
                return null;
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            log.debug("读取快照失败: serial={} code={} {}", serial, code, e.getMessage());
            return null;
        }
    }

    /**
     * 快照是否可用。只探测存在性（读几十 KB 是白浪费 IO）。
     * 必须把「过期」也算作不存在：清理是延迟的，只判断 {@code Files.exists} 会让列表显示有缩略图
     * 而点开 404，或者把几分钟前的旧画面当成当前状态。
     *
     * @return 是否有一张未过期的快照
     */
    public boolean exists(String serial, String code) {
        try {
            Path file = root().resolve(safe(serial) + "_" + safe(code) + ".jpg");
            if (!Files.exists(file)) {
                return false;
            }
            return System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis()
                    <= MAX_AGE_MS;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 清理过期快照（默认 1 小时）：快照是「访问列表时现抓」的产物，过期图会让人
     * 误以为通道当前状态如此。
     */
    public void purgeExpired() {
        try {
            Path dir = root();
            if (!Files.isDirectory(dir)) {
                return;
            }
            long now = System.currentTimeMillis();
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".jpg")).forEach(p -> {
                    try {
                        if (now - Files.getLastModifiedTime(p).toMillis() > MAX_AGE_MS) {
                            Files.deleteIfExists(p);
                        }
                    } catch (IOException ignore) {
                        // 单个文件清理失败不影响其它
                    }
                });
            }
        } catch (IOException e) {
            log.debug("清理过期快照失败: {}", e.getMessage());
        }
    }

    /**
     * 文件名安全化：只保留字母数字与下划线，避免路径穿越。
     *
     * <p>静态是因为 {@link #eventUrl} 也要用 —— 它是纯粹的字符串变换，
     * 不需要实例状态。为调用它而 {@code new SnapService(null)} 是坏味道：
     * 传 null 给必填依赖，将来有人在里面碰 props 就是 NPE。
     */
    private static String safe(String v) {
        if (v == null || v.isBlank()) {
            return "unknown";
        }
        return v.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
