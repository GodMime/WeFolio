package com.jxc.wefolio.service;

import com.jxc.wefolio.common.cache.CacheService;
import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.dto.WechatAccessTokenResponse;
import com.jxc.wefolio.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 微信接口调用凭证服务 — 提供无锁缓存快路径、单飞刷新和失效凭证条件刷新。
 */
@Service
@RequiredArgsConstructor
public class WechatAccessTokenService {

    /** 凭证缓存键前缀。 */
    private static final String CACHE_KEY_PREFIX = "wechat:miniapp:access-token:";

    /** 凭证到期前刷新缓冲秒数。 */
    private static final long REFRESH_BUFFER_SECONDS = 5L * 60L;

    /** 微信小程序配置。 */
    private final WechatMiniappProperties properties;

    /** 缓存服务。 */
    private final CacheService cacheService;

    /** 微信凭证远端获取器。 */
    private final WechatAccessTokenFetcher wechatAccessTokenFetcher;

    /** 按小程序隔离的刷新锁；应用运行期间保留，避免锁对象更替破坏单飞语义。 */
    private final ConcurrentMap<String, ReentrantLock> refreshLocks = new ConcurrentHashMap<>();

    /**
     * 获取可用凭证。缓存未命中时仅允许一个线程访问微信服务。
     *
     * @return 微信接口调用凭证
     */
    public String getAccessToken() {
        validateProperties();
        String cacheKey = cacheKey();
        Optional<String> cached = cacheService.get(cacheKey, String.class);
        if (cached.isPresent() && !isBlank(cached.get())) {
            return cached.get();
        }
        ReentrantLock lock = refreshLocks.computeIfAbsent(properties.getAppId(), ignored -> new ReentrantLock());
        lock.lock();
        try {
            cached = cacheService.get(cacheKey, String.class);
            if (cached.isPresent() && !isBlank(cached.get())) {
                return cached.get();
            }
            return fetchAndCache(cacheKey);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 微信拒绝某个凭证后执行条件刷新。若其他线程已经刷新，则直接复用新值。
     *
     * @param rejectedAccessToken 被微信拒绝的旧凭证
     * @return 刷新后或其他线程已经写入的凭证
     */
    public String refreshAfterRejected(String rejectedAccessToken) {
        validateProperties();
        String cacheKey = cacheKey();
        ReentrantLock lock = refreshLocks.computeIfAbsent(properties.getAppId(), ignored -> new ReentrantLock());
        lock.lock();
        try {
            String current = cacheService.get(cacheKey, String.class).orElse(null);
            if (!isBlank(current) && !current.equals(rejectedAccessToken)) {
                return current;
            }
            cacheService.evict(cacheKey);
            return fetchAndCache(cacheKey);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 访问微信并缓存新凭证。
     *
     * @param cacheKey 缓存键
     * @return 新凭证
     */
    private String fetchAndCache(String cacheKey) {
        WechatAccessTokenResponse response = wechatAccessTokenFetcher.fetch();
        if (response == null) {
            throw new BusinessException("微信 access_token 服务无响应");
        }
        if (response.getErrcode() != null && response.getErrcode() != 0) {
            throw new BusinessException("微信 access_token 获取失败：" + defaultString(response.getErrmsg(), "未知错误"));
        }
        if (isBlank(response.getAccessToken())) {
            throw new BusinessException("微信 access_token 服务未返回凭证");
        }
        cacheService.put(cacheKey, response.getAccessToken(), cacheTtl(response));
        return response.getAccessToken();
    }

    /**
     * 计算缓存有效期，避免在微信凭证临界到期时继续使用。
     *
     * @param response 微信响应
     * @return 缓存有效期
     */
    private Duration cacheTtl(WechatAccessTokenResponse response) {
        long expiresInSeconds = Math.max(60L, response.getExpiresIn() == null ? 7200L : response.getExpiresIn());
        long bufferSeconds = Math.min(REFRESH_BUFFER_SECONDS, expiresInSeconds / 2);
        return Duration.ofSeconds(expiresInSeconds - bufferSeconds);
    }

    /** 校验微信凭证配置。 */
    private void validateProperties() {
        if (isBlank(properties.getAppId()) || isBlank(properties.getAppSecret())) {
            throw new BusinessException("微信小程序配置缺失");
        }
    }

    /** @return 当前小程序的缓存键。 */
    private String cacheKey() {
        return CACHE_KEY_PREFIX + properties.getAppId();
    }

    /** @param value 文本 @return 是否为空。 */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** @param value 原值 @param fallback 默认值 @return 非空文本。 */
    private String defaultString(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }
}
