package com.jxc.wefolio.service.teamportfolio;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.config.TeamPortfolioProperties;
import com.jxc.wefolio.constant.TeamPortfolioConstants;
import com.jxc.wefolio.dict.JoinStatusDict;
import com.jxc.wefolio.dict.PortfolioConfigScopeDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.PortfolioTypeDict;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import com.jxc.wefolio.dict.ShareChannelDict;
import com.jxc.wefolio.dict.TeamPortfolioComponentTypeDict;
import com.jxc.wefolio.dict.TeamRoleDict;
import com.jxc.wefolio.dict.TeamStatusDict;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.dto.teamportfolio.TeamContactLeadResponse;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioCreateRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioDetailResponse;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioDraftSaveRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioMaintainableTeamResponse;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioPublishRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioRenderDto;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioScheduleQueryRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioScheduleQueryResponse;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioShareRecordRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioSummaryResponse;
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
import com.jxc.wefolio.service.teamportfolio.component.contactform.TeamContactFormComponentService;
import com.jxc.wefolio.service.teamportfolio.component.schedulequery.TeamScheduleQueryComponentService;
import com.jxc.wefolio.service.teamportfolio.component.teamprofile.TeamProfileComponentConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 团队作品集维护端业务服务。
 */
@Service
@RequiredArgsConstructor
public class MineTeamPortfolioService {

    /** 未携带能力版本的旧客户端按 revision 2 处理。 */
    private static final int COMPONENT_LIBRARY_LEGACY_REVISION = 2;

    /** 团队组件库按单项引入版本过滤。 */
    private static final List<ComponentLibraryDefinition> COMPONENT_LIBRARY_DEFINITIONS = List.of(
            new ComponentLibraryDefinition(TeamPortfolioComponentTypeDict.TEAM_PROFILE, "展示当前团队资料"),
            new ComponentLibraryDefinition(TeamPortfolioComponentTypeDict.CAROUSEL, "轮播展示已授权成员作品"),
            new ComponentLibraryDefinition(TeamPortfolioComponentTypeDict.VIDEO_CAROUSEL,
                    "叠放循环展示视频作品，访客左右滑动浏览、点击播放"),
            new ComponentLibraryDefinition(TeamPortfolioComponentTypeDict.SINGLE_WORK, "展示一个已授权成员的图片、视频或动图作品"),
            new ComponentLibraryDefinition(TeamPortfolioComponentTypeDict.DIVIDER, "分隔团队作品集内容"),
            new ComponentLibraryDefinition(TeamPortfolioComponentTypeDict.MEMBER_PORTFOLIO_GRID, "双列展示成员个人作品集"),
            new ComponentLibraryDefinition(TeamPortfolioComponentTypeDict.MEMBER_PORTFOLIO_LIST, "单列展示成员个人作品集"),
            new ComponentLibraryDefinition(TeamPortfolioComponentTypeDict.TEXT_SECTION, "展示团队服务说明"),
            new ComponentLibraryDefinition(TeamPortfolioComponentTypeDict.SCHEDULE_QUERY, "允许访客查询团队档期"),
            new ComponentLibraryDefinition(TeamPortfolioComponentTypeDict.CONTACT_FORM, "收集访客预留联系信息"),
            new ComponentLibraryDefinition(TeamPortfolioComponentTypeDict.QR_CONTACT, "展示团队联系二维码")
    );

    /** 作品集积分业务类型。 */
    private static final String POINT_BUSINESS_TYPE_PORTFOLIO = "PORTFOLIO";

    /** 发布标准团队作品集积分备注。 */
    private static final String PUBLISH_POINT_REMARK = "发布标准团队作品集";

    /** 团队作品集分享码前缀。 */
    private static final String SHARE_CODE_PREFIX = "TPF";

    /** 手工保存来源。 */
    private static final String SOURCE_TYPE_MANUAL = "MANUAL";

    /** 草稿保存历史动作。 */
    private static final String HISTORY_ACTION_DRAFT_SAVE = "DRAFT_SAVE";

    /** 发布历史动作。 */
    private static final String HISTORY_ACTION_PUBLISH = "PUBLISH";

    /** 创建历史动作。 */
    private static final String HISTORY_ACTION_CREATE = "CREATE";

    /** 历史快照动作字段。 */
    private static final String HISTORY_FIELD_ACTION_TYPE = "actionType";

    /** 历史快照幂等键字段。 */
    private static final String HISTORY_FIELD_IDEMPOTENCY_KEY = "idempotencyKey";

    /** 历史快照请求指纹字段。 */
    private static final String HISTORY_FIELD_REQUEST_FINGERPRINT = "requestFingerprint";

    /** 历史快照配置字段。 */
    private static final String HISTORY_FIELD_CONFIG = "config";

    /** 历史快照草稿结果版本字段。 */
    private static final String HISTORY_FIELD_RESULT_DRAFT_REVISION = "resultDraftRevision";

    /** 历史快照发布结果版本字段。 */
    private static final String HISTORY_FIELD_RESULT_PUBLISHED_REVISION = "resultPublishedRevision";

    /** 保存请求指纹的客户端版本字段。 */
    private static final String FINGERPRINT_FIELD_CLIENT_REVISION = "clientRevision";

    /** 发布请求指纹的草稿版本字段。 */
    private static final String FINGERPRINT_FIELD_DRAFT_REVISION = "draftRevision";

    /** 默认作品集标题。 */
    private static final String DEFAULT_TITLE = "团队作品集";

    /** SHA-256 算法名称。 */
    private static final String SHA_256_ALGORITHM = "SHA-256";

    /** 草稿预览范围。 */
    private static final String PREVIEW_SCOPE_DRAFT = "draft";

    /** 正式预览范围。 */
    private static final String PREVIEW_SCOPE_PUBLISHED = "published";

    /** 作品集表 ID 列。 */
    private static final String PORTFOLIO_COLUMN_ID = "id";

    /** 作品集归属类型列。 */
    private static final String PORTFOLIO_COLUMN_OWNER_TYPE = "owner_type";

    /** 作品集归属 ID 列。 */
    private static final String PORTFOLIO_COLUMN_OWNER_ID = "owner_id";

    /** 作品集逻辑删除列。 */
    private static final String PORTFOLIO_COLUMN_DELETED = "deleted";

    /** 作品集删除时间列。 */
    private static final String PORTFOLIO_COLUMN_DELETED_AT = "deleted_at";

    /** 允许维护的角色。 */
    private static final Set<String> MAINTAINABLE_ROLES = Set.of(
            TeamRoleDict.OWNER.getCode(), TeamRoleDict.MANAGER.getCode());

    /** 团队访问与记录可读取角色。 */
    private static final Set<String> RECORD_READABLE_ROLES = Set.of(
            TeamRoleDict.OWNER.getCode(), TeamRoleDict.MANAGER.getCode(), TeamRoleDict.MEMBER.getCode());

    /** 维护查询最大页大小。 */
    private static final int RECORD_PAGE_SIZE_MAX = 100;

    /** 功能开关配置。 */
    private final TeamPortfolioProperties teamPortfolioProperties;

    /** 作品集数据访问器。 */
    private final PortfolioEntityMapper portfolioEntityMapper;

    /** 历史数据访问器。 */
    private final PortfolioHistoryEntityMapper portfolioHistoryEntityMapper;

    /** 引用数据访问器。 */
    private final PortfolioReferenceEntityMapper portfolioReferenceEntityMapper;

