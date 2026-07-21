package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.RechargeOrderCloseProperties;
import com.jxc.wefolio.job.repo.RechargeOrderCloseRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 过期充值订单关闭服务测试。
 */
class RechargeOrderCloseServiceTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void shouldContinueUntilLastBatchIsNotFull() {
        RechargeOrderCloseRepository repository = mock(RechargeOrderCloseRepository.class);
        when(repository.closeExpiredOrders(any(), eq(200))).thenReturn(200, 200, 50);
        RechargeOrderCloseProperties properties = defaultProperties();
        Clock clock = Clock.fixed(Instant.parse("2026-07-17T04:00:30Z"), SHANGHAI);
        RechargeOrderCloseService service = new RechargeOrderCloseService(repository, properties, clock);

        RechargeOrderCloseService.CloseResult result = service.closeExpiredOrders();

        ArgumentCaptor<LocalDateTime> nowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository, times(3)).closeExpiredOrders(nowCaptor.capture(), eq(200));
        assertThat(nowCaptor.getAllValues()).containsOnly(LocalDateTime.of(2026, 7, 17, 12, 0, 30));
        assertThat(result.closedCount()).isEqualTo(450);
        assertThat(result.batchCount()).isEqualTo(3);
        assertThat(result.limitReached()).isFalse();
    }

    @Test
    void shouldStopAtConfiguredMaximumBatches() {
        RechargeOrderCloseRepository repository = mock(RechargeOrderCloseRepository.class);
        when(repository.closeExpiredOrders(any(), eq(200))).thenReturn(200);
        RechargeOrderCloseProperties properties = defaultProperties();
        Clock clock = Clock.fixed(Instant.parse("2026-07-17T04:00:30Z"), SHANGHAI);
        RechargeOrderCloseService service = new RechargeOrderCloseService(repository, properties, clock);

        RechargeOrderCloseService.CloseResult result = service.closeExpiredOrders();

        verify(repository, times(10)).closeExpiredOrders(any(), eq(200));
        assertThat(result.closedCount()).isEqualTo(2000);
        assertThat(result.batchCount()).isEqualTo(10);
        assertThat(result.limitReached()).isTrue();
    }

    private RechargeOrderCloseProperties defaultProperties() {
        RechargeOrderCloseProperties properties = new RechargeOrderCloseProperties();
        properties.setBatchSize(200);
        properties.setMaxBatches(10);
        properties.setZone(SHANGHAI.getId());
        return properties;
    }
}
