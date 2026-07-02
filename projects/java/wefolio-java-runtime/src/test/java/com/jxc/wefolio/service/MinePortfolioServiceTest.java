package com.jxc.wefolio.service;

import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.PortfolioConfigScopeDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.dto.MinePortfolioCreateRequest;
import com.jxc.wefolio.dto.MinePortfolioCoverUploadTicketRequest;
import com.jxc.wefolio.dto.MinePortfolioCoverUploadTicketResponse;
import com.jxc.wefolio.dto.MinePortfolioDetailResponse;
import com.jxc.wefolio.dto.MinePortfolioDraftSaveRequest;
import com.jxc.wefolio.dto.MinePortfolioListResponse;
import com.jxc.wefolio.dto.MinePortfolioPublishRequest;
import com.jxc.wefolio.dto.PortfolioConfigDto;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
    void createCoverUploadTicketShouldUsePortfolioCoverNamingRuleAndReturnPublicUrl() {
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
        MinePortfolioCoverUploadTicketRequest request = new MinePortfolioCoverUploadTicketRequest();
        request.setClientId("cover-local-1");
        request.setMimeType("image/jpeg");
        request.setFileSize(300L * 1024L);

        MinePortfolioCoverUploadTicketResponse response = service().createCoverUploadTicket(88L, request);

        assertThat(response.getClientId()).isEqualTo("cover-local-1");
        assertThat(response.getMaxBytes()).isEqualTo(300L * 1024L);
        assertThat(response.getContentType()).isEqualTo("image/jpeg");
        assertThat(response.getObjectKey())
                .matches("WFA3B1E7A2/protfolio/cover-88-\\d{14}-[0-9a-f]{8}\\.jpg");
        assertThat(response.getPublicUrl()).isEqualTo("https://cos.example.com/" + response.getObjectKey());
        assertThat(response.getFormData()).containsEntry("key", response.getObjectKey());
    }

    @Test
    void createCoverUploadTicketShouldRejectOversizedCoverBeforeCreatingCosTicket() {
        PortfolioEntity portfolio = ownedPortfolio();
        when(portfolioEntityMapper.selectById(88L)).thenReturn(portfolio);
        MinePortfolioCoverUploadTicketRequest request = new MinePortfolioCoverUploadTicketRequest();
        request.setMimeType("image/png");
        request.setFileSize(300L * 1024L + 1L);

        assertThatThrownBy(() -> service().createCoverUploadTicket(88L, request))
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
    void previewShouldReturnDraftConfigWithoutConsumingPointsOrWritingReferences() {
        PortfolioEntity portfolio = ownedPortfolio();
        portfolio.setDraftConfigJson("{\"schemaVersion\":\"standard-personal-v1\",\"components\":[]}");
        portfolio.setDraftRevision(5);
        when(portfolioEntityMapper.selectById(88L)).thenReturn(portfolio);

        MinePortfolioDetailResponse response = service().preview(88L);

        assertThat(response.getDraftRevision()).isEqualTo(5);
        assertThat(response.getConfig()).isNotNull();
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

    private MinePortfolioService service() {
        return new MinePortfolioService(
                portfolioEntityMapper,
                portfolioHistoryEntityMapper,
                portfolioReferenceEntityMapper,
                portfolioShareRecordEntityMapper,
                pointService,
                portfolioConfigValidator,
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
