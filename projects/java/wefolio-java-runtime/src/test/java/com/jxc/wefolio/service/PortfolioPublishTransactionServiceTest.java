package com.jxc.wefolio.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 标准作品集发布事务服务测试 — 验证独立事务边界和动作结果透传。
 */
class PortfolioPublishTransactionServiceTest {

    @Test
    void executeShouldDeclareRollbackForException() throws NoSuchMethodException {
        Method method = PortfolioPublishTransactionService.class.getMethod("execute", java.util.function.Supplier.class);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.rollbackFor()).containsExactly(Exception.class);
    }

    @Test
    void executeShouldInvokePublishActionOnceAndReturnResult() {
        PortfolioPublishTransactionService service = new PortfolioPublishTransactionService();
        AtomicInteger invocationCount = new AtomicInteger();

        String result = service.execute(() -> {
            invocationCount.incrementAndGet();
            return "published";
        });

        assertThat(result).isEqualTo("published");
        assertThat(invocationCount).hasValue(1);
    }
}
