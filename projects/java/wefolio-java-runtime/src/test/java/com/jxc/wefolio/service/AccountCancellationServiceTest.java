package com.jxc.wefolio.service;

import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.mapper.UserEntityMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountCancellationServiceTest {

    @Mock
    private UserEntityMapper userEntityMapper;

    @Mock
    private AuthTokenService authTokenService;

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    @Test
    void cancelCurrentUserDisablesAccountAndEvictsCachedTokens() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setStatus("ACTIVE");
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(userEntityMapper.updateById(any(UserEntity.class))).thenReturn(1);
        AccountCancellationService service = new AccountCancellationService(userEntityMapper, authTokenService);

        service.cancelCurrentUser();

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userEntityMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("DISABLED");
        assertThat(captor.getValue().getUpdatedAt()).isNull();
        verify(authTokenService).evictUser(7L);
    }
}
