package com.jxc.wefolio.common.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * 缓存服务接口 — 统一业务缓存入口，后续可将本地实现替换为 Redis 实现
 */
public interface CacheService {

    /**
     * 读取缓存
     *
     * @param key 缓存键
     * @param valueType 缓存值类型
     * @return 命中且类型匹配时返回缓存值，否则返回空
     * @param <T> 缓存值类型
     */
    <T> Optional<T> get(String key, Class<T> valueType);

    /**
     * 写入缓存
     *
     * @param key 缓存键
     * @param value 缓存值
     * @param ttl 缓存有效期
     */
    void put(String key, Object value, Duration ttl);

    /**
     * 删除缓存
     *
     * @param key 缓存键
     */
    void evict(String key);
}