    /** 分享记录数据访问器。 */
    private final PortfolioShareRecordEntityMapper portfolioShareRecordEntityMapper;

    /** 团队数据访问器。 */
    private final TeamEntityMapper teamEntityMapper;

    /** 团队成员数据访问器。 */
    private final TeamMemberEntityMapper teamMemberEntityMapper;

    /** 团队作品集访问控制。 */
    private final TeamPortfolioAccessService accessService;

    /** 团队配置校验器。 */
    private final TeamPortfolioConfigValidator configValidator;

    /** 团队渲染服务。 */
    private final TeamPortfolioRenderService renderService;

    /** 团队引用服务。 */
    private final TeamPortfolioReferenceService referenceService;

    /** 团队素材服务。 */
    private final TeamPortfolioAssetService assetService;

    /** 团队档期查询组件服务。 */
    private final TeamScheduleQueryComponentService scheduleQueryService;

    /** 团队访问汇总数据访问器。 */
    private final VisitRecordEntityMapper visitRecordEntityMapper;

    /** 团队查档记录数据访问器。 */
    private final TeamScheduleQueryRecordEntityMapper teamScheduleQueryRecordEntityMapper;

    /** 团队预留联系信息组件服务。 */
    private final TeamContactFormComponentService teamContactFormComponentService;

    /** 内容数量上限服务。 */
    private final ContentLimitService contentLimitService;

    /** 积分服务。 */
    private final PointService pointService;

    /** 标准作品集发布事务服务。 */
    private final PortfolioPublishTransactionService portfolioPublishTransactionService;

    /**
     * 查询当前用户所有有效已加入团队中的标准团队作品集。
     *
     * @param userId 当前用户 ID
     * @return 团队作品集摘要
     */
    public List<TeamPortfolioSummaryResponse> listPortfolios(long userId) {
        requireFeatureEnabled();
        List<TeamMemberEntity> memberships = joinedMemberships(userId, null);
        if (memberships.isEmpty()) {
            return List.of();
        }
        Map<Long, TeamEntity> teams = activeTeams(memberships);
        if (teams.isEmpty()) {
            return List.of();
        }
        Map<Long, TeamMemberEntity> membershipsByTeam = memberships.stream()
                .filter(membership -> teams.containsKey(membership.getTeamId()))
                .collect(Collectors.toMap(TeamMemberEntity::getTeamId, Function.identity(), (left, right) -> left));
        List<PortfolioEntity> portfolios = portfolioEntityMapper.selectList(
                Wrappers.lambdaQuery(PortfolioEntity.class)
                        .eq(PortfolioEntity::getOwnerType, PortfolioOwnerTypeDict.TEAM.getCode())
                        .in(PortfolioEntity::getOwnerId, teams.keySet())
                        .eq(PortfolioEntity::getTemplateType, PortfolioTemplateTypeDict.STANDARD.getCode())
                        .eq(PortfolioEntity::getSchemaVersion,
                                TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1)
                        .eq(PortfolioEntity::getStatus, PortfolioStatusDict.ACTIVE.getCode())
                        .eq(PortfolioEntity::getDeleted, 0L)
                        .orderByDesc(PortfolioEntity::getUpdatedAt));
        return safeList(portfolios).stream()
                .map(portfolio -> buildSummary(portfolio, teams.get(portfolio.getOwnerId()),
                        membershipsByTeam.get(portfolio.getOwnerId())))
                .filter(item -> item.getTeamId() != null)
                .toList();
    }

    /**
     * 查询当前用户可维护团队作品集的有效团队。
     *
     * @param userId 当前用户 ID
     * @return 可维护团队列表
     */
    public List<TeamPortfolioMaintainableTeamResponse> listMaintainableTeams(long userId) {
        requireFeatureEnabled();
        List<TeamMemberEntity> memberships = joinedMemberships(userId, MAINTAINABLE_ROLES);
        if (memberships.isEmpty()) {
            return List.of();
        }
        Map<Long, TeamEntity> teams = activeTeams(memberships);
        return memberships.stream()
                .filter(membership -> teams.containsKey(membership.getTeamId()))
                .map(membership -> buildMaintainableTeam(teams.get(membership.getTeamId()), membership))
                .sorted(Comparator.comparing(TeamPortfolioMaintainableTeamResponse::getTeamId))
                .toList();
    }

    /**
     * 获取团队作品集组件库。
     *
     * @param editorSchemaRevision 客户端编辑器能力版本
     * @return 当前客户端可识别的团队组件
     */
    public List<ComponentLibraryItem> getComponentLibrary(Integer editorSchemaRevision) {
        requireFeatureEnabled();
        int effectiveRevision = editorSchemaRevision == null
                ? COMPONENT_LIBRARY_LEGACY_REVISION : editorSchemaRevision;
        return COMPONENT_LIBRARY_DEFINITIONS.stream()
                .filter(definition -> definition.type().getIntroducedAtRevision() <= effectiveRevision)
                .map(definition -> componentItem(definition.type(), definition.description()))
                .toList();
    }

    /**
     * 为显式指定团队创建标准团队作品集。
     *
     * @param teamId 团队 ID
     * @param request 创建请求
     * @param userId 当前用户 ID
     * @return 新作品集详情
     */
    @Transactional(rollbackFor = Exception.class)
    public TeamPortfolioDetailResponse createStandard(
            long teamId,
            TeamPortfolioCreateRequest request,
            long userId
    ) {
        accessService.requireTeamRole(teamId, userId, MAINTAINABLE_ROLES);
        contentLimitService.ensureTeamPortfolioCapacity(teamId);
        TeamPortfolioConfigDto initialConfig = request == null || request.getConfig() == null
                ? defaultConfig() : request.getConfig();
        String initialJson = JSON.toJSONString(initialConfig);
        LocalDateTime now = LocalDateTime.now();
        PortfolioEntity portfolio = new PortfolioEntity();
        portfolio.setShareCode(generateShareCode());
        portfolio.setOwnerType(PortfolioOwnerTypeDict.TEAM.getCode());
        portfolio.setOwnerId(teamId);
        portfolio.setTemplateType(PortfolioTemplateTypeDict.STANDARD.getCode());
        portfolio.setStatus(PortfolioStatusDict.ACTIVE.getCode());
        portfolio.setSchemaVersion(TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1);
        portfolio.setDraftConfigJson(initialJson);
        portfolio.setDraftRevision(0);
        portfolio.setDraftContentHash(sha256(initialJson));
        portfolio.setDraftSavedBy(userId);
        portfolio.setDraftSavedAt(now);
        portfolio.setPublishedRevision(0);
        portfolio.setPublicationStatus(PortfolioPublicationStatusDict.DRAFT_ONLY.getCode());
        portfolio.setSourceType(SOURCE_TYPE_MANUAL);
        portfolio.setContentHash(sha256(initialJson));
        portfolio.setCurrentRevision(0);
        portfolio.setLastSavedBy(userId);
        portfolio.setLastSavedAt(now);
        if (portfolioEntityMapper.insert(portfolio) != 1 || portfolio.getId() == null) {
            throw new BusinessException(TeamPortfolioMessage.CONCURRENT_UPDATE_MESSAGE);
        }
        // 首次创建尚无服务端历史草稿，传 null 表示直接按当前请求生成首版规范化草稿。
        TeamPortfolioConfigDto normalized = configValidator.normalizeForDraft(
                initialConfig,
                null,
                new TeamPortfolioComponentContext(teamId, portfolio.getId(), 1));
        validateCoverIfPresent(teamId, portfolio.getId(), normalized);
        String normalizedJson = JSON.toJSONString(normalized);
        String contentHash = sha256(normalizedJson);
        portfolio.setDraftConfigJson(normalizedJson);
        portfolio.setDraftRevision(1);
        portfolio.setDraftContentHash(contentHash);
        portfolio.setContentHash(contentHash);
        portfolio.setCurrentRevision(1);
        if (portfolioEntityMapper.updateById(portfolio) != 1) {
            throw new BusinessException(TeamPortfolioMessage.CONCURRENT_UPDATE_MESSAGE);
        }
        referenceService.rebuild(portfolio.getId(), PortfolioConfigScopeDict.DRAFT.getCode(), normalized,
                new TeamPortfolioComponentContext(teamId, portfolio.getId(), 1));
        insertHistory(portfolio, 1, normalized, contentHash, userId, now,
                HISTORY_ACTION_CREATE, null, null, null, null);
        return buildDetail(portfolio, normalized);
    }

