package com.jxc.wefolio.service.teamportfolio;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jxc.wefolio.config.TeamPortfolioProperties;
import com.jxc.wefolio.constant.TeamPortfolioConstants;
import com.jxc.wefolio.dict.JoinStatusDict;
import com.jxc.wefolio.dict.PortfolioConfigScopeDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import com.jxc.wefolio.dict.TeamRoleDict;
import com.jxc.wefolio.dict.TeamStatusDict;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioCreateRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioDraftSaveRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioPublishRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioRenderDto;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioScheduleQueryRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioScheduleQueryResponse;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioShareRecordRequest;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.PortfolioHistoryEntity;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.entity.PortfolioShareRecordEntity;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.entity.TeamScheduleQueryRecordEntity;
import com.jxc.wefolio.entity.VisitRecordEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.PortfolioHistoryEntityMapper;
import com.jxc.wefolio.mapper.PortfolioReferenceEntityMapper;
import com.jxc.wefolio.mapper.PortfolioShareRecordEntityMapper;
import com.jxc.wefolio.mapper.TeamEntityMapper;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.mapper.TeamScheduleQueryRecordEntityMapper;
import com.jxc.wefolio.mapper.VisitRecordEntityMapper;
import com.jxc.wefolio.message.TeamPortfolioMessage;
import com.jxc.wefolio.service.ContentLimitService;
import com.jxc.wefolio.service.PointService;
import com.jxc.wefolio.service.PortfolioPublishTransactionService;
import com.jxc.wefolio.service.teamportfolio.component.schedulequery.TeamScheduleQueryComponentService;
import com.jxc.wefolio.service.teamportfolio.component.contactform.TeamContactFormComponentService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 团队作品集维护服务测试。 */
class MineTeamPortfolioServiceTest {

    private static final long USER_ID = 7L;
    private static final long TEAM_ID = 11L;
    private static final long PORTFOLIO_ID = 13L;

