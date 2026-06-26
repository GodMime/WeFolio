package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.SystemAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.AdminPointGrantRequest;
import com.jxc.wefolio.dto.PointMutationResponse;
import com.jxc.wefolio.service.PointAdminService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.PostMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台积分控制器测试 — 控制器只负责路由和服务委托。
 */
@ExtendWith(MockitoExtension.class)
class PointAdminControllerTest {

    /** 后台积分服务模拟 */
    @Mock
    private PointAdminService pointAdminService;

    @Test
    void grantPointsDelegatesSecretAndRequestToService() throws NoSuchMethodException {
        AdminPointGrantRequest request = new AdminPointGrantRequest();
        request.setUniqueCode("WFA3B1E7A2");
        request.setPoints(1000L);
        request.setIdempotencyKey("manual-20260626-WFA3B1E7A2");
        request.setRemark("一期初始化积分");
        PointMutationResponse serviceResponse = new PointMutationResponse();
        serviceResponse.setBalanceAfter(1000L);
        when(pointAdminService.grantPoints("admin-secret", request))
                .thenReturn(serviceResponse);

        PointAdminController controller = new PointAdminController(pointAdminService);
        Response<PointMutationResponse> response = controller.grantPoints("admin-secret", request);

        assertThat(PointAdminController.class.isAnnotationPresent(SystemAccess.class)).isTrue();
        PostMapping mapping = PointAdminController.class
                .getMethod("grantPoints", String.class, AdminPointGrantRequest.class)
                .getAnnotation(PostMapping.class);
        assertThat(mapping.value()).containsExactly("/api/admin/points/grants");
        assertThat(response.getData()).isSameAs(serviceResponse);
        verify(pointAdminService).grantPoints("admin-secret", request);
    }
}
