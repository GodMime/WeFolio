package com.jxc.wefolio.config;

import org.redisson.api.RedissonClient;
import org.redisson.api.redisnode.RedisNodes;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 在应用启动阶段通过 PING 验证 Redis 可用性。 */
@Component
@ConditionalOnProperty(prefix = "redis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisStartupVerifier implements InitializingBean {

    /** 待验证的 Redisson 客户端。 */
    private final RedissonClient redissonClient;

    /**
     * 创建启动连通性检查器。
     *
     * @param redissonClient Redisson 客户端
     */
    public RedisStartupVerifier(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * Redis 未返回 PONG 时直接阻断应用启动。
     */
    @Override
    public void afterPropertiesSet() {
        if (!redissonClient.getRedisNodes(RedisNodes.SINGLE).pingAll()) {
            throw new IllegalStateException("Redis PING 失败");
        }
    }
}
