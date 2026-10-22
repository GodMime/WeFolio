package com.jxc.wefolio.controller;

import java.util.List;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.jxc.wefolio.message.PortfolioMessage;
import com.jxc.wefolio.exception.GlobalExceptionHandler;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.MinePortfolioAssetUploadTicketRequest;
import com.jxc.wefolio.dto.MinePortfolioAssetUploadTicketResponse;
import com.jxc.wefolio.dto.MinePortfolioCreateRequest;
import com.jxc.wefolio.dto.MinePortfolioDetailResponse;
import com.jxc.wefolio.dto.MinePortfolioDraftSaveRequest;
import com.jxc.wefolio.dto.MinePortfolioListResponse;
import com.jxc.wefolio.dto.MinePortfolioPublishRequest;
import com.jxc.wefolio.dto.MinePortfolioShareRecordRequest;
import com.jxc.wefolio.dto.PortfolioComponentLibraryResponse;
import com.jxc.wefolio.dto.PortfolioHyperlinkTargetResponse;
import com.jxc.wefolio.dto.PortfolioScheduleOptionsResponse;
import com.jxc.wefolio.dto.PortfolioScheduleQueryRequest;
import com.jxc.wefolio.dto.PortfolioScheduleQueryResponse;
import com.jxc.wefolio.dto.PortfolioVideoCarouselWorkPageResponse;
import com.jxc.wefolio.service.MinePortfolioService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Arrays;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 我的作品集控制器测试 — 固定维护端作品集接口路径和服务委托。
 */
@ExtendWith(MockitoExtension.class)
class MinePortfolioControllerTest {

    /** 作品集服务模拟 */
    @Mock
    private MinePortfolioService minePortfolioService;

    /**
     * 视频轮播候选接口只绑定标签、搜索和分页参数，不暴露媒体与审核筛选。
     */
    @Test
    void videoCarouselWorksShouldDelegateDedicatedCandidateContract() throws NoSuchMethodException {
        MinePortfolioController controller = new MinePortfolioController(minePortfolioService);
        PortfolioVideoCarouselWorkPageResponse expected = new PortfolioVideoCarouselWorkPageResponse();
        when(minePortfolioService.pageVideoCarouselWorks("草坪", 7L, 2, 30)).thenReturn(expected);

        Response<PortfolioVideoCarouselWorkPageResponse> response =
                controller.videoCarouselWorks("草坪", 7L, 2, 30);

        assertThat(response.getData()).isSameAs(expected);
        verify(minePortfolioService).pageVideoCarouselWorks("草坪", 7L, 2, 30);
        var method = MinePortfolioController.class.getMethod(
                "videoCarouselWorks", String.class, Long.class, int.class, int.class);
        assertThat(method.getAnnotation(GetMapping.class).value())
                .containsExactly("/api/mine/portfolios/components/video-carousel/works");
        RequestParam keyword = method.getParameters()[0].getAnnotation(RequestParam.class);
        RequestParam tagId = method.getParameters()[1].getAnnotation(RequestParam.class);
        RequestParam page = method.getParameters()[2].getAnnotation(RequestParam.class);
        RequestParam pageSize = method.getParameters()[3].getAnnotation(RequestParam.class);
        assertThat(keyword.value()).isEqualTo("keyword");
        assertThat(keyword.required()).isFalse();
        assertThat(tagId.value()).isEqualTo("tagId");
        assertThat(tagId.required()).isFalse();
        assertThat(page.defaultValue()).isEqualTo("1");
        assertThat(pageSize.defaultValue()).isEqualTo("20");
    }

