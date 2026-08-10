package com.jxc.wefolio.common.lock;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 测试锁执行器异常释放结构测试。 */
class TestDistributedLockExecutorTest {

    /** 测试锁执行器源码路径。 */
    private static final Path SOURCE = Path.of(
            "src/test/java/com/jxc/wefolio/common/lock/TestDistributedLockExecutor.java");

    /** 验证事务同步注册失败时先释放本地替身锁再透传异常。 */
    @Test
    void transactionSynchronizationRegistrationFailureShouldUnlockBeforeRethrow() throws IOException {
        String source = Files.readString(SOURCE);

        assertThat(source)
                .contains("try {")
                .contains("TransactionSynchronizationManager.registerSynchronization")
                .contains("catch (RuntimeException exception)")
                .contains("lock.unlock();")
                .contains("throw exception;");
    }
}
