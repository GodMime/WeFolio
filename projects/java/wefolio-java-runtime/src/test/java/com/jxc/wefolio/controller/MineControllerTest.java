package com.jxc.wefolio.controller;

import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.dto.MineProfileAssetUploadTicketRequest;
import com.jxc.wefolio.dto.MineProfileAssetUploadTicketResponse;
import com.jxc.wefolio.dto.MineVisitRecordPageResponse;
import com.jxc.wefolio.dto.MineVisitRecordsResponse;
import com.jxc.wefolio.dto.MineVisitStatisticsResponse;
import com.jxc.wefolio.service.MineDashboardService;
import com.jxc.wefolio.service.MineProfileService;
import com.jxc.wefolio.service.MineVisitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;

import java.lang.annotation.Annotation;
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
        assertThat(method.isAnnotationPresent(Deprecated.class)).isTrue();
        assertThat(getMapping).isNotNull();
        assertThat(getMapping.value()).containsExactly("/api/mine/visits");
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isSameAs(serviceResponse);
        verify(mineVisitService).getVisitRecords();
    }

    @Test
    void visitStatisticsEndpointDelegatesToService() throws NoSuchMethodException {
        Method method = MineController.class.getMethod("visitStatistics");
        GetMapping getMapping = method.getAnnotation(GetMapping.class);
        MineVisitStatisticsResponse serviceResponse = new MineVisitStatisticsResponse();
        when(mineVisitService.getVisitStatistics()).thenReturn(serviceResponse);
        MineController controller = new MineController(
                mineDashboardService, mineProfileService, mineVisitService);

        Response<MineVisitStatisticsResponse> response = controller.visitStatistics();

        assertThat(getMapping).isNotNull();
        assertThat(getMapping.value()).containsExactly("/api/mine/visits/statistics");
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isSameAs(serviceResponse);
        verify(mineVisitService).getVisitStatistics();
    }

    @Test
    void visitRecordsEndpointDelegatesToServiceWithPagination() throws NoSuchMethodException {
        Method method = MineController.class.getMethod("visitRecords", Integer.class, Integer.class);
        GetMapping getMapping = method.getAnnotation(GetMapping.class);
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        RequestParam pageNoParam = findRequestParam(parameterAnnotations[0]);
        RequestParam pageSizeParam = findRequestParam(parameterAnnotations[1]);
        MineVisitRecordPageResponse serviceResponse = new MineVisitRecordPageResponse();
        serviceResponse.setPageNo(2);
        when(mineVisitService.getVisitRecordPage(2, 10)).thenReturn(serviceResponse);
        MineController controller = new MineController(
                mineDashboardService, mineProfileService, mineVisitService);

        Response<MineVisitRecordPageResponse> response = controller.visitRecords(2, 10);

        assertThat(getMapping).isNotNull();
        assertThat(getMapping.value()).containsExactly("/api/mine/visits/records");
        assertThat(pageNoParam.required()).isFalse();
        assertThat(pageSizeParam.required()).isFalse();
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isSameAs(serviceResponse);
        verify(mineVisitService).getVisitRecordPage(2, 10);
    }

    @Test
    void visitEventsEndpointDelegatesToServiceWithPagination() throws NoSuchMethodException {
        Method method = MineController.class.getMethod("visitEvents", Long.class, Integer.class, Integer.class);
        GetMapping getMapping = method.getAnnotation(GetMapping.class);
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        RequestParam pageNoParam = findRequestParam(parameterAnnotations[1]);
        RequestParam pageSizeParam = findRequestParam(parameterAnnotations[2]);
        MineVisitRecordsResponse.EventTimeline serviceResponse = new MineVisitRecordsResponse.EventTimeline();
        serviceResponse.setRecordId(101L);
        when(mineVisitService.getVisitEvents(101L, 2, 10)).thenReturn(serviceResponse);
        MineController controller = new MineController(
                mineDashboardService, mineProfileService, mineVisitService);

        Response<MineVisitRecordsResponse.EventTimeline> response = controller.visitEvents(101L, 2, 10);

        assertThat(getMapping).isNotNull();
        assertThat(getMapping.value()).containsExactly("/api/mine/visits/{recordId}/events");
        assertThat(parameterAnnotations[0]).anyMatch(annotation -> annotation instanceof PathVariable);
        assertThat(parameterAnnotations[1]).anyMatch(annotation -> annotation instanceof RequestParam);
        assertThat(parameterAnnotations[2]).anyMatch(annotation -> annotation instanceof RequestParam);
        assertThat(pageNoParam.required()).isFalse();
        assertThat(pageNoParam.defaultValue()).isEqualTo(ValueConstants.DEFAULT_NONE);
        assertThat(pageSizeParam.required()).isFalse();
        assertThat(pageSizeParam.defaultValue()).isEqualTo(ValueConstants.DEFAULT_NONE);
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isSameAs(serviceResponse);
        verify(mineVisitService).getVisitEvents(101L, 2, 10);
    }

    @Test
    void scheduleQueriesEndpointDelegatesToServiceWithPagination() throws NoSuchMethodException {
        Method method = MineController.class.getMethod("scheduleQueries", Integer.class, Integer.class);
        GetMapping getMapping = method.getAnnotation(GetMapping.class);
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        RequestParam pageNoParam = findRequestParam(parameterAnnotations[0]);
        RequestParam pageSizeParam = findRequestParam(parameterAnnotations[1]);
        MineVisitRecordsResponse.ScheduleQueryPage serviceResponse = new MineVisitRecordsResponse.ScheduleQueryPage();
        serviceResponse.setPageNo(1);
        when(mineVisitService.getScheduleQueryRecords(1, 20)).thenReturn(serviceResponse);
        MineController controller = new MineController(
                mineDashboardService, mineProfileService, mineVisitService);

        Response<MineVisitRecordsResponse.ScheduleQueryPage> response = controller.scheduleQueries(1, 20);

        assertThat(getMapping).isNotNull();
        assertThat(getMapping.value()).containsExactly("/api/mine/visits/schedule-queries");
        assertThat(parameterAnnotations[0]).anyMatch(annotation -> annotation instanceof RequestParam);
        assertThat(parameterAnnotations[1]).anyMatch(annotation -> annotation instanceof RequestParam);
        assertThat(pageNoParam.required()).isFalse();
        assertThat(pageSizeParam.required()).isFalse();
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isSameAs(serviceResponse);
        verify(mineVisitService).getScheduleQueryRecords(1, 20);
    }

    @Test
    void contactLeadsEndpointDelegatesToServiceWithPagination() throws NoSuchMethodException {
        Method method = MineController.class.getMethod("contactLeads", Integer.class, Integer.class);
        GetMapping getMapping = method.getAnnotation(GetMapping.class);
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        RequestParam pageNoParam = findRequestParam(parameterAnnotations[0]);
        RequestParam pageSizeParam = findRequestParam(parameterAnnotations[1]);
        MineVisitRecordsResponse.ContactLeadPage serviceResponse = new MineVisitRecordsResponse.ContactLeadPage();
        serviceResponse.setPageNo(1);
        when(mineVisitService.getContactLeads(1, 20)).thenReturn(serviceResponse);
        MineController controller = new MineController(
                mineDashboardService, mineProfileService, mineVisitService);

        Response<MineVisitRecordsResponse.ContactLeadPage> response = controller.contactLeads(1, 20);

        assertThat(getMapping).isNotNull();
        assertThat(getMapping.value()).containsExactly("/api/mine/visits/contact-leads");
        assertThat(parameterAnnotations[0]).anyMatch(annotation -> annotation instanceof RequestParam);
        assertThat(parameterAnnotations[1]).anyMatch(annotation -> annotation instanceof RequestParam);
        assertThat(pageNoParam.required()).isFalse();
        assertThat(pageSizeParam.required()).isFalse();
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isSameAs(serviceResponse);
        verify(mineVisitService).getContactLeads(1, 20);
    }

    @Test
    void markVisitFollowedEndpointDelegatesToService() throws NoSuchMethodException {
        Method method = MineController.class.getMethod("markVisitFollowed", Long.class);
        PutMapping putMapping = method.getAnnotation(PutMapping.class);
        MineVisitRecordsResponse.Record serviceResponse = new MineVisitRecordsResponse.Record();
        serviceResponse.setId(101L);
        serviceResponse.setFollowStatusText("已跟进");
        when(mineVisitService.markVisitFollowed(101L)).thenReturn(serviceResponse);
        MineController controller = new MineController(
                mineDashboardService, mineProfileService, mineVisitService);

        Response<MineVisitRecordsResponse.Record> response = controller.markVisitFollowed(101L);

        assertThat(putMapping).isNotNull();
        assertThat(putMapping.value()).containsExactly("/api/mine/visits/{recordId}/followed");
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isSameAs(serviceResponse);
        verify(mineVisitService).markVisitFollowed(101L);
    }

    @Test
    void markContactLeadFollowedEndpointDelegatesToService() throws NoSuchMethodException {
        Method method = MineController.class.getMethod("markContactLeadFollowed", Long.class);
        PutMapping putMapping = method.getAnnotation(PutMapping.class);
        MineVisitRecordsResponse.ContactLeadItem serviceResponse = new MineVisitRecordsResponse.ContactLeadItem();
        serviceResponse.setId(401L);
        serviceResponse.setFollowStatusText("已跟进");
        when(mineVisitService.markContactLeadFollowed(401L)).thenReturn(serviceResponse);
        MineController controller = new MineController(
                mineDashboardService, mineProfileService, mineVisitService);

        Response<MineVisitRecordsResponse.ContactLeadItem> response = controller.markContactLeadFollowed(401L);

        assertThat(putMapping).isNotNull();
        assertThat(putMapping.value()).containsExactly("/api/mine/visits/contact-leads/{leadId}/followed");
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isSameAs(serviceResponse);
        verify(mineVisitService).markContactLeadFollowed(401L);
    }

    @Test
    void profileAssetUploadTicketEndpointDelegatesToService() throws NoSuchMethodException {
        Method method = MineController.class.getMethod(
                "createProfileAssetUploadTicket",
                MineProfileAssetUploadTicketRequest.class
        );
        PostMapping postMapping = method.getAnnotation(PostMapping.class);
        MineProfileAssetUploadTicketRequest request = new MineProfileAssetUploadTicketRequest();
        request.setAssetType("WECHAT_QR");
        MineProfileAssetUploadTicketResponse serviceResponse = new MineProfileAssetUploadTicketResponse();
        serviceResponse.setAssetType("WECHAT_QR");
        when(mineProfileService.createProfileAssetUploadTicket(request)).thenReturn(serviceResponse);
        MineController controller = new MineController(
                mineDashboardService, mineProfileService, mineVisitService);

        Response<MineProfileAssetUploadTicketResponse> response = controller.createProfileAssetUploadTicket(request);

        assertThat(postMapping).isNotNull();
        assertThat(postMapping.value()).containsExactly("/api/mine/profile/assets/upload-ticket");
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isSameAs(serviceResponse);
        verify(mineProfileService).createProfileAssetUploadTicket(request);
    }

    /**
     * 从参数注解中读取请求参数注解。
     *
     * @param annotations 单个方法参数上的注解
     * @return 请求参数注解
     */
    private RequestParam findRequestParam(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            if (annotation instanceof RequestParam requestParam) {
                return requestParam;
            }
        }
        throw new AssertionError("缺少 RequestParam 注解");
    }
}
