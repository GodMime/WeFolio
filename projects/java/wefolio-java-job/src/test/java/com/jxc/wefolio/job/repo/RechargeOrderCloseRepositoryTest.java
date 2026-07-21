package com.jxc.wefolio.job.repo;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 过期充值订单关闭仓储测试。
 */
class RechargeOrderCloseRepositoryTest {

    @Test
    void shouldCloseOnlyExpiredPendingOrdersWithBatchLimit() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(2);
        RechargeOrderCloseRepository repository = new RechargeOrderCloseRepository(jdbcTemplate);
        LocalDateTime now = LocalDateTime.of(2026, 7, 17, 12, 0, 30);

        int affected = repository.closeExpiredOrders(now, 200);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), argsCaptor.capture());
        assertThat(affected).isEqualTo(2);
        assertThat(sqlCaptor.getValue())
                .contains("status = 'CLOSED'")
                .contains("status = 'PENDING_PAYMENT'")
                .contains("deleted = 0")
                .contains("expire_at < ?")
                .contains("ORDER BY expire_at ASC, id ASC")
                .contains("LIMIT ?")
                .contains("version = version + 1");
        assertThat(argsCaptor.getValue()).containsExactly(now, now, now, 200);
    }
}
