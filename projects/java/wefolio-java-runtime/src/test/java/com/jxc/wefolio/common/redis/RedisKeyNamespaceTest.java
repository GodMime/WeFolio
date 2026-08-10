package com.jxc.wefolio.common.redis;

import com.jxc.wefolio.config.RedisProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Redis 物理 key 命名空间测试。 */
class RedisKeyNamespaceTest {

    /** 验证物理 key 只追加统一前缀且保留逻辑 key 的可读内容。 */
    @Test
    void prefixesLogicalKeyWithoutChangingReadableContent() {
        RedisProperties properties = new RedisProperties();
        properties.setKeyPrefix("wefolio:runtime:");
        RedisKeyNamespace namespace = new RedisKeyNamespace(properties);

        assertThat(namespace.physicalKey("auth:token:plain-token"))
                .isEqualTo("wefolio:runtime:auth:token:plain-token");
    }

    /** 验证空白逻辑 key 会被明确拒绝。 */
    @Test
    void rejectsBlankLogicalKey() {
        RedisProperties properties = new RedisProperties();
        RedisKeyNamespace namespace = new RedisKeyNamespace(properties);

        assertThatThrownBy(() -> namespace.physicalKey(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Redis 逻辑 key 不能为空");
    }
}
