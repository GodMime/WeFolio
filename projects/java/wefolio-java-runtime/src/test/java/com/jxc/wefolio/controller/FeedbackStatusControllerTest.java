package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.SystemAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.FeedbackStatusUpdateRequest;
import com.jxc.wefolio.dto.FeedbackStatusUpdateResponse;
import com.jxc.wefolio.service.MineFeedbackApplicationService;
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

/** 问题反馈内部状态控制器测试。 */
@ExtendWith(MockitoExtension.class)
class FeedbackStatusControllerTest {

    /** 问题反馈应用服务模拟。 */
    @Mock
    private MineFeedbackApplicationService applicationService;

    /** 内部接口必须使用系统访问控制，且只依赖应用服务。 */
    @Test
    void controllerUsesSystemAccessAndApplicationServiceOnly() {
        assertThat(FeedbackStatusController.class.isAnnotationPresent(SystemAccess.class)).isTrue();
        assertThat(Arrays.stream(FeedbackStatusController.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers())))
                .extracting(Field::getType)
                .containsExactly(MineFeedbackApplicationService.class);
    }

    /** 状态更新接口必须绑定内部密钥并原样委派密钥、编号和请求体。 */
    @Test
    void updateStatusDelegatesSecretPathAndBody() throws NoSuchMethodException {
        FeedbackStatusUpdateRequest request = new FeedbackStatusUpdateRequest();
        FeedbackStatusUpdateResponse serviceResponse = new FeedbackStatusUpdateResponse();
        when(applicationService.updateStatus(
                "admin-secret", "FB0123456789abcdef0123456789abcdef", request))
                .thenReturn(serviceResponse);
        FeedbackStatusController controller = new FeedbackStatusController(applicationService);

        Response<FeedbackStatusUpdateResponse> response = controller.updateStatus(
                "admin-secret", "FB0123456789abcdef0123456789abcdef", request);

        Method method = FeedbackStatusController.class.getMethod(
                "updateStatus", String.class, String.class, FeedbackStatusUpdateRequest.class);
        assertThat(method.getAnnotation(PutMapping.class).value())
                .containsExactly("/api/internal/feedbacks/{feedbackNo}");
        RequestHeader requestHeader = method.getParameters()[0].getAnnotation(RequestHeader.class);
        assertThat(requestHeader.value()).isEqualTo(ADMIN_POINT_SECRET_HEADER);
        assertThat(requestHeader.required()).isFalse();
        assertThat(method.getParameters()[1].getAnnotation(PathVariable.class).value())
                .isEqualTo("feedbackNo");
        assertThat(method.getParameters()[2].isAnnotationPresent(RequestBody.class)).isTrue();
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isSameAs(serviceResponse);
        verify(applicationService).updateStatus(
                "admin-secret", "FB0123456789abcdef0123456789abcdef", request);
    }
}
