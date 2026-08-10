package com.jxc.wefolio.common.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import com.jxc.wefolio.config.LocalCacheProperties;
import com.jxc.wefolio.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地缓存服务 — 基于 JVM 内存保存缓存数据，并按写入时设置的过期时间自动失效
 */
@Slf4j
@Service
@Profile("test")
public class LocalCacheService implements CacheService {

    /** 缓存状态聚合日志间隔。 */
    private static final long CACHE_STATUS_LOG_INTERVAL = 10_000L;

    /** 有容量上限、支持条目独立 TTL 的缓存。 */
    private final Cache<String, CacheEntry> cache;

    /** 写入计数，用于低频输出聚合状态。 */
    private final AtomicLong writeCount = new AtomicLong();

    /**
     * 按应用配置创建本地缓存服务。
     *
     * @param properties 本地缓存配置
     */
    @Autowired
    public LocalCacheService(LocalCacheProperties properties) {
        this(properties.getMaxSize(), Ticker.systemTicker());
    }

    /**
     * 使用可控时钟创建测试缓存服务。
     *
     * @param clock 时钟
     */
    LocalCacheService(Clock clock) {
        this(LocalCacheProperties.DEFAULT_MAX_SIZE, clockTicker(clock));
    }

    /**
     * 使用指定容量和可控时钟创建测试缓存服务。
     *
     * @param maxSize 最大条目数
     * @param clock 时钟
     */
    LocalCacheService(long maxSize, Clock clock) {
        this(maxSize, clockTicker(clock));
    }

    /** 创建 Caffeine 缓存。 */
    private LocalCacheService(long maxSize, Ticker ticker) {
        if (maxSize <= 0L) {
            throw new IllegalArgumentException("本地缓存最大容量必须大于 0");
        }
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfter(new CacheEntryExpiry())
                .ticker(ticker)
                .recordStats()
                .build();
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
        CacheEntry entry = cache.getIfPresent(key);
        if (entry == null) {
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
        long ttlNanos;
        try {
            ttlNanos = ttl.toNanos();
        } catch (ArithmeticException exception) {
            throw new BusinessException("缓存有效期超出支持范围");
        }
        cache.put(key, new CacheEntry(value, ttlNanos));
        logCacheStatusPeriodically();
    }

    /**
     * 删除缓存
     *
     * @param key 缓存键
     */
    @Override
    public void evict(String key) {
        validateKey(key);
        cache.invalidate(key);
    }

    /** 执行待处理的过期和容量淘汰维护任务。 */
    void cleanUp() {
        cache.cleanUp();
    }

    /** 获取当前估算条目数。 */
    long estimatedSize() {
        return cache.estimatedSize();
    }

    /** 每累计一定写入次数输出一次聚合状态，避免逐次访问写日志。 */
    private void logCacheStatusPeriodically() {
        long currentWriteCount = writeCount.incrementAndGet();
        if (currentWriteCount % CACHE_STATUS_LOG_INTERVAL == 0L) {
            cache.cleanUp();
            log.info("本地缓存聚合状态 estimatedSize={} evictionCount={} writeCount={}",
                    cache.estimatedSize(), cache.stats().evictionCount(), currentWriteCount);
        }
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
     * @param ttlNanos 从写入开始计算的有效期（纳秒）
     */
    private record CacheEntry(Object value, long ttlNanos) {
    }

    /** 基于测试时钟创建 Caffeine 单调时间源。 */
    private static Ticker clockTicker(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("缓存时钟不能为空");
        }
        return () -> TimeUnit.MILLISECONDS.toNanos(clock.millis());
    }

    /** 从单个缓存条目读取独立 TTL，不维护额外的过期时间映射。 */
    private static final class CacheEntryExpiry implements Expiry<String, CacheEntry> {

        @Override
        public long expireAfterCreate(String key, CacheEntry value, long currentTime) {
            return value.ttlNanos();
        }

        @Override
        public long expireAfterUpdate(String key, CacheEntry value, long currentTime, long currentDuration) {
            return value.ttlNanos();
        }

        @Override
        public long expireAfterRead(String key, CacheEntry value, long currentTime, long currentDuration) {
            // 保持原实现的写后过期语义：读取只返回剩余时长，不触发续期。
            return currentDuration;
        }
    }
}
