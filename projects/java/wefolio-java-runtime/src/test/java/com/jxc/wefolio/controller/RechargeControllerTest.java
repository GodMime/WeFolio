package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dto.CreateRechargeOrderRequest;
import com.jxc.wefolio.dto.CreateRechargeOrderResponse;
import com.jxc.wefolio.dto.RechargeOrderSyncResponse;
import com.jxc.wefolio.dto.RechargeOrdersResponse;
import com.jxc.wefolio.dto.RechargePageResponse;
import com.jxc.wefolio.service.payment.RechargeCommandService;
import com.jxc.wefolio.service.payment.RechargeQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 虚拟支付充值维护者接口测试。
 */
@ExtendWith(MockitoExtension.class)
class RechargeControllerTest {

    /** 充值查询服务模拟。 */
    @Mock
    private RechargeQueryService rechargeQueryService;

    /** 充值写入服务模拟。 */
    @Mock
    private RechargeCommandService rechargeCommandService;

    /** 为每个测试设置当前维护者身份。 */
    @BeforeEach
    void setUp() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
    }

    /** 清理测试使用的认证上下文。 */
    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    /** 维护者接口保留原有路径、当前用户委派和响应数据。 */
    @Test
    void maintainerRechargeEndpointsShouldUseExactPathsAndCurrentUser() throws NoSuchMethodException {
        MineRechargeController controller = new MineRechargeController(rechargeQueryService, rechargeCommandService);
        RechargePageResponse pageResponse = new RechargePageResponse();
        CreateRechargeOrderRequest request = new CreateRechargeOrderRequest();
        CreateRechargeOrderResponse createResponse = new CreateRechargeOrderResponse();
        RechargeOrdersResponse recordsResponse = new RechargeOrdersResponse();
        RechargeOrderSyncResponse syncResponse = new RechargeOrderSyncResponse();
        when(rechargeQueryService.getPage(7L)).thenReturn(pageResponse);
        when(rechargeCommandService.createOrder(7L, request)).thenReturn(createResponse);
        when(rechargeQueryService.listOrders(7L, 1, 20)).thenReturn(recordsResponse);
        when(rechargeCommandService.syncOrder(7L, "WFR1")).thenReturn(syncResponse);

        Response<RechargePageResponse> page = controller.page();
        Response<CreateRechargeOrderResponse> created = controller.createOrder(request);
        Response<RechargeOrdersResponse> records = controller.orders(1, 20);
        Response<RechargeOrderSyncResponse> synced = controller.syncOrder("WFR1");

        assertThat(MineRechargeController.class.isAnnotationPresent(MaintainerAccess.class)).isTrue();
        assertThat(MineRechargeController.class.getMethod("page").getAnnotation(GetMapping.class).value())
                .containsExactly("/api/mine/recharges");
        assertThat(MineRechargeController.class
                .getMethod("createOrder", CreateRechargeOrderRequest.class)
                .getAnnotation(PostMapping.class).value())
                .containsExactly("/api/mine/recharges/orders");
        assertThat(MineRechargeController.class
                .getMethod("orders", int.class, int.class)
                .getAnnotation(GetMapping.class).value())
                .containsExactly("/api/mine/recharges/orders");
        assertThat(MineRechargeController.class
                .getMethod("syncOrder", String.class)
                .getAnnotation(PostMapping.class).value())
                .containsExactly("/api/mine/recharges/orders/{merchantOrderNo}/sync");
        assertThat(page.getData()).isSameAs(pageResponse);
        assertThat(created.getData()).isSameAs(createResponse);
        assertThat(records.getData()).isSameAs(recordsResponse);
        assertThat(synced.getData()).isSameAs(syncResponse);
    }

}
