package com.jxc.wefolio.config;

import com.jxc.wefolio.common.cache.RedisCacheService;
import com.jxc.wefolio.common.lock.RedissonDistributedLockExecutor;
import com.jxc.wefolio.common.redis.RedisKeyNamespace;
import com.jxc.wefolio.service.VisitorAuthTokenService.ResolvedVisitorToken;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.redisson.api.RedissonClient;
import org.redisson.api.redisnode.RedisNodes;
import org.redisson.client.codec.StringCodec;
import org.springframework.boot.convert.DurationStyle;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 真实 Redis 单机连通性测试。
 *
 * <p>仅由 {@code mvn -Predis-integration-test verify} 执行，默认 Maven 测试不会连接 Redis。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisConnectionIT {

    /** 真实 Redis 集成测试专用 key 前缀。 */
    private static final String TEST_KEY_PREFIX = "integration-test:";

    /** 跨客户端缓存读写测试使用的逻辑 key。 */
    private static final String CACHE_LOGICAL_KEY = "cache:record";

    /** 跨客户端互斥测试使用的逻辑锁 key。 */
    private static final String LOCK_LOGICAL_KEY = "lock:mutual-exclusion";

    /** 第一个独立 Redisson 客户端。 */
    private RedissonClient firstClient;

    /** 第二个独立 Redisson 客户端。 */
    private RedissonClient secondClient;

    /** 集成测试隔离后的 Redis key 命名空间。 */
    private RedisKeyNamespace keyNamespace;

    /** 第一个客户端对应的缓存服务。 */
    private RedisCacheService firstCache;

    /** 第二个客户端对应的缓存服务。 */
    private RedisCacheService secondCache;

    /** 第一个客户端对应的锁执行器。 */
    private RedissonDistributedLockExecutor firstLockExecutor;

    /** 第二个客户端对应的锁执行器。 */
    private RedissonDistributedLockExecutor secondLockExecutor;

    /** 根据真实环境变量建立两个相互独立的 Redis 客户端。 */
    @BeforeAll
    void connect() {
        RedisProperties properties = redisPropertiesFromEnvironment();
        keyNamespace = new RedisKeyNamespace(properties);
        RedissonConfiguration configuration = new RedissonConfiguration();
        firstClient = configuration.redissonClient(properties);
        secondClient = configuration.redissonClient(properties);
        firstCache = new RedisCacheService(firstClient, keyNamespace);
        secondCache = new RedisCacheService(secondClient, keyNamespace);
        firstLockExecutor = new RedissonDistributedLockExecutor(firstClient, keyNamespace);
        secondLockExecutor = new RedissonDistributedLockExecutor(secondClient, keyNamespace);
    }

    /** 删除本用例生成的隔离 key 并关闭 Redis 客户端。 */
    @AfterAll
    void cleanUp() {
        if (firstClient != null) {
            firstClient.getKeys().deleteByPattern(keyNamespace.physicalKey("*"));
            firstClient.shutdown();
        }
        if (secondClient != null) {
            secondClient.shutdown();
        }
    }

    /** 验证两个独立客户端均能对单机 Redis 执行 PING。 */
    @Test
    void shouldPingSingleRedisFromBothClients() {
        assertThat(firstClient.getRedisNodes(RedisNodes.SINGLE).pingAll()).isTrue();
        assertThat(secondClient.getRedisNodes(RedisNodes.SINGLE).pingAll()).isTrue();
    }

    /** 验证两个客户端共享可读 JSON 缓存且 TTL 到期后自动删除。 */
    @Test
    void shouldShareReadableJsonCacheAcrossClientsAndExpireByTtl() {
        ResolvedVisitorToken value = new ResolvedVisitorToken(
                7L,
                "visitor-key",
                Instant.parse("2026-08-10T12:34:56.789Z")
        );
        firstCache.put(CACHE_LOGICAL_KEY, value, Duration.ofSeconds(3));

        assertThat(secondCache.get(CACHE_LOGICAL_KEY, ResolvedVisitorToken.class)).contains(value);
        String storedJson = secondClient.<String>getBucket(
                keyNamespace.physicalKey(CACHE_LOGICAL_KEY),
                StringCodec.INSTANCE
        ).get();
        assertThat(storedJson)
                .contains("\"type\":\"" + ResolvedVisitorToken.class.getName() + "\"")
                .contains("\"expiresAt\":\"2026-08-10T12:34:56.789Z\"");

        await().atMost(Duration.ofSeconds(8)).untilAsserted(() ->
                assertThat(secondCache.get(CACHE_LOGICAL_KEY, ResolvedVisitorToken.class)).isEmpty());
    }

    /** 验证两个客户端对同一逻辑锁 key 全局互斥。 */
    @Test
    void shouldMutuallyExcludeSameLockAcrossClients() throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        try {
            Future<?> first = executorService.submit(() -> firstLockExecutor.execute(LOCK_LOGICAL_KEY, () -> {
                firstEntered.countDown();
                awaitLatch(releaseFirst);
                return null;
            }));
            assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();

            Future<?> second = executorService.submit(() -> secondLockExecutor.execute(LOCK_LOGICAL_KEY, () -> {
                secondEntered.countDown();
                return null;
            }));
            assertThat(secondEntered.await(200, TimeUnit.MILLISECONDS)).isFalse();

            releaseFirst.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            assertThat(secondEntered.getCount()).isZero();
        } finally {
            releaseFirst.countDown();
            executorService.shutdownNow();
        }
    }

    /** 从明确的 Redis 环境变量构建独立测试连接配置。 */
    private RedisProperties redisPropertiesFromEnvironment() {
        RedisProperties properties = new RedisProperties();
        properties.setAddress(requiredEnvironment("REDIS_ADDRESS"));
        properties.setPassword(optionalEnvironment("REDIS_PASSWORD", ""));
        properties.setDatabase(Integer.parseInt(optionalEnvironment("REDIS_DATABASE", "0")));
        String configuredPrefix = optionalEnvironment(
                "REDIS_KEY_PREFIX",
                RedisProperties.DEFAULT_KEY_PREFIX
        );
        properties.setKeyPrefix(configuredPrefix + TEST_KEY_PREFIX + UUID.randomUUID() + ":");
        properties.setConnectTimeout(durationEnvironment("REDIS_CONNECT_TIMEOUT", "3s"));
        properties.setCommandTimeout(durationEnvironment("REDIS_COMMAND_TIMEOUT", "3s"));
        properties.validate();
        return properties;
    }

    /** 获取必填环境变量，缺失时直接使显式集成测试失败。 */
    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少真实 Redis 集成测试环境变量：" + name);
        }
        return value;
    }

    /** 获取可选环境变量。 */
    private String optionalEnvironment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /** 按 Spring Boot 配置语法解析超时环境变量。 */
    private Duration durationEnvironment(String name, String defaultValue) {
        return DurationStyle.detectAndParse(optionalEnvironment(name, defaultValue));
    }

    /** 等待并在中断时恢复线程中断标记。 */
    private void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 Redis 分布式锁测试信号时被中断", exception);
        }
    }
}
