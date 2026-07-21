package com.jxc.wefolio.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微信 AccessToken 服务结构测试 — 固定无锁快路径、锁内二次检查和条件刷新。
 */
class WechatAccessTokenServiceStructureTest {

    /** AccessToken 服务源码。 */
    private static final Path SOURCE = Path.of(
            "src/main/java/com/jxc/wefolio/service/WechatAccessTokenService.java");

    @Test
    void serviceShouldOwnCacheRefreshLockAndRejectedTokenComparison() throws IOException {
        assertThat(SOURCE).exists();
        String source = Files.readString(SOURCE);

        assertThat(source)
                .contains("String getAccessToken()")
                .contains("String refreshAfterRejected(String rejectedAccessToken)")
                .contains("ConcurrentMap<String, ReentrantLock>")
                .contains("cacheService.get(cacheKey")
                .contains("lock.lock()")
                .contains("finally")
                .contains("lock.unlock()")
                .contains("!current.equals(rejectedAccessToken)")
                .contains("cacheService.evict(cacheKey)")
                .contains("wechatAccessTokenFetcher.fetch()");
    }

    @Test
    void miniappClientShouldDelegateCredentialLookupToSharedService() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/jxc/wefolio/service/RestWechatMiniappClient.java"));

        assertThat(source)
                .contains("private final WechatAccessTokenService wechatAccessTokenService;")
                .contains("wechatAccessTokenService.getAccessToken()")
                .doesNotContain("private synchronized String accessToken()")
                .doesNotContain("ACCESS_TOKEN_CACHE_KEY_PREFIX");
    }
}
