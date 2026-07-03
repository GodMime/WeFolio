package com.jxc.wefolio.controller;

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

    @Test
    void controllerShouldUseMaintainerAccessAndDelegateToService() throws NoSuchMethodException {
        MinePortfolioController controller = new MinePortfolioController(minePortfolioService);
        MinePortfolioListResponse listResponse = new MinePortfolioListResponse();
        PortfolioComponentLibraryResponse libraryResponse = new PortfolioComponentLibraryResponse();
        MinePortfolioCreateRequest createRequest = new MinePortfolioCreateRequest();
        MinePortfolioAssetUploadTicketRequest assetTicketRequest = new MinePortfolioAssetUploadTicketRequest();
        MinePortfolioAssetUploadTicketResponse assetTicketResponse = new MinePortfolioAssetUploadTicketResponse();
        MinePortfolioDraftSaveRequest draftRequest = new MinePortfolioDraftSaveRequest();
        MinePortfolioPublishRequest publishRequest = new MinePortfolioPublishRequest();
        MinePortfolioShareRecordRequest shareRequest = new MinePortfolioShareRecordRequest();
        MinePortfolioDetailResponse detailResponse = new MinePortfolioDetailResponse();
        when(minePortfolioService.listPortfolios("USER")).thenReturn(listResponse);
        when(minePortfolioService.getComponentLibrary()).thenReturn(libraryResponse);
        when(minePortfolioService.createStandardPersonal(createRequest)).thenReturn(detailResponse);
        when(minePortfolioService.createAssetUploadTicket(88L, assetTicketRequest)).thenReturn(assetTicketResponse);
        when(minePortfolioService.getDetail(88L)).thenReturn(detailResponse);
        when(minePortfolioService.saveDraft(88L, draftRequest)).thenReturn(detailResponse);
        when(minePortfolioService.preview(88L)).thenReturn(detailResponse);
        when(minePortfolioService.publish(88L, publishRequest)).thenReturn(detailResponse);

        Response<MinePortfolioListResponse> listed = controller.list("USER");
        Response<PortfolioComponentLibraryResponse> library = controller.componentLibrary();
        Response<MinePortfolioDetailResponse> created = controller.createStandardPersonal(createRequest);
        Response<MinePortfolioAssetUploadTicketResponse> assetTicket = controller.createAssetUploadTicket(88L, assetTicketRequest);
        Response<MinePortfolioDetailResponse> detail = controller.detail(88L);
        Response<MinePortfolioDetailResponse> saved = controller.saveDraft(88L, draftRequest);
        Response<MinePortfolioDetailResponse> preview = controller.preview(88L);
        Response<MinePortfolioDetailResponse> published = controller.publish(88L, publishRequest);
        Response<Void> shared = controller.createShareRecord(88L, shareRequest);
        Response<Void> deleted = controller.deletePortfolio(88L);

        assertThat(MinePortfolioController.class.isAnnotationPresent(MaintainerAccess.class)).isTrue();
        assertGetMapping("list", new Class<?>[] {String.class}, "/api/mine/portfolios");
        assertGetMapping("componentLibrary", new Class<?>[] {}, "/api/mine/portfolios/component-library");
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
        assertPostMapping("publish",
                new Class<?>[] {Long.class, MinePortfolioPublishRequest.class},
                "/api/mine/portfolios/{portfolioId}/publish");
        assertPostMapping("createShareRecord",
                new Class<?>[] {Long.class, MinePortfolioShareRecordRequest.class},
                "/api/mine/portfolios/{portfolioId}/share-records");
        assertPostMapping("deletePortfolio",
                new Class<?>[] {Long.class},
                "/api/mine/portfolios/delete/{portfolioId}");
        assertThat(MinePortfolioController.class.getMethod("list", String.class)
                .getParameters()[0].isAnnotationPresent(RequestParam.class)).isTrue();
        assertThat(MinePortfolioController.class.getMethod("detail", Long.class)
                .getParameters()[0].isAnnotationPresent(PathVariable.class)).isTrue();
        assertThat(Arrays.stream(MinePortfolioController.class.getMethods()).map(method -> method.getName()))
                .doesNotContain("createCoverUploadTicket");
        assertThat(listed.getData()).isSameAs(listResponse);
        assertThat(library.getData()).isSameAs(libraryResponse);
        assertThat(created.getData()).isSameAs(detailResponse);
        assertThat(assetTicket.getData()).isSameAs(assetTicketResponse);
        assertThat(detail.getData()).isSameAs(detailResponse);
        assertThat(saved.getData()).isSameAs(detailResponse);
        assertThat(preview.getData()).isSameAs(detailResponse);
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
}
