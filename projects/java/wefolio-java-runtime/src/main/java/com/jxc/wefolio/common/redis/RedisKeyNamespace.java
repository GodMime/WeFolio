package com.jxc.wefolio.common.redis;

import com.jxc.wefolio.config.RedisProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 为业务逻辑 key 添加运行时统一前缀。 */
@Component
@ConditionalOnProperty(prefix = "redis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisKeyNamespace {

    /** 所有 runtime Redis key 共用的物理前缀。 */
    private final String keyPrefix;

    /**
     * 创建 Redis key 命名空间。
     *
     * @param properties Redis 配置
     */
    public RedisKeyNamespace(RedisProperties properties) {
        this.keyPrefix = properties.getKeyPrefix();
    }

    /**
     * 将可读的业务逻辑 key 转换为 Redis 物理 key。
     *
     * @param logicalKey 业务逻辑 key
     * @return 带运行时前缀的物理 key
     */
    public String physicalKey(String logicalKey) {
        if (!StringUtils.hasText(logicalKey)) {
            throw new IllegalArgumentException("Redis 逻辑 key 不能为空");
        }
        return keyPrefix + logicalKey;
    }
}
