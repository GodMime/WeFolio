package com.jxc.wefolio.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/** Redisson 单机客户端配置。 */
@Configuration
@ConditionalOnProperty(prefix = "redis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedissonConfiguration {

    /**
     * 创建由 Spring 管理生命周期的 Redisson 客户端。
     *
     * @param properties Redis 配置
     * @return Redisson 客户端
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisProperties properties) {
        properties.validate();
        Config config = new Config();
        SingleServerConfig singleServer = config.useSingleServer()
                .setAddress(properties.getAddress())
                .setDatabase(properties.getDatabase())
                .setConnectTimeout(Math.toIntExact(properties.getConnectTimeout().toMillis()))
                .setTimeout(Math.toIntExact(properties.getCommandTimeout().toMillis()));
        if (StringUtils.hasText(properties.getPassword())) {
            singleServer.setPassword(properties.getPassword());
        }
        return Redisson.create(config);
    }
}
