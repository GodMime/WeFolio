package com.jxc.wefolio.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/** Redis 单机连接与运行时 key 配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "redis")
public class RedisProperties {

    /** 未显式配置时使用的 Redis 物理 key 前缀。 */
    public static final String DEFAULT_KEY_PREFIX = "wefolio:runtime:";

    /** 未显式配置时使用的连接与命令超时时间。 */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);

    /** 是否启用生产 Redis 基础设施。 */
    private boolean enabled = true;

    /** Redisson 单机地址，例如 redis://127.0.0.1:6379。 */
    private String address;

    /** Redis 密码；空字符串表示不设置密码。 */
    private String password = "";

    /** Redis 逻辑数据库编号。 */
    private int database;

    /** 本服务所有物理 key 的统一前缀。 */
    private String keyPrefix = DEFAULT_KEY_PREFIX;

    /** 建立 Redis 连接的超时时间。 */
    private Duration connectTimeout = DEFAULT_TIMEOUT;

    /** Redis 命令响应超时时间。 */
    private Duration commandTimeout = DEFAULT_TIMEOUT;

    /**
     * 校验启用 Redis 时所需的配置。
     */
    public void validate() {
        if (!StringUtils.hasText(address)) {
            throw new IllegalArgumentException("Redis 地址不能为空");
        }
        if (database < 0) {
            throw new IllegalArgumentException("Redis 数据库编号不能小于 0");
        }
        if (!StringUtils.hasText(keyPrefix)) {
            throw new IllegalArgumentException("Redis key 前缀不能为空");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("Redis 连接超时必须大于 0");
        }
        if (commandTimeout == null || commandTimeout.isZero() || commandTimeout.isNegative()) {
            throw new IllegalArgumentException("Redis 命令超时必须大于 0");
        }
    }
}
