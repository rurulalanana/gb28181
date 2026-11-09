package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.deviceaccess.ConnectMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SDK 设备「探测失败能不能判离线」的判据。
 *
 * <h3>为什么要专门测这个</h3>
 * 这段逻辑决定界面上设备显示在线还是离线，而它踩过的两个坑都极其反直觉：
 *
 * <ol>
 *   <li><b>多实例互相覆盖</b>：两台平台共用一个库，各自跑 60 秒巡检。
 *       持有主动注册会话的实例探测成功写 {@code online=true}，
 *       另一实例拿不到会话、按 NAT 出口地址直连失败写 {@code online=false}，
 *       库里每 60 秒翻转两次，界面绝大多数时刻显示离线。</li>
 *   <li><b>重连空窗误判</b>：平台重启后设备要过几十秒才重新注册，
 *       那段空窗期注册表查不到它、退化成 CLIENT 直连（地址不可达），
 *       把一台完全正常的设备写成离线。</li>
 * </ol>
 *
 * 结论：<b>「我这个观察点看不到它」不等于「它离线了」</b>。
 */
class SdkDeviceOfflineDecisionTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 9, 20, 0, 0);

    @Test
    @DisplayName("SERVER 模式：永不判离线（注册表里有它 = 设备正主动注册着）")
    void serverModeNeverJudgesOffline() {
        // 哪怕从来没有 keepalive 记录，也不该由「探测失败」判离线：
        // 注册表能查到它本身就是最强的存活证据
        assertTrue(DeviceProvisionService.cannotJudgeOffline(
                ConnectMode.SERVER, false, null, NOW));

        assertTrue(DeviceProvisionService.cannotJudgeOffline(
                ConnectMode.SERVER, true, NOW.minusHours(2), NOW));
    }

    @Test
    @DisplayName("CLIENT 模式 + 从未注册过：直连失败就是离线")
    void clientModeWithoutRegistrationJudgesOffline() {
        // 纯局域网直连部署，地址就是设备真实地址，连不上确实是离线
        assertFalse(DeviceProvisionService.cannotJudgeOffline(
                ConnectMode.CLIENT, false, null, NOW));

        // 就算以前在线过，只要不是主动注册接入的，直连失败仍然算离线
        assertFalse(DeviceProvisionService.cannotJudgeOffline(
                ConnectMode.CLIENT, false, NOW.minusMinutes(1), NOW));
    }

    @Test
    @DisplayName("CLIENT 模式 + 曾主动注册过：窗口内保留原状态（重连空窗）")
    void clientModeWithRecentRegistrationKeepsState() {
        // 平台刚重启，设备还没重新注册上来 —— 档案地址是 NAT 出口，不可直连
        assertTrue(DeviceProvisionService.cannotJudgeOffline(
                ConnectMode.CLIENT, true, NOW.minusSeconds(30), NOW));

        assertTrue(DeviceProvisionService.cannotJudgeOffline(
                ConnectMode.CLIENT, true, NOW.minusMinutes(4), NOW));
    }

    @Test
    @DisplayName("CLIENT 模式 + 曾主动注册过：超窗口后判离线（设备真断电）")
    void clientModeWithStaleRegistrationJudgesOffline() {
        // 没人刷新 keepalive 超过窗口，说明设备确实没再出现过
        assertFalse(DeviceProvisionService.cannotJudgeOffline(
                ConnectMode.CLIENT, true, NOW.minusMinutes(6), NOW));

        assertFalse(DeviceProvisionService.cannotJudgeOffline(
                ConnectMode.CLIENT, true, NOW.minusHours(1), NOW));
    }

    @Test
    @DisplayName("窗口边界：恰好在窗口内保留，恰好超出判离线")
    void windowBoundary() {
        // 窗口是 5 分钟
        assertTrue(DeviceProvisionService.cannotJudgeOffline(
                ConnectMode.CLIENT, true, NOW.minusMinutes(5).plusSeconds(1), NOW));

        assertFalse(DeviceProvisionService.cannotJudgeOffline(
                ConnectMode.CLIENT, true, NOW.minusMinutes(5).minusSeconds(1), NOW));
    }

    @Test
    @DisplayName("曾注册过但 keepalive 为空：不判离线（刚重启，还没写过一次 true）")
    void everRegisteredWithoutKeepalive() {
        // 内存里记着它注册过，但库里 keepalive 还是空（平台刚重启、本轮还没探测成功）。
        // 此时直连必然失败（NAT 出口），不能据此判离线。
        assertTrue(DeviceProvisionService.cannotJudgeOffline(
                ConnectMode.CLIENT, true, null, NOW));
    }
}
