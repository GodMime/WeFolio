package com.jxc.wefolio.service;

import com.jxc.wefolio.common.cache.CacheService;
import com.jxc.wefolio.common.auth.AuthorizationHeaderUtils;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.mapper.UserEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 登录令牌认证服务 — 统一解析并缓存 Authorization 对应的用户身份。
 */
@Service
@RequiredArgsConstructor
public class AuthTokenService {

    /** 登录令牌缓存 key 前缀 */
    private static final String CACHE_KEY_PREFIX = "auth:token:";

    /** 用户令牌索引缓存 key 前缀 */
    private static final String USER_TOKEN_CACHE_KEY_PREFIX = "auth:user-tokens:";

    /** 登录态解析缓存有效期 */
    private static final Duration AUTH_CACHE_TTL = Duration.ofMinutes(10);

    /** 小程序登录服务 */
    private final MiniappAuthService miniappAuthService;

    /** 用户资料 Mapper */
    private final UserEntityMapper userEntityMapper;

    /** 统一缓存服务 */
    private final CacheService cacheService;

    /** 用户令牌反向索引监视器，保证本地缓存读改写与清理互斥 */
    private final Object userTokenIndexMonitor = new Object();

    /**
     * 解析并校验当前登录用户 ID。
     *
     * @param authorization Authorization 请求头
     * @return 已认证用户 ID
     */
    public Optional<Long> resolveAuthenticatedUserId(String authorization) {
        String token = normalizeToken(authorization);
        if (token.isBlank()) {
            return Optional.empty();
        }

        String cacheKey = buildCacheKey(token);
        Optional<Long> cachedUserId = cacheService.get(cacheKey, Long.class);
        if (cachedUserId.isPresent()) {
            return cachedUserId;
        }

        MiniappAuthService.ResolvedAuthToken resolvedToken = miniappAuthService.resolveAuthToken(authorization);
        if (resolvedToken == null) {
            return Optional.empty();
        }
        Long userId = resolvedToken.userId();
        UserEntity user = userEntityMapper.selectById(userId);
        if (user == null || !UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) {
            return Optional.empty();
        }

        Duration cacheTtl = resolveCacheTtl(resolvedToken.expiresAt());
        if (cacheTtl.isZero() || cacheTtl.isNegative()) {
            return Optional.empty();
        }
        cacheService.put(cacheKey, userId, cacheTtl);
        rememberUserToken(userId, token);
        return Optional.of(userId);
    }

    /**
     * 清理指定用户的全部登录令牌缓存。
     *
     * @param userId 用户 ID
     */
    public void evictUser(Long userId) {
        if (userId == null) {
            return;
        }
        synchronized (userTokenIndexMonitor) {
            String userTokenCacheKey = buildUserTokenCacheKey(userId);
            Set<String> tokens = readUserTokens(userTokenCacheKey);
            for (String token : tokens) {
                cacheService.evict(buildCacheKey(token));
            }
            cacheService.evict(userTokenCacheKey);
        }
    }

    /**
     * 清理指定 Authorization 对应的登录令牌缓存。
     *
     * @param authorization Authorization 请求头
     */
    public void evictAuthorization(String authorization) {
        String token = normalizeToken(authorization);
        if (!token.isBlank()) {
            cacheService.evict(buildCacheKey(token));
        }
    }

    /**
     * 标准化 Authorization 中的令牌值。
     *
     * @param authorization Authorization 请求头
     * @return 去除认证类型后的令牌
     */
    String normalizeToken(String authorization) {
        return AuthorizationHeaderUtils.normalizeBearerToken(authorization);
    }

    /**
     * 构建登录令牌缓存 key。
     *
     * @param token 标准化后的令牌
     * @return 缓存 key
     */
    private String buildCacheKey(String token) {
        return CACHE_KEY_PREFIX + token;
    }

    /**
     * 计算登录态缓存有效期，不能超过令牌服务端剩余有效期。
     *
     * @param tokenExpiresAt 令牌服务端过期时间
     * @return 登录态缓存有效期
     */
    private Duration resolveCacheTtl(Instant tokenExpiresAt) {
        if (tokenExpiresAt == null) {
            return Duration.ZERO;
        }
        Duration remainingTtl = Duration.between(Instant.now(), tokenExpiresAt);
        if (remainingTtl.compareTo(AUTH_CACHE_TTL) < 0) {
            return remainingTtl;
        }
        return AUTH_CACHE_TTL;
    }

    /**
     * 记录用户与令牌的反向索引，便于用户停用时主动失效全部令牌。
     *
     * @param userId 用户 ID
     * @param token 标准化后的令牌
     */
    private void rememberUserToken(Long userId, String token) {
        synchronized (userTokenIndexMonitor) {
            String userTokenCacheKey = buildUserTokenCacheKey(userId);
            Set<String> tokens = readUserTokens(userTokenCacheKey);
            tokens.add(token);
            cacheService.put(userTokenCacheKey, Collections.unmodifiableSet(tokens), AUTH_CACHE_TTL);
        }
    }

    /**
     * 读取用户令牌索引。
     *
     * @param userTokenCacheKey 用户令牌索引缓存 key
     * @return 用户令牌集合
     */
    private Set<String> readUserTokens(String userTokenCacheKey) {
        Optional<Set> cachedTokens = cacheService.get(userTokenCacheKey, Set.class);
        if (cachedTokens.isEmpty()) {
            return new LinkedHashSet<>();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (Object token : cachedTokens.get()) {
            if (token instanceof String value && !value.isBlank()) {
                tokens.add(value);
            }
        }
        return tokens;
    }

    /**
     * 构建用户令牌索引缓存 key。
     *
     * @param userId 用户 ID
     * @return 用户令牌索引缓存 key
     */
    private String buildUserTokenCacheKey(Long userId) {
        return USER_TOKEN_CACHE_KEY_PREFIX + userId;
    }
}
