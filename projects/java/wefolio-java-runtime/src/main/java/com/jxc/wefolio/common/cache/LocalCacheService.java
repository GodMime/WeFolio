package com.jxc.wefolio.common.cache;

import com.jxc.wefolio.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 本地缓存服务 — 基于 JVM 内存保存缓存数据，并按写入时设置的过期时间自动失效
 */
@Service
public class LocalCacheService implements CacheService {

    /** 缓存存储 */
    private final ConcurrentMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** 时钟，用于计算缓存过期时间 */
    private final Clock clock;

    /**
     * 创建本地缓存服务
     */
    public LocalCacheService() {
        this(Clock.systemUTC());
    }

    /**
     * 创建本地缓存服务
     *
     * @param clock 时钟
     */
    public LocalCacheService(Clock clock) {
        this.clock = clock;
    }

    /**
     * 读取缓存
     *
     * @param key 缓存键
     * @param valueType 缓存值类型
     * @return 命中且类型匹配时返回缓存值，否则返回空
     * @param <T> 缓存值类型
     */
    @Override
    public <T> Optional<T> get(String key, Class<T> valueType) {
        validateKey(key);
        if (valueType == null) {
            throw new BusinessException("缓存值类型不能为空");
        }
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.isExpired(clock.instant())) {
            cache.remove(key, entry);
            return Optional.empty();
        }
        if (!valueType.isInstance(entry.value())) {
            return Optional.empty();
        }
        return Optional.of(valueType.cast(entry.value()));
    }

    /**
     * 写入缓存
     *
     * @param key 缓存键
     * @param value 缓存值
     * @param ttl 缓存有效期
     */
    @Override
    public void put(String key, Object value, Duration ttl) {
        validateKey(key);
        if (value == null) {
            throw new BusinessException("缓存值不能为空");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new BusinessException("缓存有效期必须大于 0");
        }
        cache.put(key, new CacheEntry(value, clock.instant().plus(ttl)));
    }

    /**
     * 删除缓存
     *
     * @param key 缓存键
     */
    @Override
    public void evict(String key) {
        validateKey(key);
        cache.remove(key);
    }

    /**
     * 校验缓存键
     *
     * @param key 缓存键
     */
    private void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BusinessException("缓存 key 不能为空");
        }
    }

    /**
     * 缓存条目
     *
     * @param value 缓存值
     * @param expiresAt 过期时间
     */
    private record CacheEntry(Object value, Instant expiresAt) {

        /**
         * 判断缓存条目是否已经过期
         *
         * @param now 当前时间
         * @return 是否过期
         */
        private boolean isExpired(Instant now) {
            return !expiresAt.isAfter(now);
        }
    }
}
