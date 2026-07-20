package com.jxc.wefolio.service;

import com.jxc.wefolio.dto.WorkStorageBillingSettlementRequest;
import com.jxc.wefolio.dto.WorkStorageBillingSettlementResponse;
import com.jxc.wefolio.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** runtime 月度作品存储结算入口应用服务测试。 */
class WorkStorageBillingSettlementApplicationServiceTest {

    /** 有效请求必须按原顺序校验密钥、解析账期并调用事务服务。 */
    @Test
    void validRequestShouldValidateSecretAndDelegateParsedMonth() {
        AdminPointSecretValidator validator = mock(AdminPointSecretValidator.class);
        WorkStorageBillingSettlementService settlementService = mock(WorkStorageBillingSettlementService.class);
        WorkStorageBillingSettlementRequest request = request(7L, "2026-07");
        WorkStorageBillingSettlementResponse expected =
                new WorkStorageBillingSettlementResponse(7L, "2026-07", "CHARGED", 10L, 10L, false);
        when(settlementService.settle(7L, YearMonth.of(2026, 7))).thenReturn(expected);
        WorkStorageBillingSettlementApplicationService service =
                new WorkStorageBillingSettlementApplicationService(validator, settlementService);

        WorkStorageBillingSettlementResponse result = service.settle("secret", request);

        assertThat(result).isSameAs(expected);
        verify(validator).validate("secret");
        verify(settlementService).settle(7L, YearMonth.of(2026, 7));
    }

    /** 空请求必须保留原有业务异常。 */
    @Test
    void nullRequestShouldKeepOriginalBusinessMessage() {
        WorkStorageBillingSettlementApplicationService service =
                new WorkStorageBillingSettlementApplicationService(
                        mock(AdminPointSecretValidator.class), mock(WorkStorageBillingSettlementService.class));

        assertThatThrownBy(() -> service.settle("secret", null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("结算请求不能为空");
    }

    /** 非法或空账期必须保留原有格式消息和解析异常原因。 */
    @Test
    void invalidBillingMonthShouldKeepOriginalBusinessMessage() {
        WorkStorageBillingSettlementApplicationService service =
                new WorkStorageBillingSettlementApplicationService(
                        mock(AdminPointSecretValidator.class), mock(WorkStorageBillingSettlementService.class));

        assertThatThrownBy(() -> service.settle("secret", request(7L, "2026-7")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("账期格式必须为 yyyy-MM")
                .hasCauseInstanceOf(DateTimeParseException.class);
        assertThatThrownBy(() -> service.settle("secret", request(7L, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("账期格式必须为 yyyy-MM")
                .hasCauseInstanceOf(NullPointerException.class);
    }

    /** 事务服务抛出的空指针不得伪装成账期格式错误。 */
    @Test
    void settlementNullPointerShouldPropagateOriginalFailure() {
        WorkStorageBillingSettlementService settlementService = mock(WorkStorageBillingSettlementService.class);
        doThrow(new NullPointerException("existing behavior"))
                .when(settlementService).settle(7L, YearMonth.of(2026, 7));
        WorkStorageBillingSettlementApplicationService service =
                new WorkStorageBillingSettlementApplicationService(
                        mock(AdminPointSecretValidator.class), settlementService);

        assertThatThrownBy(() -> service.settle("secret", request(7L, "2026-07")))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("existing behavior");
    }

    private WorkStorageBillingSettlementRequest request(Long userId, String billingMonth) {
        WorkStorageBillingSettlementRequest request = new WorkStorageBillingSettlementRequest();
        request.setUserId(userId);
        request.setBillingMonth(billingMonth);
        return request;
    }
}
