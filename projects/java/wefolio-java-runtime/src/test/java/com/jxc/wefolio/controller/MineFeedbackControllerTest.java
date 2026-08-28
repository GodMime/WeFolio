package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.MineFeedbackCreateRequest;
import com.jxc.wefolio.dto.MineFeedbackCreationStateResponse;
import com.jxc.wefolio.dto.MineFeedbackDetailResponse;
import com.jxc.wefolio.dto.MineFeedbackListResponse;
import com.jxc.wefolio.dto.MineFeedbackUploadTicketRequest;
import com.jxc.wefolio.dto.MineFeedbackUploadTicketResponse;
import com.jxc.wefolio.service.FeedbackUploadService;
import com.jxc.wefolio.service.MineFeedbackApplicationService;
import com.jxc.wefolio.service.MineFeedbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 当前用户问题反馈控制器测试。 */
@ExtendWith(MockitoExtension.class)
class MineFeedbackControllerTest {

    /** 反馈查询服务模拟。 */
    @Mock
    private MineFeedbackService mineFeedbackService;

    /** 反馈上传服务模拟。 */
    @Mock
    private FeedbackUploadService feedbackUploadService;

    /** 反馈应用服务模拟。 */
    @Mock
    private MineFeedbackApplicationService applicationService;

    /** 待测试控制器。 */
    private MineFeedbackController controller;

    /** 每个用例创建独立控制器。 */
    @BeforeEach
    void setUp() {
        controller = new MineFeedbackController(
                mineFeedbackService, feedbackUploadService, applicationService);
    }

    /** 控制器必须统一使用维护者访问控制且仅依赖三项应用服务。 */
    @Test
    void controllerUsesMaintainerAccessAndAllowedDependenciesOnly() {
        assertThat(MineFeedbackController.class.isAnnotationPresent(MaintainerAccess.class)).isTrue();
        assertThat(MineFeedbackController.class.getDeclaredFields())
                .extracting(Field::getType)
                .containsExactlyInAnyOrder(
                        MineFeedbackService.class,
                        FeedbackUploadService.class,
                        MineFeedbackApplicationService.class);
    }

    /** 创建状态接口必须原样委派查询服务并封装成功响应。 */
    @Test
    void creationStateDelegatesAndMapsSuccess() throws NoSuchMethodException {
        MineFeedbackCreationStateResponse serviceResponse = new MineFeedbackCreationStateResponse();
        when(mineFeedbackService.creationState()).thenReturn(serviceResponse);

        Response<MineFeedbackCreationStateResponse> response = controller.creationState();

        assertGetMapping("creationState", "/api/mine/feedbacks/creation-state");
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isSameAs(serviceResponse);
        verify(mineFeedbackService).creationState();
    }

    /** 上传票据接口必须原样委派上传服务并封装成功响应。 */
    @Test
    void uploadTicketsDelegatesAndMapsSuccess() throws NoSuchMethodException {
        MineFeedbackUploadTicketRequest request = new MineFeedbackUploadTicketRequest();
        MineFeedbackUploadTicketResponse serviceResponse = new MineFeedbackUploadTicketResponse();
        when(feedbackUploadService.createUploadTickets(request)).thenReturn(serviceResponse);

        Response<MineFeedbackUploadTicketResponse> response = controller.uploadTickets(request);

        Method method = assertPostMapping(
                "uploadTickets", "/api/mine/feedbacks/upload-tickets",
                MineFeedbackUploadTicketRequest.class);
        assertThat(method.getParameters()[0].isAnnotationPresent(RequestBody.class)).isTrue();
        assertThat(response.getData()).isSameAs(serviceResponse);
        verify(feedbackUploadService).createUploadTickets(request);
    }

    /** 创建接口必须原样委派应用服务并封装完整详情。 */
    @Test
    void createDelegatesAndMapsSuccess() throws NoSuchMethodException {
        MineFeedbackCreateRequest request = new MineFeedbackCreateRequest();
        MineFeedbackDetailResponse serviceResponse = new MineFeedbackDetailResponse();
        when(applicationService.create(request)).thenReturn(serviceResponse);

        Response<MineFeedbackDetailResponse> response = controller.create(request);

        Method method = assertPostMapping(
                "create", "/api/mine/feedbacks", MineFeedbackCreateRequest.class);
        assertThat(method.getParameters()[0].isAnnotationPresent(RequestBody.class)).isTrue();
        assertThat(response.getData()).isSameAs(serviceResponse);
        verify(applicationService).create(request);
    }