    @Test
    void controllerShouldUseMaintainerAccessAndDelegateToService() throws NoSuchMethodException {
        MinePortfolioController controller = new MinePortfolioController(minePortfolioService);
        MinePortfolioListResponse listResponse = new MinePortfolioListResponse();
        PortfolioComponentLibraryResponse libraryResponse = new PortfolioComponentLibraryResponse();
        PortfolioHyperlinkTargetResponse hyperlinkTargetsResponse = new PortfolioHyperlinkTargetResponse();
        MinePortfolioCreateRequest createRequest = new MinePortfolioCreateRequest();
        MinePortfolioAssetUploadTicketRequest assetTicketRequest = new MinePortfolioAssetUploadTicketRequest();
        MinePortfolioAssetUploadTicketResponse assetTicketResponse = new MinePortfolioAssetUploadTicketResponse();
        MinePortfolioDraftSaveRequest draftRequest = new MinePortfolioDraftSaveRequest();
        MinePortfolioPublishRequest publishRequest = new MinePortfolioPublishRequest();
        MinePortfolioShareRecordRequest shareRequest = new MinePortfolioShareRecordRequest();
        MinePortfolioDetailResponse detailResponse = new MinePortfolioDetailResponse();
        PortfolioScheduleOptionsResponse scheduleOptionsResponse = new PortfolioScheduleOptionsResponse();
        PortfolioScheduleQueryRequest scheduleQueryRequest = new PortfolioScheduleQueryRequest();
        PortfolioScheduleQueryResponse scheduleQueryResponse = new PortfolioScheduleQueryResponse();
        when(minePortfolioService.listPortfolios("USER", null)).thenReturn(listResponse);
        when(minePortfolioService.getComponentLibrary(3)).thenReturn(libraryResponse);
        when(minePortfolioService.listHyperlinkTargets(88L, 99L)).thenReturn(hyperlinkTargetsResponse);
        when(minePortfolioService.createStandardPersonal(createRequest)).thenReturn(detailResponse);
        when(minePortfolioService.createAssetUploadTicket(88L, assetTicketRequest)).thenReturn(assetTicketResponse);
        when(minePortfolioService.getDetail(88L)).thenReturn(detailResponse);
        when(minePortfolioService.saveDraft(88L, draftRequest)).thenReturn(detailResponse);
        when(minePortfolioService.preview(88L)).thenReturn(detailResponse);
        when(minePortfolioService.previewPublished(88L)).thenReturn(detailResponse);
        when(minePortfolioService.publish(88L, publishRequest)).thenReturn(detailResponse);
        when(minePortfolioService.queryPreviewScheduleOptions(88L, "2026-07", "c_schedule", "published"))
                .thenReturn(scheduleOptionsResponse);
        when(minePortfolioService.submitPreviewScheduleQuery(88L, scheduleQueryRequest, "published"))
                .thenReturn(scheduleQueryResponse);

        Response<MinePortfolioListResponse> listed = controller.list("USER", null);
        Response<PortfolioComponentLibraryResponse> library = controller.componentLibrary(3);
        Response<PortfolioHyperlinkTargetResponse> hyperlinkTargets = controller.hyperlinkTargets(88L, 99L);
        Response<MinePortfolioDetailResponse> created = controller.createStandardPersonal(createRequest);
        Response<MinePortfolioAssetUploadTicketResponse> assetTicket = controller.createAssetUploadTicket(88L, assetTicketRequest);
        Response<MinePortfolioDetailResponse> detail = controller.detail(88L);
        Response<MinePortfolioDetailResponse> saved = controller.saveDraft(88L, draftRequest);
        Response<MinePortfolioDetailResponse> preview = controller.preview(88L);
        Response<MinePortfolioDetailResponse> publishedPreview = controller.previewPublished(88L);
        Response<PortfolioScheduleOptionsResponse> scheduleOptions = controller.scheduleOptions(
                88L, "2026-07", "c_schedule", "published");
        Response<PortfolioScheduleQueryResponse> scheduleQuery = controller.scheduleQueryPreview(
                88L, scheduleQueryRequest, "published");
        Response<MinePortfolioDetailResponse> published = controller.publish(88L, publishRequest);
        Response<Void> shared = controller.createShareRecord(88L, shareRequest);
        Response<Void> deleted = controller.deletePortfolio(88L);

        assertThat(MinePortfolioController.class.isAnnotationPresent(MaintainerAccess.class)).isTrue();
        assertGetMapping("list", new Class<?>[] {String.class, String.class}, "/api/mine/portfolios");
        assertGetMapping("componentLibrary", new Class<?>[] {Integer.class}, "/api/mine/portfolios/component-library");
        assertGetMapping("hyperlinkTargets", new Class<?>[] {Long.class, Long.class},
                "/api/mine/portfolios/hyperlink-targets");
        assertPostMapping("createStandardPersonal",
                new Class<?>[] {MinePortfolioCreateRequest.class},
                "/api/mine/portfolios/standard-personal");
        assertGetMapping("detail", new Class<?>[] {Long.class}, "/api/mine/portfolios/{portfolioId}");
        assertPostMapping("createAssetUploadTicket",
                new Class<?>[] {Long.class, MinePortfolioAssetUploadTicketRequest.class},
                "/api/mine/portfolios/{portfolioId}/asset/upload-ticket");
        assertPutMapping("saveDraft",
                new Class<?>[] {Long.class, MinePortfolioDraftSaveRequest.class},
                "/api/mine/portfolios/{portfolioId}/draft");
        assertGetMapping("preview", new Class<?>[] {Long.class}, "/api/mine/portfolios/{portfolioId}/preview");
        assertGetMapping("previewPublished",
                new Class<?>[] {Long.class},
                "/api/mine/portfolios/{portfolioId}/published-preview");
        assertGetMapping("scheduleOptions",
                new Class<?>[] {Long.class, String.class, String.class, String.class},
                "/api/mine/portfolios/{portfolioId}/schedule-options");
        assertPostMapping("scheduleQueryPreview",
                new Class<?>[] {Long.class, PortfolioScheduleQueryRequest.class, String.class},
                "/api/mine/portfolios/{portfolioId}/schedule-query-preview");
        assertPostMapping("publish",
                new Class<?>[] {Long.class, MinePortfolioPublishRequest.class},
                "/api/mine/portfolios/{portfolioId}/publish");
        assertPostMapping("createShareRecord",
                new Class<?>[] {Long.class, MinePortfolioShareRecordRequest.class},
                "/api/mine/portfolios/{portfolioId}/share-records");
        assertPostMapping("deletePortfolio",
                new Class<?>[] {Long.class},
                "/api/mine/portfolios/delete/{portfolioId}");
        assertThat(MinePortfolioController.class.getMethod("list", String.class, String.class)
                .getParameters()[0].isAnnotationPresent(RequestParam.class)).isTrue();
        assertThat(MinePortfolioController.class.getMethod("detail", Long.class)
                .getParameters()[0].isAnnotationPresent(PathVariable.class)).isTrue();
        assertThat(Arrays.stream(MinePortfolioController.class.getMethods()).map(method -> method.getName()))
                .doesNotContain("createCoverUploadTicket");
        assertThat(listed.getData()).isSameAs(listResponse);
        assertThat(library.getData()).isSameAs(libraryResponse);
        assertThat(hyperlinkTargets.getData()).isSameAs(hyperlinkTargetsResponse);
        assertThat(created.getData()).isSameAs(detailResponse);
        assertThat(assetTicket.getData()).isSameAs(assetTicketResponse);
        assertThat(detail.getData()).isSameAs(detailResponse);
        assertThat(saved.getData()).isSameAs(detailResponse);
        assertThat(preview.getData()).isSameAs(detailResponse);
        assertThat(publishedPreview.getData()).isSameAs(detailResponse);
        assertThat(scheduleOptions.getData()).isSameAs(scheduleOptionsResponse);
        assertThat(scheduleQuery.getData()).isSameAs(scheduleQueryResponse);
        assertThat(published.getData()).isSameAs(detailResponse);
        assertThat(shared.isSuccess()).isTrue();
        assertThat(deleted.isSuccess()).isTrue();
        verify(minePortfolioService).createShareRecord(88L, shareRequest);
        verify(minePortfolioService).deletePortfolio(88L);
    }

