package com.jxc.wefolio.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 维护者令牌缓存反向索引分布式互斥结构测试。 */
class AuthTokenServiceStructureTest {

    /** 维护者令牌服务源码路径。 */
    private static final Path SOURCE = Path.of(
            "src/main/java/com/jxc/wefolio/service/AuthTokenService.java");

    /** 验证用户令牌反向索引按用户使用分布式锁且不再使用 JVM 监视器。 */
    @Test
    void userTokenIndexShouldUsePerUserDistributedLock() throws IOException {
        assertThat(SOURCE).exists();
        String source = Files.readString(SOURCE);

        assertThat(source)
                .contains("DistributedLockExecutor")
                .contains("lock:auth:user-token-index:")
                .contains("lockExecutor.execute(buildUserTokenIndexLockKey(userId)")
                .doesNotContain("userTokenIndexMonitor")
                .doesNotContain("synchronized (");
    }
}
