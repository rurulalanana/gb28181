package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.starter.common.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 对讲会话归属校验的离线单测。
 *
 * <p>不连设备、不连数据库、不需要 Sa-Token 上下文：只测纯函数，避免设备网段不通时
 * 「测试红」被误判成鉴权逻辑有问题。talkId 随机化 + owner 校验共同堵住越权收听/注入。
 */
class DahuaTalkOwnershipTest {

    @ParameterizedTest
    @CsvSource({
            "alice, alice, talk-9f1c",
            "1001, 1001, talk-8a7b",
            "admin, admin, talk-0001"
    })
    @DisplayName("同账号放行")
    void ownerMatches(String owner, String loginId, String talkId) {
        assertDoesNotThrow(() -> DahuaTalkService.assertOwnership(owner, loginId, talkId));
    }

    @ParameterizedTest
    @CsvSource({
            "alice, bob, talk-9f1c",
            "admin, admin2, talk-9f1c"
    })
    @DisplayName("异账号拒绝")
    void ownerMismatchRejected(String owner, String loginId, String talkId) {
        assertThrows(BizException.class,
                () -> DahuaTalkService.assertOwnership(owner, loginId, talkId));
    }

    @Test
    @DisplayName("空 loginId 拒绝")
    void blankLoginRejected() {
        assertThrows(BizException.class,
                () -> DahuaTalkService.assertOwnership("alice", "", "talk-9f1c"));
        assertThrows(BizException.class,
                () -> DahuaTalkService.assertOwnership("alice", null, "talk-9f1c"));
    }

    @Test
    @DisplayName("talkId 缺失/空白拒绝")
    void blankTalkIdRejected() {
        assertThrows(BizException.class,
                () -> DahuaTalkService.assertOwnership("alice", "alice", null));
        assertThrows(BizException.class,
                () -> DahuaTalkService.assertOwnership("alice", "alice", "  "));
    }

    @Test
    @DisplayName("owner 缺失拒绝")
    void nullOwnerRejected() {
        assertThrows(BizException.class,
                () -> DahuaTalkService.assertOwnership(null, "alice", "talk-9f1c"));
    }

    @Test
    @DisplayName("owner 与 loginId 同时缺失也要拒绝（不能把两个 null 都当成字符串 \"null\"）")
    void bothNullRejected() {
        /*
         * 早期写法是 String.valueOf(loginId).equals(String.valueOf(owner))：
         * 两个 null 都会变成 "null" 从而<b>相等</b>，这条会被放行。
         * 现在先判空再比较，必须拒绝。
         */
        assertThrows(BizException.class,
                () -> DahuaTalkService.assertOwnership(null, null, "talk-9f1c"));
    }
}
