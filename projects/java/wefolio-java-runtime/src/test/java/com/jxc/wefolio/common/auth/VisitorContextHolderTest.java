package com.jxc.wefolio.common.auth;

import com.jxc.wefolio.exception.AuthenticationRequiredException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 访客认证线程上下文持有器测试。
 */
class VisitorContextHolderTest {

    @AfterEach
    void tearDown() {
        VisitorContextHolder.clear();
    }

    @Test
    void visitorContextBehavesAsValueObject() {
        VisitorContext first = new VisitorContext(1024L, "visitor-key", "Bearer wf-visitor-v1.token");
        VisitorContext same = new VisitorContext(1024L, "visitor-key", "Bearer wf-visitor-v1.token");
        VisitorContext other = new VisitorContext(2048L, "visitor-b", "Bearer wf-visitor-v1.other");

        assertThat(first).isEqualTo(same);
        assertThat(first.hashCode()).isEqualTo(same.hashCode());
        assertThat(first).isNotEqualTo(other);
        assertThat(first.toString())
                .contains("visitorId=1024")
                .contains("visitorKey=visitor-key")
                .contains("token=***")
                .doesNotContain("Bearer wf-visitor-v1.token");
    }

    @Test
    void storesCurrentVisitorIdentityInThreadContext() {
        VisitorContextHolder.set(new VisitorContext(1024L, "visitor-key", "Bearer wf-visitor-v1.token"));

        assertThat(VisitorContextHolder.getVisitorId()).contains(1024L);
        assertThat(VisitorContextHolder.requireVisitorId()).isEqualTo(1024L);
        assertThat(VisitorContextHolder.getVisitorKey()).contains("visitor-key");
        assertThat(VisitorContextHolder.requireVisitorKey()).isEqualTo("visitor-key");
    }

    @Test
    void requireVisitorIdentityRejectsMissingContext() {
        assertThatThrownBy(VisitorContextHolder::requireVisitorId)
                .isInstanceOf(AuthenticationRequiredException.class)
                .hasMessage("访客未登录");
        assertThatThrownBy(VisitorContextHolder::requireVisitorKey)
                .isInstanceOf(AuthenticationRequiredException.class)
                .hasMessage("访客未登录");
    }

    @Test
    void clearRemovesCurrentVisitorContext() {
        VisitorContextHolder.set(new VisitorContext(1024L, "visitor-key", "Bearer wf-visitor-v1.token"));

        VisitorContextHolder.clear();

        assertThat(VisitorContextHolder.getVisitorId()).isEmpty();
        assertThat(VisitorContextHolder.getVisitorKey()).isEmpty();
        assertThat(VisitorContextHolder.current()).isEmpty();
    }
}
