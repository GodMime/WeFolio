package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.PortfolioConfigScopeDict;
import com.jxc.wefolio.dict.PortfolioComponentTypeDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.dto.MinePortfolioAssetUploadTicketRequest;
import com.jxc.wefolio.dto.MinePortfolioAssetUploadTicketResponse;
import com.jxc.wefolio.dto.MinePortfolioCreateRequest;
import com.jxc.wefolio.dto.MinePortfolioDetailResponse;
import com.jxc.wefolio.dto.MinePortfolioDraftSaveRequest;
import com.jxc.wefolio.dto.MinePortfolioListResponse;
import com.jxc.wefolio.dto.MinePortfolioPublishRequest;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.dto.PortfolioRenderDto;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.PortfolioHistoryEntity;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.PortfolioHistoryEntityMapper;
import com.jxc.wefolio.mapper.PortfolioReferenceEntityMapper;
import com.jxc.wefolio.mapper.PortfolioShareRecordEntityMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 我的作品集服务测试 — 覆盖标准个人作品集草稿、预览和发布流程。
 */
@ExtendWith(MockitoExtension.class)
class MinePortfolioServiceTest {

    /** 作品集 Mapper 模拟 */
    @Mock
    private PortfolioEntityMapper portfolioEntityMapper;

    /** 历史 Mapper 模拟 */
    @Mock
    private PortfolioHistoryEntityMapper portfolioHistoryEntityMapper;

    /** 引用 Mapper 模拟 */
    @Mock
    private PortfolioReferenceEntityMapper portfolioReferenceEntityMapper;

    /** 分享记录 Mapper 模拟 */
    @Mock
    private PortfolioShareRecordEntityMapper portfolioShareRecordEntityMapper;

    /** 积分服务模拟 */
    @Mock
    private PointService pointService;

    /** 配置校验器模拟 */
    @Mock
    private PortfolioConfigValidator portfolioConfigValidator;

    /** 作品集渲染服务模拟 */
    @Mock
    private PortfolioRenderService portfolioRenderService;

    /** 登录注册服务模拟 */
    @Mock
    private MiniappAuthService miniappAuthService;

    /** COS 服务模拟 */
    @Mock
    private CosService cosService;

    @BeforeEach
    void setUp() {
        AuthContextHolder.set(new AuthContext(7L, "wf-user-7"));
    }

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    @Test
    void saveDraftAndPublishShouldBeTransactional() throws NoSuchMethodException {
        Method saveDraft = MinePortfolioService.class.getMethod("saveDraft", Long.class, MinePortfolioDraftSaveRequest.class);
        Method publish = MinePortfolioService.class.getMethod("publish", Long.class, MinePortfolioPublishRequest.class);

        assertThat(saveDraft.getAnnotation(Transactional.class).rollbackFor()).contains(Exception.class);
        assertThat(publish.getAnnotation(Transactional.class).rollbackFor()).contains(Exception.class);
        assertThat(Arrays.stream(MinePortfolioService.class.getMethods()).map(method -> method.getName()))
                .doesNotContain("createCoverUploadTicket");
    }

    @Test
    void createStandardPersonalShouldCreateDraftOnlyPortfolio() {
        when(portfolioEntityMapper.insert(any(PortfolioEntity.class))).thenAnswer(invocation -> {
            PortfolioEntity portfolio = invocation.getArgument(0);
            portfolio.setId(88L);
            return 1;
        });
        MinePortfolioCreateRequest request = new MinePortfolioCreateRequest();
        request.setConfig(config());

        MinePortfolioDetailResponse response = service().createStandardPersonal(request);

        ArgumentCaptor<PortfolioEntity> captor = ArgumentCaptor.forClass(PortfolioEntity.class);
        verify(portfolioEntityMapper).insert(captor.capture());
        PortfolioEntity inserted = captor.getValue();
        assertThat(inserted.getOwnerType()).isEqualTo(PortfolioOwnerTypeDict.USER.getCode());
        assertThat(inserted.getOwnerId()).isEqualTo(7L);
        assertThat(inserted.getTemplateType()).isEqualTo(PortfolioTemplateTypeDict.STANDARD.getCode());
        assertThat(inserted.getStatus()).isEqualTo(PortfolioStatusDict.ACTIVE.getCode());
        assertThat(inserted.getPublicationStatus()).isEqualTo(PortfolioPublicationStatusDict.DRAFT_ONLY.getCode());
        assertThat(inserted.getDraftRevision()).isZero();
        assertThat(inserted.getPublishedRevision()).isZero();
        assertThat(response.getPortfolioId()).isEqualTo(88L);
        assertThat(response.getPublicationStatus()).isEqualTo(PortfolioPublicationStatusDict.DRAFT_ONLY.getCode());
        verify(pointService, never()).consume(any(), any(), any(), any(), any(Integer.class), any(), any());
    }

    @Test
    void createAssetUploadTicketShouldUsePortfolioCoverNamingRuleAndReturnPublicUrl() {
        PortfolioEntity portfolio = ownedPortfolio();
        when(portfolioEntityMapper.selectById(88L)).thenReturn(portfolio);
        when(miniappAuthService.getUniqueCodeByUserId(7L)).thenReturn("WFA3B1E7A2");
        when(cosService.createPostUploadTicket(any(), eq("image/jpeg"), eq(300L * 1024L), any()))
                .thenAnswer(invocation -> new CosService.PostUploadTicket(
                        "https://cos-upload.example.com",
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3),
                        Map.of("key", invocation.getArgument(0))
                ));
        when(cosService.publicUrl(any())).thenAnswer(invocation -> "https://cos.example.com/" + invocation.getArgument(0));
        MinePortfolioAssetUploadTicketRequest request = new MinePortfolioAssetUploadTicketRequest();
        request.setClientId("cover-local-1");
        request.setAssetType("COVER");
        request.setMimeType("image/jpeg");
        request.setFileSize(300L * 1024L);