    /** 历史分页接口必须原样委派两个可选分页参数。 */
    @Test
    void listDelegatesOptionalPaginationAndMapsSuccess() throws NoSuchMethodException {
        MineFeedbackListResponse serviceResponse = new MineFeedbackListResponse();
        when(mineFeedbackService.list(null, 20)).thenReturn(serviceResponse);

        Response<MineFeedbackListResponse> response = controller.list(null, 20);

        Method method = MineFeedbackController.class.getMethod("list", Integer.class, Integer.class);
        assertMapping(method.getAnnotation(GetMapping.class).value(), "/api/mine/feedbacks");
        assertRequestParam(method, 0, "pageNo");
        assertRequestParam(method, 1, "pageSize");
        assertThat(response.getData()).isSameAs(serviceResponse);
        verify(mineFeedbackService).list(null, 20);
    }

    /** 详情接口必须原样委派反馈 ID。 */
    @Test
    void detailDelegatesFeedbackIdAndMapsSuccess() throws NoSuchMethodException {
        MineFeedbackDetailResponse serviceResponse = new MineFeedbackDetailResponse();
        when(mineFeedbackService.detail(91L)).thenReturn(serviceResponse);

        Response<MineFeedbackDetailResponse> response = controller.detail(91L);

        Method method = MineFeedbackController.class.getMethod("detail", Long.class);
        assertMapping(method.getAnnotation(GetMapping.class).value(), "/api/mine/feedbacks/{feedbackId}");
        assertThat(method.getParameters()[0].getAnnotation(PathVariable.class).value())
                .isEqualTo("feedbackId");
        assertThat(response.getData()).isSameAs(serviceResponse);
        verify(mineFeedbackService).detail(91L);
    }

    /** 追加接口必须原样委派反馈 ID 和请求体。 */
    @Test
    void appendDelegatesFeedbackIdAndRequestBody() throws NoSuchMethodException {
        MineFeedbackCreateRequest request = new MineFeedbackCreateRequest();
        MineFeedbackDetailResponse serviceResponse = new MineFeedbackDetailResponse();
        when(applicationService.append(91L, request)).thenReturn(serviceResponse);

        Response<MineFeedbackDetailResponse> response = controller.append(91L, request);

        Method method = assertPostMapping(
                "append", "/api/mine/feedbacks/{feedbackId}/rounds",
                Long.class, MineFeedbackCreateRequest.class);
        assertThat(method.getParameters()[0].getAnnotation(PathVariable.class).value())
                .isEqualTo("feedbackId");
        assertThat(method.getParameters()[1].isAnnotationPresent(RequestBody.class)).isTrue();
        assertThat(response.getData()).isSameAs(serviceResponse);
        verify(applicationService).append(91L, request);
    }

    /** 断言无参数 GET 路由。 */
    private void assertGetMapping(String methodName, String path) throws NoSuchMethodException {
        Method method = MineFeedbackController.class.getMethod(methodName);
        assertMapping(method.getAnnotation(GetMapping.class).value(), path);
    }

    /** 断言 POST 路由并返回方法元数据。 */
    private Method assertPostMapping(String methodName, String path, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = MineFeedbackController.class.getMethod(methodName, parameterTypes);
        assertMapping(method.getAnnotation(PostMapping.class).value(), path);
        return method;
    }

    /** 断言单一路由值。 */
    private void assertMapping(String[] values, String path) {
        assertThat(values).containsExactly(path);
    }

    /** 断言可选查询参数名称和必填性。 */
    private void assertRequestParam(Method method, int index, String name) {
        RequestParam requestParam = method.getParameters()[index].getAnnotation(RequestParam.class);
        assertThat(requestParam).isNotNull();
        assertThat(requestParam.value()).isEqualTo(name);
        assertThat(requestParam.required()).isFalse();
    }
}
