package com.jxc.wefolio.service.portfolioai;

import com.jxc.wefolio.common.cache.CacheService;
import com.jxc.wefolio.common.lock.DistributedLockExecutor;
import com.jxc.wefolio.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 签票限流仅控制当前请求人，不建立持久附件配额。 */
class PortfolioAiReferenceUploadQuotaTest {
    /** 第二十次可用，第二十一次拒绝且不继续累加。 */
    @Test void permitsTwentiethAndRejectsTwentyFirst() {
        CacheService cache=mock(CacheService.class);DistributedLockExecutor locks=mock(DistributedLockExecutor.class);
        when(locks.execute(anyString(),any())).thenAnswer(call->((Supplier<?>)call.getArgument(1)).get());
        when(cache.get(anyString(),eq(Integer.class))).thenReturn(Optional.of(19),Optional.of(20));
        PortfolioAiReferenceUploadQuota quota=new PortfolioAiReferenceUploadQuota(cache,locks);
        assertThatCode(()->quota.consume(7)).doesNotThrowAnyException();
        assertThatThrownBy(()->quota.consume(7)).isInstanceOf(BusinessException.class);
        verify(cache,times(1)).put(startsWith("portfolio-ai:reference-upload:quota:7:"),eq(20),eq(Duration.ofMinutes(2)));
    }
}