        MinePortfolioAssetUploadTicketResponse response = service().createAssetUploadTicket(88L, request);

        assertThat(response.getClientId()).isEqualTo("cover-local-1");
        assertThat(response.getAssetType()).isEqualTo("COVER");
        assertThat(response.getMaxBytes()).isEqualTo(300L * 1024L);
        assertThat(response.getContentType()).isEqualTo("image/jpeg");
        assertThat(response.getObjectKey())
                .matches("WFA3B1E7A2/protfolio/cover-88-\\d{14}-[0-9a-f]{8}\\.jpg");
        assertThat(response.getPublicUrl()).isEqualTo("https://cos.example.com/" + response.getObjectKey());
        assertThat(response.getFormData()).containsEntry("key", response.getObjectKey());
    }

    @Test
    void createAssetUploadTicketShouldUseProfileAvatarNamingRuleAndReturnPublicUrl() {
        PortfolioEntity portfolio = ownedPortfolio();
        when(portfolioEntityMapper.selectById(88L)).thenReturn(portfolio);
        when(miniappAuthService.getUniqueCodeByUserId(7L)).thenReturn("WFA3B1E7A2");
        when(cosService.createPostUploadTicket(any(), eq("image/png"), eq(300L * 1024L), any()))
                .thenAnswer(invocation -> new CosService.PostUploadTicket(
                        "https://cos-upload.example.com",
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3),
                        Map.of("key", invocation.getArgument(0))
                ));
        when(cosService.publicUrl(any())).thenAnswer(invocation -> "https://cos.example.com/" + invocation.getArgument(0));
        MinePortfolioAssetUploadTicketRequest request = new MinePortfolioAssetUploadTicketRequest();
        request.setClientId("profile-avatar-local-1");
        request.setAssetType("PROFILE_AVATAR");
        request.setMimeType("image/png");
        request.setFileSize(180L * 1024L);

        MinePortfolioAssetUploadTicketResponse response = service().createAssetUploadTicket(88L, request);

        assertThat(response.getClientId()).isEqualTo("profile-avatar-local-1");
        assertThat(response.getAssetType()).isEqualTo("PROFILE_AVATAR");
        assertThat(response.getMaxBytes()).isEqualTo(300L * 1024L);
        assertThat(response.getContentType()).isEqualTo("image/png");
        assertThat(response.getObjectKey())
                .matches("WFA3B1E7A2/protfolio/profile-avatar-88-\\d{14}-[0-9a-f]{8}\\.png");
        assertThat(response.getPublicUrl()).isEqualTo("https://cos.example.com/" + response.getObjectKey());
        assertThat(response.getFormData()).containsEntry("key", response.getObjectKey());
    }

    @Test
    void createAssetUploadTicketShouldUseQrContactNamingRuleAndReturnPublicUrl() {
        PortfolioEntity portfolio = ownedPortfolio();
        when(portfolioEntityMapper.selectById(88L)).thenReturn(portfolio);
        when(miniappAuthService.getUniqueCodeByUserId(7L)).thenReturn("WFA3B1E7A2");
        when(cosService.createPostUploadTicket(any(), eq("image/jpeg"), eq(300L * 1024L), any()))
                .thenAnswer(invocation -> new CosService.PostUploadTicket(
                        "https://cos-upload.example.com",
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3),
                        Map.of("key", invocation.getArgument(0))
                ));
        when(cosService.publicUrl(any())).thenAnswer(invocation -> "https://cos.example.com/" + invocation.getArgument(0));
        MinePortfolioAssetUploadTicketRequest request = new MinePortfolioAssetUploadTicketRequest();
        request.setClientId("qr-contact-local-1");
        request.setAssetType("QR_CONTACT");
        request.setMimeType("image/jpeg");
        request.setFileSize(120L * 1024L);

        MinePortfolioAssetUploadTicketResponse response = service().createAssetUploadTicket(88L, request);

        assertThat(response.getClientId()).isEqualTo("qr-contact-local-1");
        assertThat(response.getAssetType()).isEqualTo("QR_CONTACT");
        assertThat(response.getMaxBytes()).isEqualTo(300L * 1024L);
        assertThat(response.getContentType()).isEqualTo("image/jpeg");
        assertThat(response.getObjectKey())
                .matches("WFA3B1E7A2/protfolio/qr-contact-88-\\d{14}-[0-9a-f]{8}\\.jpg");
        assertThat(response.getPublicUrl()).isEqualTo("https://cos.example.com/" + response.getObjectKey());
        assertThat(response.getFormData()).containsEntry("key", response.getObjectKey());
    }

    @Test
    void createAssetUploadTicketShouldRejectOversizedCoverBeforeCreatingCosTicket() {
        PortfolioEntity portfolio = ownedPortfolio();
        when(portfolioEntityMapper.selectById(88L)).thenReturn(portfolio);
        MinePortfolioAssetUploadTicketRequest request = new MinePortfolioAssetUploadTicketRequest();
        request.setAssetType("COVER");
        request.setMimeType("image/png");
        request.setFileSize(300L * 1024L + 1L);

        assertThatThrownBy(() -> service().createAssetUploadTicket(88L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("封面图片不能超过 300KB");
        verify(cosService, never()).createPostUploadTicket(any(), any(), any(Long.class), any());
    }

    @Test
    void listPortfoliosShouldReturnCoverUrlFromDraftFirstConfig() {
        PortfolioEntity portfolio = ownedPortfolio();
        portfolio.setDraftConfigJson("""
                {"schemaVersion":"standard-personal-v1","share":{"title":"林安婚礼司仪","coverUrl":"https://cos.example.com/cover.jpg"},"components":[]}
                """);
        when(portfolioEntityMapper.selectList(any())).thenReturn(List.of(portfolio));

        MinePortfolioListResponse response = service().listPortfolios("USER");

        assertThat(response.getPortfolios()).hasSize(1);
        assertThat(response.getPortfolios().get(0).getCoverUrl()).isEqualTo("https://cos.example.com/cover.jpg");
    }

    @Test
    void componentLibraryShouldExposeSingleColumnWorkList() {
        assertThat(service().getComponentLibrary().getComponents())
                .extracting("componentType")
                .contains(PortfolioComponentTypeDict.WORK_LIST.getCode());
    }

    @Test
    void saveDraftShouldIncrementRevisionWriteHistoryAndRebuildDraftReferencesWithoutConsumingPoints() {
        PortfolioEntity portfolio = ownedPortfolio();
        portfolio.setDraftRevision(3);
        when(portfolioEntityMapper.selectById(88L)).thenReturn(portfolio);
        PortfolioConfigDto normalized = config();
        when(portfolioConfigValidator.normalize(7L, normalized)).thenReturn(normalized);
        when(portfolioConfigValidator.buildReferences(88L, 7L, PortfolioConfigScopeDict.DRAFT.getCode(), normalized))
                .thenReturn(List.of(reference(88L, PortfolioConfigScopeDict.DRAFT.getCode(), 11L)));
        when(portfolioEntityMapper.updateById(any(PortfolioEntity.class))).thenReturn(1);
        MinePortfolioDraftSaveRequest request = new MinePortfolioDraftSaveRequest();
        request.setConfig(normalized);
        request.setClientRevision(3);
        request.setIdempotencyKey("draft-1");

        MinePortfolioDetailResponse response = service().saveDraft(88L, request);

        ArgumentCaptor<PortfolioEntity> portfolioCaptor = ArgumentCaptor.forClass(PortfolioEntity.class);
        verify(portfolioEntityMapper).updateById(portfolioCaptor.capture());
        PortfolioEntity updated = portfolioCaptor.getValue();
        assertThat(updated.getDraftRevision()).isEqualTo(4);
        assertThat(updated.getDraftConfigJson()).contains("\"schemaVersion\":\"standard-personal-v1\"");
        assertThat(updated.getDraftContentHash()).hasSize(64);
        assertThat(updated.getDraftSavedBy()).isEqualTo(7L);
        assertThat(updated.getPublicationStatus()).isEqualTo(PortfolioPublicationStatusDict.DRAFT_ONLY.getCode());
        verify(portfolioReferenceEntityMapper).delete(any());
        verify(portfolioReferenceEntityMapper).insert(any(PortfolioReferenceEntity.class));
        verify(portfolioHistoryEntityMapper).insert(any(PortfolioHistoryEntity.class));
        verify(pointService, never()).consume(any(), any(), any(), any(), any(Integer.class), any(), any());
        assertThat(response.getDraftRevision()).isEqualTo(4);
    }

    @Test
    void saveDraftShouldUseCurrentRevisionForHistoryAfterPublishedRevisionExists() {
        PortfolioEntity portfolio = ownedPortfolio();
        portfolio.setDraftRevision(1);
        portfolio.setPublishedRevision(1);
        portfolio.setCurrentRevision(2);
        portfolio.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        when(portfolioEntityMapper.selectById(88L)).thenReturn(portfolio);
        PortfolioConfigDto normalized = config();
        when(portfolioConfigValidator.normalize(7L, normalized)).thenReturn(normalized);
        when(portfolioConfigValidator.buildReferences(88L, 7L, PortfolioConfigScopeDict.DRAFT.getCode(), normalized))
                .thenReturn(List.of());
        when(portfolioEntityMapper.updateById(any(PortfolioEntity.class))).thenReturn(1);
        MinePortfolioDraftSaveRequest request = new MinePortfolioDraftSaveRequest();
        request.setConfig(normalized);
        request.setClientRevision(1);
        request.setIdempotencyKey("draft-after-publish");

        service().saveDraft(88L, request);

        ArgumentCaptor<PortfolioEntity> portfolioCaptor = ArgumentCaptor.forClass(PortfolioEntity.class);
        verify(portfolioEntityMapper).updateById(portfolioCaptor.capture());
        assertThat(portfolioCaptor.getValue().getDraftRevision()).isEqualTo(2);
        assertThat(portfolioCaptor.getValue().getCurrentRevision()).isEqualTo(3);
        ArgumentCaptor<PortfolioHistoryEntity> historyCaptor = ArgumentCaptor.forClass(PortfolioHistoryEntity.class);
        verify(portfolioHistoryEntityMapper).insert(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getRevisionNo()).isEqualTo(3);
    }

    @Test
    void saveDraftShouldRejectConcurrentUpdateBeforeWritingReferencesAndHistory() {
        PortfolioEntity portfolio = ownedPortfolio();
        portfolio.setDraftRevision(3);
        when(portfolioEntityMapper.selectById(88L)).thenReturn(portfolio);
        PortfolioConfigDto normalized = config();
        when(portfolioConfigValidator.normalize(7L, normalized)).thenReturn(normalized);
        when(portfolioEntityMapper.updateById(any(PortfolioEntity.class))).thenReturn(0);
        MinePortfolioDraftSaveRequest request = new MinePortfolioDraftSaveRequest();
        request.setConfig(normalized);
        request.setClientRevision(3);
        request.setIdempotencyKey("draft-conflict");

        assertThatThrownBy(() -> service().saveDraft(88L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("并发冲突，请刷新重试");
        verify(portfolioReferenceEntityMapper, never()).delete(any());
        verify(portfolioReferenceEntityMapper, never()).insert(any(PortfolioReferenceEntity.class));
        verify(portfolioHistoryEntityMapper, never()).insert(any(PortfolioHistoryEntity.class));
        verify(pointService, never()).consume(any(), any(), any(), any(), any(Integer.class), any(), any());
    }

    @Test
    void previewShouldReturnDraftConfigWithoutConsumingPointsOrWritingReferences() {
        PortfolioEntity portfolio = ownedPortfolio();
        portfolio.setDraftConfigJson("{\"schemaVersion\":\"standard-personal-v1\",\"components\":[]}");
        portfolio.setDraftRevision(5);
        when(portfolioEntityMapper.selectById(88L)).thenReturn(portfolio);
        PortfolioRenderDto renderData = new PortfolioRenderDto();
        renderData.setPreview(true);
        when(portfolioRenderService.render(eq(portfolio), any(PortfolioConfigDto.class), eq(true), eq(false), isNull(), isNull()))
                .thenReturn(renderData);

        MinePortfolioDetailResponse response = service().preview(88L);

        assertThat(response.getDraftRevision()).isEqualTo(5);
        assertThat(response.getConfig()).isNotNull();
        assertThat(response.getRenderData()).isSameAs(renderData);
        assertThat(response.getRenderData().isPreview()).isTrue();
        verify(pointService, never()).consume(any(), any(), any(), any(), any(Integer.class), any(), any());
        verify(portfolioReferenceEntityMapper, never()).insert(any(PortfolioReferenceEntity.class));
        verify(portfolioHistoryEntityMapper, never()).insert(any(PortfolioHistoryEntity.class));
    }

    @Test
    void previewPublishedShouldReturnPublishedConfigWithoutConsumingPointsOrWritingReferences() {
        PortfolioEntity portfolio = ownedPortfolio();
        portfolio.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        portfolio.setDraftConfigJson("{\"schemaVersion\":\"standard-personal-v1\",\"share\":{\"title\":\"草稿标题\"},\"components\":[]}");
        portfolio.setPublishedConfigJson("{\"schemaVersion\":\"standard-personal-v1\",\"share\":{\"title\":\"发布标题\"},\"components\":[]}");
        portfolio.setDraftRevision(5);
        portfolio.setPublishedRevision(4);
        when(portfolioEntityMapper.selectById(88L)).thenReturn(portfolio);
        PortfolioRenderDto renderData = new PortfolioRenderDto();
        renderData.setPreview(true);
        renderData.setTitle("发布标题");
        when(portfolioRenderService.render(eq(portfolio), any(PortfolioConfigDto.class), eq(true), eq(false), isNull(), isNull()))
                .thenReturn(renderData);

        MinePortfolioDetailResponse response = service().previewPublished(88L);

        assertThat(response.getDraftRevision()).isEqualTo(5);
        assertThat(response.getPublishedRevision()).isEqualTo(4);
        assertThat(response.getConfig().getShare().getTitle()).isEqualTo("发布标题");
        assertThat(response.getRenderData()).isSameAs(renderData);
        assertThat(response.getRenderData().isPreview()).isTrue();
        verify(pointService, never()).consume(any(), any(), any(), any(), any(Integer.class), any(), any());
        verify(portfolioReferenceEntityMapper, never()).insert(any(PortfolioReferenceEntity.class));
        verify(portfolioHistoryEntityMapper, never()).insert(any(PortfolioHistoryEntity.class));
    }

    @Test
    void publishShouldCopyDraftConfigConsumeOnePointAndRebuildPublishedReferences() {
        PortfolioEntity portfolio = ownedPortfolio();
        portfolio.setDraftRevision(4);
        portfolio.setPublishedRevision(1);
        portfolio.setDraftConfigJson("{\"schemaVersion\":\"standard-personal-v1\",\"share\":{\"title\":\"林安婚礼司仪\"},\"components\":[]}");
        when(portfolioEntityMapper.selectById(88L)).thenReturn(portfolio);
        PortfolioConfigDto normalized = config();
        when(portfolioConfigValidator.normalize(eq(7L), any(PortfolioConfigDto.class))).thenReturn(normalized);
        when(portfolioConfigValidator.buildReferences(88L, 7L, PortfolioConfigScopeDict.PUBLISHED.getCode(), normalized))
                .thenReturn(List.of(reference(88L, PortfolioConfigScopeDict.PUBLISHED.getCode(), 11L)));
        when(portfolioEntityMapper.updateById(any(PortfolioEntity.class))).thenReturn(1);
        MinePortfolioPublishRequest request = new MinePortfolioPublishRequest();
        request.setDraftRevision(4);
        request.setIdempotencyKey("publish-1");

        MinePortfolioDetailResponse response = service().publish(88L, request);

        verify(pointService).consume(
                7L,
                PointSceneCodeDict.MAINTAIN_STANDARD_PORTFOLIO.getCode(),
                "PORTFOLIO",
                "88",
                1,
                "publish-1",
                "发布标准个人作品集"
        );
        ArgumentCaptor<PortfolioEntity> portfolioCaptor = ArgumentCaptor.forClass(PortfolioEntity.class);
        verify(portfolioEntityMapper).updateById(portfolioCaptor.capture());
        PortfolioEntity updated = portfolioCaptor.getValue();
        assertThat(updated.getPublishedRevision()).isEqualTo(2);
        assertThat(updated.getPublishedConfigJson()).contains("\"schemaVersion\":\"standard-personal-v1\"");
        assertThat(updated.getPublicationStatus()).isEqualTo(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        verify(portfolioReferenceEntityMapper).delete(any());
        verify(portfolioReferenceEntityMapper).insert(any(PortfolioReferenceEntity.class));
        verify(portfolioHistoryEntityMapper).insert(any(PortfolioHistoryEntity.class));
        assertThat(response.getPublishedRevision()).isEqualTo(2);
        assertThat(response.getPublicationStatus()).isEqualTo(PortfolioPublicationStatusDict.PUBLISHED.getCode());
    }

    @Test
    void publishShouldUseCurrentRevisionForHistoryAfterDraftSave() {
        PortfolioEntity portfolio = ownedPortfolio();
        portfolio.setDraftRevision(1);
        portfolio.setPublishedRevision(0);
        portfolio.setCurrentRevision(1);
        portfolio.setDraftConfigJson("{\"schemaVersion\":\"standard-personal-v1\",\"share\":{\"title\":\"林安婚礼司仪\"},\"components\":[]}");
        when(portfolioEntityMapper.selectById(88L)).thenReturn(portfolio);
        PortfolioConfigDto normalized = config();
        when(portfolioConfigValidator.normalize(eq(7L), any(PortfolioConfigDto.class))).thenReturn(normalized);
        when(portfolioConfigValidator.buildReferences(88L, 7L, PortfolioConfigScopeDict.PUBLISHED.getCode(), normalized))
                .thenReturn(List.of());
        when(portfolioEntityMapper.updateById(any(PortfolioEntity.class))).thenReturn(1);
        MinePortfolioPublishRequest request = new MinePortfolioPublishRequest();
        request.setDraftRevision(1);
        request.setIdempotencyKey("publish-after-first-draft");

        MinePortfolioDetailResponse response = service().publish(88L, request);

        ArgumentCaptor<PortfolioEntity> portfolioCaptor = ArgumentCaptor.forClass(PortfolioEntity.class);
        verify(portfolioEntityMapper).updateById(portfolioCaptor.capture());
        assertThat(portfolioCaptor.getValue().getPublishedRevision()).isEqualTo(1);
        assertThat(portfolioCaptor.getValue().getCurrentRevision()).isEqualTo(2);
        ArgumentCaptor<PortfolioHistoryEntity> historyCaptor = ArgumentCaptor.forClass(PortfolioHistoryEntity.class);
        verify(portfolioHistoryEntityMapper).insert(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getRevisionNo()).isEqualTo(2);
        assertThat(response.getPublishedRevision()).isEqualTo(1);
    }

    @Test
    void publishShouldRejectConcurrentUpdateBeforeConsumingPointsAndWritingSideEffects() {
        PortfolioEntity portfolio = ownedPortfolio();
        portfolio.setDraftRevision(4);
        portfolio.setPublishedRevision(1);
        portfolio.setDraftConfigJson("{\"schemaVersion\":\"standard-personal-v1\",\"share\":{\"title\":\"林安婚礼司仪\"},\"components\":[]}");
        when(portfolioEntityMapper.selectById(88L)).thenReturn(portfolio);
        PortfolioConfigDto normalized = config();
        when(portfolioConfigValidator.normalize(eq(7L), any(PortfolioConfigDto.class))).thenReturn(normalized);
        when(portfolioEntityMapper.updateById(any(PortfolioEntity.class))).thenReturn(0);
        MinePortfolioPublishRequest request = new MinePortfolioPublishRequest();
        request.setDraftRevision(4);
        request.setIdempotencyKey("publish-conflict");

        assertThatThrownBy(() -> service().publish(88L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("并发冲突，请刷新重试");
        verify(pointService, never()).consume(any(), any(), any(), any(), any(Integer.class), any(), any());
        verify(portfolioReferenceEntityMapper, never()).delete(any());
        verify(portfolioReferenceEntityMapper, never()).insert(any(PortfolioReferenceEntity.class));
        verify(portfolioHistoryEntityMapper, never()).insert(any(PortfolioHistoryEntity.class));
    }

    @Test
    void publishShouldDeletePreviousPublishedCoverWhenCoverUrlChanges() {
        PortfolioEntity portfolio = ownedPortfolio();
        portfolio.setDraftRevision(4);
        portfolio.setPublishedRevision(1);
        portfolio.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        portfolio.setPublishedConfigJson("""
                {"schemaVersion":"standard-personal-v1","share":{"title":"旧封面","coverUrl":"https://cos.we-folio.dingchenyong.top/WFA3B1E7A2/protfolio/cover-88-20260701110000-a1b2c3d4.jpg"},"components":[]}
                """);
        portfolio.setDraftConfigJson("""
                {"schemaVersion":"standard-personal-v1","share":{"title":"新封面","coverUrl":"https://cos.we-folio.dingchenyong.top/WFA3B1E7A2/protfolio/cover-88-20260702120000-d4c3b2a1.png"},"components":[]}
                """);
        when(portfolioEntityMapper.selectById(88L)).thenReturn(portfolio);
        PortfolioConfigDto normalized = config();
        normalized.getShare().setCoverUrl("https://cos.we-folio.dingchenyong.top/WFA3B1E7A2/protfolio/cover-88-20260702120000-d4c3b2a1.png");
        when(portfolioConfigValidator.normalize(eq(7L), any(PortfolioConfigDto.class))).thenReturn(normalized);
        when(portfolioConfigValidator.buildReferences(88L, 7L, PortfolioConfigScopeDict.PUBLISHED.getCode(), normalized))
                .thenReturn(List.of());
        when(miniappAuthService.getUniqueCodeByUserId(7L)).thenReturn("WFA3B1E7A2");
        when(portfolioEntityMapper.updateById(any(PortfolioEntity.class))).thenReturn(1);
        MinePortfolioPublishRequest request = new MinePortfolioPublishRequest();
        request.setDraftRevision(4);
        request.setIdempotencyKey("publish-cover");

        service().publish(88L, request);

        verify(cosService).delete("WFA3B1E7A2/protfolio/cover-88-20260701110000-a1b2c3d4.jpg");
    }

    @Test
    void publishShouldDeletePreviousPublishedPortfolioImagesWhenNoLongerReferenced() {
        PortfolioEntity portfolio = ownedPortfolio();
        portfolio.setDraftRevision(4);
        portfolio.setPublishedRevision(1);
        portfolio.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        portfolio.setPublishedConfigJson("""
                {
                  "schemaVersion":"standard-personal-v1",
                  "share":{"title":"旧素材","coverUrl":"https://cos.we-folio.dingchenyong.top/WFA3B1E7A2/protfolio/cover-88-20260701110000-a1b2c3d4.jpg"},
                  "components":[
                    {"componentKey":"c_profile","componentType":"PROFILE","sortOrder":1000,"enabled":true,"config":{"profile":{"avatarUrl":"https://cos.we-folio.dingchenyong.top/WFA3B1E7A2/protfolio/profile-avatar-88-20260701111000-a1b2c3d4.jpg","wechatQrUrl":"https://cos.we-folio.dingchenyong.top/WFA3B1E7A2/protfolio/qr-contact-88-20260701111500-a1b2c3d4.png"}}},
                    {"componentKey":"c_qr","componentType":"QR_CONTACT","sortOrder":2000,"enabled":true,"config":{"qrUrlSource":"CUSTOM","qrUrl":"https://cos.we-folio.dingchenyong.top/WFA3B1E7A2/protfolio/qr-contact-88-20260701112000-a1b2c3d4.png"}}
                  ]
                }
                """);
        portfolio.setDraftConfigJson("""
                {
                  "schemaVersion":"standard-personal-v1",
                  "share":{"title":"新素材","coverUrl":"https://cos.we-folio.dingchenyong.top/WFA3B1E7A2/protfolio/cover-88-20260702120000-d4c3b2a1.jpg"},
                  "components":[
                    {"componentKey":"c_profile","componentType":"PROFILE","sortOrder":1000,"enabled":true,"config":{"profile":{"avatarUrl":"https://cos.we-folio.dingchenyong.top/WFA3B1E7A2/protfolio/profile-avatar-88-20260702121000-d4c3b2a1.jpg","wechatQrUrl":"https://cos.we-folio.dingchenyong.top/WFA3B1E7A2/protfolio/qr-contact-88-20260702121500-d4c3b2a1.png"}}},
                    {"componentKey":"c_qr","componentType":"QR_CONTACT","sortOrder":2000,"enabled":true,"config":{"qrUrlSource":"CUSTOM","qrUrl":"https://cos.we-folio.dingchenyong.top/WFA3B1E7A2/protfolio/qr-contact-88-20260702122000-d4c3b2a1.png"}}
                  ]
                }
                """);
        when(portfolioEntityMapper.selectById(88L)).thenReturn(portfolio);
        PortfolioConfigDto normalized = configWithPortfolioAssets(
                "https://cos.we-folio.dingchenyong.top/WFA3B1E7A2/protfolio/cover-88-20260702120000-d4c3b2a1.jpg",
                "https://cos.we-folio.dingchenyong.top/WFA3B1E7A2/protfolio/profile-avatar-88-20260702121000-d4c3b2a1.jpg",
                "https://cos.we-folio.dingchenyong.top/WFA3B1E7A2/protfolio/qr-contact-88-20260702122000-d4c3b2a1.png"
        );
        when(portfolioConfigValidator.normalize(eq(7L), any(PortfolioConfigDto.class))).thenReturn(normalized);
        when(portfolioConfigValidator.buildReferences(88L, 7L, PortfolioConfigScopeDict.PUBLISHED.getCode(), normalized))
                .thenReturn(List.of());
        when(miniappAuthService.getUniqueCodeByUserId(7L)).thenReturn("WFA3B1E7A2");
        when(portfolioEntityMapper.updateById(any(PortfolioEntity.class))).thenReturn(1);
        MinePortfolioPublishRequest request = new MinePortfolioPublishRequest();
        request.setDraftRevision(4);
        request.setIdempotencyKey("publish-assets");

        service().publish(88L, request);

        verify(cosService).delete("WFA3B1E7A2/protfolio/cover-88-20260701110000-a1b2c3d4.jpg");
        verify(cosService).delete("WFA3B1E7A2/protfolio/profile-avatar-88-20260701111000-a1b2c3d4.jpg");
        verify(cosService).delete("WFA3B1E7A2/protfolio/qr-contact-88-20260701111500-a1b2c3d4.png");
        verify(cosService).delete("WFA3B1E7A2/protfolio/qr-contact-88-20260701112000-a1b2c3d4.png");
    }

    @Test
    void publishShouldKeepPreviousPublishedPortfolioImageWhenStillReferencedInDraft() {
        PortfolioEntity portfolio = ownedPortfolio();
        portfolio.setDraftRevision(4);
        portfolio.setPublishedRevision(1);
        portfolio.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        portfolio.setPublishedConfigJson("""
                {"schemaVersion":"standard-personal-v1","share":{"title":"旧素材"},"components":[
                  {"componentKey":"c_profile","componentType":"PROFILE","sortOrder":1000,"enabled":true,"config":{"profile":{"avatarUrl":"https://cos.we-folio.dingchenyong.top/WFA3B1E7A2/protfolio/profile-avatar-88-20260701111000-a1b2c3d4.jpg"}}}
                ]}
                """);
        portfolio.setDraftConfigJson(portfolio.getPublishedConfigJson());
        when(portfolioEntityMapper.selectById(88L)).thenReturn(portfolio);
        PortfolioConfigDto normalized = configWithPortfolioAssets(
                "",
                "https://cos.we-folio.dingchenyong.top/WFA3B1E7A2/protfolio/profile-avatar-88-20260701111000-a1b2c3d4.jpg",
                ""
        );
        when(portfolioConfigValidator.normalize(eq(7L), any(PortfolioConfigDto.class))).thenReturn(normalized);
        when(portfolioConfigValidator.buildReferences(88L, 7L, PortfolioConfigScopeDict.PUBLISHED.getCode(), normalized))
                .thenReturn(List.of());
        when(miniappAuthService.getUniqueCodeByUserId(7L)).thenReturn("WFA3B1E7A2");
        when(portfolioEntityMapper.updateById(any(PortfolioEntity.class))).thenReturn(1);
        MinePortfolioPublishRequest request = new MinePortfolioPublishRequest();
        request.setDraftRevision(4);
        request.setIdempotencyKey("publish-keep-assets");

        service().publish(88L, request);

        verify(cosService, never()).delete(any());
    }

    @Test
    void publishShouldRejectStaleDraftRevisionBeforeConsumingPoints() {
        PortfolioEntity portfolio = ownedPortfolio();
        portfolio.setDraftRevision(5);
        when(portfolioEntityMapper.selectById(88L)).thenReturn(portfolio);
        MinePortfolioPublishRequest request = new MinePortfolioPublishRequest();
        request.setDraftRevision(4);
        request.setIdempotencyKey("publish-1");

        assertThatThrownBy(() -> service().publish(88L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("草稿已更新，请刷新后再发布");
        verify(pointService, never()).consume(any(), any(), any(), any(), any(Integer.class), any(), any());
    }

    @Test
    void deletePortfolioShouldSoftDeleteReferencesAndConservativeSavedAssets() {
        PortfolioEntity portfolio = ownedPortfolio();
        portfolio.setDraftConfigJson("""
                {
                  "schemaVersion":"standard-personal-v1",
                  "share":{"title":"草稿","coverUrl":"https://cos.we-folio.dingchenyong.top/WFA3B1E7A2/protfolio/cover-88-20260703110000-a1b2c3d4.jpg"},
                  "components":[
                    {"componentKey":"c_profile","componentType":"PROFILE","sortOrder":1000,"enabled":true,"config":{"profile":{"avatarUrl":"https://cos.we-folio.dingchenyong.top/WFA3B1E7A2/protfolio/profile-avatar-88-20260703111000-a1b2c3d4.png","wechatQrUrl":"https://cos.we-folio.dingchenyong.top/WFA3B1E7A2/protfolio/qr-contact-88-20260703111500-a1b2c3d4.jpg"}}}
                  ]
                }
                """);
        portfolio.setPublishedConfigJson("""
                {
                  "schemaVersion":"standard-personal-v1",
                  "share":{"title":"正式","coverUrl":"https://cos.we-folio.dingchenyong.top/WFA3B1E7A2/protfolio/cover-88-20260703110000-a1b2c3d4.jpg"},
                  "components":[
                    {"componentKey":"c_qr","componentType":"QR_CONTACT","sortOrder":2000,"enabled":true,"config":{"qrUrlSource":"CUSTOM","qrUrl":"https://cos.we-folio.dingchenyong.top/WFA3B1E7A2/protfolio/qr-contact-88-20260703112000-a1b2c3d4.jpg"}},
                    {"componentKey":"c_foreign","componentType":"PROFILE","sortOrder":3000,"enabled":true,"config":{"profile":{"avatarUrl":"https://cos.we-folio.dingchenyong.top/WFA3B1E7A2/protfolio/profile-avatar-99-20260703113000-a1b2c3d4.jpg"}}}
                  ]
                }
                """);
        when(portfolioEntityMapper.selectById(88L)).thenReturn(portfolio);
        when(miniappAuthService.getUniqueCodeByUserId(7L)).thenReturn("WFA3B1E7A2");
        when(portfolioEntityMapper.update(any(PortfolioEntity.class), any())).thenReturn(1);

        service().deletePortfolio(88L);

        verify(portfolioReferenceEntityMapper).delete(any());
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<UpdateWrapper> updateCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(portfolioEntityMapper).update(any(PortfolioEntity.class), updateCaptor.capture());
        @SuppressWarnings("unchecked")
        UpdateWrapper<PortfolioEntity> wrapper = updateCaptor.getValue();
        assertThat(wrapper.getSqlSet()).contains("deleted_at=");
        assertThat(wrapper.getSqlSet()).contains("deleted=#{");
        assertThat(wrapper.getParamNameValuePairs()).containsValue(88L);
        verify(cosService, times(1)).delete("WFA3B1E7A2/protfolio/cover-88-20260703110000-a1b2c3d4.jpg");
        verify(cosService).delete("WFA3B1E7A2/protfolio/profile-avatar-88-20260703111000-a1b2c3d4.png");
        verify(cosService).delete("WFA3B1E7A2/protfolio/qr-contact-88-20260703111500-a1b2c3d4.jpg");
        verify(cosService).delete("WFA3B1E7A2/protfolio/qr-contact-88-20260703112000-a1b2c3d4.jpg");
        verify(cosService, never()).delete("WFA3B1E7A2/protfolio/profile-avatar-99-20260703113000-a1b2c3d4.jpg");
    }

    @Test
    void deletePortfolioShouldContinueWhenOneCosDeleteFails() {
        PortfolioEntity portfolio = ownedPortfolio();
        portfolio.setDraftConfigJson("""
                {"schemaVersion":"standard-personal-v1","share":{"coverUrl":"https://cos.we-folio.dingchenyong.top/WFA3B1E7A2/protfolio/cover-88-20260703110000-a1b2c3d4.jpg"},"components":[]}
                """);
        portfolio.setPublishedConfigJson("""
                {"schemaVersion":"standard-personal-v1","share":{"coverUrl":"https://cos.we-folio.dingchenyong.top/WFA3B1E7A2/protfolio/cover-88-20260703120000-d4c3b2a1.png"},"components":[]}
                """);
        when(portfolioEntityMapper.selectById(88L)).thenReturn(portfolio);
        when(miniappAuthService.getUniqueCodeByUserId(7L)).thenReturn("WFA3B1E7A2");
        when(portfolioEntityMapper.update(any(PortfolioEntity.class), any())).thenReturn(1);
        doThrow(new RuntimeException("COS 删除失败"))
                .when(cosService).delete("WFA3B1E7A2/protfolio/cover-88-20260703110000-a1b2c3d4.jpg");

        service().deletePortfolio(88L);

        verify(portfolioEntityMapper).update(any(PortfolioEntity.class), any());
        verify(cosService).delete("WFA3B1E7A2/protfolio/cover-88-20260703110000-a1b2c3d4.jpg");
        verify(cosService).delete("WFA3B1E7A2/protfolio/cover-88-20260703120000-d4c3b2a1.png");
    }

    private MinePortfolioService service() {
        return new MinePortfolioService(
                portfolioEntityMapper,
                portfolioHistoryEntityMapper,
                portfolioReferenceEntityMapper,
                portfolioShareRecordEntityMapper,
                pointService,
                portfolioConfigValidator,
                portfolioRenderService,
                miniappAuthService,
                cosService
        );
    }

    private PortfolioEntity ownedPortfolio() {
        PortfolioEntity portfolio = new PortfolioEntity();
        portfolio.setId(88L);
        portfolio.setShareCode("PF000088");
        portfolio.setOwnerType(PortfolioOwnerTypeDict.USER.getCode());
        portfolio.setOwnerId(7L);
        portfolio.setTemplateType(PortfolioTemplateTypeDict.STANDARD.getCode());
        portfolio.setStatus(PortfolioStatusDict.ACTIVE.getCode());
        portfolio.setPublicationStatus(PortfolioPublicationStatusDict.DRAFT_ONLY.getCode());
        portfolio.setDraftRevision(0);
        portfolio.setPublishedRevision(0);
        return portfolio;
    }

    private PortfolioConfigDto config() {
        PortfolioConfigDto config = new PortfolioConfigDto();
        config.setSchemaVersion(PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1);
        PortfolioConfigDto.Share share = new PortfolioConfigDto.Share();
        share.setTitle("林安婚礼司仪");
        config.setShare(share);
        PortfolioConfigDto.Component component = new PortfolioConfigDto.Component();
        component.setComponentKey("c_profile");
        component.setComponentType("PROFILE");
        component.setSortOrder(1000);
        component.setEnabled(true);
        component.setConfig(new LinkedHashMap<>(Map.of()));
        config.setComponents(List.of(component));
        return config;
    }

    private PortfolioConfigDto configWithPortfolioAssets(String coverUrl, String avatarUrl, String qrUrl) {
        PortfolioConfigDto config = config();
        config.getShare().setCoverUrl(coverUrl);
        PortfolioConfigDto.Component profile = config.getComponents().get(0);
        profile.setConfig(new LinkedHashMap<>(Map.of(
                "profile",
                new LinkedHashMap<>(Map.of("avatarUrl", avatarUrl))
        )));
        PortfolioConfigDto.Component qrContact = new PortfolioConfigDto.Component();
        qrContact.setComponentKey("c_qr");
        qrContact.setComponentType(PortfolioComponentTypeDict.QR_CONTACT.getCode());
        qrContact.setSortOrder(2000);
        qrContact.setEnabled(true);
        qrContact.setConfig(new LinkedHashMap<>(Map.of(
                "qrUrlSource", "CUSTOM",
                "qrUrl", qrUrl
        )));
        config.setComponents(List.of(profile, qrContact));
        return config;
    }

    private PortfolioReferenceEntity reference(Long portfolioId, String scope, Long workId) {
        PortfolioReferenceEntity reference = new PortfolioReferenceEntity();
        reference.setPortfolioId(portfolioId);
        reference.setConfigScope(scope);
        reference.setReferenceType(ReferenceTypeDict.WORK.getCode());
        reference.setReferenceId(workId);
        reference.setComponentKey("c_grid");
        reference.setComponentPath("components[0].workIds[0]");
        reference.setSortOrder(0);
        reference.setIsValid(1);
        return reference;
    }
}