    /**
     * 获取团队作品集维护详情。
     */
    public TeamPortfolioDetailResponse getDetail(long portfolioId, long userId) {
        PortfolioEntity portfolio = accessService.requireMaintainablePortfolio(portfolioId, userId).portfolio();
        return buildDetail(portfolio, parseConfig(portfolio.getDraftConfigJson()));
    }

    /**
     * 保存团队作品集草稿并重建草稿引用。
     */
    @Transactional(rollbackFor = Exception.class)
    public TeamPortfolioDetailResponse saveDraft(
            long portfolioId,
            TeamPortfolioDraftSaveRequest request,
            long userId
    ) {
        TeamPortfolioAccessService.TeamPortfolioAccess access =
                accessService.requireMaintainablePortfolio(portfolioId, userId);
        PortfolioEntity portfolio = access.portfolio();
        if (request == null || request.getConfig() == null) {
            throw new BusinessException(TeamPortfolioMessage.DRAFT_CONFIG_REQUIRED_MESSAGE);
        }
        if (request.getClientRevision() == null) {
            throw new BusinessException(TeamPortfolioMessage.CLIENT_REVISION_REQUIRED_MESSAGE);
        }
        String idempotencyKey = requireText(
                request.getIdempotencyKey(), TeamPortfolioMessage.IDEMPOTENCY_KEY_REQUIRED_MESSAGE);
        String requestFingerprint = saveRequestFingerprint(request);
        PortfolioHistoryEntity idempotencyHistory = findIdempotencyHistory(
                portfolioId, HISTORY_ACTION_DRAFT_SAVE, idempotencyKey);
        if (idempotencyHistory != null) {
            return restoreIdempotentSave(portfolio, idempotencyHistory, requestFingerprint);
        }
        if (request.getClientRevision() != safeInt(portfolio.getDraftRevision())) {
            throw new BusinessException(TeamPortfolioMessage.DRAFT_REVISION_CHANGED_MESSAGE);
        }
        String oldDraftConfigJson = portfolio.getDraftConfigJson();
        String publishedConfigJson = portfolio.getPublishedConfigJson();
        int nextDraftRevision = safeInt(portfolio.getDraftRevision()) + 1;
        TeamPortfolioConfigDto normalized = configValidator.normalizeForDraft(
                request.getConfig(),
                parseConfig(oldDraftConfigJson),
                new TeamPortfolioComponentContext(access.team().getId(), portfolioId, nextDraftRevision));
        validateCoverIfPresent(access.team().getId(), portfolioId, normalized);
        String configJson = JSON.toJSONString(normalized);
        String contentHash = sha256(configJson);
        int nextHistoryRevision = nextHistoryRevision(portfolio);
        LocalDateTime now = LocalDateTime.now();
        portfolio.setDraftConfigJson(configJson);
        portfolio.setDraftRevision(nextDraftRevision);
        portfolio.setDraftContentHash(contentHash);
        portfolio.setDraftSavedBy(userId);
        portfolio.setDraftSavedAt(now);
        portfolio.setContentHash(contentHash);
        portfolio.setCurrentRevision(nextHistoryRevision);
        portfolio.setLastSavedBy(userId);
        portfolio.setLastSavedAt(now);
        if (!PortfolioPublicationStatusDict.PUBLISHED.getCode().equals(portfolio.getPublicationStatus())) {
            portfolio.setPublicationStatus(PortfolioPublicationStatusDict.DRAFT_ONLY.getCode());
        }
        requireUpdated(portfolioEntityMapper.updateById(portfolio));
        referenceService.rebuild(portfolioId, PortfolioConfigScopeDict.DRAFT.getCode(), normalized,
                new TeamPortfolioComponentContext(access.team().getId(), portfolioId, nextDraftRevision));
        insertHistory(portfolio, nextHistoryRevision, normalized, contentHash, userId, now,
                HISTORY_ACTION_DRAFT_SAVE, idempotencyKey, requestFingerprint,
                nextDraftRevision, null);
        assetService.deleteUnreferencedAssetsAfterCommit(
                access.team().getId(),
                portfolioId,
                assetStateJson(oldDraftConfigJson, publishedConfigJson),
                assetStateJson(configJson, publishedConfigJson));
        return buildDetail(portfolio, normalized);
    }

    /**
     * 发布团队作品集并重建正式引用。
     */
    public TeamPortfolioDetailResponse publish(
            long portfolioId,
            TeamPortfolioPublishRequest request,
            long userId
    ) {
        TeamPortfolioAccessService.TeamPortfolioAccess access =
                accessService.requireMaintainablePortfolio(portfolioId, userId);
        PortfolioEntity portfolio = access.portfolio();
        if (request == null || request.getDraftRevision() == null) {
            throw new BusinessException(TeamPortfolioMessage.PUBLISH_REVISION_REQUIRED_MESSAGE);
        }
        String idempotencyKey = requireText(
                request.getIdempotencyKey(), TeamPortfolioMessage.IDEMPOTENCY_KEY_REQUIRED_MESSAGE);
        String requestFingerprint = publishRequestFingerprint(request);
        PortfolioHistoryEntity idempotencyHistory = findIdempotencyHistory(
                portfolioId, HISTORY_ACTION_PUBLISH, idempotencyKey);
        if (idempotencyHistory != null) {
            return restoreIdempotentPublish(portfolio, idempotencyHistory, requestFingerprint);
        }
        validatePublishDraft(portfolio, request);
        pointService.assertCanConsume(
                userId,
                PointSceneCodeDict.MAINTAIN_STANDARD_PORTFOLIO.getCode(),
                POINT_BUSINESS_TYPE_PORTFOLIO,
                String.valueOf(portfolioId),
                1,
                idempotencyKey
        );
        return portfolioPublishTransactionService.execute(
                () -> publishInTransaction(portfolioId, request, userId)
        );
    }

