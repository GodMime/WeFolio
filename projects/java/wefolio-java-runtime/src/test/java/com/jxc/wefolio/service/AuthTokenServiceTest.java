package com.jxc.wefolio.service;

import com.jxc.wefolio.common.cache.LocalCacheService;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.mapper.UserEntityMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 认证令牌解析与缓存逻辑测试。
 */
@ExtendWith(MockitoExtension.class)
class AuthTokenServiceTest {

    @Mock
    private MiniappAuthService miniappAuthService;

    @Mock
    private UserEntityMapper userEntityMapper;

    @Test
    void resolvesUserIdFromCacheWithoutQueryingDatabase() {
        LocalCacheService cacheService = new LocalCacheService();
        cacheService.put("auth:token:wf-dev-user-7", 7L, Duration.ofMinutes(10));
        AuthTokenService service = new AuthTokenService(miniappAuthService, userEntityMapper, cacheService);

        Optional<Long> userId = service.resolveAuthenticatedUserId("Bearer wf-dev-user-7");

        assertThat(userId).contains(7L);
        verify(userEntityMapper, never()).selectById(7L);
    }

    @Test
    void resolvesCachedTokenWrappedByUnicodeWhitespace() {
        LocalCacheService cacheService = new LocalCacheService();
        cacheService.put("auth:token:wf-dev-user-7", 7L, Duration.ofMinutes(10));
        AuthTokenService service = new AuthTokenService(miniappAuthService, userEntityMapper, cacheService);

        Optional<Long> fullWidthSpaceUserId = service.resolveAuthenticatedUserId("\u3000Bearer wf-dev-user-7\u3000");
        Optional<Long> nonBreakingSpaceUserId = service.resolveAuthenticatedUserId("\u00A0Bearer wf-dev-user-7\u00A0");
        Optional<Long> nonBreakingSeparatorUserId = service.resolveAuthenticatedUserId("\u3000Bearer\u00A0wf-dev-user-7\u3000");

        assertThat(fullWidthSpaceUserId).contains(7L);
        assertThat(nonBreakingSpaceUserId).contains(7L);
        assertThat(nonBreakingSeparatorUserId).contains(7L);
        verify(userEntityMapper, never()).selectById(7L);
    }

    @Test
    void validatesUserAndWritesCacheWhenCacheMisses() {
        when(miniappAuthService.resolveUserId("Bearer wf-dev-user-7")).thenReturn(7L);
        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setStatus("ACTIVE");
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        LocalCacheService cacheService = new LocalCacheService();
        AuthTokenService service = new AuthTokenService(miniappAuthService, userEntityMapper, cacheService);

        Optional<Long> userId = service.resolveAuthenticatedUserId("Bearer wf-dev-user-7");

        assertThat(userId).contains(7L);
        assertThat(cacheService.get("auth:token:wf-dev-user-7", Long.class)).contains(7L);
    }

    @Test
    void rejectsInvalidTokenWithoutWritingCache() {
        when(miniappAuthService.resolveUserId("Bearer invalid")).thenReturn(null);
        LocalCacheService cacheService = new LocalCacheService();
        AuthTokenService service = new AuthTokenService(miniappAuthService, userEntityMapper, cacheService);

        Optional<Long> userId = service.resolveAuthenticatedUserId("Bearer invalid");

        assertThat(userId).isEmpty();
        assertThat(cacheService.get("auth:token:invalid", Long.class)).isEmpty();
    }

    @Test
    void rejectsDisabledUser() {
        when(miniappAuthService.resolveUserId("Bearer wf-dev-user-7")).thenReturn(7L);
        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setStatus("DISABLED");
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        LocalCacheService cacheService = new LocalCacheService();
        AuthTokenService service = new AuthTokenService(miniappAuthService, userEntityMapper, cacheService);

        Optional<Long> userId = service.resolveAuthenticatedUserId("Bearer wf-dev-user-7");

        assertThat(userId).isEmpty();
        assertThat(cacheService.get("auth:token:wf-dev-user-7", Long.class)).isEmpty();
    }

    @Test
    void evictUserClearsCachedTokenBeforeDisabledUserAccessesAgain() {
        when(miniappAuthService.resolveUserId("Bearer wf-dev-user-7")).thenReturn(7L);
        UserEntity activeUser = new UserEntity();
        activeUser.setId(7L);
        activeUser.setStatus("ACTIVE");
        UserEntity disabledUser = new UserEntity();
        disabledUser.setId(7L);
        disabledUser.setStatus("DISABLED");
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser, disabledUser);
        LocalCacheService cacheService = new LocalCacheService();
        AuthTokenService service = new AuthTokenService(miniappAuthService, userEntityMapper, cacheService);

        Optional<Long> cachedUserId = service.resolveAuthenticatedUserId("Bearer wf-dev-user-7");
        service.evictUser(7L);
        Optional<Long> resolvedAfterDisabled = service.resolveAuthenticatedUserId("Bearer wf-dev-user-7");

        assertThat(cachedUserId).contains(7L);
        assertThat(resolvedAfterDisabled).isEmpty();
        assertThat(cacheService.get("auth:token:wf-dev-user-7", Long.class)).isEmpty();
        verify(userEntityMapper, times(2)).selectById(7L);
    }
}
