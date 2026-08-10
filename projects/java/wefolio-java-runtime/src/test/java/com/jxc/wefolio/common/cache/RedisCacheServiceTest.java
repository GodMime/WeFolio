package com.jxc.wefolio.common.cache;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.common.redis.RedisKeyNamespace;
import com.jxc.wefolio.config.RedisProperties;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.service.VisitorAuthTokenService.ResolvedVisitorToken;
import com.jxc.wefolio.service.VisitorService.VisitorProfileTokenContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Redis JSON 缓存实现测试。 */
class RedisCacheServiceTest {

    /** 测试使用的缓存逻辑 key。 */
    private static final String LOGICAL_KEY = "visitor:auth:token:plain-token";

    /** 测试使用的完整 Redis 物理 key。 */
    private static final String PHYSICAL_KEY = "wefolio:runtime:" + LOGICAL_KEY;

    /** 模拟的 Redisson 客户端。 */
    private RedissonClient redissonClient;

    /** 模拟的字符串 Redis bucket。 */
    private RBucket<String> bucket;

    /** 被测 Redis 缓存服务。 */
    private RedisCacheService cacheService;

    /** 为每个用例创建独立的缓存服务与 Redis bucket 替身。 */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redissonClient = mock(RedissonClient.class);
        bucket = mock(RBucket.class);
        when(redissonClient.<String>getBucket(anyString(), eq(StringCodec.INSTANCE))).thenReturn(bucket);
        RedisProperties properties = new RedisProperties();
        cacheService = new RedisCacheService(redissonClient, new RedisKeyNamespace(properties));
    }

    /** 验证写入值会形成显式类型与 TTL 的可读 JSON 信封。 */
    @Test
    void writesReadableJsonEnvelopeWithExplicitTypeAndTtl() {
        Duration ttl = Duration.ofMinutes(5);

        cacheService.put(LOGICAL_KEY, 42L, ttl);

        org.mockito.ArgumentCaptor<String> jsonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(bucket).set(jsonCaptor.capture(), eq(ttl));
        verify(redissonClient).getBucket(PHYSICAL_KEY, StringCodec.INSTANCE);
        JSONObject envelope = JSON.parseObject(jsonCaptor.getValue());
        assertThat(envelope.getString("type")).isEqualTo("java.lang.Long");
        assertThat(envelope.getLong("value")).isEqualTo(42L);
        assertThat(jsonCaptor.getValue()).doesNotContain("@type");
    }

    /** 验证字符串、布尔值和字符串集合写入稳定类型标识。 */
    @Test
    void mapsStringBooleanAndSetToStableTypeNames() {
        assertEnvelopeType("plain", "java.lang.String");
        assertEnvelopeType(Boolean.TRUE, "java.lang.Boolean");
        assertEnvelopeType(new LinkedHashSet<>(Set.of("token-a", "token-b")), "java.util.Set");
    }

    /** 验证含 Instant 的 record 能按可读时间格式完整往返。 */
    @Test
    void roundTripsRecordAndKeepsInstantReadable() {
        Instant expiresAt = Instant.parse("2026-08-10T12:34:56.789Z");
        ResolvedVisitorToken value = new ResolvedVisitorToken(7L, "visitor-key", expiresAt);

        cacheService.put(LOGICAL_KEY, value, Duration.ofMinutes(1));

        org.mockito.ArgumentCaptor<String> jsonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(bucket).set(jsonCaptor.capture(), eq(Duration.ofMinutes(1)));
        JSONObject envelope = JSON.parseObject(jsonCaptor.getValue());
        assertThat(envelope.getString("type")).isEqualTo(ResolvedVisitorToken.class.getName());
        assertThat(envelope.getJSONObject("value").getString("expiresAt"))
                .isEqualTo("2026-08-10T12:34:56.789Z");
        assertThat(JSON.parseObject(
                envelope.getJSONObject("value").toJSONString(),
                ResolvedVisitorToken.class
        )).isEqualTo(value);

        when(bucket.get()).thenReturn(jsonCaptor.getValue());
        assertThat(cacheService.get(LOGICAL_KEY, ResolvedVisitorToken.class)).contains(value);
    }

    /** 验证不同 Set 实现均可通过稳定集合类型标识往返。 */
    @Test
    void roundTripsStringSetUsingStableSetType() {
        Set<String> value = new LinkedHashSet<>(Set.of("token-a", "token-b"));
        cacheService.put(LOGICAL_KEY, value, Duration.ofMinutes(1));

        org.mockito.ArgumentCaptor<String> jsonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(bucket).set(jsonCaptor.capture(), eq(Duration.ofMinutes(1)));
        when(bucket.get()).thenReturn(jsonCaptor.getValue());

        Set<?> restored = cacheService.get(LOGICAL_KEY, Set.class).orElseThrow();
        assertThat(restored.stream().map(String.class::cast).toList())
                .containsExactlyInAnyOrder("token-a", "token-b");
    }

    /** 验证访客令牌上下文使用全限定类型名并可完整往返。 */
    @Test
    void roundTripsVisitorProfileTokenContextWithQualifiedTypeName() {
        VisitorProfileTokenContext value = new VisitorProfileTokenContext(7L, 8L, 9L);
        cacheService.put(LOGICAL_KEY, value, Duration.ofMinutes(1));

        org.mockito.ArgumentCaptor<String> jsonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(bucket).set(jsonCaptor.capture(), eq(Duration.ofMinutes(1)));
        JSONObject envelope = JSON.parseObject(jsonCaptor.getValue());
        assertThat(envelope.getString("type")).isEqualTo(VisitorProfileTokenContext.class.getName());
        when(bucket.get()).thenReturn(jsonCaptor.getValue());

        assertThat(cacheService.get(LOGICAL_KEY, VisitorProfileTokenContext.class)).contains(value);
    }

    /** 验证缓存缺失、类型不匹配或信封缺少类型时返回空值。 */
    @Test
    void returnsEmptyForMissingOrMismatchedType() {
        when(bucket.get()).thenReturn(null);
        assertThat(cacheService.get(LOGICAL_KEY, String.class)).isEmpty();

        when(bucket.get()).thenReturn("{\"type\":\"java.lang.Long\",\"value\":42}");
        assertThat(cacheService.get(LOGICAL_KEY, String.class)).isEmpty();

        when(bucket.get()).thenReturn("{\"value\":\"plain\"}");
        assertThat(cacheService.get(LOGICAL_KEY, String.class)).isEmpty();
    }

    /** 验证格式损坏的 JSON 信封被拒绝且不启用自动类型解析。 */
    @Test
    void rejectsMalformedEnvelopeInsteadOfEnablingAutoType() {
        when(bucket.get()).thenReturn("not-json");

        assertThatThrownBy(() -> cacheService.get(LOGICAL_KEY, String.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Redis 缓存数据格式异常");
    }

    /** 验证缓存入参边界并确认删除操作使用完整物理 key。 */
    @Test
    void validatesArgumentsAndDeletesPhysicalKey() {
        assertThatThrownBy(() -> cacheService.get(" ", String.class))
                .isInstanceOf(BusinessException.class)
                .hasMessage("缓存 key 不能为空");
        assertThatThrownBy(() -> cacheService.get(LOGICAL_KEY, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("缓存值类型不能为空");
        assertThatThrownBy(() -> cacheService.put(LOGICAL_KEY, null, Duration.ofSeconds(1)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("缓存值不能为空");
        assertThatThrownBy(() -> cacheService.put(LOGICAL_KEY, "value", Duration.ZERO))
                .isInstanceOf(BusinessException.class)
                .hasMessage("缓存有效期必须大于 0");
        assertThatThrownBy(() -> cacheService.put(LOGICAL_KEY, "value", Duration.ofSeconds(-1)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("缓存有效期必须大于 0");
        assertThatThrownBy(() -> cacheService.put(
                LOGICAL_KEY,
                "value",
                Duration.ofSeconds(Long.MAX_VALUE)
        )).isInstanceOf(BusinessException.class)
                .hasMessage("缓存有效期超出支持范围");

        cacheService.evict(LOGICAL_KEY);
        verify(bucket).delete();
    }

    /** 写入指定值并断言 JSON 信封中的稳定类型标识。 */
    private void assertEnvelopeType(Object value, String expectedType) {
        cacheService.put(LOGICAL_KEY, value, Duration.ofSeconds(1));
        org.mockito.ArgumentCaptor<String> jsonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(bucket).set(jsonCaptor.capture(), eq(Duration.ofSeconds(1)));
        assertThat(JSON.parseObject(jsonCaptor.getValue()).getString("type")).isEqualTo(expectedType);
        org.mockito.Mockito.clearInvocations(bucket);
    }
}