    /**
     * 在事务内重新校验并发布标准团队作品集。
     *
     * @param portfolioId 作品集 ID
     * @param request 发布请求
     * @param userId 当前发布者 ID
     * @return 团队作品集详情
     */
    private TeamPortfolioDetailResponse publishInTransaction(
            long portfolioId,
            TeamPortfolioPublishRequest request,
            long userId
    ) {
        TeamPortfolioAccessService.TeamPortfolioAccess access =
                accessService.requireMaintainablePortfolio(portfolioId, userId);
        PortfolioEntity portfolio = access.portfolio();
        if (request == null || request.getDraftRevision() == null) {
            throw new BusinessException(TeamPortfolioMessage.PUBLISH_REVISION_REQUIRED_MESSAGE);
        }
        String idempotencyKey = requireText(
                request.getIdempotencyKey(), TeamPortfolioMessage.IDEMPOTENCY_KEY_REQUIRED_MESSAGE);
        String requestFingerprint = publishRequestFingerprint(request);
        PortfolioHistoryEntity idempotencyHistory = findIdempotencyHistory(
                portfolioId, HISTORY_ACTION_PUBLISH, idempotencyKey);
        if (idempotencyHistory != null) {
            return restoreIdempotentPublish(portfolio, idempotencyHistory, requestFingerprint);
        }
        validatePublishDraft(portfolio, request);
        int nextPublishedRevision = safeInt(portfolio.getPublishedRevision()) + 1;
        TeamPortfolioConfigDto normalized = configValidator.validateForPublish(
                parseConfig(portfolio.getDraftConfigJson()),
                new TeamPortfolioComponentContext(access.team().getId(), portfolioId, nextPublishedRevision));
        validateCoverIfPresent(access.team().getId(), portfolioId, normalized);
        String configJson = JSON.toJSONString(normalized);
        String contentHash = sha256(configJson);
        String oldPublishedConfigJson = portfolio.getPublishedConfigJson();
        int nextHistoryRevision = nextHistoryRevision(portfolio);
        LocalDateTime now = LocalDateTime.now();
        portfolio.setPublishedConfigJson(configJson);
        portfolio.setPublishedRevision(nextPublishedRevision);
        portfolio.setPublishedContentHash(contentHash);
        portfolio.setPublishedBy(userId);
        portfolio.setPublishedAt(now);
        portfolio.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        portfolio.setContentHash(contentHash);
        portfolio.setCurrentRevision(nextHistoryRevision);
        portfolio.setLastSavedBy(userId);
        portfolio.setLastSavedAt(now);
        requireUpdated(portfolioEntityMapper.updateById(portfolio));
        referenceService.rebuild(portfolioId, PortfolioConfigScopeDict.PUBLISHED.getCode(), normalized,
                new TeamPortfolioComponentContext(access.team().getId(), portfolioId, nextPublishedRevision));
        insertHistory(portfolio, nextHistoryRevision, normalized, contentHash, userId, now,
                HISTORY_ACTION_PUBLISH, idempotencyKey, requestFingerprint,
                null, nextPublishedRevision);
        pointService.consume(
                userId,
                PointSceneCodeDict.MAINTAIN_STANDARD_PORTFOLIO.getCode(),
                POINT_BUSINESS_TYPE_PORTFOLIO,
                String.valueOf(portfolioId),
                1,
                idempotencyKey,
                PUBLISH_POINT_REMARK
        );
        assetService.deleteUnreferencedAssetsAfterCommit(
                access.team().getId(),
                portfolioId,
                assetStateJson(portfolio.getDraftConfigJson(), oldPublishedConfigJson),
                assetStateJson(portfolio.getDraftConfigJson(), configJson));
        return buildDetail(portfolio, normalized);
    }

    /**
     * 校验团队作品集待发布草稿状态。
     *
     * @param portfolio 作品集
     * @param request 发布请求
     */
    private void validatePublishDraft(PortfolioEntity portfolio, TeamPortfolioPublishRequest request) {
        if (request.getDraftRevision() != safeInt(portfolio.getDraftRevision())) {
            throw new BusinessException(TeamPortfolioMessage.DRAFT_REVISION_CHANGED_MESSAGE);
        }
        if (portfolio.getDraftConfigJson() == null || portfolio.getDraftConfigJson().isBlank()) {
            throw new BusinessException(TeamPortfolioMessage.DRAFT_CONFIG_REQUIRED_MESSAGE);
        }
    }

    /**
     * 组装素材清理所需的当前草稿态与发布态配置包。
     *
     * @param draftConfigJson 草稿配置 JSON
     * @param publishedConfigJson 发布配置 JSON
     * @return 状态配置包 JSON
     */
    private String assetStateJson(String draftConfigJson, String publishedConfigJson) {
        JSONObject state = new JSONObject();
        state.put("draft", parseAssetStateConfig(draftConfigJson));
        state.put("published", parseAssetStateConfig(publishedConfigJson));
        return state.toJSONString();
    }

    /**
     * 解析素材状态配置，空配置使用空对象占位。
     */
    private JSONObject parseAssetStateConfig(String configJson) {
        return configJson == null || configJson.isBlank()
                ? new JSONObject() : JSON.parseObject(configJson);
    }

    /**
     * 预览团队作品集草稿，已加入成员均可用。
     */
    public TeamPortfolioDetailResponse preview(long portfolioId, long userId) {
        TeamPortfolioAccessService.TeamPortfolioAccess access =
                accessService.requireVisiblePortfolio(portfolioId, userId);
        PortfolioEntity portfolio = access.portfolio();
        TeamPortfolioConfigDto config = parseConfig(portfolio.getDraftConfigJson());
        TeamPortfolioDetailResponse response = buildDetail(portfolio, config);
        TeamPortfolioRenderDto render = renderService.render(portfolio.getDraftConfigJson(),
                new TeamPortfolioComponentContext(access.team().getId(), portfolioId,
                        safeInt(portfolio.getDraftRevision())));
        fillMaintenancePreviewRenderContext(render, portfolio, access.team());
        response.setRenderData(render);
        return response;
    }

    /**
     * 预览已发布团队作品集，已加入成员均可用。
     */
    public TeamPortfolioDetailResponse previewPublished(long portfolioId, long userId) {
        TeamPortfolioAccessService.TeamPortfolioAccess access =
                accessService.requireVisiblePortfolio(portfolioId, userId);
        PortfolioEntity portfolio = requirePublished(access.portfolio());
        TeamPortfolioConfigDto config = parseConfig(portfolio.getPublishedConfigJson());
        TeamPortfolioDetailResponse response = buildDetail(portfolio, config);
        TeamPortfolioRenderDto render = renderService.render(portfolio.getPublishedConfigJson(),
                new TeamPortfolioComponentContext(access.team().getId(), portfolioId,
                        safeInt(portfolio.getPublishedRevision())));
        fillMaintenancePreviewRenderContext(render, portfolio, access.team());
        response.setRenderData(render);
        return response;
    }

    /**
     * 返回指定预览范围中的档期组件配置。
     */
    public JSONObject scheduleOptions(
            long portfolioId,
            String componentKey,
            String scope,
            long userId
    ) {
        TeamPortfolioAccessService.TeamPortfolioAccess access =
                accessService.requireVisiblePortfolio(portfolioId, userId);
        PortfolioEntity portfolio = access.portfolio();
        TeamPortfolioConfigDto config = resolvePreviewConfig(portfolio, scope);
        int revision = PREVIEW_SCOPE_PUBLISHED.equals(normalizeScope(scope))
                ? safeInt(portfolio.getPublishedRevision()) : safeInt(portfolio.getDraftRevision());
        return scheduleQueryService.previewOptions(
                new TeamPortfolioComponentContext(access.team().getId(), portfolioId, revision),
                config,
                componentKey);
    }

