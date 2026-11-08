package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.hikvision.HikvisionSsListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 海康抓图直传的落盘入口。
 *
 * <h3>为什么直传比反向抓图好</h3>
 * 反向抓图要平台连设备（NAT 下未必通），且图片要设备编码后完整传一遍。
 * 直传是设备连平台上传，平台只落盘 —— 设备本来就会为「报警联动抓图」
 * 上传图片，平台顺势收下，等于零额外流量。
 *
 * <h3>与 ISAPI 抓图的关系</h3>
 * 两条路径并存，互不替代：
 * <ul>
 *   <li>直传（这里）：设备主动推，平台收，适合报警联动与定时抓图；</li>
 *   <li>ISAPI（{@code HikvisionDeviceExtensions.snapshot}）：平台主动要，适合
 *       「用户点一下立刻要一张」。</li>
 * </ul>
 * 直传的图片可能比用户请求早几秒，所以「点一下立刻要」仍应走 ISAPI。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HikvisionPictureReceiver implements HikvisionSsListener.PictureSink {

    private final SnapService snapService;

    @Override
    public void onPicture(String serial, String name, byte[] content) {
        if (content == null || content.length == 0) {
            return;
        }
        // 序列号可能解析不出来（设备 URI 格式随固件变），此时落到 unknown 目录，
        // 图本身仍可查看，不至于因为解析失败把图丢了
        String device = serial == null || serial.isBlank() ? "unknown" : serial;
        // 通道号从文件名里尽力解析；解析不出来就用 1（单通道设备占多数）
        String channel = String.valueOf(channelOf(name));
        String url = snapService.saveBytes(device, channel, content);
        if (url == null) {
            log.warn("海康抓图落盘失败: 设备={} 文件={} 大小={}",
                    device, name, content.length);
        } else {
            log.info("海康抓图已落盘: 设备={} 通道={} 大小={} 地址={}",
                    device, channel, content.length, url);
        }
    }

    /**
     * 从文件名解析通道号。
     *
     * <p>海康文件名形如 {@code DS-2CD3T46_序列号_通道号_时间.jpg}，
     * 但也有 {@code ch1_...} 之类，所以两种都试。
     */
    static int channelOf(String name) {
        if (name == null || name.isBlank()) {
            return 1;
        }
        String base = name;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        // 形态一：ch1_xxx / CH01_xxx
        int ch = base.toLowerCase().indexOf("ch");
        if (ch >= 0) {
            int i = ch + 2;
            StringBuilder digits = new StringBuilder();
            while (i < base.length() && Character.isDigit(base.charAt(i))) {
                digits.append(base.charAt(i));
                i++;
            }
            if (!digits.isEmpty()) {
                return safeChannel(Integer.parseInt(digits.toString()));
            }
        }
        // 形态二：下划线分段里找纯数字段
        String[] parts = base.split("[_.\\-]");
        for (String p : parts) {
            if (p.length() <= 2 && p.chars().allMatch(Character::isDigit) && !p.isEmpty()) {
                int n = Integer.parseInt(p);
                if (n >= 1 && n <= 64) {
                    return n;
                }
            }
        }
        return 1;
    }

    private static int safeChannel(int n) {
        return n >= 1 && n <= 64 ? n : 1;
    }
}
