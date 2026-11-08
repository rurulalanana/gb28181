package io.github.rurulalanana.starter.service.control;

import io.github.rurulalanana.gb28181.sip.Gb28181SipClient;
import io.github.rurulalanana.starter.service.ControlAckService;
import io.github.rurulalanana.starter.service.DeviceService;
import io.github.rurulalanana.starter.service.Gb28181SipClientProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 国标侧的<b>协议兼容性</b>回归测试。
 *
 * <h3>为什么要专门测这个</h3>
 * 前端把光圈/聚焦从「点一次」改成「按住持续、松手停止」后，
 * 停止指令会发回 {@code /control/fi} 而不是 {@code /control/ptz}。
 *
 * <p>这对国标设备是<b>关键风险点</b>：两条接口如果生成的 PTZCmd 不一致，
 * 就会出现「大华能停、国标停不住」或「国标一松手就把别的动作也停了」这类
 * 只在现场才暴露的问题。而它是<b>纯静态等价性</b>，跑单测就能钉死，
 * 不该靠部署到现场去试。
 *
 * <p>GB/T 28181 附录 A.3.1 的停止指令是<b>字节 4/5/6 全 0</b>
 * （{@code PtzEncoder.stop()}），对方向、变倍、聚焦、光圈一视同仁 ——
 * 这也是「两条接口的 stop 必须逐字节相同」的依据。
 */
class Gb28181FocusIrisStopTest {

    /** 一个通道编码（国标 20 位）。 */
    private static final String CH = "34020000001320000001";

    private Gb28181SipClient client;
    private Gb28181DeviceControl control;

    @BeforeEach
    void setUp() {
        Gb28181SipClientProvider provider = mock(Gb28181SipClientProvider.class);
        client = mock(Gb28181SipClient.class);
        when(provider.client()).thenReturn(client);
        when(client.nextSn()).thenReturn(1);

        DeviceService deviceService = mock(DeviceService.class);
        // 查不到通道所属设备 → 寻址对两者都退回 channelId（直连摄像机场景）
        when(deviceService.deviceIdOfChannel(anyString())).thenReturn(null);

        control = new Gb28181DeviceControl(provider, deviceService, new ControlAckService());
    }

    @Test
    @DisplayName("国标：/control/ptz 与 /control/fi 的 stop 生成完全相同的 PTZCmd")
    void stopIsIdenticalAcrossEndpoints() {
        control.ptz(new DeviceControl.Ptz(CH, "stop", null));
        control.focusIris(new DeviceControl.FocusIris(CH, "stop", null));

        ArgumentCaptor<String> xml = ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).sendMessage(eq(CH), xml.capture());
        List<String> sent = xml.getAllValues();

        String ptzStop = ptzCmdOf(sent.get(0));
        String fiStop = ptzCmdOf(sent.get(1));
        assertNotNull(ptzStop, "方向接口的报文里没有 PTZCmd：" + sent.get(0));
        assertNotNull(fiStop, "聚焦光圈接口的报文里没有 PTZCmd：" + sent.get(1));
        assertEquals(ptzStop, fiStop,
                "两条接口的停止指令必须逐字节相同，否则前端松手时"
                        + "国标设备会停不住（或误停别的动作）");
    }

    @Test
    @DisplayName("国标：停止指令是字节 4/5/6 全 0（附录 A.3.1）")
    void stopCommandHasZeroPayload() {
        control.focusIris(new DeviceControl.FocusIris(CH, "stop", null));

        ArgumentCaptor<String> xml = ArgumentCaptor.forClass(String.class);
        verify(client, times(1)).sendMessage(eq(CH), xml.capture());
        String cmd = ptzCmdOf(xml.getValue());
        assertNotNull(cmd, "报文里没有 PTZCmd：" + xml.getValue());

        /*
         * PTZCmd 是 8 字节（附录 A.3.1）：
         *   b1=A5 头 / b2=0F 版本+校验位 / b3=地址 / b4=指令码
         *   b5=水平速度 / b6=垂直速度 / b7=变倍(高4位)+地址高4位 / b8=校验和
         * 十六进制串去掉 b1~b3（6 位）后，前 6 位就是 b4/b5/b6。
         * 停止要求这三字节全 0 —— b7 是变倍字段、b8 是校验和，都不该算进来。
         */
        String hex = cmd.replace("0x", "").replace(" ", "").toUpperCase();
        assertEquals(16, hex.length(), "PTZCmd 应为 8 字节：" + cmd);
        String payload = hex.substring(6, 12);
        assertEquals("000000", payload,
                "停止指令的字节 4/5/6 必须全 0（实际：" + hex + "）");
    }

    @Test
    @DisplayName("国标：聚焦/光圈是持续指令，不能退化成变倍")
    void focusIrisUsesOwnCommandCodes() {
        control.focusIris(new DeviceControl.FocusIris(CH, "focusnear", 129));
        control.focusIris(new DeviceControl.FocusIris(CH, "focusfar", 129));
        control.focusIris(new DeviceControl.FocusIris(CH, "irisin", 129));
        control.focusIris(new DeviceControl.FocusIris(CH, "irisout", 129));

        ArgumentCaptor<String> xml = ArgumentCaptor.forClass(String.class);
        verify(client, times(4)).sendMessage(eq(CH), xml.capture());
        List<String> sent = xml.getAllValues();

        // 附录 A.3.1：聚焦近 0x42、聚焦远 0x41、光圈放大 0x44、光圈缩小 0x48
        assertEquals("42", byte4Of(sent.get(0)), "聚焦近应为 0x42");
        assertEquals("41", byte4Of(sent.get(1)), "聚焦远应为 0x41");
        assertEquals("44", byte4Of(sent.get(2)), "光圈放大应为 0x44");
        assertEquals("48", byte4Of(sent.get(3)), "光圈缩小应为 0x48");
    }

    /** 从 MANSCDP 报文里取出 {@code <PTZCmd>} 的内容；没有则返回 {@code null}。 */
    private static String ptzCmdOf(String xml) {
        int start = xml.indexOf("<PTZCmd>");
        if (start < 0) {
            return null;
        }
        int end = xml.indexOf("</PTZCmd>", start);
        return end < 0 ? null : xml.substring(start + "<PTZCmd>".length(), end).trim();
    }

    /**
     * 取 PTZCmd 的字节 4（十六进制两位）。
     *
     * <p>{@code PtzEncoder} 的输出形如 {@code A50F01 42 00 05 00 00 0B}：
     * 前 6 位是地址 + 指令码 + 字节4。这里只关心字节4，所以取第 6~8 位。
     */
    private static String byte4Of(String xml) {
        String cmd = ptzCmdOf(xml);
        assertNotNull(cmd, "报文里没有 PTZCmd：" + xml);
        String hex = cmd.replace("0x", "").replace(" ", "").toUpperCase();
        return hex.substring(6, 8);
    }
}