    /**
     * 委托档期查询组件执行维护预览查询。
     */
    public TeamPortfolioScheduleQueryResponse scheduleQueryPreview(
            long portfolioId,
            TeamPortfolioScheduleQueryRequest request,
            String scope,
            long userId
    ) {
        TeamPortfolioAccessService.TeamPortfolioAccess access =
                accessService.requireVisiblePortfolio(portfolioId, userId);
        PortfolioEntity portfolio = access.portfolio();
        TeamPortfolioConfigDto config = resolvePreviewConfig(portfolio, scope);
        int revision = PREVIEW_SCOPE_PUBLISHED.equals(normalizeScope(scope))
                ? safeInt(portfolio.getPublishedRevision()) : safeInt(portfolio.getDraftRevision());
        return scheduleQueryService.queryPreview(
                new TeamPortfolioComponentContext(access.team().getId(), portfolioId, revision),
                config, request);
    }

    /**
     * 记录已发布团队作品集分享行为。
     */
    public void createShareRecord(
            long portfolioId,
            TeamPortfolioShareRecordRequest request,
            long userId
    ) {
        PortfolioEntity portfolio = requirePublished(
                accessService.requireVisiblePortfolio(portfolioId, userId).portfolio());
        PortfolioShareRecordEntity record = new PortfolioShareRecordEntity();
        record.setPortfolioId(portfolioId);
        record.setPortfolioRevision(safeInt(portfolio.getPublishedRevision()));
        record.setSharedByUserId(userId);
        record.setOwnerType(PortfolioOwnerTypeDict.TEAM.getCode());
        record.setOwnerId(portfolio.getOwnerId());
        String shareChannel = ShareChannelDict.normalizeCode(requireText(
                request == null ? null : request.getShareChannel(),
                TeamPortfolioMessage.SHARE_CHANNEL_REQUIRED_MESSAGE));
        if (ShareChannelDict.fromCode(shareChannel) == null) {
            throw new BusinessException(TeamPortfolioMessage.SHARE_CHANNEL_UNSUPPORTED);
        }
        record.setShareChannel(shareChannel);
        record.setShareScene(request == null || request.getShareScene() == null ? "" : request.getShareScene().strip());
        portfolioShareRecordEntityMapper.insert(record);
    }

    /**
     * 查询团队作品集访问汇总，三种有效已加入角色均可读取。
     *
     * @param teamId 团队 ID
     * @param page 页码，从 1 开始
     * @param pageSize 页大小
     * @param userId 当前维护者 ID
     * @return 团队访问汇总响应
     */
    public TeamVisitRecordsResponse getVisitRecords(
            long teamId,
            int page,
            int pageSize,
            long userId
    ) {
        accessService.requireTeamRole(teamId, userId, RECORD_READABLE_ROLES);
        validateRecordPage(page, pageSize);
        long offset = (long) (page - 1) * pageSize;
        List<VisitRecordEntity> selected = safeList(visitRecordEntityMapper.selectList(
                Wrappers.lambdaQuery(VisitRecordEntity.class)
                        .eq(VisitRecordEntity::getOwnerType, PortfolioOwnerTypeDict.TEAM.getCode())
                        .eq(VisitRecordEntity::getOwnerId, teamId)
                        .eq(VisitRecordEntity::getPortfolioType, PortfolioTypeDict.TEAM.getCode())
                        .orderByDesc(VisitRecordEntity::getLastVisitedAt)
                        .orderByDesc(VisitRecordEntity::getId)
                        .last("LIMIT " + offset + "," + (pageSize + 1))));
        boolean hasMore = selected.size() > pageSize;
        List<TeamVisitRecordItem> items = selected.stream()
                .limit(pageSize)
                .filter(record -> isOwnedTeamVisit(record, teamId))
                .map(this::toVisitItem)
                .toList();
        return new TeamVisitRecordsResponse(page, pageSize, hasMore, items);
    }

    /**
     * 分页查询团队已发布查档历史快照。
     *
     * @param teamId 团队 ID
     * @param page 页码，从 1 开始
     * @param pageSize 页大小
     * @param userId 当前维护者 ID
     * @return 团队查档记录响应
     */
    public TeamScheduleQueryRecordsResponse getScheduleQueryRecords(
            long teamId,
            int page,
            int pageSize,
            long userId
    ) {
        accessService.requireTeamRole(teamId, userId, RECORD_READABLE_ROLES);
        validateRecordPage(page, pageSize);
        long offset = (long) (page - 1) * pageSize;
        List<TeamScheduleQueryRecordEntity> selected = safeList(
                teamScheduleQueryRecordEntityMapper.selectList(
                        Wrappers.lambdaQuery(TeamScheduleQueryRecordEntity.class)
                                .eq(TeamScheduleQueryRecordEntity::getTeamId, teamId)
                                .orderByDesc(TeamScheduleQueryRecordEntity::getQueriedAt)
                                .orderByDesc(TeamScheduleQueryRecordEntity::getId)
                                .last("LIMIT " + offset + "," + (pageSize + 1))));
        boolean hasMore = selected.size() > pageSize;
        List<TeamScheduleQueryRecordItem> items = selected.stream()
                .limit(pageSize)
                .filter(record -> Objects.equals(record.getTeamId(), teamId))
                .map(this::toScheduleQueryItem)
                .toList();
        return new TeamScheduleQueryRecordsResponse(page, pageSize, hasMore, items);
    }

    /**
     * 查询团队完整未脱敏预留联系信息。
     */
    public TeamContactLeadResponse getContactLeads(
            long teamId,
            int page,
            int pageSize,
            long userId
    ) {
        return teamContactFormComponentService.list(teamId, page, pageSize, userId);
    }

    /**
     * 更新团队预留联系信息跟进状态，仅组件允许的 OWNER、MANAGER 可执行。
     */
    @Transactional(rollbackFor = Exception.class)
    public TeamContactLeadResponse.Item updateContactLeadFollowStatus(
            long teamId,
            long leadId,
            String followStatus,
            String followNote,
            long userId
    ) {
        return teamContactFormComponentService.updateFollowStatus(
                teamId, leadId, followStatus, followNote, userId);
    }

    /**
     * 删除团队作品集、引用并在提交后清理自有素材。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deletePortfolio(long portfolioId, long userId) {
        TeamPortfolioAccessService.TeamPortfolioAccess access =
                accessService.requireMaintainablePortfolio(portfolioId, userId);
        PortfolioEntity portfolio = access.portfolio();
        portfolioReferenceEntityMapper.delete(Wrappers.lambdaQuery(PortfolioReferenceEntity.class)
                .eq(PortfolioReferenceEntity::getPortfolioId, portfolioId));
        int updated = portfolioEntityMapper.update(
                new PortfolioEntity(),
                new UpdateWrapper<PortfolioEntity>()
                        .set(PORTFOLIO_COLUMN_DELETED_AT, LocalDateTime.now())
                        .set(PORTFOLIO_COLUMN_DELETED, portfolioId)
                        .eq(PORTFOLIO_COLUMN_ID, portfolioId)
                        .eq(PORTFOLIO_COLUMN_OWNER_TYPE, PortfolioOwnerTypeDict.TEAM.getCode())
                        .eq(PORTFOLIO_COLUMN_OWNER_ID, access.team().getId())
                        .eq(PORTFOLIO_COLUMN_DELETED, 0L));
        if (updated != 1) {
            throw new BusinessException(TeamPortfolioMessage.DELETE_FAILED_MESSAGE);
        }
        assetService.deletePortfolioAssetsAfterCommit(access.team().getId(), portfolioId,
                portfolio.getDraftConfigJson(), portfolio.getPublishedConfigJson());
    }

    /**
     * 查询当前用户已加入的团队关系。
     */
    private List<TeamMemberEntity> joinedMemberships(long userId, Set<String> roles) {
        return safeList(teamMemberEntityMapper.selectList(
                Wrappers.lambdaQuery(TeamMemberEntity.class)
                        .eq(TeamMemberEntity::getUserId, userId)
                        .eq(TeamMemberEntity::getJoinStatus, JoinStatusDict.JOINED.getCode())
                        .in(roles != null && !roles.isEmpty(), TeamMemberEntity::getRole, roles)));
    }

