package io.github.rurulalanana.starter.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 抓图通道号解析测试。
 *
 * <p>通道号错了会把 A 通道的图存成 B 通道，用户看到的是「画面和截图对不上」，
 * 而且不会报任何错。所以这里把已知的文件名形态都钉住。
 */
class HikvisionPictureReceiverChannelTest {

    @Test
    void 从ch前缀解析() {
        assertEquals(2, HikvisionPictureReceiver.channelOf("ch2_20240101120000.jpg"));
        assertEquals(12, HikvisionPictureReceiver.channelOf("CH12_20240101120000.jpg"));
        assertEquals(1, HikvisionPictureReceiver.channelOf("ch1.jpg"));
    }

    @Test
    void 从下划线数字段解析() {
        // 形态：型号_序列号_通道_时间
        assertEquals(3, HikvisionPictureReceiver.channelOf(
                "DS-2CD3T46_ABC123_3_20240101120000.jpg"));
    }

    @Test
    void 带路径也能解析() {
        assertEquals(2, HikvisionPictureReceiver.channelOf("/tmp/pic/ch2_20240101.jpg"));
        assertEquals(5, HikvisionPictureReceiver.channelOf(
                "C:\\pics\\DS-2CD_ABC_5_20240101.jpg"));
    }

    @Test
    void 解析不出来默认通道一() {
        // 单通道设备占多数，默认 1 比默认 0 安全（ISAPI 通道号从 1 开始）
        assertEquals(1, HikvisionPictureReceiver.channelOf(null));
        assertEquals(1, HikvisionPictureReceiver.channelOf(""));
        assertEquals(1, HikvisionPictureReceiver.channelOf("image.jpg"));
    }

    @Test
    void 超出范围的通道号回退() {
        // 64 路以上的设备很少，超出多半是解析错了（把时间戳当通道号）
        assertEquals(1, HikvisionPictureReceiver.channelOf("ch99.jpg"));
        assertEquals(1, HikvisionPictureReceiver.channelOf("ch0.jpg"));
    }

    @Test
    void 时间戳不会被误认为通道号() {
        // 文件名里的 20240101120000 是 14 位，不该被当成通道号
        assertEquals(1, HikvisionPictureReceiver.channelOf("DS-2CD_ABC_20240101120000.jpg"));
    }
}
