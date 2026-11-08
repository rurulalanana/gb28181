package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.gb28181.sip.zlm.EsMediaPublisher;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import io.github.rurulalanana.gb28181.sip.zlm.PsMediaDecoder;
import io.github.rurulalanana.gb28181.sip.zlm.ZlmMediaServer;
import io.github.rurulalanana.hikvision.HikvisionPlaybackListener;
import io.github.rurulalanana.hikvision.HikvisionStreamListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 海康码流发布器：把设备推来的 PS 解成裸流后喂给 ZLM。
 *
 * <h3>链路</h3>
 * <pre>
 *   设备 ──PS──► HikvisionStreamListener ──► PsMediaDecoder ──裸流──► EsMediaPublisher ──► ZLM
 *                                                                        ↓
 *                                                              FLV / HLS / WebRTC
 * </pre>
 *
 * <h3>为什么 PS 解码交给 ZLM 而不是自己写</h3>
 * PS 里的 PES 分包、时间戳回绕、私有流、多节目都有大量边界情况。
 * ZLM 的 {@code mk_ps_decoder_*} 就是它自己收国标 RTP 时用的那套，
 * 已经处理过各种设备厂商的兼容问题。
 *
 * <h3>一路流一套（解码器 + 发布器）</h3>
 * 两者都必须按 app/stream 一一对应：解码器有内部缓冲状态，
 * 多路复用会串流（表现为两路画面互相穿插）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HikvisionPsPublisher
        implements HikvisionStreamListener.StreamDataSink,
                   HikvisionPlaybackListener.PlaybackSink {

    /**
     * 回放流的 stream 后缀。
     *
     * <p>必须与预览区分开：同一个通道可能一边预览一边回放，
     * 两者共用一个 stream 名会互相顶掉（表现为一开回放预览就断）。
     */
    private static final String PLAYBACK_SUFFIX = "-playback";

    private final Gb28181MediaPlatform platform;

    /** app/stream → 一路流的解码器与发布器。 */
    private final Map<String, Pipeline> pipelines = new ConcurrentHashMap<>();

    @Override
    public void onData(String serial, int channelNo, int streamType, boolean sysHead, byte[] data) {
        push(serial, channelNo, null, data);
    }

    @Override
    public void onEnd(String serial, int channelNo, int streamType) {
        release(serial, channelNo, null);
    }

    @Override
    public void onData(String serial, int channelNo, boolean header, byte[] data) {
        push(serial, channelNo, PLAYBACK_SUFFIX, data);
    }

    @Override
    public void onEnd(String serial, int channelNo) {
        release(serial, channelNo, PLAYBACK_SUFFIX);
    }

    /**
     * 喂一包 PS 给对应的解码器。
     *
     * @param suffix {@code null} 表示预览，{@link #PLAYBACK_SUFFIX} 表示回放
     */
    private void push(String serial, int channelNo, String suffix, byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        // app = 设备序列号，stream = 通道编码：与平台既有规则一致，
        // 这样播放地址、录像、抓图等下游逻辑一行都不用改
        String app = serial;
        String stream = serial + "-" + channelNo + (suffix == null ? "" : suffix);
        String key = app + "/" + stream;

        Pipeline pipeline = pipelines.computeIfAbsent(key, k -> create(app, stream));
        if (pipeline == null) {
            return;
        }
        // 码流头（PS system header）也要喂进去：解码器要靠它识别节目与流类型
        pipeline.decoder.input(data, data.length);
    }

    /**
     * 建一条发布链路。
     *
     * <p>ZLM 侧失败时返回 {@code null} 并记日志：单路失败不该拖垮整个监听。
     */
    private Pipeline create(String app, String stream) {
        // 媒体服务是懒加载的：原生库缺失时整个媒体栈不存在，
        // 但海康的注册/报警/ISAPI 仍然能用。这里拿不到就只放弃取流。
        ZlmMediaServer zlm = platform.mediaServer();
        if (zlm == null) {
            log.warn("媒体服务未启动，海康码流无处发布: {}/{}", app, stream);
            return null;
        }
        try {
            EsMediaPublisher publisher = zlm.createEsPublisher(app, stream);
            PsMediaDecoder decoder = zlm.createPsDecoder(app, stream,
                    (es, codec, isVideo, dts, pts) -> {
                        // 只要视频：ZLM 的 ES 发布器目前只建视频轨，
                        // 音频帧送进去会被拒（返回 0）并刷日志
                        if (isVideo) {
                            publisher.push(es);
                        }
                    });
            log.info("海康码流发布链路已建立: {}/{}", app, stream);
            return new Pipeline(decoder, publisher);
        } catch (Exception e) {
            log.warn("建立海康码流发布链路失败: {}/{} {}", app, stream, e.getMessage());
            return null;
        }
    }

    /** 主动释放某一路（设备下线或停流时调用）。 */
    public void release(String serial, int channelNo) {
        release(serial, channelNo, null);
        release(serial, channelNo, PLAYBACK_SUFFIX);
    }

    /** 释放指定类型的一路。 */
    private void release(String serial, int channelNo, String suffix) {
        String stream = serial + "-" + channelNo + (suffix == null ? "" : suffix);
        String key = serial + "/" + stream;
        Pipeline pipeline = pipelines.remove(key);
        if (pipeline != null) {
            pipeline.close();
            log.info("海康码流结束，已释放发布链路: {}", key);
        }
    }

    /** 当前活跃链路数（诊断用）。 */
    public int activeCount() {
        return pipelines.size();
    }

    /** 一路流的解码器 + 发布器。 */
    private record Pipeline(PsMediaDecoder decoder, EsMediaPublisher publisher) {

        void close() {
            try {
                decoder.close();
            } catch (Exception e) {
                // 释放失败不影响另一侧的清理
            }
            try {
                publisher.close();
            } catch (Exception e) {
                // 同上
            }
        }
    }
}