    /**
     * 批量读取并过滤有效团队。
     */
    private Map<Long, TeamEntity> activeTeams(List<TeamMemberEntity> memberships) {
        Set<Long> teamIds = memberships.stream().map(TeamMemberEntity::getTeamId)
                .filter(id -> id != null && id > 0).collect(Collectors.toSet());
        if (teamIds.isEmpty()) {
            return Map.of();
        }
        return safeList(teamEntityMapper.selectBatchIds(teamIds)).stream()
                .filter(team -> TeamStatusDict.ACTIVE.getCode().equals(team.getStatus()))
                .collect(Collectors.toMap(TeamEntity::getId, Function.identity(), (left, right) -> left,
                        LinkedHashMap::new));
    }

    /**
     * 组装团队作品集摘要。
     */
    private TeamPortfolioSummaryResponse buildSummary(
            PortfolioEntity portfolio,
            TeamEntity team,
            TeamMemberEntity membership
    ) {
        TeamPortfolioSummaryResponse response = new TeamPortfolioSummaryResponse();
        if (team == null || membership == null) {
            return response;
        }
        TeamPortfolioConfigDto config = parseConfig(resolveListConfigJson(portfolio));
        response.setPortfolioId(portfolio.getId());
        response.setShareCode(portfolio.getShareCode());
        response.setTitle(resolveTitle(config));
        response.setCoverUrl(resolveCoverUrl(config));
        response.setTeamId(team.getId());
        response.setTeamName(team.getName());
        response.setCurrentRole(membership.getRole());
        response.setCanMaintain(MAINTAINABLE_ROLES.contains(membership.getRole()));
        response.setCanShare(PortfolioStatusDict.ACTIVE.getCode().equals(portfolio.getStatus())
                && PortfolioPublicationStatusDict.PUBLISHED.getCode().equals(portfolio.getPublicationStatus())
                && portfolio.getPublishedConfigJson() != null
                && !portfolio.getPublishedConfigJson().isBlank());
        response.setPublicationStatus(portfolio.getPublicationStatus());
        response.setDraftRevision(safeInt(portfolio.getDraftRevision()));
        response.setPublishedRevision(safeInt(portfolio.getPublishedRevision()));
        response.setUpdatedAt(portfolio.getUpdatedAt());
        return response;
    }

    /**
     * 组装可维护团队响应。
     */
    private TeamPortfolioMaintainableTeamResponse buildMaintainableTeam(
            TeamEntity team,
            TeamMemberEntity membership
    ) {
        TeamPortfolioMaintainableTeamResponse response = new TeamPortfolioMaintainableTeamResponse();
        response.setTeamId(team.getId());
        response.setTeamName(team.getName());
        response.setAvatarUrl(team.getAvatarUrl());
        response.setCurrentRole(membership.getRole());
        return response;
    }

    /**
     * 构建默认团队作品集配置。
     */
    private TeamPortfolioConfigDto defaultConfig() {
        TeamPortfolioConfigDto config = new TeamPortfolioConfigDto();
        config.setSchemaVersion(TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1);
        TeamPortfolioConfigDto.Share share = new TeamPortfolioConfigDto.Share();
        share.setTitle(DEFAULT_TITLE);
        share.setDescription("");
        share.setCoverUrl("");
        config.setShare(share);
        config.setComponents(List.of(TeamProfileComponentConfig.defaultEnvelope()));
        return config;
    }

    /**
     * 写入团队作品集历史快照。
     */
    private void insertHistory(
            PortfolioEntity portfolio,
            int revision,
            TeamPortfolioConfigDto config,
            String contentHash,
            long userId,
            LocalDateTime savedAt,
            String actionType,
            String idempotencyKey,
            String requestFingerprint,
            Integer resultDraftRevision,
            Integer resultPublishedRevision
    ) {
        PortfolioHistoryEntity history = new PortfolioHistoryEntity();
        history.setPortfolioId(portfolio.getId());
        history.setRevisionNo(revision);
        history.setSchemaVersion(TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1);
        JSONObject snapshot = new JSONObject();
        snapshot.put(HISTORY_FIELD_ACTION_TYPE, actionType);
        if (idempotencyKey != null) {
            snapshot.put(HISTORY_FIELD_IDEMPOTENCY_KEY, idempotencyKey);
        }
        if (requestFingerprint != null) {
            snapshot.put(HISTORY_FIELD_REQUEST_FINGERPRINT, requestFingerprint);
        }
        if (resultDraftRevision != null) {
            snapshot.put(HISTORY_FIELD_RESULT_DRAFT_REVISION, resultDraftRevision);
        }
        if (resultPublishedRevision != null) {
            snapshot.put(HISTORY_FIELD_RESULT_PUBLISHED_REVISION, resultPublishedRevision);
        }
        snapshot.put(HISTORY_FIELD_CONFIG, config);
        history.setSnapshotJson(snapshot.toJSONString());
        history.setSourceType(SOURCE_TYPE_MANUAL);
        history.setContentHash(contentHash);
        history.setSavedBy(userId);
        history.setSavedAt(savedAt);
        if (portfolioHistoryEntityMapper.insert(history) != 1) {
            throw new BusinessException(TeamPortfolioMessage.HISTORY_INSERT_FAILED_MESSAGE);
        }
    }

    /**
     * 查询当前作品集指定动作和幂等键的历史凭据。
     */
    private PortfolioHistoryEntity findIdempotencyHistory(
            long portfolioId,
            String actionType,
            String idempotencyKey
    ) {
        List<PortfolioHistoryEntity> histories = safeList(portfolioHistoryEntityMapper.selectList(
                Wrappers.lambdaQuery(PortfolioHistoryEntity.class)
                        .eq(PortfolioHistoryEntity::getPortfolioId, portfolioId)
                        .orderByDesc(PortfolioHistoryEntity::getRevisionNo)));
        for (PortfolioHistoryEntity history : histories) {
            JSONObject snapshot = parseHistorySnapshot(history.getSnapshotJson());
            if (snapshot != null
                    && actionType.equals(snapshot.getString(HISTORY_FIELD_ACTION_TYPE))
                    && idempotencyKey.equals(snapshot.getString(HISTORY_FIELD_IDEMPOTENCY_KEY))) {
                return history;
            }
        }
        return null;
    }

