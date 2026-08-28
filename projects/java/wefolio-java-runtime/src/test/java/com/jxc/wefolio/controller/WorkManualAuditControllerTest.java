package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.SystemAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.WorkManualAuditUpdateRequest;
import com.jxc.wefolio.dto.WorkManualAuditUpdateResponse;
import com.jxc.wefolio.service.WorkManualAuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static com.jxc.wefolio.constant.PointConstants.ADMIN_POINT_SECRET_HEADER;

/** 作品人工审核内部回传控制器测试。 */
@ExtendWith(MockitoExtension.class)
class WorkManualAuditControllerTest {

    /** 人工审核回传服务模拟。 */
    @Mock
    private WorkManualAuditService service;

    /** Controller 必须使用系统访问并只完成 HTTP 到 Service 的委派。 */
    @Test
    void controllerUsesSystemAccessAndOnlyDelegatesToService() throws Exception {
        assertThat(WorkManualAuditController.class.isAnnotationPresent(SystemAccess.class)).isTrue();
        assertThat(Arrays.stream(WorkManualAuditController.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers())))
                .extracting(Field::getType)
                .containsExactly(WorkManualAuditService.class);

        Method method = WorkManualAuditController.class.getMethod(
                "update", String.class, String.class, WorkManualAuditUpdateRequest.class);
        assertThat(method.getAnnotation(PutMapping.class).value())
                .containsExactly("/api/internal/work-audits/{manualAuditNo}");
        RequestHeader requestHeader = method.getParameters()[0].getAnnotation(RequestHeader.class);
        assertThat(requestHeader.value()).isEqualTo(ADMIN_POINT_SECRET_HEADER);
        assertThat(requestHeader.required()).isFalse();
        assertThat(method.getParameters()[1].getAnnotation(PathVariable.class)).isNotNull();
        assertThat(method.getParameters()[2].getAnnotation(RequestBody.class)).isNotNull();

        WorkManualAuditUpdateRequest request = new WorkManualAuditUpdateRequest();
        WorkManualAuditUpdateResponse response = new WorkManualAuditUpdateResponse();
        when(service.update("admin-secret", "WA20260827153042A7K2Q9", request))
                .thenReturn(response);

        Response<WorkManualAuditUpdateResponse> actual = new WorkManualAuditController(service)
                .update("admin-secret", "WA20260827153042A7K2Q9", request);

        assertThat(actual.getData()).isSameAs(response);
        verify(service).update("admin-secret", "WA20260827153042A7K2Q9", request);
    }
}
