package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.PortfolioConfigScopeDict;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dict.WfTagStatusDict;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.dict.WorkUploadTaskStatusDict;
import com.jxc.wefolio.dto.MineWorkBatchDeleteCheckResponse;
import com.jxc.wefolio.dto.MineWorkBatchDeleteRequest;
import com.jxc.wefolio.dto.MineWorkBatchDeleteResponse;
import com.jxc.wefolio.dto.MineWorkCoverUploadTicketRequest;
import com.jxc.wefolio.dto.MineWorkCoverUploadTicketResponse;
import com.jxc.wefolio.dto.MineWorkDeleteCheckResponse;
import com.jxc.wefolio.dto.MineWorkDetailResponse;
import com.jxc.wefolio.dto.MineWorkListResponse;
import com.jxc.wefolio.dto.MineWorkSortRequest;
import com.jxc.wefolio.dto.MineWorkSortItemsResponse;
import com.jxc.wefolio.dto.MineWorkTagResponse;
import com.jxc.wefolio.dto.MineWorkTagUpsertRequest;
import com.jxc.wefolio.dto.MineWorkThumbnailUploadTicketRequest;
import com.jxc.wefolio.dto.MineWorkThumbnailUploadTicketResponse;
import com.jxc.wefolio.dto.MineWorkUpdateRequest;
import com.jxc.wefolio.dto.MineWorkUploadCompleteRequest;
import com.jxc.wefolio.dto.MineWorkUploadCompleteResponse;
import com.jxc.wefolio.dto.MineWorkUploadTicketRequest;
import com.jxc.wefolio.dto.MineWorkUploadTicketResponse;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.entity.WfTagEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.entity.WorkTagEntity;
import com.jxc.wefolio.entity.WorkUploadTaskEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioReferenceEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.mapper.WfTagEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.mapper.WorkTagEntityMapper;
import com.jxc.wefolio.mapper.WorkUploadTaskEntityMapper;
import com.jxc.wefolio.message.MineWorkMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 我的作品服务 — 负责作品列表、直传票据、上传确认、编辑和删除检查。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MineWorkService {

    /** 单批最大上传数量 */
    public static final int MAX_BATCH_COUNT = 9;

    /** 图片最大字节数 */
    public static final long IMAGE_MAX_BYTES = 10L * 1024L * 1024L;

    /** 视频最大字节数 */
    public static final long VIDEO_MAX_BYTES = 100L * 1024L * 1024L;

    /** 视频最大时长毫秒 */
    public static final int VIDEO_MAX_DURATION_MS = 10 * 60 * 1000;

    /** 上传票据有效分钟数 */
    private static final int TICKET_EXPIRE_MINUTES = 15;

    /** 默认页码 */
    private static final int DEFAULT_PAGE = 1;

    /** 默认每页数量 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 最大每页数量 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 排序间隔，便于后续插入 */
    private static final int SORT_ORDER_STEP = 1000;

    /** 全部作品排序范围 */
    private static final String SORT_SCOPE_ALL = "ALL";

    /** 标签内排序范围 */
    private static final String SORT_SCOPE_TAG = "TAG";

    /** 标签排序范围缺少标签提示 */
    private static final String SORT_TAG_REQUIRED_MESSAGE = "请选择要排序的标签";

    /** 作品不在标签下提示 */
    private static final String SORT_TAG_WORK_MISMATCH_MESSAGE = "作品不在当前标签下，请刷新后重试";

    /** 作品标题最大长度 */
    private static final int TITLE_MAX_LENGTH = 30;

    /** 作品说明最大长度 */
    private static final int DESCRIPTION_MAX_LENGTH = 1000;

    /** 单用户最多作品标签数量 */
    private static final int TAG_MAX_COUNT = 10;

    /** 标签名称最大长度 */
    private static final int TAG_MAX_LENGTH = 10;

    /** 图片 COS 目录 */
    private static final String WORK_IMAGE_FOLDER = "work/image";

    /** 视频 COS 目录 */
    private static final String WORK_VIDEO_FOLDER = "work/video";

    /** 图片作品文件名类型标识 */
    private static final String IMAGE_FILE_MARKER = "P";

    /** 视频作品文件名类型标识 */
    private static final String VIDEO_FILE_MARKER = "V";

    /** 作品文件名字段分隔符 */
    private static final String WORK_FILE_NAME_SEPARATOR = "-";

    /** 文件扩展名前缀 */
    private static final String FILE_EXTENSION_SEPARATOR = ".";

    /** 缩略图和封面图最大字节数 */
    private static final long THUMB_MAX_BYTES = 100L * 1024L;

    /** 缩略图文件名后缀 */
    private static final String THUMB_FILE_SUFFIX = "-thumb";

    /** 缩略图文件扩展名 */
    private static final String THUMB_FILE_EXTENSION = "jpg";

    /** 后端生成视频封面任务幂等前缀 */
    private static final String GENERATED_VIDEO_COVER_IDEMPOTENCY_PREFIX = "WORK_VIDEO_COVER:";

    /** 手动上传视频封面任务批次前缀 */
    private static final String MANUAL_VIDEO_COVER_BATCH_PREFIX = "edit-cover-";

    /** 手动上传视频封面任务幂等前缀 */
    private static final String MANUAL_VIDEO_COVER_IDEMPOTENCY_PREFIX = "WORK_VIDEO_COVER_UPLOAD:";

    /** 手动上传图片缩略图任务批次前缀 */
    private static final String IMAGE_THUMBNAIL_BATCH_PREFIX = "edit-thumbnail-";

    /** 手动上传图片缩略图任务幂等前缀 */
    private static final String IMAGE_THUMBNAIL_IDEMPOTENCY_PREFIX = "WORK_IMAGE_THUMBNAIL_UPLOAD:";

    /** 图片缩略图任务过期记录提示 */
    private static final String IMAGE_THUMBNAIL_TASK_EXPIRED_ERROR_MESSAGE = "缩略图上传任务已过期";

    /** 视频默认封面截帧时间点 */
    private static final long DEFAULT_VIDEO_COVER_FRAME_TIME_MS = 0L;

    /** 数据万象封面截帧最长边 */
    private static final int SNAPSHOT_MAX_SIDE = 640;

    /** 小程序可信媒体尺寸最大值，防止异常入参 */
    private static final int MEDIA_DIMENSION_MAX = 10000;

    /** 上传任务幂等键兜底前缀 */
    private static final String TICKET_IDEMPOTENCY_PREFIX = "WORK_UPLOAD_TICKET:";

    /** SHA-256 小写十六进制格式 */
    private static final String SHA256_PATTERN = "^[0-9a-f]{64}$";

    /** 作品表主键列 */
    private static final String WORK_COLUMN_ID = "id";

    /** 作品表用户列 */
    private static final String WORK_COLUMN_USER_ID = "user_id";

    /** 作品表逻辑删除列 */
    private static final String WORK_COLUMN_DELETED = "deleted";

    /** 作品表删除时间列 */
    private static final String WORK_COLUMN_DELETED_AT = "deleted_at";

    /** 作品表媒体类型列 */
    private static final String WORK_COLUMN_MEDIA_TYPE = "media_type";

    /** 作品表审核状态列 */
    private static final String WORK_COLUMN_AUDIT_STATUS = "audit_status";

    /** 作品表状态列 */
    private static final String WORK_COLUMN_STATUS = "status";

    /** SQL 计数表达式 */
    private static final String SQL_COUNT_ALL_EXPRESSION = "COUNT(*)";

    /** 媒体类型查询别名 */
    private static final String WORK_MEDIA_TYPE_ALIAS = "mediaType";

    /** 计数查询别名 */
    private static final String COUNT_ALIAS = "itemCount";

    /** MySQL FIELD 排序表达式前缀 */
    private static final String SQL_ORDER_BY_FIELD_ID_PREFIX = "ORDER BY FIELD(id, ";

    /** MySQL 函数表达式右括号 */
    private static final String SQL_FUNCTION_SUFFIX = ")";

    /** 批量删除成功提示 */
    private static final String BATCH_DELETE_SUCCESS_MESSAGE = "已删除";

    /** 图片扩展名 */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif");

    /** 视频扩展名 */
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "mov", "m4v");

    /** 作品标签固定色板 */
    private static final Set<String> TAG_COLOR_OPTIONS = Set.of(
            "#0f766e",
            "#2d5f9a",
            "#8a4b09",
            "#a9354f",
            "#6d5bd0",
            "#3f6f45",
            "#36516e",
            "#9a4a35",
            "#4b5563"
    );

    /** 图片 MIME 前缀 */
    private static final String IMAGE_MIME_PREFIX = "image/";

    /** 视频 MIME 前缀 */
    private static final String VIDEO_MIME_PREFIX = "video/";

    /** 当前用户 Mapper */
    private final UserEntityMapper userEntityMapper;

    /** 作品 Mapper */
    private final WorkEntityMapper workEntityMapper;

    /** 标签 Mapper */
    private final WfTagEntityMapper wfTagEntityMapper;

    /** 作品标签 Mapper */
    private final WorkTagEntityMapper workTagEntityMapper;

    /** 上传任务 Mapper */
    private final WorkUploadTaskEntityMapper workUploadTaskEntityMapper;

    /** 作品集引用 Mapper */
    private final PortfolioReferenceEntityMapper portfolioReferenceEntityMapper;

    /** COS 服务 */
    private final CosService cosService;

    /** 上传确认事务服务 */
    private final WorkUploadTransactionService workUploadTransactionService;

    /** 内容数量上限服务 */
    private final ContentLimitService contentLimitService;

    /**
     * 分页查询我的作品。
     *
     * @param keyword 关键词，可为空
     * @param tagId 标签 ID，可为空
     * @param page 页码
     * @param pageSize 每页数量
     * @return 作品列表响应
     */
    public MineWorkListResponse listWorks(String keyword, Long tagId, int page, int pageSize) {
        return listWorks(keyword, tagId, null, null, page, pageSize);
    }

    /**
     * 分页查询我的作品。
     *
     * @param keyword 关键词，可为空
     * @param tagId 标签 ID，可为空
     * @param mediaType 媒体类型，可为空
     * @param page 页码
     * @param pageSize 每页数量
     * @return 作品列表响应
     */
    public MineWorkListResponse listWorks(String keyword, Long tagId, String mediaType, int page, int pageSize) {
        return listWorks(keyword, tagId, mediaType, null, page, pageSize);
    }

    /**
     * 分页查询我的作品。
     *
     * @param keyword 关键词，可为空
     * @param tagId 标签 ID，可为空
     * @param mediaType 媒体类型，可为空
     * @param auditStatus 审核状态，可为空
     * @param page 页码
     * @param pageSize 每页数量
     * @return 作品列表响应
     */
    public MineWorkListResponse listWorks(
            String keyword,
            Long tagId,
            String mediaType,
            String auditStatus,
            int page,
            int pageSize
    ) {
        Long userId = AuthContextHolder.requireUserId();
        int normalizedPage = page <= 0 ? DEFAULT_PAGE : page;
        int normalizedPageSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        String normalizedMediaType = normalizeOptionalMediaType(mediaType);
        String normalizedAuditStatus = normalizeOptionalAuditStatus(auditStatus);
        List<WorkTagEntity> selectedTagRelations = tagId == null ? List.of() : findRelationsForTag(userId, tagId);
        List<Long> taggedWorkIds = tagId == null
                ? null
                : selectedTagRelations.stream().map(WorkTagEntity::getWorkId).toList();
        List<Long> keywordWorkIds = findWorkIdsByKeywordTag(userId, keyword);
        var query = Wrappers.lambdaQuery(WorkEntity.class)
                .eq(WorkEntity::getUserId, userId)
                .eq(WorkEntity::getStatus, WorkStatusDict.ACTIVE.getCode())
                .eq(hasText(normalizedMediaType), WorkEntity::getMediaType, normalizedMediaType)
                .eq(hasText(normalizedAuditStatus), WorkEntity::getAuditStatus, normalizedAuditStatus)
                .and(hasText(keyword), wrapper -> wrapper
                        .like(WorkEntity::getTitle, normalizeText(keyword))
                        .or()
                        .like(WorkEntity::getOriginalFileName, normalizeText(keyword))
                        .or(!keywordWorkIds.isEmpty())
                        .in(!keywordWorkIds.isEmpty(), WorkEntity::getId, keywordWorkIds))
                .in(taggedWorkIds != null && !taggedWorkIds.isEmpty(), WorkEntity::getId, taggedWorkIds)
                .eq(taggedWorkIds != null && taggedWorkIds.isEmpty(), WorkEntity::getId, -1L);
        if (tagId == null) {
            query.orderByAsc(WorkEntity::getSortOrder).orderByDesc(WorkEntity::getId);
        } else if (!taggedWorkIds.isEmpty()) {
            query.last(buildWorkIdOrderClause(taggedWorkIds));
        }
        Page<WorkEntity> resultPage = workEntityMapper.selectPage(
                new Page<>(normalizedPage, normalizedPageSize),
                query
        );

        MineWorkListResponse response = new MineWorkListResponse();
        response.setPage(normalizedPage);
        response.setPageSize(normalizedPageSize);
        response.setTotal(resultPage.getTotal());
        response.setHasMore(resultPage.getCurrent() < resultPage.getPages());
        MineWorkListResponse.Summary summary = buildSummary(userId, normalizedMediaType, normalizedAuditStatus);
        List<WfTagEntity> activeTags = findActiveTags(userId);
        Map<Long, WfTagEntity> activeTagMap = buildTagMap(activeTags);
        List<WorkTagEntity> activeTagRelations = findRelationsForTags(userId, activeTagMap.keySet());
        List<WorkTagEntity> countedTagRelations =
                filterTagRelationsByWorkFilters(userId, activeTagRelations, normalizedMediaType, normalizedAuditStatus);
        response.setSummary(summary);
        response.setTags(buildTagItems(activeTags, buildTagUsageCounts(countedTagRelations), tagId, summary.getTotalCount()));
        Map<Long, Long> referenceCounts = buildWorkReferenceCounts(resultPage.getRecords());
        Map<Long, List<MineWorkListResponse.TagItem>> workTags =
                buildWorkTagItems(resultPage.getRecords(), activeTagRelations, activeTagMap);
        Map<Long, Integer> selectedTagSortOrders = buildRelationSortOrderMap(selectedTagRelations);
        List<MineWorkListResponse.WorkItem> workItems = resultPage.getRecords().stream()
                .map(work -> buildWorkItem(work, referenceCounts, workTags))
                .toList();
        if (tagId != null) {
            workItems.forEach(item -> item.setSortOrder(selectedTagSortOrders.getOrDefault(item.getId(), item.getSortOrder())));
        }
        response.setWorks(workItems);
        return response;
    }

    /**
     * 查询我的作品标签。
     *
     * @return 标签响应
     */
    public MineWorkTagResponse listTags() {
        Long userId = AuthContextHolder.requireUserId();
        List<WfTagEntity> activeTags = findActiveTags(userId);
        List<WorkTagEntity> activeTagRelations = findRelationsForTags(userId, buildTagMap(activeTags).keySet());
        MineWorkTagResponse response = new MineWorkTagResponse();
        response.setTags(buildTagItems(activeTags, buildTagUsageCounts(activeTagRelations), null, 0L).stream()
                .filter(item -> item.getId() != null)
                .toList());
        return response;
    }

    /**
     * 新增作品标签。
     *
     * @param request 标签请求
     * @return 新增标签项
     */
    @Transactional(rollbackFor = Exception.class)
    public MineWorkListResponse.TagItem createTag(MineWorkTagUpsertRequest request) {
        Long userId = AuthContextHolder.requireUserId();
        String name = normalizeRequiredTagName(request == null ? null : request.getName());
        String color = normalizeTagColor(request == null ? null : request.getColor());
        ensureTagCapacity(userId);
        ensureNoDuplicateTag(userId, name, null);

        WfTagEntity tag = new WfTagEntity();
        tag.setUserId(userId);
        tag.setName(name);
        tag.setColor(color);
        tag.setStatus(WfTagStatusDict.ACTIVE.getCode());
        try {
            wfTagEntityMapper.insert(tag);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(MineWorkMessage.TAG_DUPLICATE_MESSAGE);
        }
        MineWorkListResponse.TagItem item = buildTagItem(tag);
        item.setCount(countWorkTags(userId, tag.getId()));
        return item;
    }

    /**
     * 编辑作品标签。
     *
     * @param tagId 标签 ID
     * @param request 标签请求
     * @return 更新后标签项
     */
    @Transactional(rollbackFor = Exception.class)
    public MineWorkListResponse.TagItem updateTag(Long tagId, MineWorkTagUpsertRequest request) {
        Long userId = AuthContextHolder.requireUserId();
        WfTagEntity tag = requireOwnedActiveTag(userId, tagId);
        String name = normalizeRequiredTagName(request == null ? null : request.getName());
        String color = normalizeTagColor(request == null ? null : request.getColor());
        ensureNoDuplicateTag(userId, name, tag.getId());

        tag.setName(name);
        tag.setColor(color);
        int updated = wfTagEntityMapper.updateById(tag);
        if (updated <= 0) {
            throw new BusinessException(MineWorkMessage.TAG_SAVE_FAILED_MESSAGE);
        }
        MineWorkListResponse.TagItem item = buildTagItem(tag);
        item.setCount(countWorkTags(userId, tag.getId()));
        return item;
    }

    /**
     * 删除作品标签。
     *
     * @param tagId 标签 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteTag(Long tagId) {
        Long userId = AuthContextHolder.requireUserId();
        WfTagEntity tag = requireOwnedActiveTag(userId, tagId);
        long workCount = countWorkTags(userId, tag.getId());
        if (workCount > 0L) {
            throw new BusinessException(String.format(MineWorkMessage.TAG_DELETE_BLOCKED_TEMPLATE, tag.getName(), workCount));
        }
        int deleted = wfTagEntityMapper.deleteById(tag.getId());
        if (deleted <= 0) {
            throw new BusinessException(MineWorkMessage.TAG_DELETE_FAILED_MESSAGE);
        }
    }

    /**
     * 获取作品详情。
     *
     * @param workId 作品 ID
     * @return 作品详情
     */
    public MineWorkDetailResponse getWorkDetail(Long workId) {
        WorkEntity work = requireOwnedWork(workId);
        MineWorkDetailResponse response = new MineWorkDetailResponse();
        response.setWork(buildWorkItem(work));
        response.setReferences(findWorkReferences(work.getId()).stream()
                .map(this::buildReferenceItem)
                .toList());
        return response;
    }

    /**
     * 创建作品直传票据。
     *
     * @param request 票据创建请求
     * @return 票据响应
     */
    @Transactional(rollbackFor = Exception.class)
    public MineWorkUploadTicketResponse createUploadTickets(MineWorkUploadTicketRequest request) {
        Long userId = AuthContextHolder.requireUserId();
        UserEntity user = requireActiveUser(userId);
        List<MineWorkUploadTicketRequest.UploadFileItem> files = request == null ? List.of() : request.getFiles();
        if (files == null || files.isEmpty()) {
            throw new BusinessException("请选择要上传的作品");
        }
        if (files.size() > MAX_BATCH_COUNT) {
            throw new BusinessException("一次最多上传 9 个作品");
        }
        String batchId = hasText(request.getBatchId()) ? normalizeText(request.getBatchId()) : generateUuid();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(TICKET_EXPIRE_MINUTES);

        MineWorkUploadTicketResponse response = new MineWorkUploadTicketResponse();
        response.setBatchId(batchId);
        response.setMaxBatchCount(MAX_BATCH_COUNT);
        response.setImageMaxBytes(IMAGE_MAX_BYTES);
        response.setVideoMaxBytes(VIDEO_MAX_BYTES);
        response.setVideoMaxDurationMs(VIDEO_MAX_DURATION_MS);
        List<PreparedUploadFile> preparedFiles = prepareUploadFiles(userId, user.getUniqueCode(), batchId, files);
        ensureBatchWorkCapacity(userId, preparedFiles);
        for (PreparedUploadFile preparedFile : preparedFiles) {
            MineWorkUploadTicketRequest.UploadFileItem file = preparedFile.file();
            WorkUploadTaskEntity task = buildUploadTask(
                    userId,
                    batchId,
                    file,
                    preparedFile.mediaType(),
                    preparedFile.objectKey(),
                    preparedFile.idempotencyKey(),
                    preparedFile.fileSha256(),
                    expiresAt);
            WorkUploadTaskEntity persistedTask = saveOrFindUploadTask(task);
            long maxBytes = maxBytes(persistedTask.getMediaType());
            CosService.PostUploadTicket ticket = cosService.createPostUploadTicket(
                    persistedTask.getObjectKey(),
                    normalizeText(persistedTask.getMimeType()),
                    maxBytes,
                    persistedTask.getExpiresAt());
            response.getItems().add(buildTicketItem(persistedTask, file.getClientId(), ticket));
        }
        return response;
    }

    /**
     * 按媒体类型校验当前批次主作品的新增容量。
     *
     * <p>带来源任务的图片是缩略图或封面图，不会创建独立作品，因此不计入作品数量。</p>
     *
     * @param userId 当前用户 ID
     * @param preparedFiles 已完成合法性预处理的上传文件
     */
    private void ensureBatchWorkCapacity(Long userId, List<PreparedUploadFile> preparedFiles) {
        long imageCount = preparedFiles.stream()
                .filter(this::isMainWorkUpload)
                .filter(item -> MediaTypeDict.IMAGE.getCode().equals(item.mediaType()))
                .count();
        long videoCount = preparedFiles.stream()
                .filter(this::isMainWorkUpload)
                .filter(item -> MediaTypeDict.VIDEO.getCode().equals(item.mediaType()))
                .count();
        if (imageCount > 0L) {
            contentLimitService.ensureWorkCapacity(userId, MediaTypeDict.IMAGE.getCode(), imageCount);
        }
        if (videoCount > 0L) {
            contentLimitService.ensureWorkCapacity(userId, MediaTypeDict.VIDEO.getCode(), videoCount);
        }
    }

    /**
     * 判断上传文件是否会创建独立作品。
     *
     * @param preparedFile 已完成合法性预处理的上传文件
     * @return 是否为主作品文件
     */
    private boolean isMainWorkUpload(PreparedUploadFile preparedFile) {
        return preparedFile.file().getSourceTaskId() == null;
    }

    /**
     * 为已存在的视频作品创建封面直传票据。
     *
     * @param workId 作品 ID
     * @param request 封面票据创建请求
     * @return 封面票据响应
     */
    @Transactional(rollbackFor = Exception.class)
    public MineWorkCoverUploadTicketResponse createCoverUploadTicket(
            Long workId,
            MineWorkCoverUploadTicketRequest request
    ) {
        Long userId = AuthContextHolder.requireUserId();
        WorkEntity work = requireOwnedWork(workId);
        if (!MediaTypeDict.VIDEO.getCode().equals(work.getMediaType())) {
            throw new BusinessException(MineWorkMessage.VIDEO_COVER_UPDATE_MEDIA_TYPE_MESSAGE);
        }
        validateManualCoverUploadFile(request);
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(TICKET_EXPIRE_MINUTES);
        WorkUploadTaskEntity task = buildManualCoverUploadTask(userId, work, request, expiresAt);
        WorkUploadTaskEntity persistedTask = saveOrFindUploadTask(task);
        CosService.PostUploadTicket ticket = cosService.createPostUploadTicket(
                persistedTask.getObjectKey(),
                normalizeText(persistedTask.getMimeType()),
                THUMB_MAX_BYTES,
                persistedTask.getExpiresAt());
        return buildCoverTicketResponse(persistedTask, request.getClientId(), ticket);
    }

    /**
     * 为已存在的图片作品创建缩略图直传票据。
     *
     * @param workId 作品 ID
     * @param request 缩略图票据创建请求
     * @return 缩略图票据响应
     */
    @Transactional(rollbackFor = Exception.class)
    public MineWorkThumbnailUploadTicketResponse createThumbnailUploadTicket(
            Long workId,
            MineWorkThumbnailUploadTicketRequest request
    ) {
        Long userId = AuthContextHolder.requireUserId();
        WorkEntity work = requireOwnedWork(workId);
        if (!MediaTypeDict.IMAGE.getCode().equals(work.getMediaType())) {
            throw new BusinessException(MineWorkMessage.IMAGE_THUMBNAIL_UPDATE_MEDIA_TYPE_MESSAGE);
        }
        validateThumbnailUploadFile(request);
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(TICKET_EXPIRE_MINUTES);
        WorkUploadTaskEntity task = buildImageThumbnailUploadTask(userId, work, request, expiresAt);
        WorkUploadTaskEntity persistedTask = saveOrFindUploadTask(task);
        ensureThumbnailTaskDoesNotTargetOriginal(work, persistedTask.getObjectKey());
        CosService.PostUploadTicket ticket = cosService.createPostUploadTicket(
                persistedTask.getObjectKey(),
                normalizeText(persistedTask.getMimeType()),
                THUMB_MAX_BYTES,
                persistedTask.getExpiresAt());
        return buildThumbnailTicketResponse(persistedTask, request.getClientId(), ticket);
    }

    /**
     * 上传完成后确认入库。
     *
     * @param request 上传完成请求
     * @return 确认响应
     */
    public MineWorkUploadCompleteResponse completeUpload(MineWorkUploadCompleteRequest request) {
        Long userId = AuthContextHolder.requireUserId();
        List<MineWorkUploadCompleteRequest.CompleteItem> items = request == null ? List.of() : request.getItems();
        if (items == null || items.isEmpty()) {
            throw new BusinessException("请选择要确认的上传任务");
        }
        MineWorkUploadCompleteResponse response = new MineWorkUploadCompleteResponse();
        for (MineWorkUploadCompleteRequest.CompleteItem item : items) {
            Long taskId = item == null ? null : item.getTaskId();
            try {
                WorkUploadTaskEntity task = requireOwnedUploadTask(userId, taskId);
                CosService.ObjectHead head = validateCosObject(task);
                ensureGeneratedVideoCoverTask(userId, task, item);
                validateCoverTask(userId, task, head, item);
                response.getItems().add(workUploadTransactionService.confirmUploadedTask(userId, task, item));
            } catch (BusinessException e) {
                markTaskFailedIfPossible(userId, taskId, e.getMessage());
                response.getItems().add(MineWorkUploadCompleteResponse.Item.failure(taskId, e.getMessage()));
            } catch (Exception e) {
                log.warn("作品确认出现未预期异常: taskId={}", taskId, e);
                markTaskFailedIfPossible(userId, taskId, MineWorkMessage.UPLOAD_CONFIRM_UNEXPECTED_FAILED_MESSAGE);
                response.getItems().add(MineWorkUploadCompleteResponse.Item.failure(
                        taskId,
                        MineWorkMessage.UPLOAD_CONFIRM_UNEXPECTED_FAILED_MESSAGE));
            }
        }
        return response;
    }

    /**
     * 更新作品资料。
     *
     * @param workId 作品 ID
     * @param request 更新请求
     * @return 更新后详情
     */
    @Transactional(rollbackFor = Exception.class)
    public MineWorkDetailResponse updateWork(Long workId, MineWorkUpdateRequest request) {
        WorkEntity work = requireOwnedWork(workId);
        Long coverFrameTimeMs = request == null ? null : request.getCoverFrameTimeMs();
        Long coverTaskId = request == null ? null : request.getCoverTaskId();
        Long thumbnailTaskId = request == null ? null : request.getThumbnailTaskId();
        int coverEditModeCount = 0;
        coverEditModeCount += coverFrameTimeMs == null ? 0 : 1;
        coverEditModeCount += coverTaskId == null ? 0 : 1;
        coverEditModeCount += thumbnailTaskId == null ? 0 : 1;
        if (coverEditModeCount > 1) {
            throw new BusinessException(MineWorkMessage.COVER_EDIT_MODE_CONFLICT_MESSAGE);
        }
        String title = normalizeRequiredTitle(request == null ? null : request.getTitle());
        String description = normalizeDescription(request == null ? null : request.getDescription());
        MediaDimensions requestDimensions = request == null
                ? null
                : normalizeMediaDimensions(request.getWidth(), request.getHeight());
        WorkTagChange tagChange = request != null && request.getTagIds() != null
                ? prepareWorkTagChange(work.getUserId(), work.getId(), request.getTagIds())
                : null;
        String oldCoverObjectKey = work.getCoverObjectKey();
        if (tagChange != null) {
            syncWorkTags(work.getUserId(), work.getId(), tagChange);
        }
        CosService.SnapshotObject generatedCover = coverFrameTimeMs == null
                ? null
                : generateReplacementVideoCover(work, coverFrameTimeMs, requestDimensions);
        WorkUploadTaskEntity uploadedCoverTask = coverTaskId == null
                ? null
                : validateReplacementCoverTask(work, coverTaskId);
        WorkUploadTaskEntity uploadedThumbnailTask = thumbnailTaskId == null
                ? null
                : validateImageThumbnailTask(work, thumbnailTaskId);
        work.setTitle(title);
        work.setDescription(description);
        if (generatedCover != null) {
            work.setCoverObjectKey(generatedCover.objectKey());
            work.setCoverSha256(generatedCover.sha256());
            if (requestDimensions != null) {
                work.setWidth(requestDimensions.width());
                work.setHeight(requestDimensions.height());
            }
        }
        if (uploadedCoverTask != null) {
            work.setCoverObjectKey(uploadedCoverTask.getObjectKey());
            work.setCoverSha256(uploadedCoverTask.getFileSha256());
        }
        if (uploadedThumbnailTask != null) {
            work.setCoverObjectKey(uploadedThumbnailTask.getObjectKey());
            work.setCoverSha256(uploadedThumbnailTask.getFileSha256());
        }
        int updated = workEntityMapper.updateById(work);
        if (updated <= 0) {
            deleteGeneratedCoverIfNeeded(generatedCover, oldCoverObjectKey, work.getMediaObjectKey());
            deleteUploadedCoverIfNeeded(uploadedCoverTask, oldCoverObjectKey, work.getMediaObjectKey());
            deleteUploadedCoverIfNeeded(uploadedThumbnailTask, oldCoverObjectKey, work.getMediaObjectKey());
            throw new BusinessException(MineWorkMessage.WORK_SAVE_FAILED_MESSAGE);
        }
        if (uploadedCoverTask != null) {
            confirmReplacementCoverTask(uploadedCoverTask, work.getId(), oldCoverObjectKey, work.getMediaObjectKey());
        }
        if (uploadedThumbnailTask != null) {
            confirmImageThumbnailTask(uploadedThumbnailTask, work.getId(), oldCoverObjectKey, work.getMediaObjectKey());
        }
        if (generatedCover != null) {
            deleteOldCoverIfNeeded(oldCoverObjectKey, work.getCoverObjectKey(), work.getMediaObjectKey());
        }
        if (uploadedCoverTask != null) {
            deleteOldCoverIfNeeded(oldCoverObjectKey, work.getCoverObjectKey(), work.getMediaObjectKey());
        }
        if (uploadedThumbnailTask != null) {
            deleteOldCoverIfNeeded(oldCoverObjectKey, work.getCoverObjectKey(), work.getMediaObjectKey());
        }
        return getWorkDetail(work.getId());
    }

    /**
     * 更新作品排序。
     *
     * @param request 排序请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void sortWorks(MineWorkSortRequest request) {
        Long userId = AuthContextHolder.requireUserId();
        List<MineWorkSortRequest.Item> items = request == null ? List.of() : request.getItems();
        if (items == null || items.isEmpty()) {
            throw new BusinessException(MineWorkMessage.SORT_ITEMS_EMPTY_MESSAGE);
        }
        List<MineWorkSortRequest.Item> validItems = normalizeSortItems(items);
        if (validItems.isEmpty()) {
            throw new BusinessException(MineWorkMessage.SORT_ITEMS_EMPTY_MESSAGE);
        }
        String scope = normalizeSortScope(request == null ? null : request.getScope());
        if (SORT_SCOPE_TAG.equals(scope)) {
            sortTagWorks(userId, request == null ? null : request.getTagId(), validItems);
            return;
        }
        List<Long> workIds = validItems.stream()
                .map(MineWorkSortRequest.Item::getWorkId)
                .toList();
        Set<Long> ownedWorkIds = workEntityMapper.selectBatchIds(workIds).stream()
                .filter(work -> userId.equals(work.getUserId()))
                .map(WorkEntity::getId)
                .collect(Collectors.toSet());
        if (ownedWorkIds.size() != workIds.size() || !ownedWorkIds.containsAll(workIds)) {
            throw new BusinessException("作品不存在或无访问权限");
        }
        workEntityMapper.updateSortOrders(userId, validItems);
    }

    /**
     * 查询排序模式使用的作品列表。
     *
     * @param scope 排序范围
     * @param tagId 标签 ID
     * @return 排序作品列表
     */
    public MineWorkSortItemsResponse listSortItems(String scope, Long tagId) {
        Long userId = AuthContextHolder.requireUserId();
        String normalizedScope = normalizeSortScope(scope);
        MineWorkSortItemsResponse response = new MineWorkSortItemsResponse();
        response.setScope(normalizedScope);
        response.setTagId(SORT_SCOPE_TAG.equals(normalizedScope) ? tagId : null);
        if (SORT_SCOPE_TAG.equals(normalizedScope)) {
            requireOwnedActiveTag(userId, tagId);
            List<WorkTagEntity> relations = findRelationsForTag(userId, tagId);
            List<Long> workIds = relations.stream().map(WorkTagEntity::getWorkId).toList();
            Map<Long, WorkEntity> workMap = buildOwnedWorkMap(userId, workIds);
            response.setWorks(relations.stream()
                    .filter(relation -> workMap.containsKey(relation.getWorkId()))
                    .map(relation -> buildSortWorkItem(workMap.get(relation.getWorkId()), relation.getSortOrder()))
                    .toList());
            response.setTotal(response.getWorks().size());
            return response;
        }
        List<WorkEntity> works = workEntityMapper.selectList(
                Wrappers.lambdaQuery(WorkEntity.class)
                        .eq(WorkEntity::getUserId, userId)
                        .eq(WorkEntity::getStatus, WorkStatusDict.ACTIVE.getCode())
                        .orderByAsc(WorkEntity::getSortOrder)
                        .orderByDesc(WorkEntity::getId)
        );
        response.setWorks(works.stream()
                .map(work -> buildSortWorkItem(work, work.getSortOrder()))
                .toList());
        response.setTotal(response.getWorks().size());
        return response;
    }

    /**
     * 检查作品是否可以删除。
     *
     * @param workId 作品 ID
     * @return 删除检查响应
     */
    public MineWorkDeleteCheckResponse checkDeleteWork(Long workId) {
        WorkEntity work = requireOwnedWork(workId);
        long referenceCount = findWorkReferences(work.getId()).size();
        MineWorkDeleteCheckResponse response = new MineWorkDeleteCheckResponse();
        response.setCanDelete(referenceCount <= 0L);
        response.setReferenceCount(referenceCount);
        response.setMessage(buildDeleteMessage(referenceCount));
        return response;
    }

    /**
     * 批量检查作品是否可删除。
     *
     * @param request 批量删除请求
     * @return 批量删除检查响应
     */
    public MineWorkBatchDeleteCheckResponse checkDeleteWorks(MineWorkBatchDeleteRequest request) {
        Long userId = AuthContextHolder.requireUserId();
        List<Long> workIds = normalizeWorkIds(request == null ? null : request.getWorkIds());
        if (workIds.isEmpty()) {
            throw new BusinessException(MineWorkMessage.WORK_EMPTY_MESSAGE);
        }
        List<WorkEntity> works = findOwnedWorks(userId, workIds);
        ensureAllWorksOwned(workIds, works);
        Map<Long, Long> referenceCounts = buildWorkReferenceCounts(works);
        MineWorkBatchDeleteCheckResponse response = new MineWorkBatchDeleteCheckResponse();
        response.setTotal(works.size());
        for (WorkEntity work : orderWorksByIds(workIds, works)) {
            long referenceCount = referenceCounts.getOrDefault(work.getId(), 0L);
            MineWorkBatchDeleteCheckResponse.Item item = buildBatchDeleteCheckItem(work, referenceCount);
            response.getItems().add(item);
            if (item.isCanDelete()) {
                response.setDeletableCount(response.getDeletableCount() + 1);
            } else {
                response.setBlockedCount(response.getBlockedCount() + 1);
            }
        }
        return response;
    }

    /**
     * 删除作品。
     *
     * @param workId 作品 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteWork(Long workId) {
        WorkEntity work = requireOwnedWork(workId);
        long referenceCount = findWorkReferences(work.getId()).size();
        if (referenceCount > 0L) {
            throw new BusinessException(buildDeleteMessage(referenceCount));
        }
        deleteOwnedWork(work);
    }

    /**
     * 批量删除作品；被作品集引用的作品会保留并返回失败项。
     *
     * @param request 批量删除请求
     * @return 批量删除结果
     */
    @Transactional(rollbackFor = Exception.class)
    public MineWorkBatchDeleteResponse deleteWorks(MineWorkBatchDeleteRequest request) {
        Long userId = AuthContextHolder.requireUserId();
        List<Long> workIds = normalizeWorkIds(request == null ? null : request.getWorkIds());
        if (workIds.isEmpty()) {
            throw new BusinessException(MineWorkMessage.WORK_EMPTY_MESSAGE);
        }
        List<WorkEntity> works = findOwnedWorks(userId, workIds);
        ensureAllWorksOwned(workIds, works);
        Map<Long, Long> referenceCounts = buildWorkReferenceCounts(works);
        MineWorkBatchDeleteResponse response = new MineWorkBatchDeleteResponse();
        for (WorkEntity work : orderWorksByIds(workIds, works)) {
            long referenceCount = referenceCounts.getOrDefault(work.getId(), 0L);
            MineWorkBatchDeleteResponse.Item item = buildBatchDeleteItem(work, referenceCount);
            if (referenceCount <= 0L) {
                deleteOwnedWork(work);
                item.setSuccess(true);
                item.setMessage(BATCH_DELETE_SUCCESS_MESSAGE);
                response.setSuccessCount(response.getSuccessCount() + 1);
            } else {
                item.setSuccess(false);
                item.setMessage(buildDeleteMessage(referenceCount));
                response.setFailedCount(response.getFailedCount() + 1);
            }
            response.getItems().add(item);
        }
        return response;
    }

    /**
     * 预处理上传文件并检查同批次重复项。
     *
     * @param userId 当前用户 ID
     * @param uniqueCode 用户唯一码
     * @param batchId 批次 ID
     * @param files 文件元信息列表
     * @return 预处理后的上传文件
     */
    private List<PreparedUploadFile> prepareUploadFiles(
            Long userId,
            String uniqueCode,
            String batchId,
            List<MineWorkUploadTicketRequest.UploadFileItem> files
    ) {
        long uploadTimestamp = System.currentTimeMillis();
        Set<String> idempotencyKeys = new LinkedHashSet<>();
        Set<String> mainFileSha256Set = new LinkedHashSet<>();
        Set<String> objectKeys = new LinkedHashSet<>();
        List<PreparedUploadFile> preparedFiles = new ArrayList<>();
        for (int index = 0; index < files.size(); index++) {
            MineWorkUploadTicketRequest.UploadFileItem file = files.get(index);
            int uploadSequence = index + 1;
            validateUploadFile(file);
            String idempotencyKey = normalizeTicketIdempotency(batchId, file);
            if (!idempotencyKeys.add(idempotencyKey)) {
                throw new BusinessException(MineWorkMessage.SAME_BATCH_DUPLICATE_FILE_MESSAGE);
            }
            String fileSha256 = normalizeClientSha256(file.getSha256());
            String mediaType = normalizeMediaType(file.getMediaType());
            String extension = normalizeExtension(file.getFileName(), file.getMimeType(), mediaType);
            WorkUploadTaskEntity sourceTask = resolveThumbSourceTask(userId, file);
            if (sourceTask == null) {
                if (!mainFileSha256Set.add(fileSha256)) {
                    throw new BusinessException(MineWorkMessage.SAME_BATCH_DUPLICATE_FILE_MESSAGE);
                }
                WorkEntity duplicateWork = findNonDeletedWorkBySha256(userId, fileSha256);
                if (duplicateWork != null) {
                    throw new BusinessException(String.format(MineWorkMessage.DUPLICATE_WORK_MESSAGE_TEMPLATE, duplicateWork.getTitle()));
                }
            }
            String objectKey = sourceTask == null
                    ? buildWorkObjectKey(
                            uniqueCode,
                            mediaType,
                            extension,
                            uploadTimestamp,
                            uploadSequence)
                    : buildThumbObjectKeyFromSource(sourceTask, extension);
            if (!objectKeys.add(objectKey)) {
                throw new BusinessException(MineWorkMessage.WORK_OBJECT_KEY_CONFLICT_MESSAGE);
            }
            preparedFiles.add(new PreparedUploadFile(file, mediaType, objectKey, idempotencyKey, fileSha256));
        }
        return preparedFiles;
    }

    /**
     * 构造上传任务实体。
     *
     * @param userId 当前用户 ID
     * @param batchId 批次 ID
     * @param file 文件元信息
     * @param mediaType 媒体类型
     * @param objectKey COS 对象键
     * @param idempotencyKey 上传任务幂等键
     * @param fileSha256 当前上传对象 SHA-256
     * @param expiresAt 过期时间
     * @return 上传任务实体
     */
    private WorkUploadTaskEntity buildUploadTask(
            Long userId,
            String batchId,
            MineWorkUploadTicketRequest.UploadFileItem file,
            String mediaType,
            String objectKey,
            String idempotencyKey,
            String fileSha256,
            LocalDateTime expiresAt
    ) {
        WorkUploadTaskEntity task = new WorkUploadTaskEntity();
        task.setBatchId(batchId);
        task.setUserId(userId);
        task.setMediaType(mediaType);
        task.setObjectKey(objectKey);
        task.setFileSha256(fileSha256);
        task.setOriginalFileName(normalizeText(file.getFileName()));
        task.setMimeType(normalizeText(file.getMimeType()));
        task.setFileSize(file.getFileSize());
        task.setDurationMs(file.getDurationMs());
        task.setWidth(file.getWidth());
        task.setHeight(file.getHeight());
        task.setStatus(WorkUploadTaskStatusDict.CREATED.getCode());
        task.setExpiresAt(expiresAt);
        task.setIdempotencyKey(idempotencyKey);
        return task;
    }

    /**
     * 作品上传预处理结果。
     *
     * @param file 文件元信息
     * @param mediaType 标准媒体类型
     * @param objectKey COS 对象键
     * @param idempotencyKey 上传任务幂等键
     * @param fileSha256 当前上传对象 SHA-256
     */
    private record PreparedUploadFile(
            MineWorkUploadTicketRequest.UploadFileItem file,
            String mediaType,
            String objectKey,
            String idempotencyKey,
            String fileSha256
    ) {
    }

    /**
     * 作品标签变更计划。
     *
     * @param addTagIds 需要新增绑定的标签 ID
     * @param removeTagIds 需要移除绑定的标签 ID
     */
    private record WorkTagChange(Set<Long> addTagIds, Set<Long> removeTagIds) {
    }

    /**
     * 小程序上报的视频媒体尺寸。
     *
     * @param width 像素宽度
     * @param height 像素高度
     */
    private record MediaDimensions(int width, int height) {
    }

    /**
     * 数据万象截帧输出尺寸。
     *
     * @param width 输出宽度
     * @param height 输出高度
     */
    private record SnapshotDimensions(int width, int height) {
    }

    /**
     * 保存上传任务；重复提交时按幂等键返回已有任务。
     *
     * @param task 待保存任务
     * @return 已保存或已存在的上传任务
     */
    private WorkUploadTaskEntity saveOrFindUploadTask(WorkUploadTaskEntity task) {
        try {
            workUploadTaskEntityMapper.insert(task);
            return task;
        } catch (DuplicateKeyException e) {
            WorkUploadTaskEntity existing = findUploadTaskByIdempotency(task.getUserId(), task.getIdempotencyKey());
            if (existing != null) {
                return existing;
            }
            throw new BusinessException(MineWorkMessage.UPLOAD_TASK_SAVE_FAILED_MESSAGE);
        }
    }

    /**
     * 按幂等键查询上传任务。
     *
     * @param userId 当前用户 ID
     * @param idempotencyKey 幂等键
     * @return 上传任务，可为空
     */
    private WorkUploadTaskEntity findUploadTaskByIdempotency(Long userId, String idempotencyKey) {
        return workUploadTaskEntityMapper.selectOne(
                Wrappers.lambdaQuery(WorkUploadTaskEntity.class)
                        .eq(WorkUploadTaskEntity::getUserId, userId)
                        .eq(WorkUploadTaskEntity::getIdempotencyKey, idempotencyKey)
                        .last("LIMIT 1")
        );
    }

    /**
     * 构造票据响应项。
     *
     * @param task 上传任务
     * @param clientId 前端本地 ID
     * @param ticket COS 票据
     * @return 票据响应项
     */
    private MineWorkUploadTicketResponse.Item buildTicketItem(
            WorkUploadTaskEntity task,
            String clientId,
            CosService.PostUploadTicket ticket
    ) {
        MineWorkUploadTicketResponse.Item item = new MineWorkUploadTicketResponse.Item();
        item.setTaskId(task.getId());
        item.setClientId(clientId);
        item.setMediaType(task.getMediaType());
        item.setObjectKey(task.getObjectKey());
        item.setUploadUrl(ticket.uploadUrl());
        item.setFormData(ticket.formData());
        item.setExpiresAt(ticket.expiresAt());
        item.setMaxBytes(ticket.maxBytes());
        return item;
    }

    /**
     * 校验手动上传的视频封面元信息。
     *
     * @param request 封面票据创建请求
     */
    private void validateManualCoverUploadFile(MineWorkCoverUploadTicketRequest request) {
        if (request == null) {
            throw new BusinessException(MineWorkMessage.COVER_REQUIRED_MESSAGE);
        }
        long fileSize = request.getFileSize() == null ? 0L : request.getFileSize();
        if (fileSize <= 0L) {
            throw new BusinessException(MineWorkMessage.COVER_REQUIRED_MESSAGE);
        }
        if (fileSize > THUMB_MAX_BYTES) {
            throw new BusinessException(MineWorkMessage.COVER_TASK_SIZE_MESSAGE);
        }
        String mimeType = normalizeText(request.getMimeType()).toLowerCase(Locale.ROOT);
        if (!mimeType.startsWith(IMAGE_MIME_PREFIX)) {
            throw new BusinessException(MineWorkMessage.COVER_TASK_MEDIA_TYPE_MESSAGE);
        }
        normalizeExtension(request.getFileName(), request.getMimeType(), MediaTypeDict.IMAGE.getCode());
        normalizeClientSha256(request.getSha256());
    }

    /**
     * 构造手动上传视频封面的上传任务。
     *
     * @param userId 当前用户 ID
     * @param work 视频作品
     * @param request 封面票据创建请求
     * @param expiresAt 票据过期时间
     * @return 上传任务
     */
    private WorkUploadTaskEntity buildManualCoverUploadTask(
            Long userId,
            WorkEntity work,
            MineWorkCoverUploadTicketRequest request,
            LocalDateTime expiresAt
    ) {
        String objectKey = buildVersionedCoverObjectKey(work.getMediaObjectKey());
        WorkUploadTaskEntity task = new WorkUploadTaskEntity();
        task.setBatchId(MANUAL_VIDEO_COVER_BATCH_PREFIX + work.getId());
        task.setUserId(userId);
        task.setMediaType(MediaTypeDict.IMAGE.getCode());
        task.setObjectKey(objectKey);
        task.setFileSha256(normalizeClientSha256(request.getSha256()));
        task.setCoverObjectKey(objectKey);
        task.setOriginalFileName(buildThumbFileName(work.getOriginalFileName()));
        task.setMimeType(normalizeText(request.getMimeType()));
        task.setFileSize(request.getFileSize());
        task.setWidth(normalizeMediaDimension(request.getWidth()));
        task.setHeight(normalizeMediaDimension(request.getHeight()));
        task.setStatus(WorkUploadTaskStatusDict.CREATED.getCode());
        task.setExpiresAt(expiresAt);
        task.setIdempotencyKey(normalizeManualCoverIdempotency(work, request));
        return task;
    }

    /**
     * 构造视频封面票据响应。
     *
     * @param task 上传任务
     * @param clientId 前端本地 ID
     * @param ticket COS 票据
     * @return 封面票据响应
     */
    private MineWorkCoverUploadTicketResponse buildCoverTicketResponse(
            WorkUploadTaskEntity task,
            String clientId,
            CosService.PostUploadTicket ticket
    ) {
        MineWorkCoverUploadTicketResponse response = new MineWorkCoverUploadTicketResponse();
        response.setTaskId(task.getId());
        response.setClientId(clientId);
        response.setMediaType(task.getMediaType());
        response.setObjectKey(task.getObjectKey());
        response.setUploadUrl(ticket.uploadUrl());
        response.setFormData(ticket.formData());
        response.setExpiresAt(ticket.expiresAt());
        response.setMaxBytes(ticket.maxBytes());
        return response;
    }

    /**
     * 校验手动上传的图片缩略图元信息。
     *
     * @param request 缩略图票据创建请求
     */
    private void validateThumbnailUploadFile(MineWorkThumbnailUploadTicketRequest request) {
        if (request == null) {
            throw new BusinessException(MineWorkMessage.COVER_REQUIRED_MESSAGE);
        }
        long fileSize = request.getFileSize() == null ? 0L : request.getFileSize();
        if (fileSize <= 0L) {
            throw new BusinessException(MineWorkMessage.COVER_REQUIRED_MESSAGE);
        }
        if (fileSize > THUMB_MAX_BYTES) {
            throw new BusinessException(MineWorkMessage.COVER_TASK_SIZE_MESSAGE);
        }
        String mimeType = normalizeText(request.getMimeType()).toLowerCase(Locale.ROOT);
        if (!mimeType.startsWith(IMAGE_MIME_PREFIX)) {
            throw new BusinessException(MineWorkMessage.COVER_TASK_MEDIA_TYPE_MESSAGE);
        }
        validateThumbnailDimensions(request.getWidth(), request.getHeight());
        normalizeExtension(request.getFileName(), request.getMimeType(), MediaTypeDict.IMAGE.getCode());
        normalizeClientSha256(request.getSha256());
    }

    /**
     * 校验缩略图宽高必须来自小程序可信正数尺寸。
     *
     * @param width 缩略图像素宽度
     * @param height 缩略图像素高度
     */
    private void validateThumbnailDimensions(Integer width, Integer height) {
        if (!isValidMediaDimension(width) || !isValidMediaDimension(height)) {
            throw new BusinessException(MineWorkMessage.COVER_TASK_DIMENSION_MESSAGE);
        }
    }

    /**
     * 构造手动上传图片缩略图的上传任务。
     *
     * @param userId 当前用户 ID
     * @param work 图片作品
     * @param request 缩略图票据创建请求
     * @param expiresAt 票据过期时间
     * @return 上传任务
     */
    private WorkUploadTaskEntity buildImageThumbnailUploadTask(
            Long userId,
            WorkEntity work,
            MineWorkThumbnailUploadTicketRequest request,
            LocalDateTime expiresAt
    ) {
        String objectKey = buildVersionedCoverObjectKey(work.getMediaObjectKey());
        WorkUploadTaskEntity task = new WorkUploadTaskEntity();
        task.setBatchId(IMAGE_THUMBNAIL_BATCH_PREFIX + work.getId());
        task.setUserId(userId);
        task.setMediaType(MediaTypeDict.IMAGE.getCode());
        task.setObjectKey(objectKey);
        task.setFileSha256(normalizeClientSha256(request.getSha256()));
        task.setCoverObjectKey(objectKey);
        task.setOriginalFileName(buildThumbFileName(work.getOriginalFileName()));
        task.setMimeType(normalizeText(request.getMimeType()));
        task.setFileSize(request.getFileSize());
        task.setWidth(normalizeMediaDimension(request.getWidth()));
        task.setHeight(normalizeMediaDimension(request.getHeight()));
        task.setStatus(WorkUploadTaskStatusDict.CREATED.getCode());
        task.setExpiresAt(expiresAt);
        task.setIdempotencyKey(normalizeImageThumbnailIdempotency(work, request));
        return task;
    }

    /**
     * 构造图片缩略图票据响应。
     *
     * @param task 上传任务
     * @param clientId 前端本地 ID
     * @param ticket COS 票据
     * @return 缩略图票据响应
     */
    private MineWorkThumbnailUploadTicketResponse buildThumbnailTicketResponse(
            WorkUploadTaskEntity task,
            String clientId,
            CosService.PostUploadTicket ticket
    ) {
        MineWorkThumbnailUploadTicketResponse response = new MineWorkThumbnailUploadTicketResponse();
        response.setTaskId(task.getId());
        response.setClientId(clientId);
        response.setMediaType(task.getMediaType());
        response.setObjectKey(task.getObjectKey());
        response.setUploadUrl(ticket.uploadUrl());
        response.setFormData(ticket.formData());
        response.setExpiresAt(ticket.expiresAt());
        response.setMaxBytes(ticket.maxBytes());
        return response;
    }

    /**
     * 校验上传文件元信息。
     *
     * @param file 文件元信息
     */
    private void validateUploadFile(MineWorkUploadTicketRequest.UploadFileItem file) {
        if (file == null) {
            throw new BusinessException("上传文件不能为空");
        }
        String mediaType = normalizeMediaType(file.getMediaType());
        long fileSize = file.getFileSize() == null ? 0L : file.getFileSize();
        if (MediaTypeDict.IMAGE.getCode().equals(mediaType) && fileSize > IMAGE_MAX_BYTES) {
            throw new BusinessException("图片作品不能超过 10MB");
        }
        if (MediaTypeDict.VIDEO.getCode().equals(mediaType) && fileSize > VIDEO_MAX_BYTES) {
            throw new BusinessException("视频作品不能超过 100MB");
        }
        if (MediaTypeDict.VIDEO.getCode().equals(mediaType)
                && file.getDurationMs() != null
                && file.getDurationMs() > VIDEO_MAX_DURATION_MS) {
            throw new BusinessException("视频作品不能超过 10 分钟");
        }
        normalizeExtension(file.getFileName(), file.getMimeType(), mediaType);
    }

    /**
     * 校验 COS 对象头。
     *
     * @param task 上传任务
     */
    private CosService.ObjectHead validateCosObject(WorkUploadTaskEntity task) {
        CosService.ObjectHead head;
        try {
            head = cosService.headObject(task.getObjectKey());
        } catch (RuntimeException e) {
            throw new BusinessException(MineWorkMessage.COS_OBJECT_READ_FAILED_MESSAGE);
        }
        if (task.getFileSize() != null && head.contentLength() != task.getFileSize()) {
            throw new BusinessException("上传文件大小与任务不一致");
        }
        if (head.contentLength() > maxBytes(task.getMediaType())) {
            throw new BusinessException("上传文件超过限制");
        }
        if (!contentTypeCompatible(task.getMimeType(), head.contentType())) {
            throw new BusinessException("上传文件类型与任务不一致");
        }
        return head;
    }

    /**
     * 视频作品上传确认时如未携带封面任务，由后端使用数据万象生成默认首帧封面。
     *
     * @param userId 当前用户 ID
     * @param task 主上传任务
     * @param item 确认参数
     */
    private void ensureGeneratedVideoCoverTask(
            Long userId,
            WorkUploadTaskEntity task,
            MineWorkUploadCompleteRequest.CompleteItem item
    ) {
        if (item == null
                || item.getCoverTaskId() != null
                || !MediaTypeDict.VIDEO.getCode().equals(task.getMediaType())) {
            return;
        }
        String coverObjectKey = buildThumbObjectKeyFromSource(task, THUMB_FILE_EXTENSION);
        MediaDimensions mediaDimensions = resolveMediaDimensions(task.getWidth(), task.getHeight(), null, null);
        SnapshotDimensions snapshotDimensions = buildSnapshotDimensions(mediaDimensions);
        log.info("视频作品默认封面截帧入参: userId={}, sourceTaskId={}, batchId={}, sourceObjectKey={}, coverObjectKey={}, frameTimeMs={}, originalFileName={}, mediaWidth={}, mediaHeight={}, snapshotWidth={}, snapshotHeight={}",
                userId,
                task.getId(),
                normalizeText(task.getBatchId()),
                task.getObjectKey(),
                coverObjectKey,
                DEFAULT_VIDEO_COVER_FRAME_TIME_MS,
                task.getOriginalFileName(),
                mediaDimensions.width(),
                mediaDimensions.height(),
                snapshotDimensions.width(),
                snapshotDimensions.height());
        WorkUploadTaskEntity coverTask = generateVideoCoverTask(
                userId,
                task,
                DEFAULT_VIDEO_COVER_FRAME_TIME_MS,
                coverObjectKey,
                buildThumbFileName(task.getOriginalFileName()),
                snapshotDimensions);
        item.setCoverTaskId(coverTask.getId());
    }

    /**
     * 为已存在的视频作品生成替换封面。
     *
     * @param work 视频作品
     * @param requestedFrameTimeMs 请求的截帧时间点
     * @return 已生成封面对象
     */
    private CosService.SnapshotObject generateReplacementVideoCover(
            WorkEntity work,
            Long requestedFrameTimeMs,
            MediaDimensions requestDimensions
    ) {
        if (!MediaTypeDict.VIDEO.getCode().equals(work.getMediaType())) {
            throw new BusinessException(MineWorkMessage.VIDEO_COVER_UPDATE_MEDIA_TYPE_MESSAGE);
        }
        long frameTimeMs = normalizeCoverFrameTimeMs(requestedFrameTimeMs, work.getDurationMs());
        String coverObjectKey = buildFrameCoverObjectKey(work.getMediaObjectKey(), frameTimeMs);
        MediaDimensions mediaDimensions = requestDimensions == null
                ? resolveMediaDimensions(null, null, work.getWidth(), work.getHeight())
                : requestDimensions;
        SnapshotDimensions snapshotDimensions = buildSnapshotDimensions(mediaDimensions);
        log.info("视频作品替换封面截帧入参: workId={}, sourceObjectKey={}, oldCoverObjectKey={}, coverObjectKey={}, requestedFrameTimeMs={}, frameTimeMs={}, durationMs={}, requestWidth={}, requestHeight={}, mediaWidth={}, mediaHeight={}, snapshotWidth={}, snapshotHeight={}",
                work.getId(),
                work.getMediaObjectKey(),
                work.getCoverObjectKey(),
                coverObjectKey,
                requestedFrameTimeMs,
                frameTimeMs,
                work.getDurationMs(),
                requestDimensions == null ? null : requestDimensions.width(),
                requestDimensions == null ? null : requestDimensions.height(),
                mediaDimensions.width(),
                mediaDimensions.height(),
                snapshotDimensions.width(),
                snapshotDimensions.height());
        return generateVideoCoverObject(work.getMediaObjectKey(), coverObjectKey, frameTimeMs, snapshotDimensions);
    }

    /**
     * 生成视频封面上传任务。
     *
     * @param userId 当前用户 ID
     * @param sourceTask 视频主任务
     * @param frameTimeMs 截帧时间点
     * @param coverObjectKey 封面对象键
     * @param coverFileName 封面文件名
     * @return 封面上传任务
     */
    private WorkUploadTaskEntity generateVideoCoverTask(
            Long userId,
            WorkUploadTaskEntity sourceTask,
            long frameTimeMs,
            String coverObjectKey,
            String coverFileName,
            SnapshotDimensions snapshotDimensions
    ) {
        String idempotencyKey = GENERATED_VIDEO_COVER_IDEMPOTENCY_PREFIX
                + sourceTask.getId()
                + WORK_FILE_NAME_SEPARATOR
                + frameTimeMs;
        WorkUploadTaskEntity existing = findUploadTaskByIdempotency(userId, idempotencyKey);
        if (existing != null) {
            return existing;
        }
        CosService.SnapshotObject snapshot = generateVideoCoverObject(
                sourceTask.getObjectKey(),
                coverObjectKey,
                frameTimeMs,
                snapshotDimensions);
        WorkUploadTaskEntity coverTask = new WorkUploadTaskEntity();
        coverTask.setBatchId(normalizeText(sourceTask.getBatchId()));
        coverTask.setUserId(userId);
        coverTask.setMediaType(MediaTypeDict.IMAGE.getCode());
        coverTask.setObjectKey(snapshot.objectKey());
        coverTask.setFileSha256(snapshot.sha256());
        coverTask.setCoverObjectKey(snapshot.objectKey());
        coverTask.setOriginalFileName(coverFileName);
        coverTask.setMimeType(snapshot.contentType());
        coverTask.setFileSize(snapshot.contentLength());
        coverTask.setStatus(WorkUploadTaskStatusDict.UPLOADED.getCode());
        coverTask.setExpiresAt(LocalDateTime.now().plusMinutes(TICKET_EXPIRE_MINUTES));
        coverTask.setIdempotencyKey(idempotencyKey);
        return saveOrFindUploadTask(coverTask);
    }

    /**
     * 调用数据万象生成视频封面对象。
     *
     * @param sourceObjectKey 视频对象键
     * @param coverObjectKey 封面对象键
     * @param frameTimeMs 截帧时间点
     * @return 已生成封面对象
     */
    private CosService.SnapshotObject generateVideoCoverObject(
            String sourceObjectKey,
            String coverObjectKey,
            long frameTimeMs,
            SnapshotDimensions snapshotDimensions
    ) {
        try {
            log.info("视频封面数据万象截帧调用入参: sourceObjectKey={}, coverObjectKey={}, frameTimeMs={}, snapshotWidth={}, snapshotHeight={}",
                    sourceObjectKey, coverObjectKey, frameTimeMs, snapshotDimensions.width(), snapshotDimensions.height());
            CosService.SnapshotObject snapshot = cosService.snapshotVideoFrameToObject(
                    sourceObjectKey,
                    coverObjectKey,
                    frameTimeMs,
                    snapshotDimensions.width(),
                    snapshotDimensions.height());
            if (snapshot.contentLength() > THUMB_MAX_BYTES) {
                cosService.delete(snapshot.objectKey());
                throw new BusinessException(MineWorkMessage.COVER_TASK_SIZE_MESSAGE);
            }
            return snapshot;
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BusinessException(MineWorkMessage.VIDEO_COVER_GENERATE_FAILED_MESSAGE, e);
        }
    }

    /**
     * 校验缩略图或封面图上传任务。
     *
     * @param userId 当前用户 ID
     * @param task 主上传任务
     * @param head 主上传对象头
     * @param item 确认参数
     */
    private void validateCoverTask(
            Long userId,
            WorkUploadTaskEntity task,
            CosService.ObjectHead head,
            MineWorkUploadCompleteRequest.CompleteItem item
    ) {
        Long coverTaskId = item == null ? null : item.getCoverTaskId();
        if (coverTaskId == null) {
            if (MediaTypeDict.VIDEO.getCode().equals(task.getMediaType())) {
                if (hasText(task.getCoverObjectKey())) {
                    return;
                }
                throw new BusinessException(MineWorkMessage.VIDEO_COVER_REQUIRED_MESSAGE);
            }
            if (MediaTypeDict.IMAGE.getCode().equals(task.getMediaType())
                    && head.contentLength() > THUMB_MAX_BYTES) {
                if (hasText(task.getCoverObjectKey())) {
                    return;
                }
                throw new BusinessException(MineWorkMessage.IMAGE_THUMB_REQUIRED_MESSAGE);
            }
            return;
        }
        WorkUploadCoverTaskValidator.ensureNotSelfReference(task, coverTaskId);
        WorkUploadTaskEntity coverTask = requireOwnedUploadTask(userId, coverTaskId);
        WorkUploadCoverTaskValidator.ensureImageCoverTask(coverTask);
        CosService.ObjectHead coverHead = validateCosObject(coverTask);
        if (coverHead.contentLength() > THUMB_MAX_BYTES) {
            throw new BusinessException(MineWorkMessage.COVER_TASK_SIZE_MESSAGE);
        }
        validateCoverFileName(task, coverTask);
    }

    /**
     * 校验视频作品替换封面使用的上传任务。
     *
     * @param work 视频作品
     * @param coverTaskId 封面上传任务 ID
     * @return 封面上传任务
     */
    private WorkUploadTaskEntity validateReplacementCoverTask(WorkEntity work, Long coverTaskId) {
        if (!MediaTypeDict.VIDEO.getCode().equals(work.getMediaType())) {
            throw new BusinessException(MineWorkMessage.VIDEO_COVER_UPDATE_MEDIA_TYPE_MESSAGE);
        }
        WorkUploadTaskEntity coverTask = requireOwnedUploadTask(work.getUserId(), coverTaskId);
        WorkUploadCoverTaskValidator.ensureImageCoverTask(coverTask);
        if (WorkUploadTaskStatusDict.CONFIRMED.getCode().equals(coverTask.getStatus())) {
            throw new BusinessException(MineWorkMessage.COVER_TASK_USED_MESSAGE);
        }
        if (coverTask.getExpiresAt() != null && coverTask.getExpiresAt().isBefore(LocalDateTime.now())) {
            coverTask.setStatus(WorkUploadTaskStatusDict.EXPIRED.getCode());
            coverTask.setErrorMessage("封面上传任务已过期");
            workUploadTaskEntityMapper.updateById(coverTask);
            throw new BusinessException(MineWorkMessage.COVER_TASK_EXPIRED_MESSAGE);
        }
        validateCoverFileName(work.getOriginalFileName(), coverTask);
        CosService.ObjectHead coverHead = validateCosObject(coverTask);
        if (coverHead.contentLength() > THUMB_MAX_BYTES) {
            throw new BusinessException(MineWorkMessage.COVER_TASK_SIZE_MESSAGE);
        }
        return coverTask;
    }

    /**
     * 校验图片作品替换缩略图使用的上传任务。
     *
     * @param work 图片作品
     * @param thumbnailTaskId 缩略图上传任务 ID
     * @return 缩略图上传任务
     */
    private WorkUploadTaskEntity validateImageThumbnailTask(WorkEntity work, Long thumbnailTaskId) {
        if (!MediaTypeDict.IMAGE.getCode().equals(work.getMediaType())) {
            throw new BusinessException(MineWorkMessage.IMAGE_THUMBNAIL_UPDATE_MEDIA_TYPE_MESSAGE);
        }
        WorkUploadTaskEntity thumbnailTask = requireOwnedUploadTask(work.getUserId(), thumbnailTaskId);
        WorkUploadCoverTaskValidator.ensureImageThumbnailTask(thumbnailTask);
        ensureThumbnailTaskDoesNotTargetOriginal(work, thumbnailTask.getObjectKey());
        if (WorkUploadTaskStatusDict.CONFIRMED.getCode().equals(thumbnailTask.getStatus())) {
            throw new BusinessException(MineWorkMessage.COVER_TASK_USED_MESSAGE);
        }
        if (thumbnailTask.getExpiresAt() != null && thumbnailTask.getExpiresAt().isBefore(LocalDateTime.now())) {
            thumbnailTask.setStatus(WorkUploadTaskStatusDict.EXPIRED.getCode());
            thumbnailTask.setErrorMessage(IMAGE_THUMBNAIL_TASK_EXPIRED_ERROR_MESSAGE);
            workUploadTaskEntityMapper.updateById(thumbnailTask);
            throw new BusinessException(MineWorkMessage.COVER_TASK_EXPIRED_MESSAGE);
        }
        validateCoverFileName(work.getOriginalFileName(), thumbnailTask);
        CosService.ObjectHead thumbnailHead = validateCosObject(thumbnailTask);
        if (thumbnailHead.contentLength() > THUMB_MAX_BYTES) {
            throw new BusinessException(MineWorkMessage.COVER_TASK_SIZE_MESSAGE);
        }
        return thumbnailTask;
    }

    /**
     * 确认替换封面的上传任务已被当前作品使用。
     *
     * @param coverTask 封面上传任务
     * @param workId 作品 ID
     * @param oldCoverObjectKey 旧封面对象键
     * @param mediaObjectKey 视频源文件对象键
     */
    private void confirmReplacementCoverTask(
            WorkUploadTaskEntity coverTask,
            Long workId,
            String oldCoverObjectKey,
            String mediaObjectKey
    ) {
        coverTask.setStatus(WorkUploadTaskStatusDict.CONFIRMED.getCode());
        coverTask.setConfirmedWorkId(workId);
        coverTask.setCoverObjectKey(coverTask.getObjectKey());
        coverTask.setErrorMessage(null);
        int updated = workUploadTaskEntityMapper.updateById(coverTask);
        if (updated <= 0) {
            deleteUploadedCoverIfNeeded(coverTask, oldCoverObjectKey, mediaObjectKey);
            throw new BusinessException(MineWorkMessage.WORK_SAVE_FAILED_MESSAGE);
        }
    }

    /**
     * 确认替换缩略图的上传任务已被当前图片作品使用。
     *
     * @param thumbnailTask 缩略图上传任务
     * @param workId 作品 ID
     * @param oldCoverObjectKey 旧缩略图对象键
     * @param mediaObjectKey 图片原图对象键
     */
    private void confirmImageThumbnailTask(
            WorkUploadTaskEntity thumbnailTask,
            Long workId,
            String oldCoverObjectKey,
            String mediaObjectKey
    ) {
        thumbnailTask.setStatus(WorkUploadTaskStatusDict.CONFIRMED.getCode());
        thumbnailTask.setConfirmedWorkId(workId);
        thumbnailTask.setCoverObjectKey(thumbnailTask.getObjectKey());
        thumbnailTask.setErrorMessage(null);
        int updated = workUploadTaskEntityMapper.updateById(thumbnailTask);
        if (updated <= 0) {
            deleteUploadedCoverIfNeeded(thumbnailTask, oldCoverObjectKey, mediaObjectKey);
            throw new BusinessException(MineWorkMessage.WORK_SAVE_FAILED_MESSAGE);
        }
    }

    /**
     * 删除作品被替换下来的旧封面对象。
     *
     * @param oldCoverObjectKey 旧封面对象键
     * @param newCoverObjectKey 新封面对象键
     * @param mediaObjectKey 作品原文件对象键
     */
    private void deleteOldCoverIfNeeded(String oldCoverObjectKey, String newCoverObjectKey, String mediaObjectKey) {
        if (!hasText(oldCoverObjectKey)
                || oldCoverObjectKey.equals(newCoverObjectKey)
                || oldCoverObjectKey.equals(mediaObjectKey)) {
            return;
        }
        Runnable deleteTask = () -> cosService.delete(oldCoverObjectKey);
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
     * 保存失败时删除刚生成但未入库的视频封面对象。
     *
     * @param generatedCover 新生成封面对象
     * @param oldCoverObjectKey 旧封面对象键
     * @param mediaObjectKey 视频源文件对象键
     */
    private void deleteGeneratedCoverIfNeeded(
            CosService.SnapshotObject generatedCover,
            String oldCoverObjectKey,
            String mediaObjectKey
    ) {
        if (generatedCover == null
                || !hasText(generatedCover.objectKey())
                || generatedCover.objectKey().equals(oldCoverObjectKey)
                || generatedCover.objectKey().equals(mediaObjectKey)) {
            return;
        }
        cosService.delete(generatedCover.objectKey());
    }

    /**
     * 保存失败时删除已上传但未入库的手动封面对象。
     *
     * @param coverTask 封面上传任务
     * @param oldCoverObjectKey 旧封面对象键
     * @param mediaObjectKey 视频源文件对象键
     */
    private void deleteUploadedCoverIfNeeded(
            WorkUploadTaskEntity coverTask,
            String oldCoverObjectKey,
            String mediaObjectKey
    ) {
        String objectKey = coverTask == null ? "" : normalizeText(coverTask.getObjectKey());
        if (objectKey.isBlank()
                || objectKey.equals(oldCoverObjectKey)
                || objectKey.equals(mediaObjectKey)) {
            return;
        }
        cosService.delete(objectKey);
    }

    /**
     * 校验缩略图或封面图文件名。
     *
     * @param task 主上传任务
     * @param coverTask 缩略图或封面图任务
     */
    private void validateCoverFileName(WorkUploadTaskEntity task, WorkUploadTaskEntity coverTask) {
        validateCoverFileName(task.getOriginalFileName(), coverTask);
    }

    /**
     * 校验缩略图或封面图文件名。
     *
     * @param originalFileName 原作品文件名
     * @param coverTask 缩略图或封面图任务
     */
    private void validateCoverFileName(String originalFileName, WorkUploadTaskEntity coverTask) {
        String expectedFileName = buildThumbFileName(originalFileName);
        if (!expectedFileName.equals(normalizeText(coverTask.getOriginalFileName()))) {
            throw new BusinessException(MineWorkMessage.COVER_TASK_FILE_NAME_MESSAGE);
        }
    }

    /**
     * 判断 COS 头 Content-Type 是否兼容任务 MIME。
     *
     * @param expected 任务 MIME
     * @param actual COS 头 MIME
     * @return 是否兼容
     */
    private boolean contentTypeCompatible(String expected, String actual) {
        String normalizedExpected = normalizeText(expected).toLowerCase(Locale.ROOT);
        String normalizedActual = normalizeText(actual).toLowerCase(Locale.ROOT);
        if (normalizedExpected.isBlank() || normalizedActual.isBlank()
                || "application/octet-stream".equals(normalizedActual)) {
            return true;
        }
        return normalizedActual.startsWith(normalizedExpected);
    }

    /**
     * 标记上传任务失败。
     *
     * @param userId 当前用户 ID
     * @param taskId 上传任务 ID
     * @param message 失败消息
     */
    private void markTaskFailedIfPossible(Long userId, Long taskId, String message) {
        if (taskId == null) {
            return;
        }
        WorkUploadTaskEntity task = workUploadTaskEntityMapper.selectById(taskId);
        if (task == null
                || !userId.equals(task.getUserId())
                || WorkUploadTaskStatusDict.CONFIRMED.getCode().equals(task.getStatus())) {
            return;
        }
        task.setStatus(WorkUploadTaskStatusDict.FAILED.getCode());
        task.setErrorMessage(message);
        workUploadTaskEntityMapper.updateById(task);
    }

    /**
     * 查询上传任务并校验归属。
     *
     * @param userId 当前用户 ID
     * @param taskId 上传任务 ID
     * @return 上传任务
     */
    private WorkUploadTaskEntity requireOwnedUploadTask(Long userId, Long taskId) {
        if (taskId == null) {
            throw new BusinessException("上传任务不能为空");
        }
        WorkUploadTaskEntity task = workUploadTaskEntityMapper.selectById(taskId);
        if (task == null || !userId.equals(task.getUserId())) {
            throw new BusinessException("上传任务不存在");
        }
        return task;
    }

    /**
     * 归一化前端提交的 SHA-256。
     *
     * <p>SHA-256 由小程序端计算提交。后端为避免下载 COS 文件带来的性能开销，信任前端值，
     * 仅做必填、格式和唯一性校验。</p>
     *
     * @param value 前端提交的 SHA-256
     * @return 小写 SHA-256
     */
    private String normalizeClientSha256(String value) {
        String normalized = normalizeText(value).toLowerCase(Locale.ROOT);
        if (!normalized.matches(SHA256_PATTERN)) {
            throw new BusinessException(MineWorkMessage.FILE_SHA256_INVALID_MESSAGE);
        }
        return normalized;
    }

    /**
     * 按客户端 SHA-256 查询当前用户未删除作品。
     *
     * @param userId 当前用户 ID
     * @param mediaSha256 原文件 SHA-256
     * @return 已存在作品，可为空
     */
    private WorkEntity findNonDeletedWorkBySha256(Long userId, String mediaSha256) {
        return workEntityMapper.selectOne(
                Wrappers.lambdaQuery(WorkEntity.class)
                        .eq(WorkEntity::getUserId, userId)
                        .eq(WorkEntity::getMediaSha256, mediaSha256)
                        .last("LIMIT 1")
        );
    }

    /**
     * 查询当前用户作品。
     *
     * @param workId 作品 ID
     * @return 作品
     */
    private WorkEntity requireOwnedWork(Long workId) {
        Long userId = AuthContextHolder.requireUserId();
        if (workId == null) {
            throw new BusinessException(MineWorkMessage.WORK_EMPTY_MESSAGE);
        }
        WorkEntity work = workEntityMapper.selectById(workId);
        if (work == null || !userId.equals(work.getUserId())) {
            throw new BusinessException(MineWorkMessage.WORK_NOT_FOUND_MESSAGE);
        }
        return work;
    }

    /**
     * 查询启用用户。
     *
     * @param userId 当前用户 ID
     * @return 用户实体
     */
    private UserEntity requireActiveUser(Long userId) {
        UserEntity user = userEntityMapper.selectById(userId);
        if (user == null || !UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) {
            throw new BusinessException("用户不存在或已停用");
        }
        if (!hasText(user.getUniqueCode())) {
            throw new BusinessException("用户存储目录未初始化");
        }
        return user;
    }

    /**
     * 构造作品列表项。
     *
     * @param work 作品
     * @return 列表项
     */
    private MineWorkListResponse.WorkItem buildWorkItem(WorkEntity work) {
        return buildWorkItem(
                work,
                Map.of(work.getId(), (long) findWorkReferences(work.getId()).size()),
                Map.of(work.getId(), findWorkTags(work.getUserId(), work.getId()))
        );
    }

    /**
     * 按预加载数据构造作品列表项。
     *
     * @param work 作品
     * @param referenceCounts 作品引用计数
     * @param workTags 作品标签映射
     * @return 列表项
     */
    private MineWorkListResponse.WorkItem buildWorkItem(
            WorkEntity work,
            Map<Long, Long> referenceCounts,
            Map<Long, List<MineWorkListResponse.TagItem>> workTags
    ) {
        MineWorkListResponse.WorkItem item = new MineWorkListResponse.WorkItem();
        item.setId(work.getId());
        item.setMediaType(work.getMediaType());
        item.setTitle(work.getTitle());
        item.setOriginalFileName(work.getOriginalFileName());
        item.setMediaUrl(cosService.publicUrl(work.getMediaObjectKey()));
        item.setCoverUrl(hasText(work.getCoverObjectKey()) ? cosService.publicUrl(work.getCoverObjectKey()) : "");
        item.setMimeType(work.getMimeType());
        item.setFileSize(work.getFileSize());
        item.setDurationMs(work.getDurationMs());
        item.setWidth(work.getWidth());
        item.setHeight(work.getHeight());
        item.setAspectRatio(work.getAspectRatio());
        item.setDescription(work.getDescription());
        item.setServiceDate(work.getServiceDate());
        item.setSortOrder(work.getSortOrder());
        item.setStatus(work.getStatus());
        item.setAuditStatus(work.getAuditStatus());
        item.setAuditStatusText(resolveAuditStatusText(work.getAuditStatus()));
        item.setAuditRejectReason(work.getAuditRejectReason());
        item.setReferenceCount(referenceCounts.getOrDefault(work.getId(), 0L));
        item.setTags(workTags.getOrDefault(work.getId(), List.of()));
        item.setCreatedAt(work.getCreatedAt());
        item.setUpdatedAt(work.getUpdatedAt());
        return item;
    }

    /**
     * 按审核状态字典生成展示文案。
     *
     * @param auditStatus 审核状态
     * @return 审核状态展示文案
     */
    private String resolveAuditStatusText(String auditStatus) {
        WorkAuditStatusDict status = WorkAuditStatusDict.fromCode(auditStatus);
        return status == null ? "" : status.getDisplayName();
    }

    /**
     * 构造引用项。
     *
     * @param reference 引用实体
     * @return 引用项
     */
    private MineWorkDetailResponse.ReferenceItem buildReferenceItem(PortfolioReferenceEntity reference) {
        MineWorkDetailResponse.ReferenceItem item = new MineWorkDetailResponse.ReferenceItem();
        item.setId(reference.getId());
        item.setPortfolioId(reference.getPortfolioId());
        item.setComponentKey(reference.getComponentKey());
        item.setComponentPath(reference.getComponentPath());
        item.setValid(Integer.valueOf(1).equals(reference.getIsValid()));
        return item;
    }

    /**
     * 构造作品摘要。
     *
     * @param userId 当前用户 ID
     * @param mediaType 媒体类型，可为空
     * @param auditStatus 审核状态，可为空
     * @return 摘要
     */
    private MineWorkListResponse.Summary buildSummary(Long userId, String mediaType, String auditStatus) {
        MineWorkListResponse.Summary summary = new MineWorkListResponse.Summary();
        List<Map<String, Object>> rows = workEntityMapper.selectMaps(
                new QueryWrapper<WorkEntity>()
                        .select(
                                WORK_COLUMN_MEDIA_TYPE + " AS " + WORK_MEDIA_TYPE_ALIAS,
                                SQL_COUNT_ALL_EXPRESSION + " AS " + COUNT_ALIAS)
                        .eq(WORK_COLUMN_USER_ID, userId)
                        .eq(WORK_COLUMN_STATUS, WorkStatusDict.ACTIVE.getCode())
                        .eq(hasText(mediaType), WORK_COLUMN_MEDIA_TYPE, mediaType)
                        .eq(hasText(auditStatus), WORK_COLUMN_AUDIT_STATUS, auditStatus)
                        .groupBy(WORK_COLUMN_MEDIA_TYPE)
        );
        if (rows == null || rows.isEmpty()) {
            return summary;
        }
        for (Map<String, Object> row : rows) {
            String rowMediaType = rowValueAsString(row.get(WORK_MEDIA_TYPE_ALIAS));
            long count = rowValueAsLong(row.get(COUNT_ALIAS));
            summary.setTotalCount(summary.getTotalCount() + count);
            if (MediaTypeDict.IMAGE.getCode().equals(rowMediaType)) {
                summary.setImageCount(count);
            }
            if (MediaTypeDict.VIDEO.getCode().equals(rowMediaType)) {
                summary.setVideoCount(count);
            }
        }
        return summary;
    }

    /**
     * 将查询结果转为字符串。
     *
     * @param value 查询值
     * @return 字符串
     */
    private String rowValueAsString(Object value) {
        return value == null ? "" : value.toString();
    }

    /**
     * 将查询结果转为长整型。
     *
     * @param value 查询值
     * @return 长整型
     */
    private long rowValueAsLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        return Long.parseLong(value.toString());
    }

    /**
     * 构造标签筛选项。
     *
     * @param tags 启用标签
     * @param tagUsageCounts 标签使用次数
     * @param activeTagId 当前标签 ID
     * @param totalCount 全部作品数量
     * @return 标签项
     */
    private List<MineWorkListResponse.TagItem> buildTagItems(
            List<WfTagEntity> tags,
            Map<Long, Long> tagUsageCounts,
            Long activeTagId,
            long totalCount
    ) {
        List<MineWorkListResponse.TagItem> items = new ArrayList<>();
        MineWorkListResponse.TagItem all = new MineWorkListResponse.TagItem();
        all.setName("全部");
        all.setCount(totalCount);
        all.setActive(activeTagId == null);
        items.add(all);
        for (WfTagEntity tag : tags) {
            MineWorkListResponse.TagItem item = buildTagItem(tag);
            item.setCount(tagUsageCounts.getOrDefault(tag.getId(), 0L));
            item.setActive(tag.getId().equals(activeTagId));
            items.add(item);
        }
        return items;
    }

    /**
     * 查询当前用户启用标签。
     *
     * @param userId 当前用户 ID
     * @return 启用标签
     */
    private List<WfTagEntity> findActiveTags(Long userId) {
        return wfTagEntityMapper.selectList(
                Wrappers.lambdaQuery(WfTagEntity.class)
                        .eq(WfTagEntity::getUserId, userId)
                        .eq(WfTagEntity::getStatus, WfTagStatusDict.ACTIVE.getCode())
                        .orderByAsc(WfTagEntity::getId)
        );
    }

    /**
     * 将标签列表转换为按 ID 索引的映射。
     *
     * @param tags 标签列表
     * @return 标签映射
     */
    private Map<Long, WfTagEntity> buildTagMap(List<WfTagEntity> tags) {
        return tags.stream()
                .collect(Collectors.toMap(WfTagEntity::getId, tag -> tag, (left, right) -> left, LinkedHashMap::new));
    }

    /**
     * 查询当前用户指定标签的作品关系。
     *
     * @param userId 当前用户 ID
     * @param tagIds 标签 ID
     * @return 标签关系
     */
    private List<WorkTagEntity> findRelationsForTags(Long userId, Collection<Long> tagIds) {
        if (tagIds.isEmpty()) {
            return List.of();
        }
        return workTagEntityMapper.selectList(
                Wrappers.lambdaQuery(WorkTagEntity.class)
                        .eq(WorkTagEntity::getUserId, userId)
                        .in(WorkTagEntity::getTagId, tagIds)
        );
    }

    /**
     * 统计每个标签关联的作品数量。
     *
     * @param relations 标签关系
     * @return 标签作品数
     */
    private Map<Long, Long> buildTagUsageCounts(List<WorkTagEntity> relations) {
        return relations.stream()
                .collect(Collectors.groupingBy(
                        WorkTagEntity::getTagId,
                        LinkedHashMap::new,
                        Collectors.counting()));
    }

    /**
     * 按作品筛选条件过滤标签关系。
     *
     * @param userId 当前用户 ID
     * @param relations 标签关系
     * @param mediaType 媒体类型，可为空
     * @param auditStatus 审核状态，可为空
     * @return 过滤后的标签关系
     */
    private List<WorkTagEntity> filterTagRelationsByWorkFilters(
            Long userId,
            List<WorkTagEntity> relations,
            String mediaType,
            String auditStatus
    ) {
        if ((!hasText(mediaType) && !hasText(auditStatus)) || relations.isEmpty()) {
            return relations;
        }
        Set<Long> relationWorkIds = relations.stream()
                .map(WorkTagEntity::getWorkId)
                .collect(Collectors.toSet());
        Set<Long> matchedWorkIds = workEntityMapper.selectList(
                        new QueryWrapper<WorkEntity>()
                                .select(WORK_COLUMN_ID)
                                .eq(WORK_COLUMN_USER_ID, userId)
                                .eq(WORK_COLUMN_STATUS, WorkStatusDict.ACTIVE.getCode())
                                .eq(hasText(mediaType), WORK_COLUMN_MEDIA_TYPE, mediaType)
                                .eq(hasText(auditStatus), WORK_COLUMN_AUDIT_STATUS, auditStatus)
                                .in(WORK_COLUMN_ID, relationWorkIds))
                .stream()
                .map(WorkEntity::getId)
                .collect(Collectors.toSet());
        return relations.stream()
                .filter(relation -> matchedWorkIds.contains(relation.getWorkId()))
                .toList();
    }

    /**
     * 构造作品在当前标签下的排序值映射。
     *
     * @param relations 标签关系
     * @return 作品排序值映射
     */
    private Map<Long, Integer> buildRelationSortOrderMap(List<WorkTagEntity> relations) {
        return relations.stream()
                .collect(Collectors.toMap(
                        WorkTagEntity::getWorkId,
                        relation -> relation.getSortOrder() == null ? 0 : relation.getSortOrder(),
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    /**
     * 批量统计本页作品引用数量。
     *
     * @param works 本页作品
     * @return 作品引用计数
     */
    private Map<Long, Long> buildWorkReferenceCounts(Collection<WorkEntity> works) {
        List<Long> workIds = works.stream()
                .map(WorkEntity::getId)
                .toList();
        if (workIds.isEmpty()) {
            return Map.of();
        }
        return portfolioReferenceEntityMapper.selectList(
                        Wrappers.lambdaQuery(PortfolioReferenceEntity.class)
                                .eq(PortfolioReferenceEntity::getReferenceType, ReferenceTypeDict.WORK.getCode())
                                .in(PortfolioReferenceEntity::getReferenceId, workIds)
                                .eq(PortfolioReferenceEntity::getIsValid, 1))
                .stream()
                .collect(Collectors.groupingBy(
                        PortfolioReferenceEntity::getReferenceId,
                        LinkedHashMap::new,
                        Collectors.mapping(
                                PortfolioReferenceEntity::getPortfolioId,
                                Collectors.collectingAndThen(Collectors.toSet(), set -> (long) set.size())
                        )));
    }

    /**
     * 按作品批量构造标签项。
     *
     * @param works 本页作品
     * @param relations 标签关系
     * @param tagMap 标签映射
     * @return 作品标签项映射
     */
    private Map<Long, List<MineWorkListResponse.TagItem>> buildWorkTagItems(
            Collection<WorkEntity> works,
            List<WorkTagEntity> relations,
            Map<Long, WfTagEntity> tagMap
    ) {
        Set<Long> workIds = works.stream()
                .map(WorkEntity::getId)
                .collect(Collectors.toSet());
        Map<Long, List<MineWorkListResponse.TagItem>> items = new LinkedHashMap<>();
        for (WorkTagEntity relation : relations) {
            if (!workIds.contains(relation.getWorkId())) {
                continue;
            }
            WfTagEntity tag = tagMap.get(relation.getTagId());
            if (tag == null || !WfTagStatusDict.ACTIVE.getCode().equals(tag.getStatus())) {
                continue;
            }
            items.computeIfAbsent(relation.getWorkId(), workId -> new ArrayList<>()).add(buildTagItem(tag));
        }
        return items;
    }

    /**
     * 查询作品标签。
     *
     * @param userId 当前用户 ID
     * @param workId 作品 ID
     * @return 标签项
     */
    private List<MineWorkListResponse.TagItem> findWorkTags(Long userId, Long workId) {
        List<WorkTagEntity> relations = workTagEntityMapper.selectList(
                Wrappers.lambdaQuery(WorkTagEntity.class)
                        .eq(WorkTagEntity::getUserId, userId)
                        .eq(WorkTagEntity::getWorkId, workId)
        );
        if (relations.isEmpty()) {
            return List.of();
        }
        List<Long> tagIds = relations.stream()
                .map(WorkTagEntity::getTagId)
                .toList();
        Map<Long, WfTagEntity> tagMap = wfTagEntityMapper.selectBatchIds(tagIds).stream()
                .collect(Collectors.toMap(WfTagEntity::getId, tag -> tag, (left, right) -> left, LinkedHashMap::new));
        return tagIds.stream()
                .map(tagMap::get)
                .filter(tag -> tag != null && WfTagStatusDict.ACTIVE.getCode().equals(tag.getStatus()))
                .map(this::buildTagItem)
                .toList();
    }

    /**
     * 准备作品标签变更，校验标签归属和引用删除限制。
     *
     * @param userId 当前用户 ID
     * @param workId 作品 ID
     * @param tagIds 请求提交的标签 ID
     * @return 标签变更计划
     */
    private WorkTagChange prepareWorkTagChange(Long userId, Long workId, List<Long> tagIds) {
        List<Long> requestedTagIds = normalizeRequestedWorkTagIds(tagIds);
        if (requestedTagIds.size() > TAG_MAX_COUNT) {
            throw new BusinessException(MineWorkMessage.WORK_TAG_COUNT_LIMIT_MESSAGE);
        }
        requestedTagIds.forEach(tagId -> requireOwnedActiveTag(userId, tagId));
        Set<Long> requestedTagIdSet = new LinkedHashSet<>(requestedTagIds);
        Set<Long> existingTagIds = workTagEntityMapper.selectList(
                        Wrappers.lambdaQuery(WorkTagEntity.class)
                                .eq(WorkTagEntity::getUserId, userId)
                                .eq(WorkTagEntity::getWorkId, workId))
                .stream()
                .map(WorkTagEntity::getTagId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> addTagIds = new LinkedHashSet<>(requestedTagIdSet);
        addTagIds.removeAll(existingTagIds);
        Set<Long> removeTagIds = new LinkedHashSet<>(existingTagIds);
        removeTagIds.removeAll(requestedTagIdSet);
        if (!removeTagIds.isEmpty() && !findWorkReferences(workId).isEmpty()) {
            throw new BusinessException(MineWorkMessage.WORK_TAG_REMOVE_REFERENCED_MESSAGE);
        }
        return new WorkTagChange(addTagIds, removeTagIds);
    }

    /**
     * 归一化作品标签 ID，过滤空值和非法 ID 并保持提交顺序。
     *
     * @param tagIds 请求提交的标签 ID
     * @return 去重后的有效标签 ID
     */
    private List<Long> normalizeRequestedWorkTagIds(List<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        for (Long tagId : tagIds) {
            if (tagId != null && tagId > 0) {
                normalized.add(tagId);
            }
        }
        return new ArrayList<>(normalized);
    }

    /**
     * 在当前事务内同步作品标签绑定。
     *
     * @param userId 当前用户 ID
     * @param workId 作品 ID
     * @param tagChange 标签变更计划
     */
    private void syncWorkTags(Long userId, Long workId, WorkTagChange tagChange) {
        if (!tagChange.removeTagIds().isEmpty()) {
            workTagEntityMapper.delete(
                    Wrappers.lambdaQuery(WorkTagEntity.class)
                            .eq(WorkTagEntity::getUserId, userId)
                            .eq(WorkTagEntity::getWorkId, workId)
                            .in(WorkTagEntity::getTagId, tagChange.removeTagIds()));
        }
        for (Long tagId : tagChange.addTagIds()) {
            WorkTagEntity relation = new WorkTagEntity();
            relation.setUserId(userId);
            relation.setWorkId(workId);
            relation.setTagId(tagId);
            relation.setSortOrder(nextWorkTagSortOrder(userId, tagId));
            workTagEntityMapper.insert(relation);
        }
    }

    /**
     * 计算标签下一个作品排序值。
     *
     * @param userId 当前用户 ID
     * @param tagId 标签 ID
     * @return 下一个排序值，使用 int 与 WorkTagEntity.sortOrder 的 Integer 字段保持一致
     */
    private int nextWorkTagSortOrder(Long userId, Long tagId) {
        List<WorkTagEntity> relations = workTagEntityMapper.selectList(
                Wrappers.lambdaQuery(WorkTagEntity.class)
                        .eq(WorkTagEntity::getUserId, userId)
                        .eq(WorkTagEntity::getTagId, tagId)
                        .orderByDesc(WorkTagEntity::getSortOrder)
                        .last("LIMIT 1"));
        if (relations.isEmpty() || relations.get(0).getSortOrder() == null) {
            return SORT_ORDER_STEP;
        }
        return relations.get(0).getSortOrder() + SORT_ORDER_STEP;
    }

    /**
     * 构造标签项。
     *
     * @param tag 标签实体
     * @return 标签项
     */
    private MineWorkListResponse.TagItem buildTagItem(WfTagEntity tag) {
        MineWorkListResponse.TagItem item = new MineWorkListResponse.TagItem();
        item.setId(tag.getId());
        item.setName(tag.getName());
        item.setColor(tag.getColor());
        return item;
    }

    /**
     * 统计标签作品数。
     *
     * @param userId 当前用户 ID
     * @param tagId 标签 ID
     * @return 数量
     */
    private long countWorkTags(Long userId, Long tagId) {
        return workTagEntityMapper.selectCount(
                Wrappers.lambdaQuery(WorkTagEntity.class)
                        .eq(WorkTagEntity::getUserId, userId)
                        .eq(WorkTagEntity::getTagId, tagId)
        );
    }

    /**
     * 按标签查询作品 ID。
     *
     * @param userId 当前用户 ID
     * @param tagId 标签 ID
     * @return 作品 ID
     */
    private List<Long> findWorkIdsByTag(Long userId, Long tagId) {
        return findRelationsForTag(userId, tagId)
                .stream()
                .map(WorkTagEntity::getWorkId)
                .toList();
    }

    /**
     * 查询当前用户指定标签下的作品关系，按标签内排序返回。
     *
     * @param userId 当前用户 ID
     * @param tagId 标签 ID
     * @return 标签作品关系
     */
    private List<WorkTagEntity> findRelationsForTag(Long userId, Long tagId) {
        return workTagEntityMapper.selectList(
                Wrappers.lambdaQuery(WorkTagEntity.class)
                        .eq(WorkTagEntity::getUserId, userId)
                        .eq(WorkTagEntity::getTagId, tagId)
                        .orderByAsc(WorkTagEntity::getSortOrder)
                        .orderByDesc(WorkTagEntity::getWorkId)
        );
    }

    /**
     * 构造按作品 ID 列表顺序排序的 SQL 片段。
     *
     * @param workIds 作品 ID
     * @return SQL 排序片段
     */
    private String buildWorkIdOrderClause(List<Long> workIds) {
        return SQL_ORDER_BY_FIELD_ID_PREFIX
                + workIds.stream().map(String::valueOf).collect(Collectors.joining(","))
                + SQL_FUNCTION_SUFFIX;
    }

    /**
     * 按关键词匹配标签并返回作品 ID。
     *
     * @param userId 当前用户 ID
     * @param keyword 关键词
     * @return 作品 ID
     */
    private List<Long> findWorkIdsByKeywordTag(Long userId, String keyword) {
        if (!hasText(keyword)) {
            return List.of();
        }
        List<Long> tagIds = wfTagEntityMapper.selectList(
                        Wrappers.lambdaQuery(WfTagEntity.class)
                                .eq(WfTagEntity::getUserId, userId)
                                .like(WfTagEntity::getName, normalizeText(keyword)))
                .stream()
                .map(WfTagEntity::getId)
                .toList();
        if (tagIds.isEmpty()) {
            return List.of();
        }
        return workTagEntityMapper.selectList(
                        Wrappers.lambdaQuery(WorkTagEntity.class)
                                .eq(WorkTagEntity::getUserId, userId)
                                .in(WorkTagEntity::getTagId, tagIds))
                .stream()
                .map(WorkTagEntity::getWorkId)
                .distinct()
                .toList();
    }

    /**
     * 查询作品引用。
     *
     * @param workId 作品 ID
     * @return 引用列表
     */
    private List<PortfolioReferenceEntity> findWorkReferences(Long workId) {
        List<PortfolioReferenceEntity> references = portfolioReferenceEntityMapper.selectList(
                Wrappers.lambdaQuery(PortfolioReferenceEntity.class)
                        .eq(PortfolioReferenceEntity::getReferenceType, ReferenceTypeDict.WORK.getCode())
                        .eq(PortfolioReferenceEntity::getReferenceId, workId)
                        .eq(PortfolioReferenceEntity::getIsValid, 1)
        );
        return references.stream()
                .collect(Collectors.toMap(
                        PortfolioReferenceEntity::getPortfolioId,
                        reference -> reference,
                        this::preferPublishedReference,
                        LinkedHashMap::new
                ))
                .values()
                .stream()
                .toList();
    }

    /**
     * 同一作品集草稿和正式同时引用时，优先保留正式引用用于删除阻断文案。
     *
     * @param first 已收集引用
     * @param second 新引用
     * @return 更适合作为明细展示的引用
     */
    private PortfolioReferenceEntity preferPublishedReference(
            PortfolioReferenceEntity first,
            PortfolioReferenceEntity second
    ) {
        if (PortfolioConfigScopeDict.PUBLISHED.getCode().equals(second.getConfigScope())) {
            return second;
        }
        return first;
    }

    /**
     * 删除作品与标签的关联。
     *
     * @param userId 当前用户 ID
     * @param workId 作品 ID
     */
    private void deleteWorkTagRelations(Long userId, Long workId) {
        workTagEntityMapper.delete(
                Wrappers.lambdaQuery(WorkTagEntity.class)
                        .eq(WorkTagEntity::getUserId, userId)
                        .eq(WorkTagEntity::getWorkId, workId)
        );
    }

    /**
     * 在事务提交后删除作品 COS 对象。
     *
     * @param work 已删除作品
     */
    private void deleteWorkCosObjectsAfterCommit(WorkEntity work) {
        Set<String> objectKeys = collectWorkObjectKeys(work);
        if (objectKeys.isEmpty()) {
            return;
        }
        Runnable deleteTask = () -> deleteWorkCosObjects(work.getId(), objectKeys);
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
     * 收集作品原文件和封面/缩略图对象键。
     *
     * @param work 作品实体
     * @return 去重后的对象键集合
     */
    private Set<String> collectWorkObjectKeys(WorkEntity work) {
        Set<String> objectKeys = new LinkedHashSet<>();
        addObjectKey(objectKeys, work.getMediaObjectKey());
        addObjectKey(objectKeys, work.getCoverObjectKey());
        return objectKeys;
    }

    /**
     * 添加非空 COS 对象键。
     *
     * @param objectKeys 对象键集合
     * @param objectKey 对象键
     */
    private void addObjectKey(Set<String> objectKeys, String objectKey) {
        String normalizedObjectKey = normalizeText(objectKey);
        if (hasText(normalizedObjectKey)) {
            objectKeys.add(normalizedObjectKey);
        }
    }

    /**
     * 删除 COS 对象，单个对象失败不影响其它对象清理。
     *
     * @param workId 作品 ID
     * @param objectKeys 对象键集合
     */
    private void deleteWorkCosObjects(Long workId, Collection<String> objectKeys) {
        objectKeys.forEach(objectKey -> {
            try {
                cosService.delete(objectKey);
            } catch (Exception e) {
                log.warn("作品 COS 文件删除失败: workId={}, objectKey={}", workId, objectKey, e);
            }
        });
    }

    /**
     * 替换作品标签。
     *
     * @param userId 当前用户 ID
     * @param workId 作品 ID
     * @param tagNames 标签名称
     */
    private void replaceTags(Long userId, Long workId, List<String> tagNames) {
        workTagEntityMapper.delete(
                Wrappers.lambdaQuery(WorkTagEntity.class)
                        .eq(WorkTagEntity::getUserId, userId)
                        .eq(WorkTagEntity::getWorkId, workId)
        );
        for (String tagName : normalizeTagNames(tagNames)) {
            WfTagEntity tag = findOrCreateTagForUpdate(userId, tagName);
            WorkTagEntity relation = new WorkTagEntity();
            relation.setUserId(userId);
            relation.setWorkId(workId);
            relation.setTagId(tag.getId());
            workTagEntityMapper.insert(relation);
        }
    }

    /**
     * 编辑作品时查找或创建标签。
     *
     * @param userId 当前用户 ID
     * @param tagName 标签名称
     * @return 标签实体
     */
    private WfTagEntity findOrCreateTagForUpdate(Long userId, String tagName) {
        WfTagEntity existing = findTagForUpdate(userId, tagName);
        if (existing != null) {
            return existing;
        }
        ensureTagCapacity(userId);
        WfTagEntity tag = new WfTagEntity();
        tag.setUserId(userId);
        tag.setName(tagName);
        tag.setStatus(WfTagStatusDict.ACTIVE.getCode());
        try {
            wfTagEntityMapper.insert(tag);
            return tag;
        } catch (DuplicateKeyException e) {
            WfTagEntity duplicate = findTagForUpdate(userId, tagName);
            if (duplicate != null) {
                return duplicate;
            }
            throw e;
        }
    }

    /**
     * 编辑作品时查询标签。
     *
     * @param userId 当前用户 ID
     * @param tagName 标签名称
     * @return 标签实体，可为空
     */
    private WfTagEntity findTagForUpdate(Long userId, String tagName) {
        return wfTagEntityMapper.selectOne(
                Wrappers.lambdaQuery(WfTagEntity.class)
                        .eq(WfTagEntity::getUserId, userId)
                        .eq(WfTagEntity::getName, tagName)
                        .eq(WfTagEntity::getStatus, WfTagStatusDict.ACTIVE.getCode())
                        .last("LIMIT 1")
        );
    }

    /**
     * 查询当前用户启用标签。
     *
     * @param userId 当前用户 ID
     * @param tagId 标签 ID
     * @return 标签实体
     */
    private WfTagEntity requireOwnedActiveTag(Long userId, Long tagId) {
        if (tagId == null) {
            throw new BusinessException(MineWorkMessage.TAG_NOT_FOUND_MESSAGE);
        }
        WfTagEntity tag = wfTagEntityMapper.selectById(tagId);
        if (tag == null
                || !userId.equals(tag.getUserId())
                || !WfTagStatusDict.ACTIVE.getCode().equals(tag.getStatus())) {
            throw new BusinessException(MineWorkMessage.TAG_NOT_FOUND_MESSAGE);
        }
        return tag;
    }

    /**
     * 校验新增标签数量上限。
     *
     * @param userId 当前用户 ID
     */
    private void ensureTagCapacity(Long userId) {
        long count = wfTagEntityMapper.selectCount(
                Wrappers.lambdaQuery(WfTagEntity.class)
                        .eq(WfTagEntity::getUserId, userId)
                        .eq(WfTagEntity::getStatus, WfTagStatusDict.ACTIVE.getCode())
        );
        if (count >= TAG_MAX_COUNT) {
            throw new BusinessException(MineWorkMessage.TAG_COUNT_LIMIT_MESSAGE);
        }
    }

    /**
     * 校验同用户标签名称不重复。
     *
     * @param userId 当前用户 ID
     * @param name 标签名称
     * @param currentTagId 当前编辑标签 ID，新增时为空
     */
    private void ensureNoDuplicateTag(Long userId, String name, Long currentTagId) {
        WfTagEntity duplicate = findTagForUpdate(userId, name);
        if (duplicate != null && !duplicate.getId().equals(currentTagId)) {
            throw new BusinessException(MineWorkMessage.TAG_DUPLICATE_MESSAGE);
        }
    }

    /**
     * 归一化排序项，过滤空值并按作品 ID 去重。
     *
     * @param items 排序项
     * @return 有效排序项
     */
    private List<MineWorkSortRequest.Item> normalizeSortItems(List<MineWorkSortRequest.Item> items) {
        Map<Long, MineWorkSortRequest.Item> itemMap = new LinkedHashMap<>();
        for (MineWorkSortRequest.Item item : items) {
            if (item == null || item.getWorkId() == null || item.getSortOrder() == null) {
                continue;
            }
            itemMap.put(item.getWorkId(), item);
        }
        return List.copyOf(itemMap.values());
    }

    /**
     * 归一化排序范围；空值兼容旧版全局排序接口。
     *
     * @param scope 排序范围
     * @return 排序范围
     */
    private String normalizeSortScope(String scope) {
        String value = normalizeText(scope).toUpperCase(Locale.ROOT);
        return SORT_SCOPE_TAG.equals(value) ? SORT_SCOPE_TAG : SORT_SCOPE_ALL;
    }

    /**
     * 更新标签内作品排序。
     *
     * @param userId 当前用户 ID
     * @param tagId 标签 ID
     * @param validItems 排序项
     */
    private void sortTagWorks(Long userId, Long tagId, List<MineWorkSortRequest.Item> validItems) {
        if (tagId == null) {
            throw new BusinessException(SORT_TAG_REQUIRED_MESSAGE);
        }
        requireOwnedActiveTag(userId, tagId);
        List<Long> workIds = validItems.stream()
                .map(MineWorkSortRequest.Item::getWorkId)
                .toList();
        Map<Long, WorkEntity> ownedWorkMap = buildOwnedWorkMap(userId, workIds);
        if (ownedWorkMap.size() != workIds.size()) {
            throw new BusinessException(MineWorkMessage.WORK_NOT_FOUND_MESSAGE);
        }
        Set<Long> relationWorkIds = findRelationsForTag(userId, tagId).stream()
                .map(WorkTagEntity::getWorkId)
                .collect(Collectors.toSet());
        if (!relationWorkIds.containsAll(workIds)) {
            throw new BusinessException(SORT_TAG_WORK_MISMATCH_MESSAGE);
        }
        workTagEntityMapper.updateSortOrders(userId, tagId, validItems);
    }

    /**
     * 归一化作品 ID 列表，过滤空值并保持首次出现顺序。
     *
     * @param workIds 原始作品 ID
     * @return 有效作品 ID
     */
    private List<Long> normalizeWorkIds(List<Long> workIds) {
        if (workIds == null || workIds.isEmpty()) {
            return List.of();
        }
        return workIds.stream()
                .filter(id -> id != null && id > 0L)
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
    }

    /**
     * 查询当前用户作品集合。
     *
     * @param userId 当前用户 ID
     * @param workIds 作品 ID
     * @return 当前用户作品
     */
    private List<WorkEntity> findOwnedWorks(Long userId, List<Long> workIds) {
        if (workIds.isEmpty()) {
            return List.of();
        }
        return workEntityMapper.selectBatchIds(workIds).stream()
                .filter(work -> work != null && userId.equals(work.getUserId()))
                .toList();
    }

    /**
     * 构造当前用户作品映射。
     *
     * @param userId 当前用户 ID
     * @param workIds 作品 ID
     * @return 作品映射
     */
    private Map<Long, WorkEntity> buildOwnedWorkMap(Long userId, List<Long> workIds) {
        return findOwnedWorks(userId, workIds).stream()
                .collect(Collectors.toMap(WorkEntity::getId, work -> work, (left, right) -> left, LinkedHashMap::new));
    }

    /**
     * 校验提交作品均归属当前用户。
     *
     * @param workIds 请求作品 ID
     * @param works 查询到的作品
     */
    private void ensureAllWorksOwned(List<Long> workIds, List<WorkEntity> works) {
        Set<Long> ownedIds = works.stream()
                .map(WorkEntity::getId)
                .collect(Collectors.toSet());
        if (ownedIds.size() != workIds.size() || !ownedIds.containsAll(workIds)) {
            throw new BusinessException(MineWorkMessage.WORK_NOT_FOUND_MESSAGE);
        }
    }

    /**
     * 按请求顺序排列作品。
     *
     * @param workIds 请求作品 ID
     * @param works 作品列表
     * @return 排序后的作品
     */
    private List<WorkEntity> orderWorksByIds(List<Long> workIds, List<WorkEntity> works) {
        Map<Long, WorkEntity> workMap = works.stream()
                .collect(Collectors.toMap(WorkEntity::getId, work -> work, (left, right) -> left, LinkedHashMap::new));
        return workIds.stream()
                .map(workMap::get)
                .filter(work -> work != null)
                .toList();
    }

    /**
     * 构造排序作品项。
     *
     * @param work 作品
     * @param sortOrder 当前排序值
     * @return 排序作品项
     */
    private MineWorkSortItemsResponse.SortWorkItem buildSortWorkItem(WorkEntity work, Integer sortOrder) {
        MineWorkSortItemsResponse.SortWorkItem item = new MineWorkSortItemsResponse.SortWorkItem();
        item.setId(work.getId());
        item.setMediaType(work.getMediaType());
        item.setTitle(work.getTitle());
        item.setCoverUrl(hasText(work.getCoverObjectKey()) ? cosService.publicUrl(work.getCoverObjectKey()) : "");
        item.setSortOrder(sortOrder);
        return item;
    }

    /**
     * 构造批量删除检查项。
     *
     * @param work 作品
     * @param referenceCount 引用次数
     * @return 检查项
     */
    private MineWorkBatchDeleteCheckResponse.Item buildBatchDeleteCheckItem(WorkEntity work, long referenceCount) {
        MineWorkBatchDeleteCheckResponse.Item item = new MineWorkBatchDeleteCheckResponse.Item();
        item.setWorkId(work.getId());
        item.setTitle(work.getTitle());
        item.setReferenceCount(referenceCount);
        item.setCanDelete(referenceCount <= 0L);
        item.setMessage(buildDeleteMessage(referenceCount));
        return item;
    }

    /**
     * 构造批量删除结果项。
     *
     * @param work 作品
     * @param referenceCount 引用次数
     * @return 删除结果项
     */
    private MineWorkBatchDeleteResponse.Item buildBatchDeleteItem(WorkEntity work, long referenceCount) {
        MineWorkBatchDeleteResponse.Item item = new MineWorkBatchDeleteResponse.Item();
        item.setWorkId(work.getId());
        item.setTitle(work.getTitle());
        item.setReferenceCount(referenceCount);
        return item;
    }

    /**
     * 删除已确认归属且无引用的作品。
     *
     * @param work 作品
     */
    private void deleteOwnedWork(WorkEntity work) {
        LocalDateTime now = LocalDateTime.now();
        deleteWorkTagRelations(work.getUserId(), work.getId());
        int updated = workEntityMapper.update(
                new WorkEntity(),
                new UpdateWrapper<WorkEntity>()
                        .set(WORK_COLUMN_DELETED_AT, now)
                        .set(WORK_COLUMN_DELETED, work.getId())
                        .eq(WORK_COLUMN_ID, work.getId())
                        .eq(WORK_COLUMN_USER_ID, work.getUserId())
                        .eq(WORK_COLUMN_DELETED, 0L)
        );
        if (updated <= 0) {
            throw new BusinessException(MineWorkMessage.WORK_DELETE_FAILED_MESSAGE);
        }
        deleteWorkCosObjectsAfterCommit(work);
    }

    /**
     * 构造删除提示。
     *
     * @param referenceCount 引用数量
     * @return 提示文案
     */
    private String buildDeleteMessage(long referenceCount) {
        if (referenceCount <= 0L) {
            return MineWorkMessage.WORK_DELETE_ALLOWED_MESSAGE;
        }
        return String.format(MineWorkMessage.WORK_DELETE_BLOCKED_TEMPLATE, referenceCount);
    }

    /**
     * 构造作品对象键。
     *
     * @param uniqueCode 用户唯一码
     * @param mediaType 媒体类型
     * @param extension 扩展名
     * @param uploadTimestamp 上传批次毫秒时间戳
     * @param uploadSequence 上传序号
     * @return COS 对象键
     */
    private String buildWorkObjectKey(
            String uniqueCode,
            String mediaType,
            String extension,
            long uploadTimestamp,
            int uploadSequence
    ) {
        String folder = MediaTypeDict.VIDEO.getCode().equals(mediaType) ? WORK_VIDEO_FOLDER : WORK_IMAGE_FOLDER;
        String typeMarker = MediaTypeDict.VIDEO.getCode().equals(mediaType) ? VIDEO_FILE_MARKER : IMAGE_FILE_MARKER;
        String fileName = uniqueCode
                + WORK_FILE_NAME_SEPARATOR
                + typeMarker
                + WORK_FILE_NAME_SEPARATOR
                + uploadTimestamp
                + WORK_FILE_NAME_SEPARATOR
                + uploadSequence
                + FILE_EXTENSION_SEPARATOR
                + extension;
        return uniqueCode + "/" + folder + "/" + fileName;
    }

    /**
     * 查询缩略图或封面图对应的主上传任务。
     *
     * @param userId 当前用户 ID
     * @param file 文件元信息
     * @return 主上传任务，可为空
     */
    private WorkUploadTaskEntity resolveThumbSourceTask(
            Long userId,
            MineWorkUploadTicketRequest.UploadFileItem file
    ) {
        Long sourceTaskId = file == null ? null : file.getSourceTaskId();
        if (sourceTaskId == null) {
            return null;
        }
        if (!isThumbFileName(file.getFileName())) {
            throw new BusinessException(MineWorkMessage.COVER_TASK_FILE_NAME_MESSAGE);
        }
        return requireOwnedUploadTask(userId, sourceTaskId);
    }

    /**
     * 按主对象键构造缩略图或封面图对象键。
     *
     * @param sourceTask 主上传任务
     * @param extension 缩略图扩展名
     * @return 缩略图或封面图对象键
     */
    private String buildThumbObjectKeyFromSource(WorkUploadTaskEntity sourceTask, String extension) {
        String sourceObjectKey = normalizeText(sourceTask == null ? null : sourceTask.getObjectKey());
        int lastSlashIndex = sourceObjectKey.lastIndexOf('/');
        int extensionIndex = sourceObjectKey.lastIndexOf('.');
        if (sourceObjectKey.isBlank() || extensionIndex <= lastSlashIndex) {
            throw new BusinessException(MineWorkMessage.COVER_SOURCE_TASK_INVALID_MESSAGE);
        }
        return sourceObjectKey.substring(0, extensionIndex)
                + THUMB_FILE_SUFFIX
                + FILE_EXTENSION_SEPARATOR
                + extension;
    }

    /**
     * 按视频对象键和帧时间构造替换封面对象键。
     *
     * @param sourceObjectKey 视频对象键
     * @param frameTimeMs 截帧时间点
     * @return 封面对象键
     */
    private String buildFrameCoverObjectKey(String sourceObjectKey, long frameTimeMs) {
        String normalizedSourceObjectKey = normalizeText(sourceObjectKey);
        int lastSlashIndex = normalizedSourceObjectKey.lastIndexOf('/');
        int extensionIndex = normalizedSourceObjectKey.lastIndexOf('.');
        if (normalizedSourceObjectKey.isBlank() || extensionIndex <= lastSlashIndex) {
            throw new BusinessException(MineWorkMessage.COVER_SOURCE_TASK_INVALID_MESSAGE);
        }
        return normalizedSourceObjectKey.substring(0, extensionIndex)
                + THUMB_FILE_SUFFIX
                + WORK_FILE_NAME_SEPARATOR
                + frameTimeMs
                + FILE_EXTENSION_SEPARATOR
                + THUMB_FILE_EXTENSION;
    }

    /**
     * 按作品原文件对象键构造版本化封面对象键。
     *
     * @param sourceObjectKey 作品原文件对象键
     * @return 封面对象键
     */
    private String buildVersionedCoverObjectKey(String sourceObjectKey) {
        String normalizedSourceObjectKey = normalizeText(sourceObjectKey);
        int lastSlashIndex = normalizedSourceObjectKey.lastIndexOf('/');
        int extensionIndex = normalizedSourceObjectKey.lastIndexOf('.');
        if (normalizedSourceObjectKey.isBlank() || extensionIndex <= lastSlashIndex) {
            throw new BusinessException(MineWorkMessage.COVER_SOURCE_TASK_INVALID_MESSAGE);
        }
        return normalizedSourceObjectKey.substring(0, extensionIndex)
                + THUMB_FILE_SUFFIX
                + WORK_FILE_NAME_SEPARATOR
                + System.currentTimeMillis()
                + FILE_EXTENSION_SEPARATOR
                + THUMB_FILE_EXTENSION;
    }

    /**
     * 防止图片缩略图上传票据指向原图对象键。
     *
     * @param work 图片作品
     * @param thumbnailObjectKey 缩略图对象键
     */
    private void ensureThumbnailTaskDoesNotTargetOriginal(WorkEntity work, String thumbnailObjectKey) {
        if (normalizeText(thumbnailObjectKey).equals(normalizeText(work.getMediaObjectKey()))) {
            throw new BusinessException(MineWorkMessage.COVER_SOURCE_TASK_INVALID_MESSAGE);
        }
    }

    /**
     * 归一化手动上传封面任务幂等键。
     *
     * @param work 视频作品
     * @param request 封面票据创建请求
     * @return 幂等键
     */
    private String normalizeManualCoverIdempotency(WorkEntity work, MineWorkCoverUploadTicketRequest request) {
        String value = normalizeText(request.getIdempotencyKey());
        if (value.isBlank()) {
            value = normalizeText(request.getClientId());
        }
        if (value.isBlank()) {
            value = normalizeClientSha256(request.getSha256());
        }
        return MANUAL_VIDEO_COVER_IDEMPOTENCY_PREFIX + work.getId() + ":" + value;
    }

    /**
     * 归一化手动上传图片缩略图任务幂等键。
     *
     * @param work 图片作品
     * @param request 缩略图票据创建请求
     * @return 幂等键
     */
    private String normalizeImageThumbnailIdempotency(WorkEntity work, MineWorkThumbnailUploadTicketRequest request) {
        String value = normalizeText(request.getIdempotencyKey());
        if (value.isBlank()) {
            value = normalizeText(request.getClientId());
        }
        if (value.isBlank()) {
            value = normalizeClientSha256(request.getSha256());
        }
        return IMAGE_THUMBNAIL_IDEMPOTENCY_PREFIX + work.getId() + ":" + value;
    }

    /**
     * 归一化封面截帧时间，防止越界。
     *
     * @param requestedFrameTimeMs 请求时间点
     * @param durationMs 视频时长
     * @return 归一化后的毫秒时间点
     */
    private long normalizeCoverFrameTimeMs(Long requestedFrameTimeMs, Integer durationMs) {
        long frameTimeMs = Math.max(0L, requestedFrameTimeMs == null ? 0L : requestedFrameTimeMs);
        if (durationMs != null && durationMs > 0) {
            return Math.min(frameTimeMs, durationMs.longValue());
        }
        return frameTimeMs;
    }

    /**
     * 解析小程序上报或历史保存的视频尺寸。
     *
     * @param requestedWidth 本次请求宽度
     * @param requestedHeight 本次请求高度
     * @param fallbackWidth 历史宽度
     * @param fallbackHeight 历史高度
     * @return 可用于截帧比例计算的媒体尺寸
     */
    private MediaDimensions resolveMediaDimensions(
            Integer requestedWidth,
            Integer requestedHeight,
            Integer fallbackWidth,
            Integer fallbackHeight
    ) {
        MediaDimensions requested = normalizeMediaDimensions(requestedWidth, requestedHeight);
        if (requested != null) {
            return requested;
        }
        MediaDimensions fallback = normalizeMediaDimensions(fallbackWidth, fallbackHeight);
        if (fallback != null) {
            return fallback;
        }
        return new MediaDimensions(SNAPSHOT_MAX_SIDE, SNAPSHOT_MAX_SIDE);
    }

    /**
     * 归一化媒体尺寸。
     *
     * @param width 像素宽度
     * @param height 像素高度
     * @return 合法尺寸，不合法时返回 null
     */
    private MediaDimensions normalizeMediaDimensions(Integer width, Integer height) {
        int normalizedWidth = normalizeMediaDimension(width);
        int normalizedHeight = normalizeMediaDimension(height);
        if (normalizedWidth <= 0 || normalizedHeight <= 0) {
            return null;
        }
        return new MediaDimensions(normalizedWidth, normalizedHeight);
    }

    /**
     * 归一化单个媒体尺寸。
     *
     * @param value 像素尺寸
     * @return 合法尺寸，不合法时返回 0
     */
    private int normalizeMediaDimension(Integer value) {
        if (!isValidMediaDimension(value)) {
            return 0;
        }
        return value;
    }

    /**
     * 判断媒体尺寸是否在小程序可信范围内。
     *
     * @param value 像素尺寸
     * @return 尺寸是否有效
     */
    private boolean isValidMediaDimension(Integer value) {
        return value != null && value > 0 && value <= MEDIA_DIMENSION_MAX;
    }

    /**
     * 按原始比例计算数据万象截帧输出尺寸。
     *
     * @param mediaDimensions 视频媒体尺寸
     * @return 最长边不超过限制的输出尺寸
     */
    private SnapshotDimensions buildSnapshotDimensions(MediaDimensions mediaDimensions) {
        int mediaWidth = mediaDimensions == null ? SNAPSHOT_MAX_SIDE : mediaDimensions.width();
        int mediaHeight = mediaDimensions == null ? SNAPSHOT_MAX_SIDE : mediaDimensions.height();
        if (mediaWidth >= mediaHeight) {
            int outputWidth = Math.min(mediaWidth, SNAPSHOT_MAX_SIDE);
            int outputHeight = Math.max(1, (int) Math.round((double) mediaHeight * outputWidth / mediaWidth));
            return new SnapshotDimensions(outputWidth, outputHeight);
        }
        int outputHeight = Math.min(mediaHeight, SNAPSHOT_MAX_SIDE);
        int outputWidth = Math.max(1, (int) Math.round((double) mediaWidth * outputHeight / mediaHeight));
        return new SnapshotDimensions(outputWidth, outputHeight);
    }

    /**
     * 解析媒体类型。
     *
     * @param mediaType 媒体类型
     * @return 标准媒体类型
     */
    private String normalizeMediaType(String mediaType) {
        String value = normalizeText(mediaType).toUpperCase(Locale.ROOT);
        if (MediaTypeDict.IMAGE.getCode().equals(value) || MediaTypeDict.VIDEO.getCode().equals(value)) {
            return value;
        }
        throw new BusinessException(MineWorkMessage.WORK_MEDIA_TYPE_UNSUPPORTED_MESSAGE);
    }

    /**
     * 解析可选媒体类型。
     *
     * @param mediaType 媒体类型
     * @return 标准媒体类型或空字符串
     */
    private String normalizeOptionalMediaType(String mediaType) {
        if (!hasText(mediaType)) {
            return "";
        }
        return normalizeMediaType(mediaType);
    }

    /**
     * 解析可选审核状态。
     *
     * @param auditStatus 审核状态
     * @return 标准审核状态或空字符串
     */
    private String normalizeOptionalAuditStatus(String auditStatus) {
        if (!hasText(auditStatus)) {
            return "";
        }
        String normalizedStatus = normalizeText(auditStatus).toUpperCase(Locale.ROOT);
        if (WorkAuditStatusDict.fromCode(normalizedStatus) == null) {
            return "";
        }
        return normalizedStatus;
    }

    /**
     * 解析文件扩展名。
     *
     * @param fileName 文件名
     * @param mimeType MIME 类型
     * @param mediaType 媒体类型
     * @return 小写扩展名
     */
    private String normalizeExtension(String fileName, String mimeType, String mediaType) {
        String extension = extensionFromFileName(fileName);
        if (extension.isBlank()) {
            extension = extensionFromMime(mimeType, mediaType);
        }
        Set<String> allowed = MediaTypeDict.IMAGE.getCode().equals(mediaType) ? IMAGE_EXTENSIONS : VIDEO_EXTENSIONS;
        if (!allowed.contains(extension)) {
            throw new BusinessException("作品文件格式不支持");
        }
        return extension;
    }

    /**
     * 从文件名提取扩展名。
     *
     * @param fileName 文件名
     * @return 小写扩展名
     */
    private String extensionFromFileName(String fileName) {
        String value = normalizeText(fileName);
        int index = value.lastIndexOf('.');
        if (index < 0 || index == value.length() - 1) {
            return "";
        }
        return value.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 构造缩略图或封面图文件名。
     *
     * @param fileName 原始文件名
     * @return 缩略图或封面图文件名
     */
    private String buildThumbFileName(String fileName) {
        String value = normalizeText(fileName);
        int index = value.lastIndexOf('.');
        String stem = index > 0 ? value.substring(0, index) : value;
        if (stem.isBlank()) {
            stem = "work";
        }
        return stem + THUMB_FILE_SUFFIX + FILE_EXTENSION_SEPARATOR + THUMB_FILE_EXTENSION;
    }

    /**
     * 判断文件名是否为缩略图或封面图。
     *
     * @param fileName 文件名
     * @return 是否缩略图或封面图
     */
    private boolean isThumbFileName(String fileName) {
        String value = normalizeText(fileName).toLowerCase(Locale.ROOT);
        int index = value.lastIndexOf('.');
        String stem = index > 0 ? value.substring(0, index) : value;
        return stem.endsWith(THUMB_FILE_SUFFIX);
    }

    /**
     * 根据 MIME 类型推断扩展名。
     *
     * @param mimeType MIME 类型
     * @param mediaType 媒体类型
     * @return 扩展名
     */
    private String extensionFromMime(String mimeType, String mediaType) {
        String value = normalizeText(mimeType).toLowerCase(Locale.ROOT);
        if (value.contains("png")) {
            return "png";
        }
        if (value.contains("webp")) {
            return "webp";
        }
        if (value.contains("gif")) {
            return "gif";
        }
        if (value.contains("quicktime")) {
            return "mov";
        }
        if (value.contains("mp4")) {
            return "mp4";
        }
        return MediaTypeDict.VIDEO.getCode().equals(mediaType) ? "mp4" : "jpg";
    }

    /**
     * 获取媒体最大字节数。
     *
     * @param mediaType 媒体类型
     * @return 最大字节数
     */
    private long maxBytes(String mediaType) {
        return MediaTypeDict.VIDEO.getCode().equals(mediaType) ? VIDEO_MAX_BYTES : IMAGE_MAX_BYTES;
    }

    /**
     * 归一化上传任务幂等键。
     *
     * @param batchId 批次 ID
     * @param file 文件元信息
     * @return 幂等键
     */
    private String normalizeTicketIdempotency(String batchId, MineWorkUploadTicketRequest.UploadFileItem file) {
        String value = normalizeText(file.getIdempotencyKey());
        if (!value.isBlank()) {
            return value;
        }
        return TICKET_IDEMPOTENCY_PREFIX + batchId + ":" + normalizeText(file.getClientId());
    }

    /**
     * 归一化标题。
     *
     * @param title 标题
     * @return 标题
     */
    private String normalizeRequiredTitle(String title) {
        String value = normalizeText(title);
        if (value.isBlank()) {
            throw new BusinessException("作品标题不能为空");
        }
        if (value.length() > TITLE_MAX_LENGTH) {
            throw new BusinessException("作品标题不能超过 30 字");
        }
        return value;
    }

    /**
     * 归一化说明。
     *
     * @param description 说明
     * @return 说明
     */
    private String normalizeDescription(String description) {
        String value = normalizeText(description);
        if (value.length() > DESCRIPTION_MAX_LENGTH) {
            throw new BusinessException("作品说明不能超过 1000 字");
        }
        return value;
    }

    /**
     * 归一化必填标签名称。
     *
     * @param name 标签名称
     * @return 标签名称
     */
    private String normalizeRequiredTagName(String name) {
        String value = normalizeText(name);
        if (value.isBlank()) {
            throw new BusinessException(MineWorkMessage.TAG_NAME_EMPTY_MESSAGE);
        }
        if (value.codePointCount(0, value.length()) > TAG_MAX_LENGTH) {
            throw new BusinessException(MineWorkMessage.TAG_NAME_TOO_LONG_MESSAGE);
        }
        return value;
    }

    /**
     * 归一化标签颜色。
     *
     * @param color 标签颜色
     * @return 小写色值
     */
    private String normalizeTagColor(String color) {
        String value = normalizeText(color).toLowerCase(Locale.ROOT);
        if (!TAG_COLOR_OPTIONS.contains(value)) {
            throw new BusinessException(MineWorkMessage.TAG_COLOR_INVALID_MESSAGE);
        }
        return value;
    }

    /**
     * 归一化标签列表。
     *
     * @param tagNames 标签名称
     * @return 去重标签
     */
    private List<String> normalizeTagNames(List<String> tagNames) {
        if (tagNames == null) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String rawName : tagNames) {
            String name = normalizeText(rawName);
            if (name.isBlank()) {
                continue;
            }
            if (name.codePointCount(0, name.length()) > TAG_MAX_LENGTH) {
                throw new BusinessException(MineWorkMessage.TAG_NAME_TOO_LONG_MESSAGE);
            }
            names.add(name);
        }
        if (names.size() > TAG_MAX_COUNT) {
            throw new BusinessException(MineWorkMessage.WORK_TAG_COUNT_LIMIT_MESSAGE);
        }
        return List.copyOf(names);
    }

    /**
     * 生成无横线 UUID。
     *
     * @return UUID 文本
     */
    private String generateUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 归一化文本。
     *
     * @param value 文本
     * @return 去首尾空格文本
     */
    private String normalizeText(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * 判断文本是否有内容。
     *
     * @param value 文本
     * @return 是否有内容
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
