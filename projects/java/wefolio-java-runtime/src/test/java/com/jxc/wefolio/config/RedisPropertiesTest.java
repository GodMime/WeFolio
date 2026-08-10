package com.jxc.wefolio.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Redis 单机配置测试。 */
class RedisPropertiesTest {

    /** 验证 Redis 配置对象在未绑定外部配置时使用安全默认值。 */
    @Test
    void usesRuntimeSafeDefaults() {
        RedisProperties properties = new RedisProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getPassword()).isEmpty();
        assertThat(properties.getDatabase()).isZero();
        assertThat(properties.getKeyPrefix()).isEqualTo("wefolio:runtime:");
        assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.getCommandTimeout()).isEqualTo(Duration.ofSeconds(3));
    }

    /** 验证启用 Redis 时拒绝缺失或非法的必要配置。 */
    @Test
    void rejectsInvalidRequiredValuesWhenValidated() {
        RedisProperties properties = new RedisProperties();

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Redis 地址不能为空");

        properties.setAddress("redis://127.0.0.1:6379");
        properties.setDatabase(-1);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Redis 数据库编号不能小于 0");

        properties.setDatabase(0);
        properties.setKeyPrefix(" ");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Redis key 前缀不能为空");

        properties.setKeyPrefix("wefolio:runtime:");
        properties.setConnectTimeout(Duration.ZERO);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Redis 连接超时必须大于 0");

        properties.setConnectTimeout(Duration.ofSeconds(3));
        properties.setCommandTimeout(Duration.ZERO);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Redis 命令超时必须大于 0");
    }
}
