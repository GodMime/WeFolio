package com.jxc.wefolio.config;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.api.redisnode.RedisNodes;
import org.redisson.api.redisnode.RedisSingle;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Redis 启动连通性检查测试。 */
class RedisStartupVerifierTest {

    /** 验证 Redis 单节点返回 PONG 时启动检查通过。 */
    @Test
    void acceptsPongFromSingleRedisNode() throws Exception {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RedisSingle redisSingle = mock(RedisSingle.class);
        when(redissonClient.getRedisNodes(RedisNodes.SINGLE)).thenReturn(redisSingle);
        when(redisSingle.pingAll()).thenReturn(true);

        new RedisStartupVerifier(redissonClient).afterPropertiesSet();

        verify(redisSingle).pingAll();
    }

    /** 验证 Redis 单节点未返回 PONG 时启动立即失败。 */
    @Test
    void failsStartupWhenPingDoesNotReturnPong() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RedisSingle redisSingle = mock(RedisSingle.class);
        when(redissonClient.getRedisNodes(RedisNodes.SINGLE)).thenReturn(redisSingle);
        when(redisSingle.pingAll()).thenReturn(false);

        RedisStartupVerifier verifier = new RedisStartupVerifier(redissonClient);

        assertThatThrownBy(verifier::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Redis PING 失败");
    }
}
