package io.github.rurulalanana.starter.service.vision;

import io.github.rurulalanana.vision.Frame;
import io.github.rurulalanana.vision.pipeline.FrameSource;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 从 ZLM 拉流、按 fps 出 JPEG 帧的 {@link FrameSource}。
 *
 * <h3>为什么是 HTTP-TS 而不是 HTTP-FLV（踩过的坑，别改回去）</h3>
 * ZLM 输出的 H.265 FLV 用<b>传统 FLV 扩展格式</b>（视频 tag 首字节 {@code 0x1C}，即
 * {@code codecId=12}），因为 {@code MediaServerProperties.rtmpEnhanced=false} ——
 * 那是为了让 JS 播放器（xgplayer-transmuxer）能播，必须保持的默认值。
 *
 * <p>但 ffmpeg 7.0.2 的 flv demuxer <b>不支持 codecId=12</b>，只支持 E-RTMP 的增强头
 * （{@code codecId=0} + FourCC {@code hvc1}）。喂 FLV 给它只会反复打印：
 * <pre>[flv @ ...] Video codec (c) is not implemented.</pre>
 * 日志里的 {@code (c)} 是十六进制 {@code 0xc}=12，不是字母 c —— 这一点极易误判。
 * 结果就是<b>进程活着、一帧不出</b>。
 *
 * <p>注意<b>解码器本身没问题</b>：{@code ffmpeg -decoders | grep hevc} 有 {@code hevc}，
 * ffmpeg 自己 mux 的增强型 HEVC FLV 也能解。卡住的只是 flv demuxer 对老格式的支持。
 * 所以<b>不需要升级 ffmpeg</b>，换成 MPEG-TS 封装即可 —— TS 原生支持 HEVC，
 * 且 ZLM 默认就开了 {@code enable_ts}。实测同一个流：FLV 0 字节，TS 1 秒出 1280x720 JPEG。
 *
 * <h3>为什么用「常驻 ffmpeg」而不是复用 {@code FfmpegSnapService}</h3>
 * {@code FfmpegSnapService} 是<b>单次抓拍</b>：每次起一个 ffmpeg、解出第一帧就退出，
 * 实测约 1 秒。做成 1 fps 连续检测时，那等于每秒起一个进程 ——
 * 进程创建 + 拉流 + 等关键帧的开销全落在每一帧上，CPU 会被拉起进程本身吃掉。
 *
 * <p>这里改成<b>一个常驻 ffmpeg</b>：一次拉流，用 {@code -vf fps=N} 让它自己按频率
 * 出图，我们只从 stdout 读连续的 JPEG 流。进程启动成本只付一次。
 *
 * <h3>为什么必须走管道喂流，而不是 {@code -i URL}</h3>
 * 实测 {@code ffmpeg -i http://.../live.flv} 在<b>活流</b>上会挂死（不返回帧也不退出）。
 * 所以这里保持与 {@code FfmpegSnapService} 相同的做法：Java 侧读 HTTP 流，
 * 写进 ffmpeg 的 stdin，ffmpeg 从 {@code pipe:0} 读。
 *
 * <h3>出帧格式</h3>
 * {@code -f image2pipe -vcodec mjpeg} 输出的是<b>连续的 JPEG</b>，没有长度前缀。
 * 解析靠 JPEG 的标记：{@code FFD8} 开头、{@code FFD9} 结尾。这是 image2pipe 的
 * 标准做法，也避免了引入额外的容器解析。
 */
@Slf4j
public class ZlmFrameSource implements FrameSource {

    /** ffmpeg 可执行文件（与 FfmpegSnapService 保持一致）。 */
    private static final String FFMPEG = "/opt/gb28181/bin/ffmpeg";

    /** 拉流连接超时。 */
    private static final int CONNECT_TIMEOUT_MS = 5000;

    /** 拉流读超时：活流一直有数据，超时说明流断了。 */
    private static final int READ_TIMEOUT_MS = 15_000;

    /** 单帧 JPEG 上限（4K 也够）：超过说明标记解析跑飞了，丢弃重来。 */
    private static final int MAX_JPEG_BYTES = 8 * 1024 * 1024;

    /** 出图质量：2 比默认好一些，对检测精度有实际影响（压缩伪影会被当成纹理）。 */
    private static final String JPEG_QUALITY = "3";

    private final String sourceId;
    private final String streamUrl;
    private final double fps;

    /** 停止标志：置 false 后各线程退出。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 已产出的帧数（排障用）。 */
    private final AtomicLong produced = new AtomicLong();

    /** 进程与拉流线程的句柄。 */
    private volatile Process process;
    private volatile Thread puller;
    private volatile Thread reader;

    /**
     * @param sourceId  来源标识（通道编码，会透传到 {@link Frame#sourceId()}）
     * @param streamUrl ZLM 的 HTTP-TS 地址（用 {@link #toTsUrl} 从 FLV 地址转换）
     * @param fps       目标出帧频率（每路）
     */
    public ZlmFrameSource(String sourceId, String streamUrl, double fps) {
        this.sourceId = sourceId;
        this.streamUrl = streamUrl;
        this.fps = fps <= 0 ? 1.0 : fps;
    }

    /**
     * 把 ZLM 的 HTTP-FLV 地址换成 HTTP-TS 地址。
     *
     * <p>ZLM 的两种协议地址只有后缀不同：{@code /<app>/<stream>.live.flv} 与
     * {@code /<app>/<stream>.live.ts}。{@code GbStream} 只存了 FLV 地址，
     * 所以这里做一次纯字符串替换，避免为了一个字段改表结构。
     *
     * <p>对已经是 {@code .ts} 或格式不认识的地址原样返回。
     */
    static String toTsUrl(String flvUrl) {
        if (flvUrl == null || flvUrl.isBlank()) {
            return flvUrl;
        }
        String u = flvUrl;
        int q = u.indexOf('?');
        String tail = q < 0 ? "" : u.substring(q);
        String head = q < 0 ? u : u.substring(0, q);
        if (head.endsWith(".live.flv")) {
            return head.substring(0, head.length() - ".live.flv".length()) + ".live.ts" + tail;
        }
        if (head.endsWith(".flv")) {
            return head.substring(0, head.length() - ".flv".length()) + ".ts" + tail;
        }
        return flvUrl;
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    @Override
    public AutoCloseable start(Consumer<Frame> onFrame) {
        if (streamUrl == null || streamUrl.isBlank()) {
            log.warn("视觉取帧：流地址为空，跳过: source={}", sourceId);
            return () -> { };
        }
        if (!Files.isExecutable(Path.of(FFMPEG))) {
            log.warn("视觉取帧：ffmpeg 不可执行（{}），跳过: source={}", FFMPEG, sourceId);
            return () -> { };
        }
        if (!running.compareAndSet(false, true)) {
            // 重复 start：返回一个只停自己的句柄，避免把已有管道关掉
            return () -> { };
        }

        List<String> cmd = new ArrayList<>(List.of(
                FFMPEG,
                "-hide_banner",
                "-loglevel", "error",
                // 容忍解码错误：活流刚开始可能丢半个 GOP
                "-err_detect", "ignore_err",
                // 从管道读 MPEG-TS，用 fps 滤镜按目标频率出图（这是「常驻」的关键）
                "-i", "pipe:0",
                "-vf", "fps=" + fps,
                "-f", "image2pipe",
                "-vcodec", "mjpeg",
                "-q:v", JPEG_QUALITY,
                "pipe:1"));

        try {
            Process p = new ProcessBuilder(cmd).start();
            process = p;
            // stderr 必须抽干，否则 ffmpeg 写满缓冲后会阻塞
            drainError(p.getErrorStream());
            puller = startPuller(p);
            reader = startReader(p, onFrame);
            log.info("视觉取帧已启动: source={} fps={} url={}", sourceId, fps, streamUrl);
        } catch (IOException e) {
            running.set(false);
            log.warn("视觉取帧启动失败: source={} {}", sourceId, e.getMessage());
        }
        return this::stop;
    }

    /** 停止：先断流让 ffmpeg 收尾，超时再强杀。 */
    private void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Process p = process;
        if (p != null) {
            try {
                p.getOutputStream().close();
            } catch (IOException ignored) {
                // 已经关了
            }
            try {
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        interrupt(puller);
        interrupt(reader);
        process = null;
        log.info("视觉取帧已停止: source={} 累计出帧={}", sourceId, produced.get());
    }

    // ==================== 内部线程 ====================

    /** 拉 MPEG-TS 写进 ffmpeg 的 stdin。 */
    private Thread startPuller(Process p) {
        Thread t = new Thread(() -> {
            try (InputStream in = openStream(streamUrl);
                 OutputStream stdin = p.getOutputStream()) {
                byte[] buf = new byte[32 * 1024];
                while (running.get()) {
                    int n = in.read(buf);
                    if (n < 0) {
                        break;
                    }
                    stdin.write(buf, 0, n);
                    stdin.flush();
                }
            } catch (IOException e) {
                if (running.get()) {
                    log.debug("视觉取帧拉流中断: source={} {}", sourceId, e.getMessage());
                }
            } finally {
                // 断流让 ffmpeg 知道没有更多输入
                try {
                    p.getOutputStream().close();
                } catch (IOException ignored) {
                    // 已关闭
                }
            }
        }, "vision-pull-" + sourceId);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * 从 ffmpeg 的 stdout 按 JPEG 标记切帧。
     *
     * <p>image2pipe 输出的是裸 JPEG 序列，没有长度前缀，只能靠
     * {@code FFD8 ... FFD9} 定界。扫描时用一个字节的「上一字节」状态，
     * 不回溯整个缓冲，单帧开销是 O(n)。
     */
    private Thread startReader(Process p, Consumer<Frame> onFrame) {
        Thread t = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                ByteArrayOutputStream frame = new ByteArrayOutputStream(256 * 1024);
                byte[] buf = new byte[32 * 1024];
                boolean inFrame = false;
                int prev = -1;
                int n;
                while (running.get() && (n = in.read(buf)) >= 0) {
                    for (int i = 0; i < n; i++) {
                        int b = buf[i] & 0xFF;
                        if (!inFrame) {
                            // 找 JPEG 起始标记 FF D8
                            if (prev == 0xFF && b == 0xD8) {
                                frame.reset();
                                frame.write(0xFF);
                                frame.write(0xD8);
                                inFrame = true;
                            }
                        } else {
                            frame.write(b);
                            // 找结束标记 FF D9
                            if (prev == 0xFF && b == 0xD9) {
                                inFrame = false;
                                byte[] jpeg = frame.toByteArray();
                                if (jpeg.length > 1024 && jpeg.length <= MAX_JPEG_BYTES) {
                                    produced.incrementAndGet();
                                    int[] wh = jpegSize(jpeg);
                                    /*
                                     * 宽高必须给真实值：Frame.usable() 要求
                                     * width>0 && height>0，填 0 会被流水线整帧丢弃
                                     * （表现为「取帧正常但一帧都检测不到」）。
                                     * 尺寸从 JPEG 的 SOF 段解析，见 jpegSize。
                                     */
                                    onFrame.accept(new Frame(jpeg, wh[0], wh[1],
                                            System.currentTimeMillis(), sourceId));
                                }
                                frame.reset();
                            }
                            if (frame.size() > MAX_JPEG_BYTES) {
                                // 标记解析跑飞：放弃这一帧重新找起点
                                inFrame = false;
                                frame.reset();
                            }
                        }
                        prev = b;
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    log.debug("视觉取帧读输出中断: source={} {}", sourceId, e.getMessage());
                }
            } finally {
                log.debug("视觉取帧读线程退出: source={} 累计出帧={}", sourceId, produced.get());
            }
        }, "vision-read-" + sourceId);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * 从 JPEG 的 SOF 段解析宽高。
     *
     * <p>为什么不用 {@code ImageIO}：那要把整帧解码一遍，只是拿两个数字，
     * 却多付一次完整解码的 CPU（1 fps × N 路不划算）。这里只走标记链，几十个字节。
     *
     * <p>解析失败返回 {@code {1, 1}}：宁可让模型按错误尺寸缩放，
     * 也不要因为一个畸形帧把整路检测停掉（{@code Frame.usable()} 只要求 > 0）。
     */
    static int[] jpegSize(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4) {
            return new int[]{1, 1};
        }
        int i = 2; // 跳过 SOI (FF D8)
        while (i + 9 < jpeg.length) {
            if ((jpeg[i] & 0xFF) != 0xFF) {
                i++; // 填充字节，继续找下一个标记
                continue;
            }
            int marker = jpeg[i + 1] & 0xFF;
            // SOF0..SOF15，排除 DHT(C4) / JPG(C8) / DAC(CC)
            if (marker >= 0xC0 && marker <= 0xCF
                    && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                int h = ((jpeg[i + 5] & 0xFF) << 8) | (jpeg[i + 6] & 0xFF);
                int w = ((jpeg[i + 7] & 0xFF) << 8) | (jpeg[i + 8] & 0xFF);
                return new int[]{w > 0 ? w : 1, h > 0 ? h : 1};
            }
            int len = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
            if (len < 2) {
                break; // 长度非法，放弃
            }
            i += 2 + len;
        }
        return new int[]{1, 1};
    }

    /** 抽干 stderr（只在前几条记日志，避免刷屏）。 */
    private void drainError(InputStream err) {
        Thread t = new Thread(() -> {
            int logged = 0;
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(err))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    /*
                     * 前 3 条非空行用 WARN：ffmpeg 的错误就在这里，
                     * 而「解不出帧」从别处完全看不出来。
                     *
                     * 最典型的一条：`[flv] Video codec (c) is not implemented`
                     * —— 那是 codecId=12（HEVC legacy FLV），ffmpeg 7.0.2 的 flv
                     * demuxer 不支持（只认 E-RTMP 增强头）。这就是为什么本类
                     * 改用 MPEG-TS 而不是 HTTP-FLV，详见类注释。
                     * 之前把它记成 debug，导致「检测开了但一帧都没有」查了很久。
                     */
                    if (logged < 3 && !line.isBlank()) {
                        log.warn("ffmpeg[{}]: {}", sourceId, line);
                        logged++;
                    }
                }
            } catch (IOException ignored) {
                // 进程退出时正常
            }
        }, "vision-err-" + sourceId);
        t.setDaemon(true);
        t.start();
    }

    /** 已产出的帧数（排障用）。 */
    public long producedFrames() {
        return produced.get();
    }

    /** 打开 HTTP-TS 流（与 FfmpegSnapService 相同的做法）。 */
    private InputStream openStream(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "gb28181-vision/1.0");
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new IOException("拉流失败 HTTP " + code);
        }
        return conn.getInputStream();
    }

    private static void interrupt(Thread t) {
        if (t != null) {
            t.interrupt();
        }
    }
}
