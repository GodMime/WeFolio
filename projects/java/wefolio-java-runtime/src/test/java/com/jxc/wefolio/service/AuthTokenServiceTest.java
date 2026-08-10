package com.jxc.wefolio.service;

import com.jxc.wefolio.common.cache.CacheService;
import com.jxc.wefolio.common.cache.LocalCacheService;
import com.jxc.wefolio.common.lock.TestDistributedLockExecutor;
import com.jxc.wefolio.config.LocalCacheProperties;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.InvalidAuthTokenException;
import com.jxc.wefolio.mapper.UserEntityMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    private final TestDistributedLockExecutor lockExecutor = new TestDistributedLockExecutor();

    @Mock
    private MiniappAuthService miniappAuthService;

    @Mock
    private UserEntityMapper userEntityMapper;

    @Test
    void resolvesUserIdFromCacheWithoutQueryingDatabase() {
        LocalCacheService cacheService = new LocalCacheService(new LocalCacheProperties());
        cacheService.put("auth:token:wf-dev-user-7", 7L, Duration.ofMinutes(10));
        AuthTokenService service = new AuthTokenService(
                miniappAuthService, userEntityMapper, cacheService, lockExecutor);

        Optional<Long> userId = service.resolveAuthenticatedUserId("Bearer wf-dev-user-7");

        assertThat(userId).contains(7L);
        verify(userEntityMapper, never()).selectById(7L);
    }

    @Test
    void resolvesCachedTokenWrappedByUnicodeWhitespace() {
        LocalCacheService cacheService = new LocalCacheService(new LocalCacheProperties());
        cacheService.put("auth:token:wf-dev-user-7", 7L, Duration.ofMinutes(10));
        AuthTokenService service = new AuthTokenService(
                miniappAuthService, userEntityMapper, cacheService, lockExecutor);

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
        when(miniappAuthService.resolveAuthToken("Bearer wf-dev-user-7")).thenReturn(resolvedToken(7L));
        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setStatus(UserStatusDict.ACTIVE.getCode());
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        LocalCacheService cacheService = new LocalCacheService(new LocalCacheProperties());
        AuthTokenService service = new AuthTokenService(
                miniappAuthService, userEntityMapper, cacheService, lockExecutor);

        Optional<Long> userId = service.resolveAuthenticatedUserId("Bearer wf-dev-user-7");

        assertThat(userId).contains(7L);
        assertThat(cacheService.get("auth:token:wf-dev-user-7", Long.class)).contains(7L);
    }

    @Test
    void rejectsInvalidTokenWithoutWritingCache() {
        when(miniappAuthService.resolveAuthToken("Bearer invalid")).thenReturn(null);
        LocalCacheService cacheService = new LocalCacheService(new LocalCacheProperties());
        AuthTokenService service = new AuthTokenService(
                miniappAuthService, userEntityMapper, cacheService, lockExecutor);

        Optional<Long> userId = service.resolveAuthenticatedUserId("Bearer invalid");

        assertThat(userId).isEmpty();
        assertThat(cacheService.get("auth:token:invalid", Long.class)).isEmpty();
    }

    @Test
    void treatsBrokenMaintainerTokenAsUnauthenticatedWithoutWritingCache() {
        when(miniappAuthService.resolveAuthToken("Bearer broken"))
                .thenThrow(new InvalidAuthTokenException("登录令牌解析失败，请重新登录"));
        LocalCacheService cacheService = new LocalCacheService(new LocalCacheProperties());
        AuthTokenService service = new AuthTokenService(
                miniappAuthService, userEntityMapper, cacheService, lockExecutor);

        Optional<Long> userId = service.resolveAuthenticatedUserId("Bearer broken");

        assertThat(userId).isEmpty();
        assertThat(cacheService.get("auth:token:broken", Long.class)).isEmpty();
    }

    @Test
    void rejectsDisabledUser() {
        when(miniappAuthService.resolveAuthToken("Bearer wf-dev-user-7")).thenReturn(resolvedToken(7L));
        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setStatus(UserStatusDict.DISABLED.getCode());
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        LocalCacheService cacheService = new LocalCacheService(new LocalCacheProperties());
        AuthTokenService service = new AuthTokenService(
                miniappAuthService, userEntityMapper, cacheService, lockExecutor);

        Optional<Long> userId = service.resolveAuthenticatedUserId("Bearer wf-dev-user-7");

        assertThat(userId).isEmpty();
        assertThat(cacheService.get("auth:token:wf-dev-user-7", Long.class)).isEmpty();
    }

    @Test
    void evictUserClearsCachedTokenBeforeDisabledUserAccessesAgain() {
        when(miniappAuthService.resolveAuthToken("Bearer wf-dev-user-7")).thenReturn(resolvedToken(7L));
        UserEntity activeUser = new UserEntity();
        activeUser.setId(7L);
        activeUser.setStatus(UserStatusDict.ACTIVE.getCode());
        UserEntity disabledUser = new UserEntity();
        disabledUser.setId(7L);
        disabledUser.setStatus(UserStatusDict.DISABLED.getCode());
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser, disabledUser);
        LocalCacheService cacheService = new LocalCacheService(new LocalCacheProperties());
        AuthTokenService service = new AuthTokenService(
                miniappAuthService, userEntityMapper, cacheService, lockExecutor);

        Optional<Long> cachedUserId = service.resolveAuthenticatedUserId("Bearer wf-dev-user-7");
        service.evictUser(7L);
        Optional<Long> resolvedAfterDisabled = service.resolveAuthenticatedUserId("Bearer wf-dev-user-7");

        assertThat(cachedUserId).contains(7L);
        assertThat(resolvedAfterDisabled).isEmpty();
        assertThat(cacheService.get("auth:token:wf-dev-user-7", Long.class)).isEmpty();
        verify(userEntityMapper, times(2)).selectById(7L);
    }

    @Test
    void evictUserClearsAllTokensRememberedDuringConcurrentCacheMisses() throws Exception {
        when(miniappAuthService.resolveAuthToken("Bearer token-a")).thenReturn(resolvedToken(7L));
        when(miniappAuthService.resolveAuthToken("Bearer token-b")).thenReturn(resolvedToken(7L));
        UserEntity activeUser = new UserEntity();
        activeUser.setId(7L);
        activeUser.setStatus(UserStatusDict.ACTIVE.getCode());
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser);
        CoordinatedCacheService cacheService = new CoordinatedCacheService("auth:user-tokens:7", 2);
        AuthTokenService service = new AuthTokenService(
                miniappAuthService, userEntityMapper, cacheService, lockExecutor);
        ExecutorService executorService = Executors.newFixedThreadPool(2);

        try {
            Future<Optional<Long>> first = executorService.submit(
                    () -> service.resolveAuthenticatedUserId("Bearer token-a"));
            Future<Optional<Long>> second = executorService.submit(
                    () -> service.resolveAuthenticatedUserId("Bearer token-b"));

            assertThat(first.get()).contains(7L);
            assertThat(second.get()).contains(7L);
            service.evictUser(7L);

            assertThat(cacheService.get("auth:token:token-a", Long.class)).isEmpty();
            assertThat(cacheService.get("auth:token:token-b", Long.class)).isEmpty();
        } finally {
            executorService.shutdownNow();
            executorService.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    /** 主动退出后原令牌即使尚未自然过期，也不得从缓存或离线密文恢复登录。 */
    @Test
    void revokeAuthorizationShouldRejectOnlyTheLoggedOutToken() {
        when(miniappAuthService.resolveAuthToken("Bearer token-old")).thenReturn(resolvedToken(7L));
        when(miniappAuthService.resolveAuthToken("Bearer token-new")).thenReturn(resolvedToken(7L));
        UserEntity activeUser = new UserEntity();
        activeUser.setId(7L);
        activeUser.setStatus(UserStatusDict.ACTIVE.getCode());
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser);
        LocalCacheService cacheService = new LocalCacheService(new LocalCacheProperties());
        AuthTokenService service = new AuthTokenService(
                miniappAuthService, userEntityMapper, cacheService, lockExecutor);

        assertThat(service.resolveAuthenticatedUserId("Bearer token-old")).contains(7L);
        service.revokeAuthorization("Bearer token-old");

        assertThat(service.resolveAuthenticatedUserId("Bearer token-old")).isEmpty();
        assertThat(service.resolveAuthenticatedUserId("Bearer token-new")).contains(7L);
        assertThat(cacheService.get("auth:revoked-token:token-old", Boolean.class)).contains(true);
        assertThat(cacheService.get("auth:token:token-new", Long.class)).contains(7L);
        verify(miniappAuthService, times(1)).resolveAuthToken("Bearer token-new");
    }

    /**
     * 构造已解析的测试令牌。
     *
     * @param userId 用户 ID
     * @return 已解析令牌
     */
    private MiniappAuthService.ResolvedAuthToken resolvedToken(Long userId) {
        return new MiniappAuthService.ResolvedAuthToken(userId, Instant.now().plus(Duration.ofHours(1)));
    }

    /**
     * 可控缓存服务，用于稳定复现令牌反向索引的并发读改写丢失。
     */
    private static class CoordinatedCacheService implements CacheService {

        /** 需要协调读取的缓存 key */
        private final String coordinatedKey;

        /** 等待并发读取同时到达的门闩 */
        private final CountDownLatch coordinatedReads;

        /** 内存缓存值 */
        private final Map<String, Object> values = new ConcurrentHashMap<>();

        /**
         * 创建可控缓存服务。
         *
         * @param coordinatedKey 需要协调读取的缓存 key
         * @param expectedReaders 期望并发读取数量
         */
        private CoordinatedCacheService(String coordinatedKey, int expectedReaders) {
            this.coordinatedKey = coordinatedKey;
            this.coordinatedReads = new CountDownLatch(expectedReaders);
        }

        @Override
        public <T> Optional<T> get(String key, Class<T> valueType) {
            Object value = values.get(key);
            if (coordinatedKey.equals(key) && Set.class.equals(valueType)) {
                coordinatedReads.countDown();
                try {
                    coordinatedReads.await(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!valueType.isInstance(value)) {
                return Optional.empty();
            }
            return Optional.of(valueType.cast(value));
        }

        @Override
        public void put(String key, Object value, Duration ttl) {
            values.put(key, value);
        }

        @Override
        public void evict(String key) {
            values.remove(key);
        }
    }
}
