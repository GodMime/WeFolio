package com.jxc.wefolio.controller;

import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.dto.MineVisitRecordsResponse;
import com.jxc.wefolio.service.MineDashboardService;
import com.jxc.wefolio.service.MineProfileService;
import com.jxc.wefolio.service.MineVisitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 我的控制器测试 — 确认维护者工作台接口路径与服务委托。
 */
@ExtendWith(MockitoExtension.class)
class MineControllerTest {

    @Mock
    private MineDashboardService mineDashboardService;

    @Mock
    private MineProfileService mineProfileService;

    @Mock
    private MineVisitService mineVisitService;

    @Test
    void visitsEndpointUsesMaintainerAccessAndDelegatesToService() throws NoSuchMethodException {
        Method method = MineController.class.getMethod("visits");
        GetMapping getMapping = method.getAnnotation(GetMapping.class);
        MineVisitRecordsResponse serviceResponse = new MineVisitRecordsResponse();
        when(mineVisitService.getVisitRecords()).thenReturn(serviceResponse);
        MineController controller = new MineController(
                mineDashboardService, mineProfileService, mineVisitService);

        Response<MineVisitRecordsResponse> response = controller.visits();

        assertThat(MineController.class.isAnnotationPresent(MaintainerAccess.class)).isTrue();
        assertThat(getMapping).isNotNull();
        assertThat(getMapping.value()).containsExactly("/api/mine/visits");
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isSameAs(serviceResponse);
        verify(mineVisitService).getVisitRecords();
    }
}
