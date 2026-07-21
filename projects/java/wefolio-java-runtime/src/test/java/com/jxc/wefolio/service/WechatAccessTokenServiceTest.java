package com.jxc.wefolio.service;

import com.jxc.wefolio.common.cache.LocalCacheService;
import com.jxc.wefolio.config.LocalCacheProperties;
import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.dto.WechatAccessTokenResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微信 AccessToken 服务测试。
 */
class WechatAccessTokenServiceTest {

    /**
     * 冷缓存并发读取只允许一次远端获取。
     */
    @Test
    void coldCacheShouldOnlyFetchOnceUnderConcurrency() throws Exception {
        AtomicInteger fetchCount = new AtomicInteger();
        WechatAccessTokenService service = service(() -> {
            fetchCount.incrementAndGet();
            return response("token-one");
        }, new LocalCacheService(new LocalCacheProperties()));
        ExecutorService executor = Executors.newFixedThreadPool(12);
        CountDownLatch ready = new CountDownLatch(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 12; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.getAccessToken();
                }));
            }
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<String> future : futures) {
                assertThat(future.get(2, TimeUnit.SECONDS)).isEqualTo("token-one");
            }
            assertThat(fetchCount).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 拒绝旧凭证时，如果缓存已被别的线程更新，应直接复用新凭证。
     */
    @Test
    void rejectedRefreshShouldReuseAlreadyRefreshedToken() {
        LocalCacheService cacheService = new LocalCacheService(new LocalCacheProperties());
        cacheService.put("wechat:miniapp:access-token:wxa-test", "token-new", Duration.ofMinutes(5));
        AtomicInteger fetchCount = new AtomicInteger();
        WechatAccessTokenService service = service(() -> {
            fetchCount.incrementAndGet();
            return response("unexpected-token");
        }, cacheService);

        String token = service.refreshAfterRejected("token-old");

        assertThat(token).isEqualTo("token-new");
        assertThat(fetchCount).hasValue(0);
    }

    /** @return 测试服务。 */
    private WechatAccessTokenService service(
            WechatAccessTokenFetcher fetcher,
            LocalCacheService cacheService
    ) {
        WechatMiniappProperties properties = new WechatMiniappProperties();
        properties.setAppId("wxa-test");
        properties.setAppSecret("secret-test");
        return new WechatAccessTokenService(properties, cacheService, fetcher);
    }

    /** @return 成功响应。 */
    private WechatAccessTokenResponse response(String token) {
        WechatAccessTokenResponse response = new WechatAccessTokenResponse();
        response.setAccessToken(token);
        response.setExpiresIn(7200L);
        return response;
    }
}
