package io.github.rurulalanana.starter.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 巡迹 / 巡航组名称规整。
 *
 * <h3>为什么这段逻辑要单独测</h3>
 * 它决定「这条巡迹算不算已配置」。判错的后果很直观：
 * 界面上凭空多出几十条空白巡迹，用户以为平台造了假数据。
 *
 * <p>实际踩过的坑：平台早期用 GBK 写设备配置，把空槽的默认名写成了坏字节。
 * 设备按 UTF-8 解不出来，{@code AutoPattern[0][n].Name} 变成
 * {@code Ѳ\uFFFD E3} 这种字符串（{@code U+FFFD} 是解码器占位符，<b>不可逆</b>）。
 *
 * <p>这台设备的 32 个槽位里只有前 2 个还是 {@code Pattern1}/{@code Pattern2}，
 * 第 3~32 个全是乱码。它们 {@code Enable=false}（从没录过），
 * 但名称既不是默认名也不是空串 —— 前端按「名称非默认 = 已配置」判断，
 * 于是显示出了 30 条空巡迹。
 */
class PtzNameNormalizationTest {

    @Test
    @DisplayName("含 U+FFFD 的坏名归一成默认名（本次 bug 的直接原因）")
    void corruptedNameBecomesDefault() {
        // 设备实际返回值：U+FFFD 前后是 GBK 字节被 UTF-8 解码后的残渣
        assertEquals("Pattern3", DahuaPtzConfigService.normalizePatternName("\u0472\uFFFDE3", 3));
        assertEquals("Pattern32", DahuaPtzConfigService.normalizePatternName("\u0472\uFFFDE32", 32));
        // 只有替换字符也算坏名
        assertEquals("Pattern7", DahuaPtzConfigService.normalizePatternName("\uFFFD", 7));
        assertEquals("Pattern1", DahuaPtzConfigService.normalizePatternName("\uFFFD\uFFFD\uFFFD", 1));
    }

    @Test
    @DisplayName("空名与 null 归一成默认名")
    void blankNameBecomesDefault() {
        assertEquals("Pattern5", DahuaPtzConfigService.normalizePatternName(null, 5));
        assertEquals("Pattern6", DahuaPtzConfigService.normalizePatternName("", 6));
        assertEquals("Pattern7", DahuaPtzConfigService.normalizePatternName("   ", 7));
    }

    @Test
    @DisplayName("正常名称原样保留（不能误伤真实数据）")
    void normalNamePreserved() {
        assertEquals("Pattern1", DahuaPtzConfigService.normalizePatternName("Pattern1", 1));
        // 设备固件是繁体界面，默认名是「巡跡N」
        assertEquals("巡跡2", DahuaPtzConfigService.normalizePatternName("巡跡2", 2));
        assertEquals("巡迹3", DahuaPtzConfigService.normalizePatternName("巡迹3", 3));
        // 用户起的名字，含中文 / 数字 / 空格都要原样保留
        assertEquals("大门巡检", DahuaPtzConfigService.normalizePatternName("大门巡检", 4));
        assertEquals("A区 东侧", DahuaPtzConfigService.normalizePatternName("A区 东侧", 5));
        assertEquals("abc-123", DahuaPtzConfigService.normalizePatternName("abc-123", 6));
    }

    @Test
    @DisplayName("isCorruptedName 只认 U+FFFD，不误判正常文本")
    void isCorruptedNameDetection() {
        assertTrue(DahuaPtzConfigService.isCorruptedName("\uFFFD"));
        assertTrue(DahuaPtzConfigService.isCorruptedName("abc\uFFFDdef"));
        assertTrue(DahuaPtzConfigService.isCorruptedName("\u0472\uFFFDE3"));

        assertFalse(DahuaPtzConfigService.isCorruptedName(null));
        assertFalse(DahuaPtzConfigService.isCorruptedName(""));
        assertFalse(DahuaPtzConfigService.isCorruptedName("巡航群組1"));
        assertFalse(DahuaPtzConfigService.isCorruptedName("巡跡2"));
        // 方框字符 U+25A1 不是解码占位符，不能误判
        assertFalse(DahuaPtzConfigService.isCorruptedName("□"));
    }

    @Test
    @DisplayName("整批空槽：只有 2 个正常默认名时，其余 30 个都归一到默认名")
    void wholeBatchOfEmptySlots() {
        // 复现设备实际状态：1、2 是 Pattern1/Pattern2，3~32 是乱码
        int normalized = 0;
        for (int i = 1; i <= 32; i++) {
            String raw = i <= 2 ? "Pattern" + i : "\u0472\uFFFDE" + i;
            String name = DahuaPtzConfigService.normalizePatternName(raw, i);
            // 归一后必须全部等于默认名 —— 前端据此判定「空槽」而隐藏
            assertEquals("Pattern" + i, name, "槽位 " + i);
            normalized++;
        }
        assertEquals(32, normalized);
    }
}