    /**
     * 解析历史快照，旧格式或损坏记录不作为幂等凭据。
     */
    private JSONObject parseHistorySnapshot(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return null;
        }
        try {
            return JSON.parseObject(snapshotJson);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * 计算保存请求的稳定指纹，配置取自校验前的原始请求。
     */
    private String saveRequestFingerprint(TeamPortfolioDraftSaveRequest request) {
        JSONObject fingerprintSource = new JSONObject();
        fingerprintSource.put(FINGERPRINT_FIELD_CLIENT_REVISION, request.getClientRevision());
        fingerprintSource.put(HISTORY_FIELD_CONFIG, request.getConfig());
        return sha256(JSON.toJSONString(fingerprintSource, JSONWriter.Feature.MapSortField));
    }

    /**
     * 计算发布请求的稳定指纹。
     */
    private String publishRequestFingerprint(TeamPortfolioPublishRequest request) {
        JSONObject fingerprintSource = new JSONObject();
        fingerprintSource.put(FINGERPRINT_FIELD_DRAFT_REVISION, request.getDraftRevision());
        return sha256(JSON.toJSONString(fingerprintSource, JSONWriter.Feature.MapSortField));
    }

    /**
     * 从历史快照恢复草稿保存的幂等结果。
     */
    private TeamPortfolioDetailResponse restoreIdempotentSave(
            PortfolioEntity portfolio,
            PortfolioHistoryEntity history,
            String requestFingerprint
    ) {
        JSONObject snapshot = requireMatchingHistorySnapshot(history, requestFingerprint);
        Integer resultRevision = snapshot.getInteger(HISTORY_FIELD_RESULT_DRAFT_REVISION);
        if (resultRevision == null || resultRevision <= 0) {
            throw new BusinessException(TeamPortfolioMessage.IDEMPOTENCY_CONFLICT_MESSAGE);
        }
        TeamPortfolioDetailResponse response = buildDetail(portfolio, parseHistoryConfig(snapshot));
        response.setDraftRevision(resultRevision);
        return response;
    }

    /**
     * 从历史快照恢复发布的幂等结果。
     */
    private TeamPortfolioDetailResponse restoreIdempotentPublish(
            PortfolioEntity portfolio,
            PortfolioHistoryEntity history,
            String requestFingerprint
    ) {
        JSONObject snapshot = requireMatchingHistorySnapshot(history, requestFingerprint);
        Integer resultRevision = snapshot.getInteger(HISTORY_FIELD_RESULT_PUBLISHED_REVISION);
        if (resultRevision == null || resultRevision <= 0) {
            throw new BusinessException(TeamPortfolioMessage.IDEMPOTENCY_CONFLICT_MESSAGE);
        }
        TeamPortfolioDetailResponse response = buildDetail(portfolio, parseHistoryConfig(snapshot));
        response.setPublishedRevision(resultRevision);
        return response;
    }

    /**
     * 校验持久化请求指纹，旧格式或损坏快照一律不可复用。
     */
    private JSONObject requireMatchingHistorySnapshot(
            PortfolioHistoryEntity history,
            String requestFingerprint
    ) {
        JSONObject snapshot = parseHistorySnapshot(history.getSnapshotJson());
        String persistedFingerprint = snapshot == null
                ? null : snapshot.getString(HISTORY_FIELD_REQUEST_FINGERPRINT);
        if (persistedFingerprint == null || !persistedFingerprint.equals(requestFingerprint)) {
            throw new BusinessException(TeamPortfolioMessage.IDEMPOTENCY_CONFLICT_MESSAGE);
        }
        return snapshot;
    }

    /**
     * 读取历史快照中的规范化配置，损坏数据不得回退到当前草稿。
     */
    private TeamPortfolioConfigDto parseHistoryConfig(JSONObject snapshot) {
        try {
            JSONObject config = snapshot.getJSONObject(HISTORY_FIELD_CONFIG);
            if (config == null) {
                throw new IllegalArgumentException(HISTORY_FIELD_CONFIG);
            }
            TeamPortfolioConfigDto parsed = config.toJavaObject(TeamPortfolioConfigDto.class);
            if (parsed == null) {
                throw new IllegalArgumentException(HISTORY_FIELD_CONFIG);
            }
            return parsed;
        } catch (RuntimeException exception) {
            throw new BusinessException(TeamPortfolioMessage.IDEMPOTENCY_CONFLICT_MESSAGE);
        }
    }

    /**
     * 顶层分享封面非空时校验其属于当前团队作品集且已上传。
     */
    private void validateCoverIfPresent(
            long teamId,
            long portfolioId,
            TeamPortfolioConfigDto config
    ) {
        String coverUrl = config == null || config.getShare() == null
                ? null : config.getShare().getCoverUrl();
        if (coverUrl != null && !coverUrl.isBlank()) {
            assetService.validateUploadedImageUrl(teamId, portfolioId, coverUrl);
        }
    }

    /**
     * 组装团队作品集详情。
     */
    private TeamPortfolioDetailResponse buildDetail(
            PortfolioEntity portfolio,
            TeamPortfolioConfigDto config
    ) {
        TeamPortfolioDetailResponse response = new TeamPortfolioDetailResponse();
        response.setPortfolioId(portfolio.getId());
        response.setShareCode(portfolio.getShareCode());
        response.setOwnerType(portfolio.getOwnerType());
        response.setOwnerId(portfolio.getOwnerId());
        response.setTemplateType(portfolio.getTemplateType());
        response.setStatus(portfolio.getStatus());
        response.setPublicationStatus(portfolio.getPublicationStatus());
        response.setDraftRevision(safeInt(portfolio.getDraftRevision()));
        response.setPublishedRevision(safeInt(portfolio.getPublishedRevision()));
        response.setConfig(config);
        return response;
    }

    /** 填充维护端预览渲染上下文。 */
    private void fillMaintenancePreviewRenderContext(
            TeamPortfolioRenderDto render,
            PortfolioEntity portfolio,
            TeamEntity team
    ) {
        if (render == null) {
            return;
        }
        render.setPreview(true);
        render.setUnderMaintenance(false);
        render.setPortfolioId(portfolio.getId());
        render.setShareCode(portfolio.getShareCode());
        render.setTeamId(team.getId());
        render.setTeamName(team.getName());
        render.setVisitRecordId(null);
    }

    /**
     * 解析团队作品集配置。
     */
    private TeamPortfolioConfigDto parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return null;
        }
        try {
            return JSON.parseObject(configJson, TeamPortfolioConfigDto.class);
        } catch (RuntimeException exception) {
            throw new BusinessException(TeamPortfolioMessage.DRAFT_CONFIG_REQUIRED_MESSAGE);
        }
    }

    /**
     * 解析预览配置范围。
     */
    private TeamPortfolioConfigDto resolvePreviewConfig(PortfolioEntity portfolio, String scope) {
        String normalizedScope = normalizeScope(scope);
        if (PREVIEW_SCOPE_PUBLISHED.equals(normalizedScope)) {
            requirePublished(portfolio);
            return parseConfig(portfolio.getPublishedConfigJson());
        }
        return parseConfig(portfolio.getDraftConfigJson());
    }

    /**
     * 校验正式发布状态。
     */
    private PortfolioEntity requirePublished(PortfolioEntity portfolio) {
        if (portfolio == null
                || !PortfolioPublicationStatusDict.PUBLISHED.getCode().equals(portfolio.getPublicationStatus())
                || portfolio.getPublishedConfigJson() == null
                || portfolio.getPublishedConfigJson().isBlank()) {
            throw new BusinessException(TeamPortfolioMessage.PORTFOLIO_UNAVAILABLE_MESSAGE);
        }
        return portfolio;
    }

    /**
     * 计算下一条全局历史修订号。
     */
    private int nextHistoryRevision(PortfolioEntity portfolio) {
        return Math.max(safeInt(portfolio.getCurrentRevision()),
                Math.max(safeInt(portfolio.getDraftRevision()), safeInt(portfolio.getPublishedRevision()))) + 1;
    }

    /**
     * 校验更新行数。
     */
    private void requireUpdated(int updated) {
        if (updated != 1) {
            throw new BusinessException(TeamPortfolioMessage.CONCURRENT_UPDATE_MESSAGE);
        }
    }

    /**
     * 校验功能开关。
     */
    private void requireFeatureEnabled() {
        if (!teamPortfolioProperties.isEnabled()) {
            throw new BusinessException(TeamPortfolioMessage.FEATURE_DISABLED);
        }
    }

    /**
     * 规范化并校验必填文本。
     */
    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(message);
        }
        return value.strip();
    }

    /**
     * 规范化预览范围。
     */
    private String normalizeScope(String scope) {
        return scope == null || scope.isBlank()
                ? PREVIEW_SCOPE_DRAFT : scope.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * 解析列表使用的配置 JSON。
     */
    private String resolveListConfigJson(PortfolioEntity portfolio) {
        return PortfolioPublicationStatusDict.PUBLISHED.getCode().equals(portfolio.getPublicationStatus())
                && portfolio.getPublishedConfigJson() != null && !portfolio.getPublishedConfigJson().isBlank()
                ? portfolio.getPublishedConfigJson() : portfolio.getDraftConfigJson();
    }

    /**
     * 解析展示标题。
     */
    private String resolveTitle(TeamPortfolioConfigDto config) {
        return config != null && config.getShare() != null
                && config.getShare().getTitle() != null && !config.getShare().getTitle().isBlank()
                ? config.getShare().getTitle() : DEFAULT_TITLE;
    }

    /**
     * 解析展示封面。
     */
    private String resolveCoverUrl(TeamPortfolioConfigDto config) {
        return config != null && config.getShare() != null && config.getShare().getCoverUrl() != null
                ? config.getShare().getCoverUrl() : "";
    }

    /** 复验访问汇总属于目标团队。 */
    private boolean isOwnedTeamVisit(VisitRecordEntity record, long teamId) {
        return record != null
                && PortfolioOwnerTypeDict.TEAM.getCode().equals(record.getOwnerType())
                && Objects.equals(record.getOwnerId(), teamId)
                && PortfolioTypeDict.TEAM.getCode().equals(record.getPortfolioType());
    }

    /** 转换团队访问汇总，响应不暴露 visitorKey 或 openid。 */
    private TeamVisitRecordItem toVisitItem(VisitRecordEntity record) {
        return new TeamVisitRecordItem(
                record.getId(),
                record.getPortfolioId(),
                record.getPortfolioTitleSnapshot(),
                record.getPortfolioShareCodeSnapshot(),
                record.getLastPortfolioRevision(),
                record.getSourceType(),
                safeInt(record.getVisitCount()),
                safeInt(record.getViewWorkCount()),
                safeInt(record.getPlayVideoCount()),
                safeInt(record.getScheduleQueryCount()),
                safeInt(record.getQrActionCount()),
                safeInt(record.getContactSubmitCount()),
                safeInt(record.getTotalDurationSeconds()),
                record.getFollowStatus(),
                record.getFollowNote(),
                record.getFirstVisitedAt(),
                record.getLastVisitedAt());
    }

    /** 转换团队查档历史快照，不重新计算当前成员档期。 */
    private TeamScheduleQueryRecordItem toScheduleQueryItem(TeamScheduleQueryRecordEntity record) {
        return new TeamScheduleQueryRecordItem(
                record.getId(),
                record.getPortfolioId(),
                record.getPortfolioRevision(),
                record.getPortfolioTitleSnapshot(),
                record.getVisitRecordId(),
                record.getSourceType(),
                record.getDisplayMode(),
                record.getQueriedDate(),
                record.getResultStatus(),
                record.getResultStatusText(),
                Objects.equals(record.getAvailable(), 1),
                record.getResultMessage(),
                record.getTeamResultJson(),
                safeInt(record.getAvailableMemberCount()),
                safeInt(record.getPartialAvailableMemberCount()),
                safeInt(record.getFullMemberCount()),
                record.getQueriedAt());
    }

    /** 校验团队维护查询分页。 */
    private void validateRecordPage(int page, int pageSize) {
        if (page <= 0 || pageSize <= 0 || pageSize > RECORD_PAGE_SIZE_MAX) {
            throw new BusinessException(TeamPortfolioMessage.RECORD_PAGE_INVALID_MESSAGE);
        }
    }

    /**
     * 生成团队作品集分享码。
     */
    private String generateShareCode() {
        return SHARE_CODE_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
    }

    /**
     * 计算配置摘要。
     */
    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance(SHA_256_ALGORITHM)
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format(Locale.ROOT, "%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(TeamPortfolioMessage.SHA_256_UNAVAILABLE_MESSAGE, exception);
        }
    }

    /**
     * 创建组件库项。
     */
    private ComponentLibraryItem componentItem(
            TeamPortfolioComponentTypeDict componentType,
            String description
    ) {
        return new ComponentLibraryItem(componentType.getCode(), componentType.getDisplayName(), description);
    }

    /**
     * 安全返回列表。
     */
    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    /**
     * 安全读取整数。
     */
    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 团队作品集组件库项。
     *
     * @param componentType 组件类型
     * @param name 组件名称
     * @param description 组件说明
     */
    public record ComponentLibraryItem(String componentType, String name, String description) {
    }

    /** 组件库内部定义，引入版本只参与服务端过滤，不进入响应。 */
    private record ComponentLibraryDefinition(TeamPortfolioComponentTypeDict type, String description) {
    }

    /**
     * 团队访问记录列表响应。
     *
     * @param page 页码
     * @param pageSize 页大小
     * @param hasMore 是否还有下一页
     * @param items 团队访问汇总列表
     */
    public record TeamVisitRecordsResponse(
            int page,
            int pageSize,
            boolean hasMore,
            List<TeamVisitRecordItem> items
    ) {
    }

    /**
     * 团队访问汇总展示项，不包含访客稳定键等认证字段。
     */
    public record TeamVisitRecordItem(
            Long recordId,
            Long portfolioId,
            String portfolioTitle,
            String portfolioShareCode,
            Integer portfolioRevision,
            String sourceType,
            int visitCount,
            int viewWorkCount,
            int playVideoCount,
            int scheduleQueryCount,
            int qrActionCount,
            int contactSubmitCount,
            int totalDurationSeconds,
            String followStatus,
            String followNote,
            LocalDateTime firstVisitedAt,
            LocalDateTime lastVisitedAt
    ) {
    }

    /**
     * 团队查档历史分页响应。
     */
    public record TeamScheduleQueryRecordsResponse(
            int page,
            int pageSize,
            boolean hasMore,
            List<TeamScheduleQueryRecordItem> items
    ) {
    }

    /**
     * 团队查档历史快照展示项。
     */
    public record TeamScheduleQueryRecordItem(
            Long recordId,
            Long portfolioId,
            Integer portfolioRevision,
            String portfolioTitle,
            Long visitRecordId,
            String sourceType,
            String displayMode,
            LocalDate queriedDate,
            String resultStatus,
            String resultStatusText,
            boolean available,
            String resultMessage,
            String teamResultJson,
            int availableMemberCount,
            int partialAvailableMemberCount,
            int fullMemberCount,
            LocalDateTime queriedAt
    ) {
    }
}
