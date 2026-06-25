package com.jxc.wefolio.common.auth;

import com.jxc.wefolio.exception.AuthenticationRequiredException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 认证线程上下文持有器测试。
 */
class AuthContextHolderTest {

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    @Test
    void storesCurrentUserIdInThreadContext() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));

        assertThat(AuthContextHolder.getUserId()).contains(7L);
        assertThat(AuthContextHolder.requireUserId()).isEqualTo(7L);
    }

    @Test
    void requireUserIdRejectsMissingContext() {
        assertThatThrownBy(AuthContextHolder::requireUserId)
                .isInstanceOf(AuthenticationRequiredException.class)
                .hasMessage("用户未登录");
    }

    @Test
    void clearRemovesCurrentContext() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));

        AuthContextHolder.clear();

        assertThat(AuthContextHolder.getUserId()).isEmpty();
    }
}