    private void assertGetMapping(String methodName, Class<?>[] parameterTypes, String path)
            throws NoSuchMethodException {
        GetMapping mapping = MinePortfolioController.class.getMethod(methodName, parameterTypes)
                .getAnnotation(GetMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly(path);
    }

    private void assertPostMapping(String methodName, Class<?>[] parameterTypes, String path)
            throws NoSuchMethodException {
        PostMapping mapping = MinePortfolioController.class.getMethod(methodName, parameterTypes)
                .getAnnotation(PostMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly(path);
    }

    private void assertPutMapping(String methodName, Class<?>[] parameterTypes, String path)
            throws NoSuchMethodException {
        PutMapping mapping = MinePortfolioController.class.getMethod(methodName, parameterTypes)
                .getAnnotation(PutMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly(path);
    }
    /** 新状态参数可选，旧请求与原响应包装继续兼容。 */
    @Test
    void publicationFilterBindsWithoutChangingListResponse() throws Exception {
        MinePortfolioController controller = new MinePortfolioController(minePortfolioService);
        MinePortfolioListResponse result = new MinePortfolioListResponse();
        result.setPortfolios(List.of());
        String published = PortfolioPublicationStatusDict.PUBLISHED.getCode();
        when(minePortfolioService.listPortfolios(null, null)).thenReturn(result);
        when(minePortfolioService.listPortfolios(null, published)).thenReturn(result);
        when(minePortfolioService.listPortfolios(null, "INVALID")).thenThrow(
                new BusinessException(PortfolioMessage.PUBLICATION_STATUS_FILTER_INVALID_MESSAGE));
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/mine/portfolios"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.portfolios").isArray());
        mvc.perform(get("/api/mine/portfolios").param("publicationStatus", published))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.portfolios").isArray());
        mvc.perform(get("/api/mine/portfolios").param("publicationStatus", "INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PortfolioMessage.PUBLICATION_STATUS_FILTER_INVALID_MESSAGE));
        verify(minePortfolioService).listPortfolios(null, null);
        verify(minePortfolioService).listPortfolios(null, published);
    }

}