    @BeforeAll
    static void initializeTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), TeamMemberEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), TeamEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), PortfolioEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), PortfolioReferenceEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), PortfolioHistoryEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), VisitRecordEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), TeamScheduleQueryRecordEntity.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listUsesJoinedTeamsAndOneStrictlyScopedPortfolioQuery() {
        TestContext context = context(true);
        TeamMemberEntity owner = membership(TEAM_ID, TeamRoleDict.OWNER.getCode());
        TeamMemberEntity member = membership(12L, TeamRoleDict.MEMBER.getCode());
        TeamMemberEntity disabledOwner = membership(15L, TeamRoleDict.MEMBER.getCode());
        when(context.memberMapper.selectList(any())).thenReturn(List.of(owner, member, disabledOwner));
        when(context.teamMapper.selectBatchIds(any())).thenReturn(List.of(
                team(TEAM_ID, "甲团队"), team(12L, "乙团队"), team(15L, "丙团队")));
        PortfolioEntity publishedPortfolio = portfolio(TEAM_ID);
        publishedPortfolio.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        publishedPortfolio.setPublishedRevision(1);
        publishedPortfolio.setPublishedConfigJson(JSON.toJSONString(config()));
        PortfolioEntity memberPortfolio = portfolio(12L);
        memberPortfolio.setId(14L);
        PortfolioEntity disabledPortfolio = portfolio(15L);
        disabledPortfolio.setId(16L);
        disabledPortfolio.setStatus(PortfolioStatusDict.DISABLED.getCode());
        disabledPortfolio.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        disabledPortfolio.setPublishedConfigJson(JSON.toJSONString(config()));
        when(context.portfolioMapper.selectList(any()))
                .thenReturn(List.of(publishedPortfolio, memberPortfolio, disabledPortfolio));

        var result = context.service.listPortfolios(USER_ID);

        assertThat(result).hasSize(3);
        assertThat(result).filteredOn(item -> item.getTeamId().equals(TEAM_ID)).singleElement().satisfies(item -> {
            assertThat(item.getTeamId()).isEqualTo(TEAM_ID);
            assertThat(item.getTeamName()).isEqualTo("甲团队");
            assertThat(item.getCurrentRole()).isEqualTo(TeamRoleDict.OWNER.getCode());
            assertThat(item.isCanMaintain()).isTrue();
            assertThat(item.isCanShare()).isTrue();
        });
        assertThat(result).filteredOn(item -> item.getTeamId().equals(12L)).singleElement().satisfies(item -> {
            assertThat(item.getCurrentRole()).isEqualTo(TeamRoleDict.MEMBER.getCode());
            assertThat(item.isCanMaintain()).isFalse();
            assertThat(item.isCanShare()).isFalse();
        });
        assertThat(result).filteredOn(item -> item.getTeamId().equals(15L)).singleElement().satisfies(item -> {
            assertThat(item.isCanMaintain()).isFalse();
            assertThat(item.isCanShare()).isFalse();
        });
        ArgumentCaptor<Wrapper<PortfolioEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(context.portfolioMapper, times(1)).selectList(captor.capture());
        LambdaQueryWrapper<PortfolioEntity> wrapper = (LambdaQueryWrapper<PortfolioEntity>) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains(
                "owner_type", "owner_id", "template_type", "schema_version", "status", "deleted");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(
                PortfolioOwnerTypeDict.TEAM.getCode(), PortfolioTemplateTypeDict.STANDARD.getCode(),
                TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1,
                PortfolioStatusDict.ACTIVE.getCode(), 0L, TEAM_ID, 12L, 15L);
    }

    @Test
    void maintainableTeamsReturnAccurateZeroOneAndManyResults() {
        TestContext context = context(true);
        when(context.memberMapper.selectList(any())).thenReturn(List.of());
        assertThat(context.service.listMaintainableTeams(USER_ID)).isEmpty();

        when(context.memberMapper.selectList(any())).thenReturn(List.of(membership(TEAM_ID, TeamRoleDict.MANAGER.getCode())));
        when(context.teamMapper.selectBatchIds(any())).thenReturn(List.of(team(TEAM_ID, "唯一团队")));
        assertThat(context.service.listMaintainableTeams(USER_ID)).singleElement()
                .satisfies(item -> assertThat(item.getTeamId()).isEqualTo(TEAM_ID));

        when(context.memberMapper.selectList(any())).thenReturn(List.of(
                membership(TEAM_ID, TeamRoleDict.OWNER.getCode()),
                membership(12L, TeamRoleDict.MANAGER.getCode())));
        when(context.teamMapper.selectBatchIds(any())).thenReturn(List.of(team(TEAM_ID, "甲"), team(12L, "乙")));
        assertThat(context.service.listMaintainableTeams(USER_ID)).hasSize(2);
    }

    @Test
    void createBuildsRevisionOneDraftReferencesHistoryAndValidatesCoverWithoutPoints() {
        TestContext context = context(true);
        TeamPortfolioConfigDto configured = configWithQr();
        TeamPortfolioCreateRequest request = new TeamPortfolioCreateRequest();
        request.setConfig(configured);
        when(context.access.requireTeamRole(eq(TEAM_ID), eq(USER_ID), any())).thenReturn(access(null, TeamRoleDict.MANAGER.getCode()));
        when(context.validator.normalizeAndValidate(any(), eq(TEAM_ID), eq(PORTFOLIO_ID), eq(1)))
                .thenReturn(configured);
        when(context.portfolioMapper.updateById(any(PortfolioEntity.class))).thenReturn(1);
        when(context.historyMapper.insert(any(PortfolioHistoryEntity.class))).thenReturn(1);
        doAnswer(invocation -> {
            ((PortfolioEntity) invocation.getArgument(0)).setId(PORTFOLIO_ID);
            return 1;
        }).when(context.portfolioMapper).insert(any(PortfolioEntity.class));

        var result = context.service.createStandard(TEAM_ID, request, USER_ID);

        ArgumentCaptor<PortfolioEntity> captor = ArgumentCaptor.forClass(PortfolioEntity.class);
        verify(context.portfolioMapper).insert(captor.capture());
        PortfolioEntity created = captor.getValue();
        assertThat(created.getOwnerType()).isEqualTo(PortfolioOwnerTypeDict.TEAM.getCode());
        assertThat(created.getOwnerId()).isEqualTo(TEAM_ID);
        assertThat(created.getTemplateType()).isEqualTo(PortfolioTemplateTypeDict.STANDARD.getCode());
        assertThat(created.getSchemaVersion()).isEqualTo(TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1);
        assertThat(created.getShareCode()).startsWith("TPF");
        assertThat(created.getDraftRevision()).isEqualTo(1);
        assertThat(created.getCurrentRevision()).isEqualTo(1);
        assertThat(result.getPortfolioId()).isEqualTo(PORTFOLIO_ID);
        verify(context.assetService).validateUploadedImageUrl(
                TEAM_ID, PORTFOLIO_ID, configured.getShare().getCoverUrl());
        verify(context.referenceService).rebuild(
                eq(PORTFOLIO_ID), eq(PortfolioConfigScopeDict.DRAFT.getCode()), eq(configured),
                eq(new TeamPortfolioComponentContext(TEAM_ID, PORTFOLIO_ID, 1)));
        ArgumentCaptor<PortfolioHistoryEntity> historyCaptor = ArgumentCaptor.forClass(PortfolioHistoryEntity.class);
        verify(context.historyMapper).insert(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getRevisionNo()).isEqualTo(1);
        JSONObject snapshot = JSON.parseObject(historyCaptor.getValue().getSnapshotJson());
        assertThat(snapshot.getString("actionType")).isEqualTo("CREATE");
        assertThat(snapshot.getJSONObject("config")).isEqualTo(JSON.parseObject(JSON.toJSONString(configured)));
        verify(context.contentLimitService).ensureTeamPortfolioCapacity(TEAM_ID);
        assertThat(MineTeamPortfolioService.class.getDeclaredFields())
                .anyMatch(field -> field.getType().equals(PointService.class));
    }

    @Test
    void createStopsBeforeWritesWhenTeamPortfolioCountReachesLimit() {
        TestContext context = context(true);
        when(context.access.requireTeamRole(eq(TEAM_ID), eq(USER_ID), any()))
                .thenReturn(access(null, TeamRoleDict.OWNER.getCode()));
        doThrow(new BusinessException("当前团队作品集数量已达上限（10个），请删除部分团队作品集后再新建"))
                .when(context.contentLimitService).ensureTeamPortfolioCapacity(TEAM_ID);

        assertThatThrownBy(() -> context.service.createStandard(
                TEAM_ID, new TeamPortfolioCreateRequest(), USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前团队作品集数量已达上限（10个），请删除部分团队作品集后再新建");

        verify(context.portfolioMapper, never()).insert(any(PortfolioEntity.class));
        verifyNoInteractions(context.validator, context.historyMapper, context.referenceService, context.assetService);
    }

    @Test
    void deniedExplicitTeamCreateWritesNothing() {
        TestContext context = context(true);
        when(context.access.requireTeamRole(eq(TEAM_ID), eq(USER_ID), any()))
                .thenThrow(new BusinessException(TeamPortfolioMessage.NO_ACCESS));

        assertThatThrownBy(() -> context.service.createStandard(TEAM_ID, new TeamPortfolioCreateRequest(), USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage(TeamPortfolioMessage.NO_ACCESS);

        verifyNoInteractions(context.validator, context.historyMapper, context.referenceService, context.assetService);
        verify(context.portfolioMapper, never()).insert(any(PortfolioEntity.class));
    }

    @Test
    void createStopsBeforeReferencesAndHistoryWhenUploadedCoverIsInvalid() {
        TestContext context = context(true);
        when(context.access.requireTeamRole(eq(TEAM_ID), eq(USER_ID), any()))
                .thenReturn(access(null, TeamRoleDict.OWNER.getCode()));
        when(context.validator.normalizeAndValidate(any(), eq(TEAM_ID), eq(PORTFOLIO_ID), eq(1)))
                .thenReturn(config());
        doAnswer(invocation -> {
            ((PortfolioEntity) invocation.getArgument(0)).setId(PORTFOLIO_ID);
            return 1;
        }).when(context.portfolioMapper).insert(any(PortfolioEntity.class));
        org.mockito.Mockito.doThrow(new BusinessException("团队作品集图片未完成上传或不可用"))
                .when(context.assetService).validateUploadedImageUrl(
                        TEAM_ID, PORTFOLIO_ID, config().getShare().getCoverUrl());

        assertThatThrownBy(() -> context.service.createStandard(
                TEAM_ID, new TeamPortfolioCreateRequest(), USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("团队作品集图片未完成上传或不可用");

        verify(context.portfolioMapper, never()).updateById(any(PortfolioEntity.class));
        verifyNoInteractions(context.referenceService);
        verify(context.historyMapper, never()).insert(any(PortfolioHistoryEntity.class));
    }

    @Test
    void createStopsBeforeUpdateReferencesAndHistoryWhenConfigValidationFails() {
        TestContext context = context(true);
        when(context.access.requireTeamRole(eq(TEAM_ID), eq(USER_ID), any()))
                .thenReturn(access(null, TeamRoleDict.OWNER.getCode()));
        doAnswer(invocation -> {
            ((PortfolioEntity) invocation.getArgument(0)).setId(PORTFOLIO_ID);
            return 1;
        }).when(context.portfolioMapper).insert(any(PortfolioEntity.class));
        when(context.validator.normalizeAndValidate(any(), eq(TEAM_ID), eq(PORTFOLIO_ID), eq(1)))
                .thenThrow(new BusinessException("配置失败"));

        assertThatThrownBy(() -> context.service.createStandard(
                TEAM_ID, new TeamPortfolioCreateRequest(), USER_ID))
                .isInstanceOf(BusinessException.class).hasMessage("配置失败");

        verify(context.portfolioMapper, never()).updateById(any(PortfolioEntity.class));
        verifyNoInteractions(context.assetService, context.referenceService);
        verify(context.historyMapper, never()).insert(any(PortfolioHistoryEntity.class));
    }

    @Test
    void createPropagatesReferenceAndHistoryFailuresInsideRollbackTransaction() {
        TestContext referenceFailure = successfulCreateContext();
        org.mockito.Mockito.doThrow(new BusinessException("引用失败"))
                .when(referenceFailure.referenceService).rebuild(anyLong(), any(), any(), any());
        assertThatThrownBy(() -> referenceFailure.service.createStandard(
                TEAM_ID, new TeamPortfolioCreateRequest(), USER_ID))
                .isInstanceOf(BusinessException.class).hasMessage("引用失败");
        verify(referenceFailure.historyMapper, never()).insert(any(PortfolioHistoryEntity.class));

        TestContext historyFailure = successfulCreateContext();
        when(historyFailure.historyMapper.insert(any(PortfolioHistoryEntity.class))).thenReturn(0);
        assertThatThrownBy(() -> historyFailure.service.createStandard(
                TEAM_ID, new TeamPortfolioCreateRequest(), USER_ID))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void memberCannotMutateAndFailureHasNoSideEffects() {
        TestContext context = context(true);
        when(context.access.requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID))
                .thenThrow(new BusinessException(TeamPortfolioMessage.NO_MAINTAIN_PERMISSION));

        assertThatThrownBy(() -> context.service.saveDraft(PORTFOLIO_ID, new TeamPortfolioDraftSaveRequest(), USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage(TeamPortfolioMessage.NO_MAINTAIN_PERMISSION);

        verifyNoInteractions(context.validator, context.historyMapper, context.referenceService, context.assetService);
        verify(context.portfolioMapper, never()).updateById(any(PortfolioEntity.class));
    }

    @Test
    void saveDraftIncrementsRevisionAndWritesDraftReferencesAndHistory() {
        TestContext context = maintainableContext();
        PortfolioEntity portfolio = portfolio(TEAM_ID);
        portfolio.setDraftRevision(2);
        portfolio.setCurrentRevision(4);
        portfolio.setDraftConfigJson(JSON.toJSONString(configWithTitle("旧草稿")));
        portfolio.setPublishedConfigJson(JSON.toJSONString(configWithTitle("当前发布")));
        TeamPortfolioConfigDto newDraft = configWithTitle("新草稿");
        when(context.access.requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID))
                .thenReturn(access(portfolio, TeamRoleDict.OWNER.getCode()));
        when(context.validator.normalizeAndValidate(any(), eq(TEAM_ID), eq(PORTFOLIO_ID), eq(3))).thenReturn(newDraft);
        when(context.portfolioMapper.updateById(any(PortfolioEntity.class))).thenReturn(1);
        when(context.historyMapper.insert(any(PortfolioHistoryEntity.class))).thenReturn(1);
        TeamPortfolioDraftSaveRequest request = new TeamPortfolioDraftSaveRequest();
        request.setConfig(newDraft);
        request.setClientRevision(2);
        request.setIdempotencyKey("draft-1");

        context.service.saveDraft(PORTFOLIO_ID, request, USER_ID);

        assertThat(portfolio.getDraftRevision()).isEqualTo(3);
        assertThat(portfolio.getCurrentRevision()).isEqualTo(5);
        verify(context.referenceService).rebuild(eq(PORTFOLIO_ID), eq(PortfolioConfigScopeDict.DRAFT.getCode()),
                any(), eq(new TeamPortfolioComponentContext(TEAM_ID, PORTFOLIO_ID, 3)));
        ArgumentCaptor<PortfolioHistoryEntity> history = ArgumentCaptor.forClass(PortfolioHistoryEntity.class);
        verify(context.historyMapper).insert(history.capture());
        assertThat(history.getValue().getRevisionNo()).isEqualTo(5);
        JSONObject snapshot = JSON.parseObject(history.getValue().getSnapshotJson());
        assertThat(snapshot.getString("actionType")).isEqualTo("DRAFT_SAVE");
        assertThat(snapshot.getString("idempotencyKey")).isEqualTo("draft-1");
        assertThat(snapshot.getString("requestFingerprint")).isNotBlank();
        assertThat(snapshot.getInteger("resultDraftRevision")).isEqualTo(3);
        assertThat(snapshot.getJSONObject("config")).isEqualTo(JSON.parseObject(JSON.toJSONString(newDraft)));
        verify(context.assetService).validateUploadedImageUrl(
                TEAM_ID, PORTFOLIO_ID, newDraft.getShare().getCoverUrl());
        assertCleanupStates(context, "旧草稿", "当前发布", "新草稿", "当前发布");
    }

    @Test
    void saveRequiresClientRevisionAndRejectsStaleRevisionBeforeWrites() {
        TestContext context = context(true);
        PortfolioEntity portfolio = portfolio(TEAM_ID);
        portfolio.setDraftRevision(2);
        when(context.access.requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID))
                .thenReturn(access(portfolio, TeamRoleDict.OWNER.getCode()));
        TeamPortfolioDraftSaveRequest missing = draftRequest(config(), null, "draft-missing");
        TeamPortfolioDraftSaveRequest stale = draftRequest(config(), 1, "draft-stale");

        assertThatThrownBy(() -> context.service.saveDraft(PORTFOLIO_ID, missing, USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("客户端草稿版本不能为空");
        assertThatThrownBy(() -> context.service.saveDraft(PORTFOLIO_ID, stale, USER_ID))
                .isInstanceOf(BusinessException.class);

        verify(context.portfolioMapper, never()).updateById(any(PortfolioEntity.class));
        verify(context.historyMapper, never()).insert(any(PortfolioHistoryEntity.class));
        verifyNoInteractions(context.referenceService, context.assetService);
    }

    @Test
    void invalidUploadedCoverStopsSaveBeforeEveryBusinessWrite() {
        TestContext context = context(true);
        PortfolioEntity portfolio = portfolio(TEAM_ID);
        portfolio.setDraftRevision(2);
        when(context.access.requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID))
                .thenReturn(access(portfolio, TeamRoleDict.OWNER.getCode()));
        when(context.validator.normalizeAndValidate(any(), eq(TEAM_ID), eq(PORTFOLIO_ID), eq(3)))
                .thenReturn(config());
        org.mockito.Mockito.doThrow(new BusinessException("团队作品集图片未完成上传或不可用"))
                .when(context.assetService).validateUploadedImageUrl(
                        TEAM_ID, PORTFOLIO_ID, config().getShare().getCoverUrl());

        assertThatThrownBy(() -> context.service.saveDraft(
                PORTFOLIO_ID, draftRequest(config(), 2, "draft-cover"), USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("团队作品集图片未完成上传或不可用");

        verify(context.portfolioMapper, never()).updateById(any(PortfolioEntity.class));
        verify(context.historyMapper, never()).insert(any(PortfolioHistoryEntity.class));
        verifyNoInteractions(context.referenceService);
    }

    @Test
    void saveRetryWithSamePersistentKeyAndPayloadReturnsWithoutSecondWrite() {
        TestContext context = context(true);
        PortfolioEntity portfolio = portfolio(TEAM_ID);
        portfolio.setDraftRevision(2);
        portfolio.setCurrentRevision(4);
        when(context.access.requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID))
                .thenReturn(access(portfolio, TeamRoleDict.OWNER.getCode()));
        when(context.validator.normalizeAndValidate(any(), eq(TEAM_ID), eq(PORTFOLIO_ID), anyInt()))
                .thenReturn(config());
        when(context.portfolioMapper.updateById(any(PortfolioEntity.class))).thenReturn(1);
        when(context.historyMapper.insert(any(PortfolioHistoryEntity.class))).thenReturn(1);
        when(context.historyMapper.selectList(any())).thenReturn(List.of());
        TeamPortfolioDraftSaveRequest request = draftRequest(config(), 2, "draft-retry");

        context.service.saveDraft(PORTFOLIO_ID, request, USER_ID);
        ArgumentCaptor<PortfolioHistoryEntity> historyCaptor = ArgumentCaptor.forClass(PortfolioHistoryEntity.class);
        verify(context.historyMapper).insert(historyCaptor.capture());
        PortfolioHistoryEntity persistedHistory = historyCaptor.getValue();
        JSONObject persistedSnapshot = JSON.parseObject(persistedHistory.getSnapshotJson());
        assertThat(persistedSnapshot.getString("requestFingerprint")).isNotBlank();
        assertThat(persistedSnapshot.getInteger("resultDraftRevision")).isEqualTo(3);
        TeamPortfolioConfigDto currentDifferent = configWithTitle("当前更新草稿");
        portfolio.setDraftConfigJson(JSON.toJSONString(currentDifferent));
        portfolio.setDraftRevision(9);
        clearInvocations(context.portfolioMapper, context.historyMapper, context.validator,
                context.assetService, context.referenceService);
        when(context.historyMapper.selectList(any())).thenReturn(List.of(persistedHistory));
        when(context.validator.normalizeAndValidate(any(), anyLong(), anyLong(), anyInt()))
                .thenThrow(new AssertionError("幂等命中不得重新校验配置"));
        doThrow(new AssertionError("幂等命中不得访问素材服务"))
                .when(context.assetService).validateUploadedImageUrl(anyLong(), anyLong(), any());

        var retry = context.service.saveDraft(PORTFOLIO_ID, request, USER_ID);

        assertThat(retry.getDraftRevision()).isEqualTo(3);
        assertThat(retry.getPortfolioId()).isEqualTo(PORTFOLIO_ID);
        assertThat(retry.getShareCode()).isEqualTo(portfolio.getShareCode());
        assertThat(retry.getConfig()).isEqualTo(config());
        assertThat(portfolio.getDraftRevision()).isEqualTo(9);
        verify(context.historyMapper).selectList(any());
        verify(context.historyMapper, never()).insert(any(PortfolioHistoryEntity.class));
        verifyNoInteractions(context.portfolioMapper, context.validator,
                context.assetService, context.referenceService);
    }

    @Test
    void publishRevalidatesDraftCopiesPublishedStateAndWritesPublishedScope() {
        TestContext context = maintainableContext();
        PortfolioEntity portfolio = portfolio(TEAM_ID);
        portfolio.setDraftRevision(3);
        portfolio.setCurrentRevision(5);
        TeamPortfolioConfigDto currentDraft = configWithTitle("当前草稿");
        portfolio.setDraftConfigJson(JSON.toJSONString(currentDraft));
        portfolio.setPublishedConfigJson(JSON.toJSONString(configWithTitle("旧发布")));
        when(context.access.requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID))
                .thenReturn(access(portfolio, TeamRoleDict.MANAGER.getCode()));
        when(context.validator.normalizeAndValidate(any(), eq(TEAM_ID), eq(PORTFOLIO_ID), eq(1))).thenReturn(currentDraft);
        when(context.portfolioMapper.updateById(any(PortfolioEntity.class))).thenReturn(1);
        when(context.historyMapper.insert(any(PortfolioHistoryEntity.class))).thenReturn(1);
        TeamPortfolioPublishRequest request = new TeamPortfolioPublishRequest();
        request.setDraftRevision(3);
        request.setIdempotencyKey("publish-1");

        context.service.publish(PORTFOLIO_ID, request, USER_ID);

        verify(context.pointService).assertCanConsume(
                USER_ID,
                PointSceneCodeDict.MAINTAIN_STANDARD_PORTFOLIO.getCode(),
                "PORTFOLIO",
                String.valueOf(PORTFOLIO_ID),
                1,
                "publish-1"
        );
        verify(context.publishTransactionService).execute(any());
        verify(context.pointService).consume(
                USER_ID,
                PointSceneCodeDict.MAINTAIN_STANDARD_PORTFOLIO.getCode(),
                "PORTFOLIO",
                String.valueOf(PORTFOLIO_ID),
                1,
                "publish-1",
                "发布标准团队作品集"
        );
        assertThat(portfolio.getPublishedRevision()).isEqualTo(1);
        assertThat(portfolio.getPublicationStatus()).isEqualTo(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        assertThat(portfolio.getPublishedConfigJson()).isEqualTo(JSON.toJSONString(currentDraft));
        verify(context.referenceService).rebuild(eq(PORTFOLIO_ID), eq(PortfolioConfigScopeDict.PUBLISHED.getCode()),
                any(), eq(new TeamPortfolioComponentContext(TEAM_ID, PORTFOLIO_ID, 1)));
        ArgumentCaptor<PortfolioHistoryEntity> history = ArgumentCaptor.forClass(PortfolioHistoryEntity.class);
        verify(context.historyMapper).insert(history.capture());
        JSONObject snapshot = JSON.parseObject(history.getValue().getSnapshotJson());
        assertThat(snapshot.getString("actionType")).isEqualTo("PUBLISH");
        assertThat(snapshot.getString("idempotencyKey")).isEqualTo("publish-1");
        assertThat(snapshot.getString("requestFingerprint")).isNotBlank();
        assertThat(snapshot.getInteger("resultPublishedRevision")).isEqualTo(1);
        assertThat(snapshot.getJSONObject("config")).isEqualTo(JSON.parseObject(JSON.toJSONString(currentDraft)));
        verify(context.assetService).validateUploadedImageUrl(
                TEAM_ID, PORTFOLIO_ID, currentDraft.getShare().getCoverUrl());
        assertCleanupStates(context, "当前草稿", "旧发布", "当前草稿", "当前草稿");
    }

    @Test
    void ownerPublishShouldConsumeCurrentPublisherPoints() {
        TestContext context = maintainableContext();
        PortfolioEntity portfolio = portfolio(TEAM_ID);
        portfolio.setDraftRevision(2);
        when(context.access.requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID))
                .thenReturn(access(portfolio, TeamRoleDict.OWNER.getCode()));
        when(context.validator.normalizeAndValidate(any(), eq(TEAM_ID), eq(PORTFOLIO_ID), eq(1)))
                .thenReturn(config());
        when(context.portfolioMapper.updateById(any(PortfolioEntity.class))).thenReturn(1);
        when(context.historyMapper.insert(any(PortfolioHistoryEntity.class))).thenReturn(1);

        context.service.publish(PORTFOLIO_ID, publishRequest(2, "owner-publish"), USER_ID);

        verify(context.pointService).assertCanConsume(
                USER_ID,
                PointSceneCodeDict.MAINTAIN_STANDARD_PORTFOLIO.getCode(),
                "PORTFOLIO",
                String.valueOf(PORTFOLIO_ID),
                1,
                "owner-publish"
        );
        verify(context.pointService).consume(
                USER_ID,
                PointSceneCodeDict.MAINTAIN_STANDARD_PORTFOLIO.getCode(),
                "PORTFOLIO",
                String.valueOf(PORTFOLIO_ID),
                1,
                "owner-publish",
                "发布标准团队作品集"
        );
    }

    @Test
    void publishShouldStopBeforeTransactionAndWritesWhenPointsAreInsufficient() {
        TestContext context = maintainableContext();
        PortfolioEntity portfolio = portfolio(TEAM_ID);
        portfolio.setDraftRevision(2);
        when(context.access.requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID))
                .thenReturn(access(portfolio, TeamRoleDict.MANAGER.getCode()));
        doThrow(new BusinessException("积分余额不足，请充值后再试"))
                .when(context.pointService).assertCanConsume(
                        USER_ID,
                        PointSceneCodeDict.MAINTAIN_STANDARD_PORTFOLIO.getCode(),
                        "PORTFOLIO",
                        String.valueOf(PORTFOLIO_ID),
                        1,
                        "team-publish-insufficient"
                );

        assertThatThrownBy(() -> context.service.publish(
                PORTFOLIO_ID,
                publishRequest(2, "team-publish-insufficient"),
                USER_ID
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessage("积分余额不足，请充值后再试");

        verifyNoInteractions(context.publishTransactionService);
        verify(context.portfolioMapper, never()).updateById(any(PortfolioEntity.class));
        verify(context.referenceService, never()).rebuild(anyLong(), any(), any(), any());
        verify(context.historyMapper, never()).insert(any(PortfolioHistoryEntity.class));
        verify(context.pointService, never()).consume(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void publishRetryWithSamePersistentKeyAndPayloadSkipsRevisionReferencesHistoryAndCleanup() {
        TestContext context = context(true);
        PortfolioEntity portfolio = portfolio(TEAM_ID);
        portfolio.setDraftRevision(3);
        portfolio.setCurrentRevision(5);
        portfolio.setDraftConfigJson(JSON.toJSONString(config()));
        when(context.access.requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID))
                .thenReturn(access(portfolio, TeamRoleDict.MANAGER.getCode()));
        when(context.validator.normalizeAndValidate(any(), eq(TEAM_ID), eq(PORTFOLIO_ID), anyInt()))
                .thenReturn(config());
        when(context.portfolioMapper.updateById(any(PortfolioEntity.class))).thenReturn(1);
        when(context.historyMapper.insert(any(PortfolioHistoryEntity.class))).thenReturn(1);
        when(context.historyMapper.selectList(any())).thenReturn(List.of());
        TeamPortfolioPublishRequest request = publishRequest(3, "publish-retry");

        context.service.publish(PORTFOLIO_ID, request, USER_ID);
        ArgumentCaptor<PortfolioHistoryEntity> historyCaptor = ArgumentCaptor.forClass(PortfolioHistoryEntity.class);
        verify(context.historyMapper).insert(historyCaptor.capture());
        PortfolioHistoryEntity persistedHistory = historyCaptor.getValue();
        JSONObject persistedSnapshot = JSON.parseObject(persistedHistory.getSnapshotJson());
        assertThat(persistedSnapshot.getString("requestFingerprint")).isNotBlank();
        assertThat(persistedSnapshot.getInteger("resultPublishedRevision")).isEqualTo(1);
        TeamPortfolioConfigDto changedDraft = configWithTitle("发布后的新草稿");
        portfolio.setDraftConfigJson(JSON.toJSONString(changedDraft));
        portfolio.setDraftRevision(4);
        clearInvocations(context.portfolioMapper, context.historyMapper, context.validator,
                context.assetService, context.referenceService, context.pointService,
                context.publishTransactionService);
        when(context.historyMapper.selectList(any())).thenReturn(List.of(persistedHistory));
        when(context.validator.normalizeAndValidate(any(), anyLong(), anyLong(), anyInt()))
                .thenThrow(new AssertionError("幂等命中不得读取当前草稿重验"));
        doThrow(new AssertionError("幂等命中不得访问素材服务"))
                .when(context.assetService).validateUploadedImageUrl(anyLong(), anyLong(), any());

        var retry = context.service.publish(PORTFOLIO_ID, request, USER_ID);

        assertThat(retry.getPublishedRevision()).isEqualTo(1);
        assertThat(retry.getDraftRevision()).isEqualTo(4);
        assertThat(retry.getConfig()).isEqualTo(config());
        assertThat(retry.getPortfolioId()).isEqualTo(PORTFOLIO_ID);
        verify(context.historyMapper).selectList(any());
        verify(context.historyMapper, never()).insert(any(PortfolioHistoryEntity.class));
        verifyNoInteractions(context.portfolioMapper, context.validator,
                context.assetService, context.referenceService, context.pointService,
                context.publishTransactionService);
    }

    @Test
    void reusedIdempotencyKeyWithDifferentContentIsRejectedBeforeWrites() {
        TestContext save = context(true);
        PortfolioEntity savePortfolio = portfolio(TEAM_ID);
        savePortfolio.setDraftRevision(2);
        savePortfolio.setCurrentRevision(4);
        when(save.access.requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID))
                .thenReturn(access(savePortfolio, TeamRoleDict.OWNER.getCode()));
        when(save.validator.normalizeAndValidate(any(), anyLong(), anyLong(), anyInt())).thenReturn(config());
        when(save.portfolioMapper.updateById(any(PortfolioEntity.class))).thenReturn(1);
        when(save.historyMapper.insert(any(PortfolioHistoryEntity.class))).thenReturn(1);
        when(save.historyMapper.selectList(any())).thenReturn(List.of());
        save.service.saveDraft(PORTFOLIO_ID, draftRequest(config(), 2, "same-key"), USER_ID);
        ArgumentCaptor<PortfolioHistoryEntity> saveHistory = ArgumentCaptor.forClass(PortfolioHistoryEntity.class);
        verify(save.historyMapper).insert(saveHistory.capture());
        clearInvocations(save.portfolioMapper, save.historyMapper, save.validator,
                save.assetService, save.referenceService);
        when(save.historyMapper.selectList(any())).thenReturn(List.of(saveHistory.getValue()));
        when(save.validator.normalizeAndValidate(any(), anyLong(), anyLong(), anyInt()))
                .thenThrow(new AssertionError("冲突判断不得重新校验配置"));
        doThrow(new AssertionError("冲突判断不得访问素材服务"))
                .when(save.assetService).validateUploadedImageUrl(anyLong(), anyLong(), any());
        assertThatThrownBy(() -> save.service.saveDraft(
                PORTFOLIO_ID, draftRequest(configWithTitle("不同内容"), 2, "same-key"), USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("团队作品集幂等键已用于其他内容");
        verify(save.historyMapper).selectList(any());
        verify(save.historyMapper, never()).insert(any(PortfolioHistoryEntity.class));
        verifyNoInteractions(save.portfolioMapper, save.validator, save.assetService, save.referenceService);

        TestContext publish = context(true);
        PortfolioEntity publishPortfolio = portfolio(TEAM_ID);
        publishPortfolio.setDraftRevision(3);
        publishPortfolio.setDraftConfigJson(JSON.toJSONString(config()));
        when(publish.access.requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID))
                .thenReturn(access(publishPortfolio, TeamRoleDict.MANAGER.getCode()));
        when(publish.validator.normalizeAndValidate(any(), anyLong(), anyLong(), anyInt())).thenReturn(config());
        when(publish.portfolioMapper.updateById(any(PortfolioEntity.class))).thenReturn(1);
        when(publish.historyMapper.insert(any(PortfolioHistoryEntity.class))).thenReturn(1);
        when(publish.historyMapper.selectList(any())).thenReturn(List.of());
        publish.service.publish(PORTFOLIO_ID, publishRequest(3, "same-key"), USER_ID);
        ArgumentCaptor<PortfolioHistoryEntity> publishHistory = ArgumentCaptor.forClass(PortfolioHistoryEntity.class);
        verify(publish.historyMapper).insert(publishHistory.capture());
        clearInvocations(publish.portfolioMapper, publish.historyMapper, publish.validator,
                publish.assetService, publish.referenceService);
        when(publish.historyMapper.selectList(any())).thenReturn(List.of(publishHistory.getValue()));
        when(publish.validator.normalizeAndValidate(any(), anyLong(), anyLong(), anyInt()))
                .thenThrow(new AssertionError("冲突判断不得读取当前草稿"));
        doThrow(new AssertionError("冲突判断不得访问素材服务"))
                .when(publish.assetService).validateUploadedImageUrl(anyLong(), anyLong(), any());
        assertThatThrownBy(() -> publish.service.publish(
                PORTFOLIO_ID, publishRequest(4, "same-key"), USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("团队作品集幂等键已用于其他内容");
        verify(publish.historyMapper).selectList(any());
        verify(publish.historyMapper, never()).insert(any(PortfolioHistoryEntity.class));
        verifyNoInteractions(publish.portfolioMapper, publish.validator,
                publish.assetService, publish.referenceService);
    }

    @Test
    void legacyHistoryWithoutRequestFingerprintIsNeverReused() {
        TestContext context = context(true);
        PortfolioEntity portfolio = portfolio(TEAM_ID);
        portfolio.setDraftRevision(2);
        when(context.access.requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID))
                .thenReturn(access(portfolio, TeamRoleDict.OWNER.getCode()));
        when(context.historyMapper.selectList(any()))
                .thenReturn(List.of(history("DRAFT_SAVE", "legacy-key", config())));

        assertThatThrownBy(() -> context.service.saveDraft(
                PORTFOLIO_ID, draftRequest(config(), 2, "legacy-key"), USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("团队作品集幂等键已用于其他内容");

        verifyNoInteractions(context.validator, context.assetService,
                context.referenceService, context.portfolioMapper);
        verify(context.historyMapper, never()).insert(any(PortfolioHistoryEntity.class));
    }

    @Test
    void memberCanSharePublishedPortfolioButDraftPortfolioIsRejected() {
        TestContext context = context(true);
        PortfolioEntity published = portfolio(TEAM_ID);
        published.setPublishedRevision(2);
        published.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        published.setPublishedConfigJson(JSON.toJSONString(config()));
        when(context.access.requireVisiblePortfolio(PORTFOLIO_ID, USER_ID))
                .thenReturn(access(published, TeamRoleDict.MEMBER.getCode()));
        TeamPortfolioShareRecordRequest request = new TeamPortfolioShareRecordRequest();
        request.setShareChannel("WECHAT_CARD");
        request.setShareScene("LIST");

        context.service.createShareRecord(PORTFOLIO_ID, request, USER_ID);

        ArgumentCaptor<PortfolioShareRecordEntity> captor = ArgumentCaptor.forClass(PortfolioShareRecordEntity.class);
        verify(context.shareMapper).insert(captor.capture());
        assertThat(captor.getValue().getOwnerType()).isEqualTo(PortfolioOwnerTypeDict.TEAM.getCode());
        assertThat(captor.getValue().getOwnerId()).isEqualTo(TEAM_ID);
        assertThat(captor.getValue().getSharedByUserId()).isEqualTo(USER_ID);

        PortfolioEntity draft = portfolio(TEAM_ID);
        when(context.access.requireVisiblePortfolio(PORTFOLIO_ID, USER_ID))
                .thenReturn(access(draft, TeamRoleDict.MEMBER.getCode()));
        assertThatThrownBy(() -> context.service.createShareRecord(PORTFOLIO_ID, request, USER_ID))
                .isInstanceOf(BusinessException.class);
        verify(context.shareMapper, times(1)).insert(any(PortfolioShareRecordEntity.class));
    }

    @Test
    void createShareRecordRejectsUnsupportedChannelBeforeInsert() {
        TestContext context = context(true);
        PortfolioEntity published = portfolio(TEAM_ID);
        published.setPublishedRevision(2);
        published.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        published.setPublishedConfigJson(JSON.toJSONString(config()));
        when(context.access.requireVisiblePortfolio(PORTFOLIO_ID, USER_ID))
                .thenReturn(access(published, TeamRoleDict.MEMBER.getCode()));
        TeamPortfolioShareRecordRequest request = new TeamPortfolioShareRecordRequest();
        request.setShareChannel("UNKNOWN");
        request.setShareScene("TEAM_PORTFOLIO_LIST");

        assertThatThrownBy(() -> context.service.createShareRecord(PORTFOLIO_ID, request, USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("分享渠道不支持");

        verify(context.shareMapper, never()).insert(any(PortfolioShareRecordEntity.class));
    }

    @Test
    void createShareRecordNormalizesLegacyWechatMiniappChannel() {
        TestContext context = context(true);
        PortfolioEntity published = portfolio(TEAM_ID);
        published.setPublishedRevision(2);
        published.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        published.setPublishedConfigJson(JSON.toJSONString(config()));
        when(context.access.requireVisiblePortfolio(PORTFOLIO_ID, USER_ID))
                .thenReturn(access(published, TeamRoleDict.MEMBER.getCode()));
        TeamPortfolioShareRecordRequest request = new TeamPortfolioShareRecordRequest();
        request.setShareChannel("WECHAT_MINIAPP");
        request.setShareScene("TEAM_PORTFOLIO_LIST");

        context.service.createShareRecord(PORTFOLIO_ID, request, USER_ID);

        ArgumentCaptor<PortfolioShareRecordEntity> captor =
                ArgumentCaptor.forClass(PortfolioShareRecordEntity.class);
        verify(context.shareMapper).insert(captor.capture());
        assertThat(captor.getValue().getShareChannel()).isEqualTo("WECHAT_CARD");
    }

    @Test
    void deleteScopesReferencesAndDelegatesConservativeAssetCleanupOnlyAfterSuccessfulUpdate() {
        TestContext context = maintainableContext();
        PortfolioEntity portfolio = portfolio(TEAM_ID);
        when(context.access.requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID))
                .thenReturn(access(portfolio, TeamRoleDict.OWNER.getCode()));
        when(context.portfolioMapper.update(any(), any())).thenReturn(1);

        context.service.deletePortfolio(PORTFOLIO_ID, USER_ID);

        verify(context.referenceMapper).delete(any());
        verify(context.assetService).deletePortfolioAssetsAfterCommit(TEAM_ID, PORTFOLIO_ID,
                portfolio.getDraftConfigJson(), portfolio.getPublishedConfigJson());
    }

    @Test
    void failedDeleteUpdateNeverStartsCosCleanup() {
        TestContext context = context(true);
        PortfolioEntity portfolio = portfolio(TEAM_ID);
        when(context.access.requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID))
                .thenReturn(access(portfolio, TeamRoleDict.OWNER.getCode()));
        when(context.portfolioMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> context.service.deletePortfolio(PORTFOLIO_ID, USER_ID))
                .isInstanceOf(BusinessException.class);

        verify(context.assetService, never()).deletePortfolioAssetsAfterCommit(anyLong(), anyLong(), any(), any());
    }

    @Test
    void schedulePreviewDelegatesToScheduleQueryComponentService() {
        TestContext context = context(true);
        PortfolioEntity portfolio = portfolio(TEAM_ID);
        portfolio.setDraftRevision(4);
        TeamPortfolioConfigDto scheduleConfig = config();
        TeamPortfolioConfigDto.ComponentEnvelope component = new TeamPortfolioConfigDto.ComponentEnvelope();
        component.setComponentKey("schedule-1");
        component.setComponentType("SCHEDULE_QUERY");
        component.setEnabled(true);
        component.setConfig(new JSONObject());
        scheduleConfig.setComponents(List.of(component));
        portfolio.setDraftConfigJson(JSON.toJSONString(scheduleConfig));
        when(context.access.requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID))
                .thenReturn(access(portfolio, TeamRoleDict.OWNER.getCode()));
        TeamPortfolioScheduleQueryResponse expected = mock(TeamPortfolioScheduleQueryResponse.class);
        JSONObject options = JSON.parseObject("{\"queryRange\":{\"type\":\"UNLIMITED\"}}");
        when(context.scheduleService.previewOptions(any(), any(TeamPortfolioConfigDto.class), eq("schedule-1")))
                .thenReturn(options);
        when(context.scheduleService.queryPreview(
                any(), any(TeamPortfolioConfigDto.class), any(TeamPortfolioScheduleQueryRequest.class)))
                .thenReturn(expected);
        TeamPortfolioScheduleQueryRequest request = new TeamPortfolioScheduleQueryRequest();
        request.setComponentKey("schedule-1");

        JSONObject actualOptions = context.service.scheduleOptions(
                PORTFOLIO_ID, "schedule-1", "draft", USER_ID);
        TeamPortfolioScheduleQueryResponse actual = context.service.scheduleQueryPreview(
                PORTFOLIO_ID, request, "draft", USER_ID);

        assertThat(actualOptions).isSameAs(options);
        assertThat(actual).isSameAs(expected);
        verify(context.scheduleService).previewOptions(
                eq(new TeamPortfolioComponentContext(TEAM_ID, PORTFOLIO_ID, 4)),
                any(TeamPortfolioConfigDto.class), eq("schedule-1"));
        verify(context.scheduleService).queryPreview(
                eq(new TeamPortfolioComponentContext(TEAM_ID, PORTFOLIO_ID, 4)),
                any(TeamPortfolioConfigDto.class), eq(request));
    }

    @Test
    void topLevelServiceContainsNoTeamProfileDefaultAssemblyOrScheduleLookupRules() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/jxc/wefolio/service/teamportfolio/MineTeamPortfolioService.java"));

        assertThat(source)
                .contains("TeamProfileComponentConfig.defaultEnvelope()")
                .doesNotContain("DEFAULT_TEAM_PROFILE_COMPONENT_KEY", "requireScheduleComponent(",
                        "SCHEDULE_COMPONENT_REQUIRED_MESSAGE", "component.setComponentType(",
                        "component.setSortOrder(1000)");
    }

    @Test
    void mutationMethodsRollbackForEveryException() throws NoSuchMethodException {
        assertRollbackFor("createStandard", long.class, TeamPortfolioCreateRequest.class, long.class);
        assertRollbackFor("saveDraft", long.class, TeamPortfolioDraftSaveRequest.class, long.class);
        assertThat(MineTeamPortfolioService.class
                .getDeclaredMethod("publish", long.class, TeamPortfolioPublishRequest.class, long.class)
                .getAnnotation(Transactional.class)).isNull();
        assertRollbackFor("deletePortfolio", long.class, long.class);
        assertRollbackFor("updateContactLeadFollowStatus", long.class, long.class,
                String.class, String.class, long.class);
    }

    /**
     * 团队访问记录必须分页读取 pageSize+1，响应返回稳定分页元数据。
     */
    @Test
    @SuppressWarnings("unchecked")
    void visitRecordsUseBoundedPageSizePlusOneQuery() {
        TestContext context = context(true);
        when(context.visitRecordMapper.selectList(any())).thenReturn(List.of(
                teamVisit(1L), teamVisit(2L), teamVisit(3L)));

        MineTeamPortfolioService.TeamVisitRecordsResponse response =
                context.service.getVisitRecords(TEAM_ID, 2, 2, USER_ID);

        org.mockito.ArgumentCaptor<Wrapper<VisitRecordEntity>> captor =
                org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(context.visitRecordMapper).selectList(captor.capture());
        LambdaQueryWrapper<VisitRecordEntity> wrapper =
                (LambdaQueryWrapper<VisitRecordEntity>) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains(
                "owner_type", "owner_id", "portfolio_type", "LIMIT 2,3");
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.pageSize()).isEqualTo(2);
        assertThat(response.hasMore()).isTrue();
        assertThat(response.items()).hasSize(2);
        verify(context.access).requireTeamRole(eq(TEAM_ID), eq(USER_ID), eq(java.util.Set.of(
                TeamRoleDict.OWNER.getCode(), TeamRoleDict.MANAGER.getCode(), TeamRoleDict.MEMBER.getCode())));
    }

    /**
     * Task 8 三个维护依赖必须是 final 构造注入字段。
     */
    @Test
    void task8MaintenanceDependenciesAreFinalConstructorDependencies() {
        assertThat(MineTeamPortfolioService.class.getDeclaredFields())
                .filteredOn(field -> List.of(
                        "visitRecordEntityMapper",
                        "teamScheduleQueryRecordEntityMapper",
                        "teamContactFormComponentService").contains(field.getName()))
                .hasSize(3)
                .allMatch(field -> java.lang.reflect.Modifier.isFinal(field.getModifiers()))
                .noneMatch(field -> field.getAnnotation(
                        org.springframework.beans.factory.annotation.Autowired.class) != null);
    }

    /** 草稿维护预览必须补齐上下文且保留渲染器结果。 */
    @Test
    void draftPreviewFillsMaintenanceRenderContextWithoutChangingRenderedContent() {
        TestContext context = context(true);
        PortfolioEntity portfolio = portfolio(TEAM_ID);
        portfolio.setDraftRevision(3);
        TeamEntity team = team(TEAM_ID, "映期团队");
        TeamPortfolioRenderDto render = renderData();
        when(context.access.requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID))
                .thenReturn(new TeamPortfolioAccessService.TeamPortfolioAccess(
                        portfolio, team, membership(TEAM_ID, TeamRoleDict.OWNER.getCode()), true, true));
        when(context.renderService.render(
                portfolio.getDraftConfigJson(),
                new TeamPortfolioComponentContext(TEAM_ID, PORTFOLIO_ID, 3)))
                .thenReturn(render);

        var response = context.service.preview(PORTFOLIO_ID, USER_ID);

        assertMaintenancePreviewRender(render, response.getRenderData(), portfolio, team);
    }

    /** 已发布维护预览必须补齐上下文且保留渲染器结果。 */
    @Test
    void publishedPreviewFillsMaintenanceRenderContextWithoutChangingRenderedContent() {
        TestContext context = context(true);
        PortfolioEntity portfolio = portfolio(TEAM_ID);
        portfolio.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        portfolio.setPublishedRevision(5);
        portfolio.setPublishedConfigJson(JSON.toJSONString(config()));
        TeamEntity team = team(TEAM_ID, "映期团队");
        TeamPortfolioRenderDto render = renderData();
        when(context.access.requireVisiblePortfolio(PORTFOLIO_ID, USER_ID))
                .thenReturn(new TeamPortfolioAccessService.TeamPortfolioAccess(
                        portfolio, team, membership(TEAM_ID, TeamRoleDict.MEMBER.getCode()), false, true));
        when(context.renderService.render(
                portfolio.getPublishedConfigJson(),
                new TeamPortfolioComponentContext(TEAM_ID, PORTFOLIO_ID, 5)))
                .thenReturn(render);

        var response = context.service.previewPublished(PORTFOLIO_ID, USER_ID);

        assertMaintenancePreviewRender(render, response.getRenderData(), portfolio, team);
    }

    @Test
    void disabledFeatureStopsListAndLibraryBeforeAllDependencies() {
        TestContext context = context(false);
        when(context.access.requireTeamRole(eq(TEAM_ID), eq(USER_ID), any()))
                .thenThrow(new BusinessException(TeamPortfolioMessage.FEATURE_DISABLED));

        assertThatThrownBy(() -> context.service.listPortfolios(USER_ID))
                .isInstanceOf(BusinessException.class).hasMessage(TeamPortfolioMessage.FEATURE_DISABLED);
        assertThatThrownBy(() -> context.service.listMaintainableTeams(USER_ID))
                .isInstanceOf(BusinessException.class).hasMessage(TeamPortfolioMessage.FEATURE_DISABLED);
        assertThatThrownBy(context.service::getComponentLibrary)
                .isInstanceOf(BusinessException.class).hasMessage(TeamPortfolioMessage.FEATURE_DISABLED);
        assertThatThrownBy(() -> context.service.createStandard(TEAM_ID, new TeamPortfolioCreateRequest(), USER_ID))
                .isInstanceOf(BusinessException.class).hasMessage(TeamPortfolioMessage.FEATURE_DISABLED);

        verifyNoInteractions(context.portfolioMapper, context.teamMapper, context.memberMapper);
    }

    private TestContext maintainableContext() {
        return context(true);
    }

    private static TestContext successfulCreateContext() {
        TestContext context = context(true);
        when(context.access.requireTeamRole(eq(TEAM_ID), eq(USER_ID), any()))
                .thenReturn(access(null, TeamRoleDict.OWNER.getCode()));
        when(context.validator.normalizeAndValidate(any(), eq(TEAM_ID), eq(PORTFOLIO_ID), eq(1)))
                .thenReturn(config());
        doAnswer(invocation -> {
            ((PortfolioEntity) invocation.getArgument(0)).setId(PORTFOLIO_ID);
            return 1;
        }).when(context.portfolioMapper).insert(any(PortfolioEntity.class));
        when(context.portfolioMapper.updateById(any(PortfolioEntity.class))).thenReturn(1);
        when(context.historyMapper.insert(any(PortfolioHistoryEntity.class))).thenReturn(1);
        return context;
    }

    private static TestContext context(boolean enabled) {
        TeamPortfolioProperties properties = new TeamPortfolioProperties();
        properties.setEnabled(enabled);
        PortfolioEntityMapper portfolioMapper = mock(PortfolioEntityMapper.class);
        PortfolioHistoryEntityMapper historyMapper = mock(PortfolioHistoryEntityMapper.class);
        PortfolioReferenceEntityMapper referenceMapper = mock(PortfolioReferenceEntityMapper.class);
        PortfolioShareRecordEntityMapper shareMapper = mock(PortfolioShareRecordEntityMapper.class);
        TeamEntityMapper teamMapper = mock(TeamEntityMapper.class);
        TeamMemberEntityMapper memberMapper = mock(TeamMemberEntityMapper.class);
        TeamPortfolioAccessService access = mock(TeamPortfolioAccessService.class);
        TeamPortfolioConfigValidator validator = mock(TeamPortfolioConfigValidator.class);
        TeamPortfolioRenderService renderService = mock(TeamPortfolioRenderService.class);
        TeamPortfolioReferenceService referenceService = mock(TeamPortfolioReferenceService.class);
        TeamPortfolioAssetService assetService = mock(TeamPortfolioAssetService.class);
        TeamScheduleQueryComponentService scheduleService = mock(TeamScheduleQueryComponentService.class);
        VisitRecordEntityMapper visitRecordMapper = mock(VisitRecordEntityMapper.class);
        TeamScheduleQueryRecordEntityMapper scheduleRecordMapper = mock(TeamScheduleQueryRecordEntityMapper.class);
        TeamContactFormComponentService contactService = mock(TeamContactFormComponentService.class);
        ContentLimitService contentLimitService = mock(ContentLimitService.class);
        PointService pointService = mock(PointService.class);
        PortfolioPublishTransactionService publishTransactionService = mock(PortfolioPublishTransactionService.class);
        when(publishTransactionService.execute(any())).thenAnswer(invocation -> {
            Supplier<?> publishAction = invocation.getArgument(0);
            return publishAction.get();
        });
        MineTeamPortfolioService service = new MineTeamPortfolioService(properties, portfolioMapper, historyMapper,
                referenceMapper, shareMapper, teamMapper, memberMapper, access, validator, renderService,
                referenceService, assetService, scheduleService,
                visitRecordMapper, scheduleRecordMapper, contactService, contentLimitService,
                pointService, publishTransactionService);
        return new TestContext(service, portfolioMapper, historyMapper, referenceMapper, shareMapper, teamMapper,
                memberMapper, access, validator, renderService, referenceService, assetService, scheduleService,
                visitRecordMapper, scheduleRecordMapper, contactService, contentLimitService,
                pointService, publishTransactionService);
    }

    /** 创建带标题、分享信息和组件的渲染结果。 */
    private static TeamPortfolioRenderDto renderData() {
        TeamPortfolioRenderDto render = new TeamPortfolioRenderDto();
        render.setTitle("渲染标题");
        TeamPortfolioConfigDto.Share share = new TeamPortfolioConfigDto.Share();
        share.setTitle("分享标题");
        render.setShare(share);
        TeamPortfolioRenderDto.Component component = new TeamPortfolioRenderDto.Component();
        component.setComponentKey("profile-1");
        component.setComponentType("TEAM_PROFILE");
        render.setComponents(List.of(component));
        return render;
    }

    /** 校验维护预览上下文及原始渲染内容。 */
    private static void assertMaintenancePreviewRender(
            TeamPortfolioRenderDto original,
            TeamPortfolioRenderDto actual,
            PortfolioEntity portfolio,
            TeamEntity team
    ) {
        assertThat(actual).isSameAs(original);
        assertThat(actual.isPreview()).isTrue();
        assertThat(actual.isUnderMaintenance()).isFalse();
        assertThat(actual.getPortfolioId()).isEqualTo(portfolio.getId());
        assertThat(actual.getShareCode()).isEqualTo(portfolio.getShareCode());
        assertThat(actual.getTeamId()).isEqualTo(team.getId());
        assertThat(actual.getTeamName()).isEqualTo(team.getName());
        assertThat(actual.getVisitRecordId()).isNull();
        assertThat(actual.getTitle()).isEqualTo("渲染标题");
        assertThat(actual.getShare().getTitle()).isEqualTo("分享标题");
        assertThat(actual.getComponents()).singleElement()
                .extracting(TeamPortfolioRenderDto.Component::getComponentKey)
                .isEqualTo("profile-1");
    }

    private static TeamPortfolioAccessService.TeamPortfolioAccess access(PortfolioEntity portfolio, String role) {
        TeamEntity team = team(TEAM_ID, "团队");
        TeamMemberEntity membership = membership(TEAM_ID, role);
        return new TeamPortfolioAccessService.TeamPortfolioAccess(portfolio, team, membership,
                !TeamRoleDict.MEMBER.getCode().equals(role), true);
    }

    private static PortfolioEntity portfolio(long teamId) {
        PortfolioEntity portfolio = new PortfolioEntity();
        portfolio.setId(PORTFOLIO_ID);
        portfolio.setShareCode("TPF123");
        portfolio.setOwnerType(PortfolioOwnerTypeDict.TEAM.getCode());
        portfolio.setOwnerId(teamId);
        portfolio.setTemplateType(PortfolioTemplateTypeDict.STANDARD.getCode());
        portfolio.setStatus(PortfolioStatusDict.ACTIVE.getCode());
        portfolio.setSchemaVersion(TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1);
        portfolio.setDraftConfigJson(JSON.toJSONString(config()));
        portfolio.setDraftRevision(0);
        portfolio.setPublishedRevision(0);
        portfolio.setCurrentRevision(0);
        portfolio.setPublicationStatus(PortfolioPublicationStatusDict.DRAFT_ONLY.getCode());
        return portfolio;
    }

    /** 创建团队访问记录。 */
    private static VisitRecordEntity teamVisit(long id) {
        VisitRecordEntity record = new VisitRecordEntity();
        record.setId(id);
        record.setPortfolioId(PORTFOLIO_ID);
        record.setPortfolioType("TEAM");
        record.setOwnerType("TEAM");
        record.setOwnerId(TEAM_ID);
        return record;
    }

    private static TeamPortfolioConfigDto config() {
        TeamPortfolioConfigDto config = new TeamPortfolioConfigDto();
        config.setSchemaVersion(TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1);
        TeamPortfolioConfigDto.Share share = new TeamPortfolioConfigDto.Share();
        share.setTitle("团队作品集");
        share.setCoverUrl("https://example.com/cover.jpg");
        config.setShare(share);
        config.setComponents(List.of());
        return config;
    }

    private static TeamPortfolioConfigDto configWithTitle(String title) {
        TeamPortfolioConfigDto config = config();
        config.getShare().setTitle(title);
        return config;
    }

    private static TeamPortfolioConfigDto configWithQr() {
        TeamPortfolioConfigDto config = config();
        TeamPortfolioConfigDto.ComponentEnvelope qr = new TeamPortfolioConfigDto.ComponentEnvelope();
        qr.setComponentKey("qr-1");
        qr.setComponentType("QR_CONTACT");
        qr.setSortOrder(1000);
        qr.setEnabled(true);
        qr.setConfig(JSON.parseObject("{\"qrUrlSource\":\"CUSTOM\","
                + "\"qrUrl\":\"https://example.com/qr.png\"}"));
        config.setComponents(List.of(qr));
        return config;
    }

    private static void assertCleanupStates(
            TestContext context,
            String beforeDraftTitle,
            String beforePublishedTitle,
            String afterDraftTitle,
            String afterPublishedTitle
    ) {
        ArgumentCaptor<String> beforeStateCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> afterStateCaptor = ArgumentCaptor.forClass(String.class);
        verify(context.assetService).deleteUnreferencedAssetsAfterCommit(
                eq(TEAM_ID), eq(PORTFOLIO_ID), beforeStateCaptor.capture(), afterStateCaptor.capture());
        JSONObject beforeState = JSON.parseObject(beforeStateCaptor.getValue());
        JSONObject afterState = JSON.parseObject(afterStateCaptor.getValue());
        assertThat(beforeState.getJSONObject("draft").getJSONObject("share").getString("title"))
                .isEqualTo(beforeDraftTitle);
        assertThat(beforeState.getJSONObject("published").getJSONObject("share").getString("title"))
                .isEqualTo(beforePublishedTitle);
        assertThat(afterState.getJSONObject("draft").getJSONObject("share").getString("title"))
                .isEqualTo(afterDraftTitle);
        assertThat(afterState.getJSONObject("published").getJSONObject("share").getString("title"))
                .isEqualTo(afterPublishedTitle);
    }

    private static TeamPortfolioDraftSaveRequest draftRequest(
            TeamPortfolioConfigDto config,
            Integer clientRevision,
            String idempotencyKey
    ) {
        TeamPortfolioDraftSaveRequest request = new TeamPortfolioDraftSaveRequest();
        request.setConfig(config);
        request.setClientRevision(clientRevision);
        request.setIdempotencyKey(idempotencyKey);
        return request;
    }

    private static TeamPortfolioPublishRequest publishRequest(int draftRevision, String idempotencyKey) {
        TeamPortfolioPublishRequest request = new TeamPortfolioPublishRequest();
        request.setDraftRevision(draftRevision);
        request.setIdempotencyKey(idempotencyKey);
        return request;
    }

    private static PortfolioHistoryEntity history(
            String actionType,
            String idempotencyKey,
            TeamPortfolioConfigDto config
    ) {
        String configJson = JSON.toJSONString(config);
        JSONObject snapshot = new JSONObject();
        snapshot.put("actionType", actionType);
        snapshot.put("idempotencyKey", idempotencyKey);
        snapshot.put("config", JSON.parseObject(configJson));
        PortfolioHistoryEntity history = new PortfolioHistoryEntity();
        history.setPortfolioId(PORTFOLIO_ID);
        history.setSnapshotJson(snapshot.toJSONString());
        history.setContentHash(sha256(configJson));
        return history;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format(Locale.ROOT, "%02x", item));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static TeamEntity team(long id, String name) {
        TeamEntity team = new TeamEntity();
        team.setId(id);
        team.setName(name);
        team.setStatus(TeamStatusDict.ACTIVE.getCode());
        return team;
    }

    private static TeamMemberEntity membership(long teamId, String role) {
        TeamMemberEntity membership = new TeamMemberEntity();
        membership.setTeamId(teamId);
        membership.setUserId(USER_ID);
        membership.setRole(role);
        membership.setJoinStatus(JoinStatusDict.JOINED.getCode());
        return membership;
    }

    private static void assertRollbackFor(String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Transactional transactional = MineTeamPortfolioService.class
                .getDeclaredMethod(methodName, parameterTypes).getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.rollbackFor()).contains(Exception.class);
    }

    private record TestContext(
            MineTeamPortfolioService service,
            PortfolioEntityMapper portfolioMapper,
            PortfolioHistoryEntityMapper historyMapper,
            PortfolioReferenceEntityMapper referenceMapper,
            PortfolioShareRecordEntityMapper shareMapper,
            TeamEntityMapper teamMapper,
            TeamMemberEntityMapper memberMapper,
            TeamPortfolioAccessService access,
            TeamPortfolioConfigValidator validator,
            TeamPortfolioRenderService renderService,
            TeamPortfolioReferenceService referenceService,
            TeamPortfolioAssetService assetService,
            TeamScheduleQueryComponentService scheduleService,
            VisitRecordEntityMapper visitRecordMapper,
            TeamScheduleQueryRecordEntityMapper scheduleRecordMapper,
            TeamContactFormComponentService contactService,
            ContentLimitService contentLimitService,
            PointService pointService,
            PortfolioPublishTransactionService publishTransactionService
    ) {
    }
}
