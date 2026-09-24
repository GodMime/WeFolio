package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.dict.AuthTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** 支付身份必须唯一，且会话与认证完全同属一个用户。 */
class WechatPaymentIdentityTest {
    /** 禁止任取多个认证中的一条，包含禁用认证占用的名额。 */
    @Test
    void rejectsAmbiguousDisabledAndMismatchedIdentity() {
        UserAuthEntityMapper mapper = mock(UserAuthEntityMapper.class);
        UserAuthEntity auth = auth();
        when(mapper.selectList(any())).thenReturn(List.of(auth));
        assertThat(WechatPaymentIdentity.findUnique(mapper, 7L)).isSameAs(auth);
        when(mapper.selectList(any())).thenReturn(List.of(auth, auth()));
        assertThat(WechatPaymentIdentity.findUnique(mapper, 7L)).isNull();
        when(mapper.selectList(any())).thenReturn(List.of(auth));
        auth.setStatus(UserStatusDict.DISABLED.getCode());
        assertThat(WechatPaymentIdentity.findUnique(mapper, 7L)).isNull();
        assertThat(WechatPaymentIdentity.matchesSession(7L, auth(),
                new MaintainerWechatSession(7L, 12L, "test", 1L, "127.0.0.1"))).isFalse();
        assertThat(WechatPaymentIdentity.matchesSession(7L, auth(),
                new MaintainerWechatSession(8L, 11L, "test", 1L, "127.0.0.1"))).isFalse();
        assertThat(WechatPaymentIdentity.matchesSession(7L, auth(),
                new MaintainerWechatSession(7L, 11L, "test", 1L, "127.0.0.1"))).isTrue();
    }

    /** 使用保留大小写及尾空格的测试身份。 */
    private UserAuthEntity auth() {
        UserAuthEntity auth = new UserAuthEntity();
        auth.setId(11L);
        auth.setUserId(7L);
        auth.setAuthType(AuthTypeDict.WECHAT_MINI_APP.getCode());
        auth.setStatus(UserStatusDict.ACTIVE.getCode());
        auth.setOpenId("TestOpenId ");
        return auth;
    }
}
