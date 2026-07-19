package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.PortfolioComponentTypeDict;
import com.jxc.wefolio.dict.PortfolioConfigScopeDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import com.jxc.wefolio.dict.ScheduleStatusDict;
import com.jxc.wefolio.dict.ShareChannelDict;
import com.jxc.wefolio.dict.SlotDefinitionStatusDict;
import com.jxc.wefolio.dto.MinePortfolioAssetUploadTicketRequest;
import com.jxc.wefolio.dto.MinePortfolioAssetUploadTicketResponse;
import com.jxc.wefolio.dto.MinePortfolioCreateRequest;
import com.jxc.wefolio.dto.MinePortfolioDetailResponse;
import com.jxc.wefolio.dto.MinePortfolioDraftSaveRequest;
import com.jxc.wefolio.dto.MinePortfolioListResponse;
import com.jxc.wefolio.dto.MinePortfolioPublishRequest;
import com.jxc.wefolio.dto.MinePortfolioShareRecordRequest;
import com.jxc.wefolio.dto.PortfolioComponentLibraryResponse;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.dto.PortfolioScheduleOptionsResponse;
import com.jxc.wefolio.dto.PortfolioScheduleQueryRequest;
import com.jxc.wefolio.dto.PortfolioScheduleQueryResponse;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.PortfolioHistoryEntity;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.entity.PortfolioShareRecordEntity;
import com.jxc.wefolio.entity.ScheduleEntity;
import com.jxc.wefolio.entity.SlotDefinitionEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.PortfolioHistoryEntityMapper;
import com.jxc.wefolio.mapper.PortfolioReferenceEntityMapper;
import com.jxc.wefolio.mapper.PortfolioShareRecordEntityMapper;
import com.jxc.wefolio.mapper.ScheduleEntityMapper;
import com.jxc.wefolio.mapper.SlotDefinitionEntityMapper;
import com.jxc.wefolio.message.PortfolioMessage;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioReferenceGuardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 我的作品集服务 — 负责标准个人作品集维护端创建、草稿、预览、发布和分享记录。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MinePortfolioService {

    /** 作品集积分业务类型 */
    private static final String POINT_BUSINESS_TYPE_PORTFOLIO = "PORTFOLIO";

    /** 手工保存来源 */
    private static final String SOURCE_TYPE_MANUAL = "MANUAL";

    /** 发布标准个人作品集积分备注 */
    private static final String PUBLISH_POINT_REMARK = "发布标准个人作品集";

    /** 默认分享编码前缀 */
    private static final String SHARE_CODE_PREFIX = "PF";

    /** 默认作品集标题 */
    private static final String UNTITLED_PORTFOLIO_TITLE = "未命名作品集";

    /** 作品集封面最大字节数 */
    private static final long COVER_MAX_BYTES = 300L * 1024L;

    /** 作品集图片素材最大字节数 */
    private static final long PORTFOLIO_IMAGE_ASSET_MAX_BYTES = COVER_MAX_BYTES;

    /** 作品集素材 COS 目录 */
    private static final String PORTFOLIO_ASSET_FOLDER = "protfolio";

    /** 封面素材类型 */
    private static final String ASSET_TYPE_COVER = "COVER";

    /** 个人资料头像素材类型 */
    private static final String ASSET_TYPE_PROFILE_AVATAR = "PROFILE_AVATAR";

    /** 二维码联系素材类型 */
    private static final String ASSET_TYPE_QR_CONTACT = "QR_CONTACT";

    /** 封面文件名前缀 */
    private static final String COVER_FILE_PREFIX = "cover";

    /** 个人资料头像文件名前缀 */
    private static final String PROFILE_AVATAR_FILE_PREFIX = "profile-avatar";

    /** 二维码联系文件名前缀 */
    private static final String QR_CONTACT_FILE_PREFIX = "qr-contact";

    /** 封面文件名时间格式 */
    private static final DateTimeFormatter COVER_FILE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 封面文件随机后缀长度 */
    private static final int COVER_RANDOM_LENGTH = 8;

    /** 档期查询组件类型 */
    private static final String COMPONENT_TYPE_SCHEDULE_QUERY = "SCHEDULE_QUERY";

    /** 已发布预览配置范围 */
    private static final String PREVIEW_SCOPE_PUBLISHED = "published";

    /** 可约状态兜底编码 */
    private static final String STATUS_AVAILABLE = "AVAILABLE";

    /** 可约提示 */
    private static final String MESSAGE_AVAILABLE = "档期空闲";

    /** 已约提示 */
    private static final String MESSAGE_BOOKED = "该档期已约";

    /** 月份格式 */
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 档期时间格式 */
    private static final DateTimeFormatter SCHEDULE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /** JPEG MIME 类型 */
    private static final String MIME_IMAGE_JPEG = "image/jpeg";

    /** JPG MIME 类型兼容值 */
    private static final String MIME_IMAGE_JPG = "image/jpg";

    /** PNG MIME 类型 */
    private static final String MIME_IMAGE_PNG = "image/png";

    /** JPEG 文件扩展名 */
    private static final String COVER_EXTENSION_JPG = "jpg";

    /** PNG 文件扩展名 */
    private static final String COVER_EXTENSION_PNG = "png";

    /** 作品集图片素材对象键匹配模板 */
    private static final String PORTFOLIO_ASSET_OBJECT_KEY_PATTERN_TEMPLATE = "%s/" + PORTFOLIO_ASSET_FOLDER
            + "/(" + COVER_FILE_PREFIX + "|" + PROFILE_AVATAR_FILE_PREFIX + "|" + QR_CONTACT_FILE_PREFIX
            + ")-%d-\\d{14}-[0-9a-f]{8}\\.(jpg|png)";

    /** 个人资料组件配置键 */
    private static final String CONFIG_KEY_PROFILE = "profile";

    /** 头像地址配置键 */
    private static final String CONFIG_KEY_AVATAR_URL = "avatarUrl";

    /** 微信二维码地址配置键 */
    private static final String CONFIG_KEY_WECHAT_QR_URL = "wechatQrUrl";

    /** 二维码地址配置键 */
    private static final String CONFIG_KEY_QR_URL = "qrUrl";

    /** 作品集表主键列 */
    private static final String PORTFOLIO_COLUMN_ID = "id";

    /** 作品集表归属类型列 */
    private static final String PORTFOLIO_COLUMN_OWNER_TYPE = "owner_type";

    /** 作品集表归属 ID 列 */
    private static final String PORTFOLIO_COLUMN_OWNER_ID = "owner_id";

    /** 作品集表逻辑删除列 */
    private static final String PORTFOLIO_COLUMN_DELETED = "deleted";

    /** 作品集表删除时间列 */
    private static final String PORTFOLIO_COLUMN_DELETED_AT = "deleted_at";

    /** 上传票据有效分钟数 */
    private static final int TICKET_EXPIRE_MINUTES = 15;

    /** 作品集归属类型错误提示 */
    private static final String INVALID_OWNER_TYPE_MESSAGE = "作品集归属类型不正确";

    /** 草稿保存历史动作 */
    private static final String HISTORY_ACTION_DRAFT_SAVE = "DRAFT_SAVE";

    /** 发布历史动作 */
    private static final String HISTORY_ACTION_PUBLISH = "PUBLISH";

    /** SHA-256 算法名 */
    private static final String SHA_256_ALGORITHM = "SHA-256";

    /** 作品集 Mapper */
    private final PortfolioEntityMapper portfolioEntityMapper;

    /** 历史 Mapper */
    private final PortfolioHistoryEntityMapper portfolioHistoryEntityMapper;

    /** 引用 Mapper */
    private final PortfolioReferenceEntityMapper portfolioReferenceEntityMapper;

    /** 分享记录 Mapper */
    private final PortfolioShareRecordEntityMapper portfolioShareRecordEntityMapper;

    /** 档期 Mapper */
    private final ScheduleEntityMapper scheduleEntityMapper;

    /** 档位定义 Mapper */
    private final SlotDefinitionEntityMapper slotDefinitionEntityMapper;

    /** 积分服务 */
    private final PointService pointService;

    /** 标准作品集发布事务服务 */
    private final PortfolioPublishTransactionService portfolioPublishTransactionService;

    /** 配置校验器 */
    private final PortfolioConfigValidator portfolioConfigValidator;

    /** 作品集渲染服务 */
    private final PortfolioRenderService portfolioRenderService;

    /** 登录注册服务 */
    private final MiniappAuthService miniappAuthService;

    /** COS 服务 */
    private final CosService cosService;

    /** 团队作品集引用保护服务 */
    private final TeamPortfolioReferenceGuardService teamPortfolioReferenceGuardService;

    /** 内容数量上限服务 */
    private final ContentLimitService contentLimitService;

    /**
     * 查询作品集列表。
     *
     * @param ownerType 归属类型
     * @return 作品集列表
     */
    public MinePortfolioListResponse listPortfolios(String ownerType) {
        Long userId = AuthContextHolder.requireUserId();
        String normalizedOwnerType = hasText(ownerType) ? ownerType.strip() : PortfolioOwnerTypeDict.USER.getCode();
        if (PortfolioOwnerTypeDict.TEAM.getCode().equals(normalizedOwnerType)) {
            MinePortfolioListResponse response = new MinePortfolioListResponse();
            response.setPortfolios(List.of());
            return response;
        }
        if (!PortfolioOwnerTypeDict.USER.getCode().equals(normalizedOwnerType)) {
            throw new BusinessException(INVALID_OWNER_TYPE_MESSAGE);
        }
        List<PortfolioEntity> portfolios = portfolioEntityMapper.selectList(
                Wrappers.lambdaQuery(PortfolioEntity.class)
                        .eq(PortfolioEntity::getOwnerType, normalizedOwnerType)
                        .eq(PortfolioEntity::getOwnerId, userId)
                        .orderByDesc(PortfolioEntity::getUpdatedAt)
        );
        MinePortfolioListResponse response = new MinePortfolioListResponse();
        response.setPortfolios(safeList(portfolios).stream().map(this::buildListItem).toList());
        return response;
    }

    /**
     * 获取组件库说明。
     *
     * @return 组件库响应
     */
    public PortfolioComponentLibraryResponse getComponentLibrary() {
        PortfolioComponentLibraryResponse response = new PortfolioComponentLibraryResponse();
        response.setComponents(List.of(
                componentLibraryItem(PortfolioComponentTypeDict.PROFILE, "展示个人资料和服务标签"),
                componentLibraryItem(PortfolioComponentTypeDict.CAROUSEL, "首页轮播展示代表作品"),
                componentLibraryItem(PortfolioComponentTypeDict.TEXT_SECTION, "展示服务说明和补充文字"),
                componentLibraryItem(PortfolioComponentTypeDict.DIVIDER, "在内容之间加入可调高度的分割线"),
                componentLibraryItem(PortfolioComponentTypeDict.WORK_GRID, "双列展示图片和视频作品"),
                componentLibraryItem(PortfolioComponentTypeDict.WORK_LIST, "单列展示重点图片和视频作品"),
                componentLibraryItem(PortfolioComponentTypeDict.SCHEDULE_QUERY, "允许访客查询公开档期"),
                componentLibraryItem(PortfolioComponentTypeDict.CONTACT_FORM, "收集访客预留联系信息"),
                componentLibraryItem(PortfolioComponentTypeDict.QR_CONTACT, "展示微信二维码联系方式")
        ));
        return response;
    }

    /**
     * 创建标准个人作品集。
     *
     * @param request 创建请求
     * @return 作品集详情
     */
    @Transactional(rollbackFor = Exception.class)
    public MinePortfolioDetailResponse createStandardPersonal(MinePortfolioCreateRequest request) {
        Long userId = AuthContextHolder.requireUserId();
        contentLimitService.ensurePersonalPortfolioCapacity(userId);
        PortfolioConfigDto initialConfig = defaultConfig();
        String configJson = toJson(initialConfig);
        LocalDateTime now = LocalDateTime.now();
        PortfolioEntity portfolio = new PortfolioEntity();
        portfolio.setShareCode(generateShareCode());
        portfolio.setOwnerType(PortfolioOwnerTypeDict.USER.getCode());
        portfolio.setOwnerId(userId);
        portfolio.setTemplateType(PortfolioTemplateTypeDict.STANDARD.getCode());
        portfolio.setStatus(PortfolioStatusDict.ACTIVE.getCode());
        portfolio.setSchemaVersion(PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1);
        portfolio.setDraftConfigJson(configJson);
        portfolio.setDraftRevision(0);
        portfolio.setDraftContentHash(sha256(configJson));
        portfolio.setDraftSavedBy(userId);
        portfolio.setDraftSavedAt(now);
        portfolio.setPublishedRevision(0);
        portfolio.setPublicationStatus(PortfolioPublicationStatusDict.DRAFT_ONLY.getCode());
        portfolio.setSourceType(SOURCE_TYPE_MANUAL);
        portfolio.setContentHash(sha256(configJson));
        portfolio.setCurrentRevision(0);
        portfolio.setLastSavedBy(userId);
        portfolio.setLastSavedAt(now);
        portfolioEntityMapper.insert(portfolio);
        return buildDetail(portfolio, initialConfig);
    }

    /**
     * 创建作品集图片素材直传 COS 票据。
     *
     * @param portfolioId 作品集 ID
     * @param request 素材票据创建请求
     * @return 素材票据响应
     */
    public MinePortfolioAssetUploadTicketResponse createAssetUploadTicket(
            Long portfolioId,
            MinePortfolioAssetUploadTicketRequest request
    ) {
        Long userId = AuthContextHolder.requireUserId();
        PortfolioEntity portfolio = requireOwnedStandardPersonal(portfolioId);
        String assetType = normalizePortfolioAssetType(request);
        String contentType = normalizePortfolioAssetContentType(request, assetType);
        String uniqueCode = miniappAuthService.getUniqueCodeByUserId(userId);
        String objectKey = buildPortfolioAssetObjectKey(
                uniqueCode,
                portfolio.getId(),
                assetType,
                contentType,
                LocalDateTime.now()
        );
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(TICKET_EXPIRE_MINUTES);
        CosService.PostUploadTicket ticket = cosService.createPostUploadTicket(
                objectKey,
                contentType,
                PORTFOLIO_IMAGE_ASSET_MAX_BYTES,
                expiresAt
        );
        return buildAssetUploadTicketResponse(request, assetType, ticket);
    }

    /**
     * 获取维护详情。
     *
     * @param portfolioId 作品集 ID
     * @return 作品集详情
     */
    public MinePortfolioDetailResponse getDetail(Long portfolioId) {
        PortfolioEntity portfolio = requireOwnedStandardPersonal(portfolioId);
        return buildDetail(portfolio, parseConfig(portfolio.getDraftConfigJson()));
    }

    /**
     * 保存草稿。
     *
     * @param portfolioId 作品集 ID
     * @param request 保存请求
     * @return 作品集详情
     */
    @Transactional(rollbackFor = Exception.class)
    public MinePortfolioDetailResponse saveDraft(Long portfolioId, MinePortfolioDraftSaveRequest request) {
        Long userId = AuthContextHolder.requireUserId();
        PortfolioEntity portfolio = requireOwnedStandardPersonal(portfolioId);
        if (request == null || request.getConfig() == null) {
            throw new BusinessException(PortfolioMessage.DRAFT_CONFIG_REQUIRED_MESSAGE);
        }
        if (request.getClientRevision() != null && !request.getClientRevision().equals(safeInt(portfolio.getDraftRevision()))) {
            throw new BusinessException(PortfolioMessage.DRAFT_REVISION_CHANGED_SAVE_MESSAGE);
        }
        PortfolioConfigDto oldDraftConfig = parseConfig(portfolio.getDraftConfigJson());
        PortfolioConfigDto publishedConfig = parseConfig(portfolio.getPublishedConfigJson());
        PortfolioConfigDto normalized = portfolioConfigValidator.normalize(userId, request.getConfig());
        List<String> deletedObjectKeys = resolveUnreferencedAssetObjectKeys(
                userId,
                portfolio.getId(),
                oldDraftConfig,
                publishedConfig,
                normalized,
                publishedConfig
        );
        int nextDraftRevision = safeInt(portfolio.getDraftRevision()) + 1;
        int nextHistoryRevision = nextHistoryRevision(portfolio);
        String configJson = toJson(normalized);
        String hash = sha256(configJson);
        LocalDateTime now = LocalDateTime.now();

        portfolio.setDraftConfigJson(configJson);
        portfolio.setDraftRevision(nextDraftRevision);
        portfolio.setDraftContentHash(hash);
        portfolio.setDraftSavedBy(userId);
        portfolio.setDraftSavedAt(now);
        portfolio.setContentHash(hash);
        portfolio.setCurrentRevision(nextHistoryRevision);
        portfolio.setLastSavedBy(userId);
        portfolio.setLastSavedAt(now);
        if (!PortfolioPublicationStatusDict.PUBLISHED.getCode().equals(portfolio.getPublicationStatus())) {
            portfolio.setPublicationStatus(PortfolioPublicationStatusDict.DRAFT_ONLY.getCode());
        }
        int updated = portfolioEntityMapper.updateById(portfolio);
        if (updated != 1) {
            throw new BusinessException(PortfolioMessage.PORTFOLIO_CONCURRENT_UPDATE_MESSAGE);
        }
        rebuildReferences(portfolio.getId(), PortfolioConfigScopeDict.DRAFT.getCode(),
                portfolioConfigValidator.buildReferences(portfolio.getId(), userId, PortfolioConfigScopeDict.DRAFT.getCode(), normalized));
        insertHistory(portfolio, nextHistoryRevision, normalized, hash, userId, now, HISTORY_ACTION_DRAFT_SAVE);
        deleteUnreferencedAssetsAfterCommit(portfolio.getId(), deletedObjectKeys);
        return buildDetail(portfolio, normalized);
    }

    /**
     * 预览草稿。
     *
     * @param portfolioId 作品集 ID
     * @return 作品集详情
     */
    public MinePortfolioDetailResponse preview(Long portfolioId) {
        PortfolioEntity portfolio = requireOwnedStandardPersonal(portfolioId);
        PortfolioConfigDto config = parseConfig(portfolio.getDraftConfigJson());
        MinePortfolioDetailResponse response = buildDetail(portfolio, config);
        response.setRenderData(portfolioRenderService.render(portfolio, config, true, false, null, null));
        return response;
    }

    /**
     * 预览正式发布版本。
     *
     * @param portfolioId 作品集 ID
     * @return 作品集详情
     */
    public MinePortfolioDetailResponse previewPublished(Long portfolioId) {
        PortfolioEntity portfolio = requireOwnedStandardPersonal(portfolioId);
        if (!PortfolioPublicationStatusDict.PUBLISHED.getCode().equals(portfolio.getPublicationStatus())
                || !hasText(portfolio.getPublishedConfigJson())) {
            throw new BusinessException(PortfolioMessage.PORTFOLIO_UNAVAILABLE_MESSAGE);
        }
        PortfolioConfigDto config = parseConfig(portfolio.getPublishedConfigJson());
        MinePortfolioDetailResponse response = buildDetail(portfolio, config);
        response.setRenderData(portfolioRenderService.render(portfolio, config, true, false, null, null));
        return response;
    }

    /**
     * 预览已由调用方完成团队引用鉴权的个人作品集发布版本。
     *
     * @param portfolioId 个人作品集 ID
     * @return 作品集发布版本预览
     */
    public MinePortfolioDetailResponse previewPublishedReferencedPortfolio(Long portfolioId) {
        PortfolioEntity portfolio = requirePublishedStandardPersonal(portfolioId);
        PortfolioConfigDto config = parseConfig(portfolio.getPublishedConfigJson());
        MinePortfolioDetailResponse response = buildDetail(portfolio, config);
        response.setRenderData(portfolioRenderService.render(portfolio, config, true, false, null, null));
        return response;
    }

    /**
     * 查询预览页档期组件月历选项。
     *
     * @param portfolioId 作品集 ID
     * @param month 月份，格式 yyyy-MM
     * @param componentKey 组件实例键
     * @param scope 预览配置范围
     * @return 月历选项响应
     */
    public PortfolioScheduleOptionsResponse queryPreviewScheduleOptions(
            Long portfolioId,
            String month,
            String componentKey,
            String scope
    ) {
        PortfolioEntity portfolio = requireOwnedStandardPersonal(portfolioId);
        PortfolioConfigDto config = resolvePreviewConfig(portfolio, scope);
        requireScheduleComponentConfig(config, componentKey);
        return buildScheduleOptions(portfolio.getOwnerId(), parseMonth(month));
    }

    /**
     * 提交预览页档期查询。
     *
     * @param portfolioId 作品集 ID
     * @param request 查询请求
     * @param scope 预览配置范围
     * @return 查询响应
     */
    public PortfolioScheduleQueryResponse submitPreviewScheduleQuery(
            Long portfolioId,
            PortfolioScheduleQueryRequest request,
            String scope
    ) {
        if (request == null) {
            throw new BusinessException(PortfolioMessage.SCHEDULE_QUERY_REQUEST_REQUIRED_MESSAGE);
        }
        PortfolioEntity portfolio = requireOwnedStandardPersonal(portfolioId);
        PortfolioConfigDto config = resolvePreviewConfig(portfolio, scope);
        requireScheduleComponentConfig(config, request.getComponentKey());
        return buildScheduleQueryResponse(portfolio.getOwnerId(), request);
    }

    /**
     * 查询已由调用方完成团队引用鉴权的个人作品集发布版预览档期。
     */
    public PortfolioScheduleOptionsResponse queryPublishedReferencedPreviewScheduleOptions(
            Long portfolioId,
            String month,
            String componentKey
    ) {
        PortfolioEntity portfolio = requirePublishedStandardPersonal(portfolioId);
        PortfolioConfigDto config = parseConfig(portfolio.getPublishedConfigJson());
        requireScheduleComponentConfig(config, componentKey);
        return buildScheduleOptions(portfolio.getOwnerId(), parseMonth(month));
    }

    /**
     * 执行已由调用方完成团队引用鉴权的个人作品集发布版预览查档。
     */
    public PortfolioScheduleQueryResponse submitPublishedReferencedPreviewScheduleQuery(
            Long portfolioId,
            PortfolioScheduleQueryRequest request
    ) {
        if (request == null) {
            throw new BusinessException(PortfolioMessage.SCHEDULE_QUERY_REQUEST_REQUIRED_MESSAGE);
        }
        PortfolioEntity portfolio = requirePublishedStandardPersonal(portfolioId);
        PortfolioConfigDto config = parseConfig(portfolio.getPublishedConfigJson());
        requireScheduleComponentConfig(config, request.getComponentKey());
        return buildScheduleQueryResponse(portfolio.getOwnerId(), request);
    }

    /**
     * 发布草稿。
     *
     * @param portfolioId 作品集 ID
     * @param request 发布请求
     * @return 作品集详情
     */
    public MinePortfolioDetailResponse publish(Long portfolioId, MinePortfolioPublishRequest request) {
        Long userId = AuthContextHolder.requireUserId();
        PortfolioEntity portfolio = requireOwnedStandardPersonal(portfolioId);
        String idempotencyKey = validatePublishRequest(portfolio, request);
        pointService.assertCanConsume(
                userId,
                PointSceneCodeDict.MAINTAIN_STANDARD_PORTFOLIO.getCode(),
                POINT_BUSINESS_TYPE_PORTFOLIO,
                String.valueOf(portfolio.getId()),
                1,
                idempotencyKey
        );
        return portfolioPublishTransactionService.execute(
                () -> publishInTransaction(portfolioId, request, userId)
        );
    }

    /**
     * 在事务内重新校验并发布标准个人作品集。
     *
     * @param portfolioId 作品集 ID
     * @param request 发布请求
     * @param userId 当前发布者 ID
     * @return 作品集详情
     */
    private MinePortfolioDetailResponse publishInTransaction(
            Long portfolioId,
            MinePortfolioPublishRequest request,
            Long userId
    ) {
        PortfolioEntity portfolio = requireOwnedStandardPersonal(portfolioId);
        String idempotencyKey = validatePublishRequest(portfolio, request);
        String draftConfigJson = portfolio.getDraftConfigJson();
        PortfolioConfigDto currentDraftConfig = parseConfig(draftConfigJson);
        PortfolioConfigDto oldPublishedConfig = parseConfig(portfolio.getPublishedConfigJson());
        PortfolioConfigDto normalized = portfolioConfigValidator.normalize(userId, currentDraftConfig);
        List<String> deletedObjectKeys = resolveUnreferencedAssetObjectKeys(
                userId,
                portfolio.getId(),
                currentDraftConfig,
                oldPublishedConfig,
                currentDraftConfig,
                normalized
        );
        int nextPublishedRevision = safeInt(portfolio.getPublishedRevision()) + 1;
        int nextHistoryRevision = nextHistoryRevision(portfolio);
        String configJson = toJson(normalized);
        String hash = sha256(configJson);
        LocalDateTime now = LocalDateTime.now();
        portfolio.setPublishedConfigJson(configJson);
        portfolio.setPublishedRevision(nextPublishedRevision);
        portfolio.setPublishedContentHash(hash);
        portfolio.setPublishedBy(userId);
        portfolio.setPublishedAt(now);
        portfolio.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        portfolio.setContentHash(hash);
        portfolio.setCurrentRevision(nextHistoryRevision);
        portfolio.setLastSavedBy(userId);
        portfolio.setLastSavedAt(now);
        int updated = portfolioEntityMapper.updateById(portfolio);
        if (updated != 1) {
            throw new BusinessException(PortfolioMessage.PORTFOLIO_CONCURRENT_UPDATE_MESSAGE);
        }
        rebuildReferences(portfolio.getId(), PortfolioConfigScopeDict.PUBLISHED.getCode(),
                portfolioConfigValidator.buildReferences(portfolio.getId(), userId, PortfolioConfigScopeDict.PUBLISHED.getCode(), normalized));
        insertHistory(portfolio, nextHistoryRevision, normalized, hash, userId, now, HISTORY_ACTION_PUBLISH);
        pointService.consume(
                userId,
                PointSceneCodeDict.MAINTAIN_STANDARD_PORTFOLIO.getCode(),
                POINT_BUSINESS_TYPE_PORTFOLIO,
                String.valueOf(portfolio.getId()),
                1,
                idempotencyKey,
                PUBLISH_POINT_REMARK
        );
        deleteUnreferencedAssetsAfterCommit(portfolio.getId(), deletedObjectKeys);
        return buildDetail(portfolio, normalized);
    }

    /**
     * 校验个人作品集发布请求并返回规范化幂等键。
     *
     * @param portfolio 作品集
     * @param request 发布请求
     * @return 规范化幂等键
     */
    private String validatePublishRequest(PortfolioEntity portfolio, MinePortfolioPublishRequest request) {
        if (request == null || request.getDraftRevision() == null) {
            throw new BusinessException(PortfolioMessage.PUBLISH_DRAFT_REVISION_REQUIRED_MESSAGE);
        }
        if (!request.getDraftRevision().equals(safeInt(portfolio.getDraftRevision()))) {
            throw new BusinessException(PortfolioMessage.DRAFT_REVISION_CHANGED_PUBLISH_MESSAGE);
        }
        String draftConfigJson = portfolio.getDraftConfigJson();
        if (!hasText(draftConfigJson)) {
            throw new BusinessException(PortfolioMessage.DRAFT_SAVE_REQUIRED_MESSAGE);
        }
        parseConfig(draftConfigJson);
        return normalizeRequiredString(
                request.getIdempotencyKey(),
                PortfolioMessage.PUBLISH_IDEMPOTENCY_REQUIRED_MESSAGE
        );
    }

    /**
     * 创建分享记录。
     *
     * @param portfolioId 作品集 ID
     * @param request 分享记录请求
     */
    public void createShareRecord(Long portfolioId, MinePortfolioShareRecordRequest request) {
        Long userId = AuthContextHolder.requireUserId();
        PortfolioEntity portfolio = requireOwnedStandardPersonal(portfolioId);
        PortfolioShareRecordEntity record = new PortfolioShareRecordEntity();
        record.setPortfolioId(portfolio.getId());
        record.setPortfolioRevision(safeInt(portfolio.getPublishedRevision()));
        record.setSharedByUserId(userId);
        record.setOwnerType(portfolio.getOwnerType());
        record.setOwnerId(portfolio.getOwnerId());
        String shareChannel = ShareChannelDict.normalizeCode(normalizeRequiredString(
                request == null ? null : request.getShareChannel(),
                PortfolioMessage.SHARE_CHANNEL_REQUIRED_MESSAGE
        ));
        if (ShareChannelDict.fromCode(shareChannel) == null) {
            throw new BusinessException(PortfolioMessage.SHARE_CHANNEL_UNSUPPORTED_MESSAGE);
        }
        record.setShareChannel(shareChannel);
        record.setShareScene(defaultString(request == null ? null : request.getShareScene()));
        portfolioShareRecordEntityMapper.insert(record);
    }

    /**
     * 删除标准个人作品集。
     *
     * @param portfolioId 作品集 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void deletePortfolio(Long portfolioId) {
        Long userId = AuthContextHolder.requireUserId();
        PortfolioEntity portfolio = requireOwnedStandardPersonal(portfolioId);
        teamPortfolioReferenceGuardService.assertPersonalPortfolioNotReferenced(portfolio.getId());
        List<String> deletedObjectKeys = resolveDeletedPortfolioAssetObjectKeys(userId, portfolio);
        portfolioReferenceEntityMapper.delete(
                Wrappers.lambdaQuery(PortfolioReferenceEntity.class)
                        .eq(PortfolioReferenceEntity::getPortfolioId, portfolio.getId())
        );
        LocalDateTime now = LocalDateTime.now();
        int updated = portfolioEntityMapper.update(
                new PortfolioEntity(),
                new UpdateWrapper<PortfolioEntity>()
                        .set(PORTFOLIO_COLUMN_DELETED_AT, now)
                        .set(PORTFOLIO_COLUMN_DELETED, portfolio.getId())
                        .eq(PORTFOLIO_COLUMN_ID, portfolio.getId())
                        .eq(PORTFOLIO_COLUMN_OWNER_TYPE, PortfolioOwnerTypeDict.USER.getCode())
                        .eq(PORTFOLIO_COLUMN_OWNER_ID, userId)
                        .eq(PORTFOLIO_COLUMN_DELETED, 0L)
        );
        if (updated <= 0) {
            throw new BusinessException(PortfolioMessage.PORTFOLIO_DELETE_FAILED_MESSAGE);
        }
        deletePortfolioAssetsAfterCommit(portfolio.getId(), deletedObjectKeys);
    }

    /**
     * 校验作品集归属和模板。
     *
     * @param portfolioId 作品集 ID
     * @return 作品集实体
     */
    private PortfolioEntity requireOwnedStandardPersonal(Long portfolioId) {
        Long userId = AuthContextHolder.requireUserId();
        PortfolioEntity portfolio = portfolioEntityMapper.selectById(portfolioId);
        if (portfolio == null || !PortfolioOwnerTypeDict.USER.getCode().equals(portfolio.getOwnerType())
                || !userId.equals(portfolio.getOwnerId())) {
            throw new BusinessException(PortfolioMessage.PORTFOLIO_NOT_FOUND_MESSAGE);
        }
        if (!PortfolioTemplateTypeDict.STANDARD.getCode().equals(portfolio.getTemplateType())) {
            throw new BusinessException(PortfolioMessage.PORTFOLIO_MAINTENANCE_UNAVAILABLE_MESSAGE);
        }
        return portfolio;
    }

    /** 校验团队引用预览目标仍为有效的标准个人作品集发布版本。 */
    private PortfolioEntity requirePublishedStandardPersonal(Long portfolioId) {
        PortfolioEntity portfolio = portfolioEntityMapper.selectById(portfolioId);
        if (portfolio == null
                || !PortfolioOwnerTypeDict.USER.getCode().equals(portfolio.getOwnerType())
                || !PortfolioTemplateTypeDict.STANDARD.getCode().equals(portfolio.getTemplateType())
                || !PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1.equals(portfolio.getSchemaVersion())
                || !PortfolioStatusDict.ACTIVE.getCode().equals(portfolio.getStatus())
                || !PortfolioPublicationStatusDict.PUBLISHED.getCode().equals(portfolio.getPublicationStatus())
                || !hasText(portfolio.getPublishedConfigJson())) {
            throw new BusinessException(PortfolioMessage.PORTFOLIO_UNAVAILABLE_MESSAGE);
        }
        return portfolio;
    }

    /**
     * 重建指定作用域引用。
     *
     * @param portfolioId 作品集 ID
     * @param configScope 配置作用域
     * @param references 引用列表
     */
    private void rebuildReferences(Long portfolioId, String configScope, List<PortfolioReferenceEntity> references) {
        portfolioReferenceEntityMapper.delete(
                Wrappers.lambdaQuery(PortfolioReferenceEntity.class)
                        .eq(PortfolioReferenceEntity::getPortfolioId, portfolioId)
                        .eq(PortfolioReferenceEntity::getConfigScope, configScope)
        );
        safeList(references).forEach(portfolioReferenceEntityMapper::insert);
    }

    /**
     * 写入历史快照。
     *
     * @param portfolio 作品集
     * @param revision 修订号
     * @param config 配置
     * @param hash 内容哈希
     * @param userId 保存人
     * @param savedAt 保存时间
     * @param actionType 动作类型
     */
    private void insertHistory(
            PortfolioEntity portfolio,
            int revision,
            PortfolioConfigDto config,
            String hash,
            Long userId,
            LocalDateTime savedAt,
            String actionType
    ) {
        PortfolioHistoryEntity history = new PortfolioHistoryEntity();
        history.setPortfolioId(portfolio.getId());
        history.setRevisionNo(revision);
        history.setSchemaVersion(PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1);
        history.setSnapshotJson(toJson(Map.of("actionType", actionType, "config", config)));
        history.setSourceType(SOURCE_TYPE_MANUAL);
        history.setContentHash(hash);
        history.setSavedBy(userId);
        history.setSavedAt(savedAt);
        portfolioHistoryEntityMapper.insert(history);
    }

    /**
     * 计算下一条历史快照的全局修订号，避免草稿版本与发布版本各自从 1 开始时冲突。
     *
     * @param portfolio 作品集
     * @return 下一条历史修订号
     */
    private int nextHistoryRevision(PortfolioEntity portfolio) {
        return Math.max(
                safeInt(portfolio.getCurrentRevision()),
                Math.max(safeInt(portfolio.getDraftRevision()), safeInt(portfolio.getPublishedRevision()))
        ) + 1;
    }

    /**
     * 构建详情响应。
     *
     * @param portfolio 作品集
     * @param config 配置
     * @return 详情响应
     */
    private MinePortfolioDetailResponse buildDetail(PortfolioEntity portfolio, PortfolioConfigDto config) {
        MinePortfolioDetailResponse response = new MinePortfolioDetailResponse();
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

    /**
     * 构建列表项。
     *
     * @param portfolio 作品集实体
     * @return 列表项
     */
    private MinePortfolioListResponse.Item buildListItem(PortfolioEntity portfolio) {
        PortfolioConfigDto config = resolveListConfig(portfolio);
        MinePortfolioListResponse.Item item = new MinePortfolioListResponse.Item();
        item.setPortfolioId(portfolio.getId());
        item.setShareCode(portfolio.getShareCode());
        item.setTitle(resolveTitle(config));
        item.setCoverUrl(resolveCoverUrl(config));
        item.setOwnerType(portfolio.getOwnerType());
        item.setTemplateType(portfolio.getTemplateType());
        item.setPublicationStatus(portfolio.getPublicationStatus());
        item.setDraftRevision(safeInt(portfolio.getDraftRevision()));
        item.setPublishedRevision(safeInt(portfolio.getPublishedRevision()));
        item.setUpdatedAt(portfolio.getUpdatedAt());
        return item;
    }

    /**
     * 构建组件库项。
     *
     * @param componentType 组件类型
     * @param description 说明
     * @return 组件库项
     */
    private PortfolioComponentLibraryResponse.ComponentItem componentLibraryItem(
            PortfolioComponentTypeDict componentType,
            String description
    ) {
        PortfolioComponentLibraryResponse.ComponentItem item = new PortfolioComponentLibraryResponse.ComponentItem();
        item.setComponentType(componentType.getCode());
        item.setName(componentType.getDisplayName());
        item.setDescription(description);
        return item;
    }

    /**
     * 解析作品集标题。
     *
     * @param config 作品集配置
     * @return 标题
     */
    private String resolveTitle(PortfolioConfigDto config) {
        if (config != null && config.getShare() != null && hasText(config.getShare().getTitle())) {
            return config.getShare().getTitle();
        }
        return UNTITLED_PORTFOLIO_TITLE;
    }

    /**
     * 解析作品集封面地址。
     *
     * @param config 作品集配置
     * @return 封面地址
     */
    private String resolveCoverUrl(PortfolioConfigDto config) {
        if (config != null && config.getShare() != null && hasText(config.getShare().getCoverUrl())) {
            return config.getShare().getCoverUrl();
        }
        return "";
    }

    /**
     * 规范化作品集素材类型。
     *
     * @param request 素材票据请求
     * @return 素材类型
     */
    private String normalizePortfolioAssetType(MinePortfolioAssetUploadTicketRequest request) {
        String assetType = request == null ? "" : defaultString(request.getAssetType()).toUpperCase(Locale.ROOT);
        if (ASSET_TYPE_COVER.equals(assetType)
                || ASSET_TYPE_PROFILE_AVATAR.equals(assetType)
                || ASSET_TYPE_QR_CONTACT.equals(assetType)) {
            return assetType;
        }
        throw new BusinessException(PortfolioMessage.PORTFOLIO_ASSET_TYPE_UNSUPPORTED_MESSAGE);
    }

    /**
     * 规范化作品集图片素材 MIME 类型并校验大小。
     *
     * @param request 素材票据请求
     * @param assetType 素材类型
     * @return 允许写入 COS policy 的 MIME 类型
     */
    private String normalizePortfolioAssetContentType(
            MinePortfolioAssetUploadTicketRequest request,
            String assetType
    ) {
        if (request == null || request.getFileSize() == null || request.getFileSize() <= 0L) {
            throw new BusinessException(resolveAssetRequiredMessage(assetType));
        }
        if (request.getFileSize() > PORTFOLIO_IMAGE_ASSET_MAX_BYTES) {
            throw new BusinessException(resolveAssetSizeLimitMessage(assetType));
        }
        String mimeType = defaultString(request.getMimeType()).toLowerCase(Locale.ROOT);
        if (MIME_IMAGE_JPEG.equals(mimeType) || MIME_IMAGE_JPG.equals(mimeType)) {
            return MIME_IMAGE_JPEG;
        }
        if (MIME_IMAGE_PNG.equals(mimeType)) {
            return MIME_IMAGE_PNG;
        }
        throw new BusinessException(resolveAssetFormatUnsupportedMessage(assetType));
    }

    /**
     * 解析素材为空提示。
     *
     * @param assetType 素材类型
     * @return 提示文案
     */
    private String resolveAssetRequiredMessage(String assetType) {
        if (ASSET_TYPE_PROFILE_AVATAR.equals(assetType)) {
            return PortfolioMessage.PROFILE_AVATAR_REQUIRED_MESSAGE;
        }
        return PortfolioMessage.COVER_REQUIRED_MESSAGE;
    }

    /**
     * 解析素材大小超限提示。
     *
     * @param assetType 素材类型
     * @return 提示文案
     */
    private String resolveAssetSizeLimitMessage(String assetType) {
        if (ASSET_TYPE_PROFILE_AVATAR.equals(assetType)) {
            return PortfolioMessage.PROFILE_AVATAR_SIZE_LIMIT_MESSAGE;
        }
        return PortfolioMessage.COVER_SIZE_LIMIT_MESSAGE;
    }

    /**
     * 解析素材格式不支持提示。
     *
     * @param assetType 素材类型
     * @return 提示文案
     */
    private String resolveAssetFormatUnsupportedMessage(String assetType) {
        if (ASSET_TYPE_PROFILE_AVATAR.equals(assetType)) {
            return PortfolioMessage.PROFILE_AVATAR_FORMAT_UNSUPPORTED_MESSAGE;
        }
        return PortfolioMessage.COVER_FORMAT_UNSUPPORTED_MESSAGE;
    }

    /**
     * 构建作品集图片素材对象键。
     *
     * @param uniqueCode 用户唯一码
     * @param portfolioId 作品集 ID
     * @param assetType 素材类型
     * @param contentType MIME 类型
     * @param now 当前时间
     * @return COS 对象键
     */
    private String buildPortfolioAssetObjectKey(
            String uniqueCode,
            Long portfolioId,
            String assetType,
            String contentType,
            LocalDateTime now
    ) {
        String extension = MIME_IMAGE_PNG.equals(contentType) ? COVER_EXTENSION_PNG : COVER_EXTENSION_JPG;
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, COVER_RANDOM_LENGTH);
        String fileName = resolveAssetFilePrefix(assetType)
                + "-" + portfolioId
                + "-" + now.format(COVER_FILE_TIME_FORMATTER)
                + "-" + random
                + "." + extension;
        return uniqueCode + "/" + PORTFOLIO_ASSET_FOLDER + "/" + fileName;
    }

    /**
     * 解析素材文件名前缀。
     *
     * @param assetType 素材类型
     * @return 文件名前缀
     */
    private String resolveAssetFilePrefix(String assetType) {
        if (ASSET_TYPE_PROFILE_AVATAR.equals(assetType)) {
            return PROFILE_AVATAR_FILE_PREFIX;
        }
        if (ASSET_TYPE_QR_CONTACT.equals(assetType)) {
            return QR_CONTACT_FILE_PREFIX;
        }
        return COVER_FILE_PREFIX;
    }

    /**
     * 构建图片素材直传票据响应。
     *
     * @param request 前端请求
     * @param assetType 素材类型
     * @param ticket COS 直传票据
     * @return 素材票据响应
     */
    private MinePortfolioAssetUploadTicketResponse buildAssetUploadTicketResponse(
            MinePortfolioAssetUploadTicketRequest request,
            String assetType,
            CosService.PostUploadTicket ticket
    ) {
        MinePortfolioAssetUploadTicketResponse response = new MinePortfolioAssetUploadTicketResponse();
        response.setClientId(request.getClientId());
        response.setAssetType(assetType);
        response.setObjectKey(ticket.objectKey());
        response.setPublicUrl(cosService.publicUrl(ticket.objectKey()));
        response.setUploadUrl(ticket.uploadUrl());
        response.setContentType(ticket.contentType());
        response.setFormData(ticket.formData());
        response.setExpiresAt(ticket.expiresAt());
        response.setMaxBytes(ticket.maxBytes());
        return response;
    }

    /**
     * 解析删除作品集后需要清理的当前作品集图片素材对象键。
     *
     * @param userId 用户 ID
     * @param portfolio 作品集
     * @return 需要删除的 COS 对象键列表
     */
    private List<String> resolveDeletedPortfolioAssetObjectKeys(Long userId, PortfolioEntity portfolio) {
        String uniqueCode = miniappAuthService.getUniqueCodeByUserId(userId);
        Set<String> objectKeys = new LinkedHashSet<>();
        objectKeys.addAll(collectOwnedPortfolioAssetObjectKeys(
                parseConfig(portfolio.getDraftConfigJson()),
                uniqueCode,
                portfolio.getId()
        ));
        objectKeys.addAll(collectOwnedPortfolioAssetObjectKeys(
                parseConfig(portfolio.getPublishedConfigJson()),
                uniqueCode,
                portfolio.getId()
        ));
        return new ArrayList<>(objectKeys);
    }

    /**
     * 解析更新后不再被当前草稿态或发布态引用的作品集图片素材对象键。
     *
     * @param userId 用户 ID
     * @param portfolioId 作品集 ID
     * @param beforeDraftConfig 更新前草稿配置
     * @param beforePublishedConfig 更新前发布配置
     * @param afterDraftConfig 更新后草稿配置
     * @param afterPublishedConfig 更新后发布配置
     * @return 需要删除的 COS 对象键列表
     */
    private List<String> resolveUnreferencedAssetObjectKeys(
            Long userId,
            Long portfolioId,
            PortfolioConfigDto beforeDraftConfig,
            PortfolioConfigDto beforePublishedConfig,
            PortfolioConfigDto afterDraftConfig,
            PortfolioConfigDto afterPublishedConfig
    ) {
        String uniqueCode = miniappAuthService.getUniqueCodeByUserId(userId);
        Set<String> beforeObjectKeys = collectOwnedPortfolioAssetObjectKeys(
                beforeDraftConfig, uniqueCode, portfolioId);
        beforeObjectKeys.addAll(collectOwnedPortfolioAssetObjectKeys(
                beforePublishedConfig, uniqueCode, portfolioId));
        Set<String> afterObjectKeys = collectOwnedPortfolioAssetObjectKeys(
                afterDraftConfig, uniqueCode, portfolioId);
        afterObjectKeys.addAll(collectOwnedPortfolioAssetObjectKeys(
                afterPublishedConfig, uniqueCode, portfolioId));
        beforeObjectKeys.removeAll(afterObjectKeys);
        return new ArrayList<>(beforeObjectKeys);
    }

    /**
     * 收集配置中当前作品集拥有的图片素材对象键。
     *
     * @param config 作品集配置
     * @param uniqueCode 用户唯一码
     * @param portfolioId 作品集 ID
     * @return 去重且保序的对象键
     */
    private Set<String> collectOwnedPortfolioAssetObjectKeys(
            PortfolioConfigDto config,
            String uniqueCode,
            Long portfolioId
    ) {
        Set<String> objectKeys = new LinkedHashSet<>();
        if (config == null) {
            return objectKeys;
        }
        if (config.getShare() != null) {
            addOwnedPortfolioAssetObjectKey(objectKeys, config.getShare().getCoverUrl(), uniqueCode, portfolioId);
            addOwnedPortfolioAssetObjectKey(objectKeys, config.getShare().getAvatarUrl(), uniqueCode, portfolioId);
        }
        for (PortfolioConfigDto.Component component : safeList(config.getComponents())) {
            if (component == null) {
                continue;
            }
            Map<String, Object> componentConfig = component.getConfig() == null ? Map.of() : component.getConfig();
            if (PortfolioComponentTypeDict.PROFILE.getCode().equals(component.getComponentType())) {
                Map<String, Object> profileConfig = asObjectMap(componentConfig.get(CONFIG_KEY_PROFILE));
                addOwnedPortfolioAssetObjectKey(objectKeys, asString(profileConfig.get(CONFIG_KEY_AVATAR_URL)), uniqueCode, portfolioId);
                addOwnedPortfolioAssetObjectKey(objectKeys, asString(profileConfig.get(CONFIG_KEY_WECHAT_QR_URL)), uniqueCode, portfolioId);
            }
            if (PortfolioComponentTypeDict.QR_CONTACT.getCode().equals(component.getComponentType())) {
                addOwnedPortfolioAssetObjectKey(objectKeys, asString(componentConfig.get(CONFIG_KEY_QR_URL)), uniqueCode, portfolioId);
            }
        }
        return objectKeys;
    }

    /**
     * 有归属匹配时加入图片素材对象键。
     *
     * @param objectKeys 对象键集合
     * @param value URL 或对象键
     * @param uniqueCode 用户唯一码
     * @param portfolioId 作品集 ID
     */
    private void addOwnedPortfolioAssetObjectKey(
            Set<String> objectKeys,
            String value,
            String uniqueCode,
            Long portfolioId
    ) {
        String objectKey = extractOwnedPortfolioAssetObjectKey(value, uniqueCode, portfolioId);
        if (hasText(objectKey)) {
            objectKeys.add(objectKey);
        }
    }

    /**
     * 从 URL 或对象键中提取当前作品集拥有的图片素材对象键。
     *
     * @param value 素材 URL 或对象键
     * @param uniqueCode 用户唯一码
     * @param portfolioId 作品集 ID
     * @return 匹配到的对象键；未匹配时为空
     */
    private String extractOwnedPortfolioAssetObjectKey(String value, String uniqueCode, Long portfolioId) {
        if (!hasText(value) || !hasText(uniqueCode) || portfolioId == null) {
            return "";
        }
        String patternText = String.format(
                PORTFOLIO_ASSET_OBJECT_KEY_PATTERN_TEMPLATE,
                Pattern.quote(uniqueCode),
                portfolioId
        );
        Matcher matcher = Pattern.compile(patternText).matcher(value);
        if (!matcher.find()) {
            return "";
        }
        String objectKey = matcher.group();
        if (value.equals(objectKey) || value.equals(cosService.publicUrl(objectKey))) {
            return objectKey;
        }
        return "";
    }

    /**
     * 更新事务提交后删除不再被当前草稿态或发布态引用的图片素材。
     *
     * @param portfolioId 作品集 ID
     * @param objectKeys COS 对象键列表
     */
    private void deleteUnreferencedAssetsAfterCommit(Long portfolioId, List<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()) {
            return;
        }
        Runnable deleteTask = () -> {
            for (String objectKey : objectKeys) {
                try {
                    cosService.delete(objectKey);
                } catch (Exception e) {
                    log.warn("作品集无引用图片素材删除失败: portfolioId={}, objectKey={}", portfolioId, objectKey, e);
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteTask.run();
                }
            });
            return;
        }
        deleteTask.run();
    }

    /**
     * 删除事务提交后清理当前作品集图片素材。
     *
     * @param portfolioId 作品集 ID
     * @param objectKeys COS 对象键列表
     */
    private void deletePortfolioAssetsAfterCommit(Long portfolioId, List<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()) {
            return;
        }
        Runnable deleteTask = () -> {
            for (String objectKey : objectKeys) {
                try {
                    cosService.delete(objectKey);
                } catch (Exception e) {
                    log.warn("作品集图片素材删除失败: portfolioId={}, objectKey={}", portfolioId, objectKey, e);
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteTask.run();
                }
            });
            return;
        }
        deleteTask.run();
    }

    /**
     * 转换为字符串键 Map。
     *
     * @param value 原始值
     * @return 字符串键 Map
     */
    private Map<String, Object> asObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    /**
     * 转换为字符串。
     *
     * @param value 原始值
     * @return 字符串
     */
    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    /**
     * 解析预览配置。
     *
     * @param portfolio 作品集
     * @param scope 预览范围
     * @return 作品集配置
     */
    private PortfolioConfigDto resolvePreviewConfig(PortfolioEntity portfolio, String scope) {
        if (PREVIEW_SCOPE_PUBLISHED.equals(scope)) {
            if (!PortfolioPublicationStatusDict.PUBLISHED.getCode().equals(portfolio.getPublicationStatus())
                    || !hasText(portfolio.getPublishedConfigJson())) {
                throw new BusinessException(PortfolioMessage.PORTFOLIO_UNAVAILABLE_MESSAGE);
            }
            return parseConfig(portfolio.getPublishedConfigJson());
        }
        return parseConfig(portfolio.getDraftConfigJson());
    }

    /**
     * 校验档期查询组件存在。
     *
     * @param config 作品集配置
     * @param componentKey 组件实例键
     */
    private void requireScheduleComponentConfig(PortfolioConfigDto config, String componentKey) {
        safeList(config == null ? null : config.getComponents()).stream()
                .filter(component -> component != null && Boolean.TRUE.equals(component.getEnabled()))
                .filter(component -> COMPONENT_TYPE_SCHEDULE_QUERY.equals(component.getComponentType()))
                .filter(component -> Objects.equals(component.getComponentKey(), componentKey))
                .findFirst()
                .orElseThrow(() -> new BusinessException(PortfolioMessage.SCHEDULE_QUERY_COMPONENT_NOT_FOUND_MESSAGE));
    }

    /**
     * 构建预览档期选项。
     *
     * @param ownerId 作品集归属用户 ID
     * @param yearMonth 月份
     * @return 档期选项
     */
    private PortfolioScheduleOptionsResponse buildScheduleOptions(Long ownerId, YearMonth yearMonth) {
        LocalDate monthStart = yearMonth.atDay(1);
        LocalDate monthEnd = yearMonth.atEndOfMonth();
        List<SlotDefinitionEntity> slots = safeList(slotDefinitionEntityMapper.selectList(
                Wrappers.lambdaQuery(SlotDefinitionEntity.class)
                        .eq(SlotDefinitionEntity::getUserId, ownerId)
                        .eq(SlotDefinitionEntity::getStatus, SlotDefinitionStatusDict.ACTIVE.getCode())
                        .orderByAsc(SlotDefinitionEntity::getStartTime)
                        .orderByAsc(SlotDefinitionEntity::getId)
        ));
        List<ScheduleEntity> schedules = safeList(scheduleEntityMapper.selectList(
                Wrappers.lambdaQuery(ScheduleEntity.class)
                        .eq(ScheduleEntity::getUserId, ownerId)
                        .ge(ScheduleEntity::getScheduleDate, monthStart)
                        .le(ScheduleEntity::getScheduleDate, monthEnd)
                        .orderByAsc(ScheduleEntity::getScheduleDate)
                        .orderByAsc(ScheduleEntity::getStartTimeSnapshot)
        ));
        PortfolioScheduleOptionsResponse response = new PortfolioScheduleOptionsResponse();
        response.setYearMonth(yearMonth.format(MONTH_FORMATTER));
        response.setSlotDefinitions(slots.stream().map(this::buildSlotDefinitionItem).toList());
        response.setSchedules(schedules.stream().map(this::buildScheduleOptionItem).toList());
        response.setDays(buildMonthDays(yearMonth, schedules));
        return response;
    }

    /**
     * 构建预览档期查询结果。
     *
     * @param ownerId 作品集归属用户 ID
     * @param request 查询请求
     * @return 查询结果
     */
    private PortfolioScheduleQueryResponse buildScheduleQueryResponse(Long ownerId, PortfolioScheduleQueryRequest request) {
        if (request == null || request.getQueriedDate() == null) {
            throw new BusinessException(PortfolioMessage.SCHEDULE_QUERY_DATE_REQUIRED_MESSAGE);
        }
        if (request.getSlotDefinitionId() == null) {
            throw new BusinessException(PortfolioMessage.SCHEDULE_QUERY_SLOT_REQUIRED_MESSAGE);
        }
        SlotDefinitionEntity slot = slotDefinitionEntityMapper.selectById(request.getSlotDefinitionId());
        if (slot == null
                || !Objects.equals(slot.getUserId(), ownerId)
                || !SlotDefinitionStatusDict.ACTIVE.getCode().equals(slot.getStatus())) {
            throw new BusinessException(PortfolioMessage.SCHEDULE_QUERY_SLOT_UNAVAILABLE_MESSAGE);
        }
        ScheduleEntity schedule = scheduleEntityMapper.selectOne(
                Wrappers.lambdaQuery(ScheduleEntity.class)
                        .eq(ScheduleEntity::getUserId, ownerId)
                        .eq(ScheduleEntity::getScheduleDate, request.getQueriedDate())
                        .eq(ScheduleEntity::getSlotDefinitionId, request.getSlotDefinitionId())
                        .last("LIMIT 1")
        );
        return buildScheduleQueryResponse(request.getQueriedDate(), slot, schedule);
    }

    /**
     * 构建档期查询结果。
     *
     * @param queriedDate 查询日期
     * @param slot 档位定义
     * @param schedule 档期记录
     * @return 查询结果
     */
    private PortfolioScheduleQueryResponse buildScheduleQueryResponse(
            LocalDate queriedDate,
            SlotDefinitionEntity slot,
            ScheduleEntity schedule
    ) {
        ScheduleStatusDict status = schedule == null ? null : ScheduleStatusDict.fromCode(schedule.getStatus());
        boolean booked = schedule != null && ScheduleStatusDict.BOOKED.getCode().equals(schedule.getStatus());
        PortfolioScheduleQueryResponse response = new PortfolioScheduleQueryResponse();
        response.setQueriedDate(queriedDate.toString());
        response.setSlotDefinitionId(slot.getId());
        response.setSlotName(schedule == null ? slot.getName() : schedule.getSlotNameSnapshot());
        response.setStartTime(formatTime(schedule == null ? slot.getStartTime() : schedule.getStartTimeSnapshot()));
        response.setEndTime(formatTime(schedule == null ? slot.getEndTime() : schedule.getEndTimeSnapshot()));
        response.setColor(schedule == null ? slot.getColor() : schedule.getColorSnapshot());
        response.setStatus(schedule == null ? STATUS_AVAILABLE : schedule.getStatus());
        response.setStatusText(schedule == null ? MESSAGE_AVAILABLE : status == null ? schedule.getStatus() : status.getDisplayName());
        response.setAvailable(!booked);
        response.setMessage(booked ? MESSAGE_BOOKED : MESSAGE_AVAILABLE);
        return response;
    }

    /**
     * 构建档位定义响应项。
     *
     * @param slot 档位定义
     * @return 响应项
     */
    private PortfolioScheduleOptionsResponse.SlotDefinitionItem buildSlotDefinitionItem(SlotDefinitionEntity slot) {
        PortfolioScheduleOptionsResponse.SlotDefinitionItem item = new PortfolioScheduleOptionsResponse.SlotDefinitionItem();
        item.setId(slot.getId());
        item.setName(slot.getName());
        item.setStartTime(formatTime(slot.getStartTime()));
        item.setEndTime(formatTime(slot.getEndTime()));
        item.setColor(slot.getColor());
        return item;
    }

    /**
     * 构建访客可见档期响应项。
     *
     * @param schedule 档期
     * @return 响应项
     */
    private PortfolioScheduleOptionsResponse.ScheduleItem buildScheduleOptionItem(ScheduleEntity schedule) {
        String statusCode = defaultString(schedule.getStatus());
        ScheduleStatusDict status = ScheduleStatusDict.fromCode(statusCode);
        PortfolioScheduleOptionsResponse.ScheduleItem item = new PortfolioScheduleOptionsResponse.ScheduleItem();
        item.setDate(schedule.getScheduleDate() == null ? "" : schedule.getScheduleDate().toString());
        item.setSlotDefinitionId(schedule.getSlotDefinitionId());
        item.setSlotName(defaultString(schedule.getSlotNameSnapshot()));
        item.setStartTime(formatTime(schedule.getStartTimeSnapshot()));
        item.setEndTime(formatTime(schedule.getEndTimeSnapshot()));
        item.setColor(defaultString(schedule.getColorSnapshot()));
        item.setStatus(statusCode);
        item.setStatusText(status == null ? statusCode : status.getDisplayName());
        item.setStatusTone(status == null ? "muted" : status.getTone());
        return item;
    }

    /**
     * 构建固定 6 行月历格子。
     *
     * @param yearMonth 月份
     * @param schedules 当月档期
     * @return 月历格子
     */
    private List<PortfolioScheduleOptionsResponse.MonthDayItem> buildMonthDays(
            YearMonth yearMonth,
            List<ScheduleEntity> schedules
    ) {
        LocalDate firstDay = yearMonth.atDay(1);
        LocalDate cursor = firstDay.minusDays(firstDay.getDayOfWeek().getValue() % 7L);
        Map<LocalDate, List<ScheduleEntity>> schedulesByDate = safeList(schedules).stream()
                .filter(schedule -> schedule.getScheduleDate() != null)
                .collect(Collectors.groupingBy(ScheduleEntity::getScheduleDate));
        List<PortfolioScheduleOptionsResponse.MonthDayItem> days = new ArrayList<>();
        for (int index = 0; index < 42; index++) {
            LocalDate date = cursor.plus(index, ChronoUnit.DAYS);
            List<ScheduleEntity> daySchedules = schedulesByDate.getOrDefault(date, List.of());
            PortfolioScheduleOptionsResponse.MonthDayItem item = new PortfolioScheduleOptionsResponse.MonthDayItem();
            item.setDate(date.toString());
            item.setDayNumber(date.getDayOfMonth());
            item.setCurrentMonth(YearMonth.from(date).equals(yearMonth));
            item.setColors(daySchedules.stream()
                    .map(ScheduleEntity::getColorSnapshot)
                    .filter(this::hasText)
                    .distinct()
                    .toList());
            item.setCount(daySchedules.size());
            days.add(item);
        }
        return days;
    }

    /**
     * 解析月份。
     *
     * @param month 月份文本
     * @return 月份
     */
    private YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month, MONTH_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new BusinessException(PortfolioMessage.SCHEDULE_QUERY_DATE_INVALID_MESSAGE, e);
        }
    }

    /**
     * 格式化时间。
     *
     * @param time 时间
     * @return HH:mm 文本
     */
    private String formatTime(LocalTime time) {
        return time == null ? "" : time.format(SCHEDULE_TIME_FORMATTER);
    }

    /**
     * 解析列表优先使用的配置。
     *
     * @param portfolio 作品集实体
     * @return 草稿优先的作品集配置
     */
    private PortfolioConfigDto resolveListConfig(PortfolioEntity portfolio) {
        return parseConfig(hasText(portfolio.getDraftConfigJson())
                ? portfolio.getDraftConfigJson()
                : portfolio.getPublishedConfigJson());
    }

    /**
     * 默认初始配置。
     *
     * @return 默认配置
     */
    private PortfolioConfigDto defaultConfig() {
        PortfolioConfigDto config = new PortfolioConfigDto();
        config.setSchemaVersion(PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1);
        PortfolioConfigDto.Share share = new PortfolioConfigDto.Share();
        share.setTitle(UNTITLED_PORTFOLIO_TITLE);
        config.setShare(share);
        PortfolioConfigDto.Component component = new PortfolioConfigDto.Component();
        component.setComponentKey("c_profile");
        component.setComponentType(PortfolioComponentTypeDict.PROFILE.getCode());
        component.setSortOrder(1000);
        component.setEnabled(true);
        component.setConfig(Map.of());
        config.setComponents(List.of(component));
        return config;
    }

    /**
     * 解析配置 JSON。
     *
     * @param configJson 配置 JSON
     * @return 配置 DTO
     */
    private PortfolioConfigDto parseConfig(String configJson) {
        if (!hasText(configJson)) {
            return null;
        }
        try {
            return JSON.parseObject(configJson, PortfolioConfigDto.class);
        } catch (Exception e) {
            throw new BusinessException(PortfolioMessage.PORTFOLIO_CONFIG_FORMAT_INVALID_MESSAGE);
        }
    }

    /**
     * 转换 JSON。
     *
     * @param value 对象
     * @return JSON 字符串
     */
    private String toJson(Object value) {
        return JSON.toJSONString(value);
    }

    /**
     * 生成分享编码。
     *
     * @return 分享编码
     */
    private String generateShareCode() {
        return SHARE_CODE_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    /**
     * 计算 SHA-256。
     *
     * @param text 原文
     * @return 小写十六进制哈希
     */
    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256_ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(defaultString(text).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 规范化必填字符串。
     *
     * @param value 原值
     * @param message 错误提示
     * @return 去空白值
     */
    private String normalizeRequiredString(String value, String message) {
        if (!hasText(value)) {
            throw new BusinessException(message);
        }
        return value.strip();
    }

    /**
     * 默认字符串。
     *
     * @param value 原值
     * @return 非空字符串
     */
    private String defaultString(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * 判断字符串是否有内容。
     *
     * @param value 原值
     * @return true 表示有内容
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 安全整数。
     *
     * @param value 原值
     * @return 非空整数
     */
    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 空列表兜底。
     *
     * @param list 原列表
     * @param <T> 元素类型
     * @return 非空列表
     */
    private <T> List<T> safeList(List<T> list) {
        return list == null ? List.of() : list;
    }
}
