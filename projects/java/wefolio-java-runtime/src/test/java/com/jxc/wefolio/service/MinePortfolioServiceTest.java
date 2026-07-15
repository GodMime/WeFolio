package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import com.jxc.wefolio.dict.SlotDefinitionStatusDict;
import com.jxc.wefolio.dto.MinePortfolioAssetUploadTicketRequest;
import com.jxc.wefolio.dto.MinePortfolioAssetUploadTicketResponse;
import com.jxc.wefolio.dto.MinePortfolioCreateRequest;
import com.jxc.wefolio.dto.MinePortfolioDetailResponse;
import com.jxc.wefolio.dto.MinePortfolioDraftSaveRequest;
import com.jxc.wefolio.dto.MinePortfolioListResponse;
import com.jxc.wefolio.dto.MinePortfolioPublishRequest;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.dto.PortfolioRenderDto;
import com.jxc.wefolio.dto.PortfolioScheduleOptionsResponse;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.PortfolioHistoryEntity;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.entity.ScheduleEntity;
import com.jxc.wefolio.entity.SlotDefinitionEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.PortfolioHistoryEntityMapper;
import com.jxc.wefolio.mapper.PortfolioReferenceEntityMapper;
import com.jxc.wefolio.mapper.PortfolioShareRecordEntityMapper;
import com.jxc.wefolio.mapper.ScheduleEntityMapper;
import com.jxc.wefolio.mapper.SlotDefinitionEntityMapper;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioReferenceGuardService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 我的作品集服务测试 — 覆盖标准个人作品集草稿、预览和发布流程。
 */
@ExtendWith(MockitoExtension.class)
class MinePortfolioServiceTest {

    /** 初始化 Lambda 查询列缓存。 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                PortfolioEntity.class
        );
    }

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

    /** 档期 Mapper 模拟 */
    @Mock
    private ScheduleEntityMapper scheduleEntityMapper;

    /** 档位定义 Mapper 模拟 */
    @Mock
    private SlotDefinitionEntityMapper slotDefinitionEntityMapper;

    /** 积分服务模拟 */
    @Mock
    private PointService pointService;

    /** 标准作品集发布事务服务模拟 */
    @Mock
    private PortfolioPublishTransactionService portfolioPublishTransactionService;

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

    /** 团队作品集引用保护服务模拟 */
    @Mock
    private TeamPortfolioReferenceGuardService teamPortfolioReferenceGuardService;

    /** 内容数量上限服务模拟 */
    @Mock
    private ContentLimitService contentLimitService;

    @BeforeEach
    void setUp() {
        AuthContextHolder.set(new AuthContext(7L, "wf-user-7"));
        lenient().when(cosService.publicUrl(any())).thenAnswer(
                invocation -> "https://cos.we-folio.dingchenyong.top/" + invocation.getArgument(0));
        lenient().when(portfolioPublishTransactionService.execute(any())).thenAnswer(invocation -> {
            Supplier<?> publishAction = invocation.getArgument(0);
            return publishAction.get();
        });
    }

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    @Test
    void saveDraftShouldBeTransactionalAndPublishShouldUseIndependentBoundary() throws NoSuchMethodException {
        Method saveDraft = MinePortfolioService.class.getMethod("saveDraft", Long.class, MinePortfolioDraftSaveRequest.class);
        Method publish = MinePortfolioService.class.getMethod("publish", Long.class, MinePortfolioPublishRequest.class);

        assertThat(saveDraft.getAnnotation(Transactional.class).rollbackFor()).contains(Exception.class);
        assertThat(publish.getAnnotation(Transactional.class)).isNull();
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
        verify(contentLimitService).ensurePersonalPortfolioCapacity(7L);
        verify(pointService, never()).consume(any(), any(), any(), any(), any(Integer.class), any(), any());
    }

