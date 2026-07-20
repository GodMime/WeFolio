package com.jxc.wefolio.controller;

import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.WorkStorageBillingSettlementRequest;
import com.jxc.wefolio.dto.WorkStorageBillingSettlementResponse;
import com.jxc.wefolio.service.WorkStorageBillingSettlementApplicationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 月度作品存储结算控制器测试。 */
@ExtendWith(MockitoExtension.class)
class WorkStorageBillingSettlementControllerTest {

    /** 月度作品存储结算入口应用服务模拟 */
    @Mock
    private WorkStorageBillingSettlementApplicationService settlementApplicationService;

    @Test
    void settleShouldDelegateRequestToApplicationService() {
        WorkStorageBillingSettlementRequest request = new WorkStorageBillingSettlementRequest();
        request.setUserId(100L);
        request.setBillingMonth("2026-06");
        WorkStorageBillingSettlementResponse settlementResponse =
                new WorkStorageBillingSettlementResponse(100L, "2026-06", "SUCCESS", 10L, 10L, false);
        when(settlementApplicationService.settle("secret", request)).thenReturn(settlementResponse);
        WorkStorageBillingSettlementController controller =
                new WorkStorageBillingSettlementController(settlementApplicationService);

        Response<WorkStorageBillingSettlementResponse> response =
                controller.settle("secret", request);

        assertThat(response.getData()).isSameAs(settlementResponse);
        verify(settlementApplicationService).settle("secret", request);
    }
}