    @Test
    void createStandardPersonalShouldRejectWhenPortfolioCountReachesLimit() {
        doThrow(new BusinessException("个人作品集数量已达上限（10个），请删除部分个人作品集后再新建"))
                .when(contentLimitService).ensurePersonalPortfolioCapacity(7L);

        assertThatThrownBy(() -> service().createStandardPersonal(new MinePortfolioCreateRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("个人作品集数量已达上限（10个），请删除部分个人作品集后再新建");

        verify(portfolioEntityMapper, never()).insert(any(PortfolioEntity.class));
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
    void listPortfoliosShouldReturnUpdatedAtForPageDisplay() {
        PortfolioEntity portfolio = ownedPortfolio();
        LocalDateTime updatedAt = LocalDateTime.of(2026, 7, 4, 16, 30, 12);
        portfolio.setUpdatedAt(updatedAt);
        when(portfolioEntityMapper.selectList(any())).thenReturn(List.of(portfolio));

        MinePortfolioListResponse response = service().listPortfolios("USER");

        assertThat(response.getPortfolios()).hasSize(1);
        assertThat(response.getPortfolios().get(0))
                .hasFieldOrPropertyWithValue("updatedAt", updatedAt);
    }

    @Test
    void listPortfoliosShouldKeepPersonalOwnerFiltersForBlankOwnerType() {
        when(portfolioEntityMapper.selectList(any())).thenReturn(List.of());

        MinePortfolioListResponse response = service().listPortfolios(" \t ");

        assertThat(response.getPortfolios()).isEmpty();
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<PortfolioEntity>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(portfolioEntityMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("owner_type", "owner_id");
        assertThat(((AbstractWrapper<?, ?, ?>) captor.getValue()).getParamNameValuePairs().values())
                .contains(PortfolioOwnerTypeDict.USER.getCode(), 7L);
    }

    @Test
    void listPortfoliosShouldKeepPersonalOwnerFiltersForExplicitUserOwnerType() {
        when(portfolioEntityMapper.selectList(any())).thenReturn(List.of());

        MinePortfolioListResponse response = service().listPortfolios(" USER ");

        assertThat(response.getPortfolios()).isEmpty();
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<PortfolioEntity>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(portfolioEntityMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("owner_type", "owner_id");
        assertThat(((AbstractWrapper<?, ?, ?>) captor.getValue()).getParamNameValuePairs().values())
                .contains(PortfolioOwnerTypeDict.USER.getCode(), 7L);
    }

    @Test
    void listPortfoliosShouldReturnEmptyWithoutMapperCallForTeamOwnerType() {
        MinePortfolioListResponse response = service().listPortfolios(" TEAM ");

        assertThat(response.getPortfolios()).isEmpty();
        verifyNoInteractions(portfolioEntityMapper);
    }

    @Test
    void listPortfoliosShouldRejectStrippedInvalidOwnerTypeWithoutMapperCall() {
        assertThatThrownBy(() -> service().listPortfolios(" UNKNOWN "))
                .isInstanceOf(BusinessException.class)
                .hasMessage("作品集归属类型不正确");
        verifyNoInteractions(portfolioEntityMapper);
    }

    @Test
    void componentLibraryShouldReturnConfiguredDisplayOrder() {
        assertThat(service().getComponentLibrary().getComponents())
                .extracting("componentType")
                .containsExactly(
                        PortfolioComponentTypeDict.PROFILE.getCode(),
                        PortfolioComponentTypeDict.CAROUSEL.getCode(),
                        PortfolioComponentTypeDict.TEXT_SECTION.getCode(),
                        PortfolioComponentTypeDict.DIVIDER.getCode(),
                        PortfolioComponentTypeDict.WORK_GRID.getCode(),
                        PortfolioComponentTypeDict.WORK_LIST.getCode(),
                        PortfolioComponentTypeDict.SCHEDULE_QUERY.getCode(),
                        PortfolioComponentTypeDict.CONTACT_FORM.getCode(),
                        PortfolioComponentTypeDict.QR_CONTACT.getCode()
                );
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
    void saveDraftShouldDeleteOnlyOldDraftAssetsNoLongerReferencedByEitherCurrentState() {
        String oldDraftCover = "WFA3B1E7A2/protfolio/cover-88-20260701110000-a1b2c3d4.jpg";
        String publishedQr = "WFA3B1E7A2/protfolio/qr-contact-88-20260701120000-b2c3d4e5.png";
        PortfolioEntity portfolio = ownedPortfolio();
        portfolio.setDraftRevision(3);
        portfolio.setPublishedRevision(1);
        portfolio.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        portfolio.setDraftConfigJson("""
                {"schemaVersion":"standard-personal-v1","share":{"coverUrl":"%s"},"components":[]}
                """.formatted(cosUrl(oldDraftCover)));
        portfolio.setPublishedConfigJson("""
                {"schemaVersion":"standard-personal-v1","components":[
                  {"componentKey":"qr","componentType":"QR_CONTACT","sortOrder":1000,"enabled":true,
                   "config":{"qrUrl":"%s"}}
                ]}
                """.formatted(cosUrl(publishedQr)));
        when(portfolioEntityMapper.selectById(88L)).thenReturn(portfolio);
        PortfolioConfigDto normalized = configWithPortfolioAssets("", "", "");
        when(portfolioConfigValidator.normalize(7L, normalized)).thenReturn(normalized);
        when(portfolioConfigValidator.buildReferences(
                88L, 7L, PortfolioConfigScopeDict.DRAFT.getCode(), normalized)).thenReturn(List.of());
        when(miniappAuthService.getUniqueCodeByUserId(7L)).thenReturn("WFA3B1E7A2");
        when(portfolioEntityMapper.updateById(any(PortfolioEntity.class))).thenReturn(1);
        MinePortfolioDraftSaveRequest request = new MinePortfolioDraftSaveRequest();
        request.setConfig(normalized);
        request.setClientRevision(3);
        request.setIdempotencyKey("draft-cleanup");

        service().saveDraft(88L, request);

        verify(cosService).delete(oldDraftCover);
        verify(cosService, never()).delete(publishedQr);
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
    void queryPreviewScheduleOptionsShouldReturnEmptyStringsForNullableScheduleFields() {
        PortfolioEntity portfolio = ownedPortfolio();
        portfolio.setDraftConfigJson(scheduleComponentConfigJson());
        when(portfolioEntityMapper.selectById(88L)).thenReturn(portfolio);
        when(slotDefinitionEntityMapper.selectList(any())).thenReturn(List.of(slotDefinition(12L, "午宴")));
        when(scheduleEntityMapper.selectList(any())).thenReturn(List.of(scheduleWithNullableSnapshots(
                9L,
                12L,
                LocalDate.of(2026, 7, 18)
        )));

        PortfolioScheduleOptionsResponse response = service().queryPreviewScheduleOptions(88L, "2026-07", "c_schedule", null);

        assertThat(response.getSchedules()).hasSize(1);
        PortfolioScheduleOptionsResponse.ScheduleItem item = response.getSchedules().get(0);
        assertThat(item.getDate()).isEqualTo("2026-07-18");
        assertThat(item.getSlotName()).isEmpty();
        assertThat(item.getStartTime()).isEmpty();
        assertThat(item.getEndTime()).isEmpty();
        assertThat(item.getColor()).isEmpty();
        assertThat(item.getStatus()).isEmpty();
        assertThat(item.getStatusText()).isEmpty();
        assertThat(item.getStatusTone()).isEqualTo("muted");
    }

    @Test
    void submitPreviewScheduleQueryShouldRejectEmptyRequestBodyBeforeComponentLookup() {
        assertThatThrownBy(() -> service().submitPreviewScheduleQuery(88L, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("档期查询请求不能为空");
        verify(portfolioEntityMapper, never()).selectById(any());
        verify(slotDefinitionEntityMapper, never()).selectById(any());
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

        verify(pointService).assertCanConsume(
                7L,
                PointSceneCodeDict.MAINTAIN_STANDARD_PORTFOLIO.getCode(),
                "PORTFOLIO",
                "88",
                1,
                "publish-1"
        );
        verify(portfolioPublishTransactionService).execute(any());
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
    void publishShouldStopBeforeTransactionAndWritesWhenPointsAreInsufficient() {
        PortfolioEntity portfolio = ownedPortfolio();
        portfolio.setDraftRevision(4);
        portfolio.setDraftConfigJson(
                "{\"schemaVersion\":\"standard-personal-v1\",\"share\":{\"title\":\"林安婚礼司仪\"},\"components\":[]}"
        );
        when(portfolioEntityMapper.selectById(88L)).thenReturn(portfolio);
        doThrow(new BusinessException("积分余额不足，请充值后再试"))
                .when(pointService).assertCanConsume(
                        7L,
                        PointSceneCodeDict.MAINTAIN_STANDARD_PORTFOLIO.getCode(),
                        "PORTFOLIO",
                        "88",
                        1,
                        "publish-insufficient"
                );
        MinePortfolioPublishRequest request = new MinePortfolioPublishRequest();
        request.setDraftRevision(4);
        request.setIdempotencyKey("publish-insufficient");

        assertThatThrownBy(() -> service().publish(88L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("积分余额不足，请充值后再试");

        verifyNoInteractions(portfolioPublishTransactionService);
        verify(portfolioEntityMapper, never()).updateById(any(PortfolioEntity.class));
        verify(portfolioReferenceEntityMapper, never()).delete(any());
        verify(portfolioReferenceEntityMapper, never()).insert(any(PortfolioReferenceEntity.class));
        verify(portfolioHistoryEntityMapper, never()).insert(any(PortfolioHistoryEntity.class));
        verify(pointService, never()).consume(any(), any(), any(), any(), any(Integer.class), any(), any());
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
    void publishShouldNotDeleteOwnedKeyEmbeddedInForeignUrl() {
        String objectKey = "WFA3B1E7A2/protfolio/cover-88-20260701110000-a1b2c3d4.jpg";
        PortfolioEntity portfolio = ownedPortfolio();
        portfolio.setDraftRevision(4);
        portfolio.setPublishedRevision(1);
        portfolio.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        portfolio.setPublishedConfigJson("""
                {"schemaVersion":"standard-personal-v1","share":{"coverUrl":"https://external.example.com/%s"},"components":[]}
                """.formatted(objectKey));
        portfolio.setDraftConfigJson("""
                {"schemaVersion":"standard-personal-v1","components":[]}
                """);
        when(portfolioEntityMapper.selectById(88L)).thenReturn(portfolio);
        PortfolioConfigDto normalized = configWithPortfolioAssets("", "", "");
        when(portfolioConfigValidator.normalize(eq(7L), any(PortfolioConfigDto.class))).thenReturn(normalized);
        when(portfolioConfigValidator.buildReferences(
                88L, 7L, PortfolioConfigScopeDict.PUBLISHED.getCode(), normalized)).thenReturn(List.of());
        when(miniappAuthService.getUniqueCodeByUserId(7L)).thenReturn("WFA3B1E7A2");
        when(portfolioEntityMapper.updateById(any(PortfolioEntity.class))).thenReturn(1);
        MinePortfolioPublishRequest request = new MinePortfolioPublishRequest();
        request.setDraftRevision(4);
        request.setIdempotencyKey("publish-foreign-url");

        service().publish(88L, request);

        verify(cosService, never()).delete(objectKey);
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

    @Test
    void deletePortfolioShouldStopBeforeWritesAndCosWhenTeamReferenceGuardBlocks() {
        PortfolioEntity portfolio = ownedPortfolio();
        when(portfolioEntityMapper.selectById(88L)).thenReturn(portfolio);
        doThrow(new BusinessException("作品集正在被团队作品集使用，请先移除引用。"))
                .when(teamPortfolioReferenceGuardService).assertPersonalPortfolioNotReferenced(88L);

        assertThatThrownBy(() -> service().deletePortfolio(88L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("作品集正在被团队作品集使用，请先移除引用。");

        verify(teamPortfolioReferenceGuardService).assertPersonalPortfolioNotReferenced(88L);
        verify(portfolioReferenceEntityMapper, never()).delete(any());
        verify(portfolioEntityMapper, never()).update(any(PortfolioEntity.class), any());
        verifyNoInteractions(miniappAuthService, cosService);
    }

    private MinePortfolioService service() {
        return new MinePortfolioService(
                portfolioEntityMapper,
                portfolioHistoryEntityMapper,
                portfolioReferenceEntityMapper,
                portfolioShareRecordEntityMapper,
                scheduleEntityMapper,
                slotDefinitionEntityMapper,
                pointService,
                portfolioPublishTransactionService,
                portfolioConfigValidator,
                portfolioRenderService,
                miniappAuthService,
                cosService,
                teamPortfolioReferenceGuardService,
                contentLimitService
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

    private String cosUrl(String objectKey) {
        return "https://cos.we-folio.dingchenyong.top/" + objectKey;
    }

    private String scheduleComponentConfigJson() {
        return """
                {"schemaVersion":"standard-personal-v1","share":{"title":"林安婚礼司仪"},"components":[
                  {"componentKey":"c_schedule","componentType":"SCHEDULE_QUERY","sortOrder":1000,"enabled":true,
                   "config":{"displayMode":"MODAL_CALENDAR","queryRange":{"type":"UNLIMITED"}}}
                ]}
                """;
    }

    private SlotDefinitionEntity slotDefinition(Long id, String name) {
        SlotDefinitionEntity entity = new SlotDefinitionEntity();
        entity.setId(id);
        entity.setUserId(7L);
        entity.setName(name);
        entity.setStartTime(LocalTime.of(10, 0));
        entity.setEndTime(LocalTime.of(14, 0));
        entity.setColor("#2d5f9a");
        entity.setStatus(SlotDefinitionStatusDict.ACTIVE.getCode());
        return entity;
    }

    private ScheduleEntity scheduleWithNullableSnapshots(Long id, Long slotDefinitionId, LocalDate scheduleDate) {
        ScheduleEntity entity = new ScheduleEntity();
        entity.setId(id);
        entity.setUserId(7L);
        entity.setScheduleDate(scheduleDate);
        entity.setSlotDefinitionId(slotDefinitionId);
        return entity;
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
