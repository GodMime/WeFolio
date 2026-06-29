package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dict.WfTagStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.dict.WorkUploadTaskStatusDict;
import com.jxc.wefolio.dto.MineWorkDeleteCheckResponse;
import com.jxc.wefolio.dto.MineWorkDetailResponse;
import com.jxc.wefolio.dto.MineWorkListResponse;
import com.jxc.wefolio.dto.MineWorkSortRequest;
import com.jxc.wefolio.dto.MineWorkTagResponse;
import com.jxc.wefolio.dto.MineWorkTagUpsertRequest;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** 上传任务幂等键兜底前缀 */
    private static final String TICKET_IDEMPOTENCY_PREFIX = "WORK_UPLOAD_TICKET:";

    /** 上传任务保存失败提示 */
    private static final String UPLOAD_TASK_SAVE_FAILED_MESSAGE = "上传任务创建失败，请刷新后重试";

    /** 作品表主键列 */
    private static final String WORK_COLUMN_ID = "id";

    /** 作品表用户列 */
    private static final String WORK_COLUMN_USER_ID = "user_id";

    /** 作品表逻辑删除列 */
    private static final String WORK_COLUMN_DELETED = "deleted";

    /** 作品表删除时间列 */
    private static final String WORK_COLUMN_DELETED_AT = "deleted_at";

    /** 作品表更新时间列 */
    private static final String WORK_COLUMN_UPDATED_AT = "updated_at";

    /** 作品表媒体类型列 */
    private static final String WORK_COLUMN_MEDIA_TYPE = "media_type";

    /** 作品表状态列 */
    private static final String WORK_COLUMN_STATUS = "status";

    /** SQL 计数表达式 */
    private static final String SQL_COUNT_ALL_EXPRESSION = "COUNT(*)";

    /** 媒体类型查询别名 */
    private static final String WORK_MEDIA_TYPE_ALIAS = "mediaType";

    /** 计数查询别名 */
    private static final String COUNT_ALIAS = "itemCount";

    /** 标签不存在提示 */
    private static final String TAG_NOT_FOUND_MESSAGE = "标签不存在或已删除";

    /** 标签重复提示 */
    private static final String TAG_DUPLICATE_MESSAGE = "标签不能重复";

    /** 标签保存失败提示 */
    private static final String TAG_SAVE_FAILED_MESSAGE = "标签保存失败，请刷新后重试";

    /** 标签删除失败提示 */
    private static final String TAG_DELETE_FAILED_MESSAGE = "标签删除失败，请刷新后重试";

    /** 标签数量超限提示 */
    private static final String TAG_COUNT_LIMIT_MESSAGE = "标签最多保留 10 个";

    /** 标签名称为空提示 */
    private static final String TAG_NAME_EMPTY_MESSAGE = "标签名称不能为空";

    /** 标签名称超长提示 */
    private static final String TAG_NAME_TOO_LONG_MESSAGE = "标签名称不能超过 10 个字";

    /** 排序列表为空提示 */
    private static final String SORT_ITEMS_EMPTY_MESSAGE = "请提交要排序的作品";

    /** COS 文件读取失败提示 */
    private static final String COS_OBJECT_READ_FAILED_MESSAGE = "上传文件读取失败，请重新上传";

    /** 上传确认未预期失败提示 */
    private static final String UPLOAD_CONFIRM_UNEXPECTED_FAILED_MESSAGE = "作品确认失败，请稍后重试";

    /** 单个作品标签数量超限提示 */
    private static final String WORK_TAG_COUNT_LIMIT_MESSAGE = "作品标签最多 10 个";

    /** 标签颜色非法提示 */
    private static final String TAG_COLOR_INVALID_MESSAGE = "请选择有效的标签颜色";

    /** 标签被作品占用提示模板 */
    private static final String TAG_DELETE_BLOCKED_TEMPLATE = "标签「%s」下还有 %d 个作品，先移除这些作品的标签后再删除。";

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
        Long userId = AuthContextHolder.requireUserId();
        int normalizedPage = page <= 0 ? DEFAULT_PAGE : page;
        int normalizedPageSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        List<Long> taggedWorkIds = tagId == null ? null : findWorkIdsByTag(userId, tagId);
        List<Long> keywordWorkIds = findWorkIdsByKeywordTag(userId, keyword);
        Page<WorkEntity> resultPage = workEntityMapper.selectPage(
                new Page<>(normalizedPage, normalizedPageSize),
                Wrappers.lambdaQuery(WorkEntity.class)
                        .eq(WorkEntity::getUserId, userId)
                        .eq(WorkEntity::getStatus, WorkStatusDict.ACTIVE.getCode())
                        .and(hasText(keyword), wrapper -> wrapper
                                .like(WorkEntity::getTitle, normalizeText(keyword))
                                .or()
                                .like(WorkEntity::getOriginalFileName, normalizeText(keyword))
                                .or(!keywordWorkIds.isEmpty())
                                .in(!keywordWorkIds.isEmpty(), WorkEntity::getId, keywordWorkIds))
                        .in(taggedWorkIds != null && !taggedWorkIds.isEmpty(), WorkEntity::getId, taggedWorkIds)
                        .eq(taggedWorkIds != null && taggedWorkIds.isEmpty(), WorkEntity::getId, -1L)
                        .orderByAsc(WorkEntity::getSortOrder)
                        .orderByDesc(WorkEntity::getId)
        );

        MineWorkListResponse response = new MineWorkListResponse();
        response.setPage(normalizedPage);
        response.setPageSize(normalizedPageSize);
        response.setTotal(resultPage.getTotal());
        response.setHasMore(resultPage.getCurrent() < resultPage.getPages());
        MineWorkListResponse.Summary summary = buildSummary(userId);
        List<WfTagEntity> activeTags = findActiveTags(userId);
        Map<Long, WfTagEntity> activeTagMap = buildTagMap(activeTags);
        List<WorkTagEntity> activeTagRelations = findRelationsForTags(userId, activeTagMap.keySet());
        response.setSummary(summary);
        response.setTags(buildTagItems(activeTags, buildTagUsageCounts(activeTagRelations), tagId, summary.getTotalCount()));
        Map<Long, Long> referenceCounts = buildWorkReferenceCounts(resultPage.getRecords());
        Map<Long, List<MineWorkListResponse.TagItem>> workTags =
                buildWorkTagItems(resultPage.getRecords(), activeTagRelations, activeTagMap);
        response.setWorks(resultPage.getRecords().stream()
                .map(work -> buildWorkItem(work, referenceCounts, workTags))
                .toList());
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
        tag.setCreatedAt(LocalDateTime.now());
        tag.setUpdatedAt(LocalDateTime.now());
        try {
            wfTagEntityMapper.insert(tag);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(TAG_DUPLICATE_MESSAGE);
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
        tag.setUpdatedAt(LocalDateTime.now());
        int updated = wfTagEntityMapper.updateById(tag);
        if (updated <= 0) {
            throw new BusinessException(TAG_SAVE_FAILED_MESSAGE);
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
            throw new BusinessException(String.format(TAG_DELETE_BLOCKED_TEMPLATE, tag.getName(), workCount));
        }
        int deleted = wfTagEntityMapper.deleteById(tag.getId());
        if (deleted <= 0) {
            throw new BusinessException(TAG_DELETE_FAILED_MESSAGE);
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
        for (MineWorkUploadTicketRequest.UploadFileItem file : files) {
            validateUploadFile(file);
            String mediaType = normalizeMediaType(file.getMediaType());
            String extension = normalizeExtension(file.getFileName(), file.getMimeType(), mediaType);
            String objectKey = buildWorkObjectKey(user.getUniqueCode(), mediaType, extension);
            WorkUploadTaskEntity task = buildUploadTask(userId, batchId, file, mediaType, objectKey, expiresAt);
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
                validateCosObject(task);
                response.getItems().add(workUploadTransactionService.confirmUploadedTask(userId, task, item));
            } catch (BusinessException e) {
                markTaskFailedIfPossible(taskId, e.getMessage());
                response.getItems().add(MineWorkUploadCompleteResponse.Item.failure(taskId, e.getMessage()));
            } catch (Exception e) {
                log.warn("作品确认出现未预期异常: taskId={}", taskId, e);
                markTaskFailedIfPossible(taskId, UPLOAD_CONFIRM_UNEXPECTED_FAILED_MESSAGE);
                response.getItems().add(MineWorkUploadCompleteResponse.Item.failure(
                        taskId,
                        UPLOAD_CONFIRM_UNEXPECTED_FAILED_MESSAGE));
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
        work.setTitle(normalizeRequiredTitle(request == null ? null : request.getTitle()));
        work.setDescription(normalizeDescription(request == null ? null : request.getDescription()));
        work.setUpdatedAt(LocalDateTime.now());
        int updated = workEntityMapper.updateById(work);
        if (updated <= 0) {
            throw new BusinessException("作品保存失败，请刷新后重试");
        }
        replaceTags(work.getUserId(), work.getId(), request == null ? List.of() : request.getTagNames());
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
            throw new BusinessException(SORT_ITEMS_EMPTY_MESSAGE);
        }
        List<MineWorkSortRequest.Item> validItems = normalizeSortItems(items);
        if (validItems.isEmpty()) {
            throw new BusinessException(SORT_ITEMS_EMPTY_MESSAGE);
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
        workEntityMapper.updateSortOrders(userId, validItems, LocalDateTime.now());
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
        LocalDateTime now = LocalDateTime.now();
        int updated = workEntityMapper.update(
                null,
                new UpdateWrapper<WorkEntity>()
                        .set(WORK_COLUMN_DELETED_AT, now)
                        .set(WORK_COLUMN_UPDATED_AT, now)
                        .set(WORK_COLUMN_DELETED, work.getId())
                        .eq(WORK_COLUMN_ID, work.getId())
                        .eq(WORK_COLUMN_USER_ID, work.getUserId())
                        .eq(WORK_COLUMN_DELETED, 0L)
        );
        if (updated <= 0) {
            throw new BusinessException("作品删除失败，请刷新后重试");
        }
    }

    /**
     * 构造上传任务实体。
     *
     * @param userId 当前用户 ID
     * @param batchId 批次 ID
     * @param file 文件元信息
     * @param mediaType 媒体类型
     * @param objectKey COS 对象键
     * @param expiresAt 过期时间
     * @return 上传任务实体
     */
    private WorkUploadTaskEntity buildUploadTask(
            Long userId,
            String batchId,
            MineWorkUploadTicketRequest.UploadFileItem file,
            String mediaType,
            String objectKey,
            LocalDateTime expiresAt
    ) {
        WorkUploadTaskEntity task = new WorkUploadTaskEntity();
        task.setBatchId(batchId);
        task.setUserId(userId);
        task.setMediaType(mediaType);
        task.setObjectKey(objectKey);
        task.setOriginalFileName(normalizeText(file.getFileName()));
        task.setMimeType(normalizeText(file.getMimeType()));
        task.setFileSize(file.getFileSize());
        task.setDurationMs(file.getDurationMs());
        task.setWidth(file.getWidth());
        task.setHeight(file.getHeight());
        task.setStatus(WorkUploadTaskStatusDict.CREATED.getCode());
        task.setExpiresAt(expiresAt);
        task.setIdempotencyKey(normalizeTicketIdempotency(batchId, file));
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        return task;
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
            throw new BusinessException(UPLOAD_TASK_SAVE_FAILED_MESSAGE);
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
    private void validateCosObject(WorkUploadTaskEntity task) {
        CosService.ObjectHead head;
        try {
            head = cosService.headObject(task.getObjectKey());
        } catch (RuntimeException e) {
            throw new BusinessException(COS_OBJECT_READ_FAILED_MESSAGE);
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
     * @param taskId 上传任务 ID
     * @param message 失败消息
     */
    private void markTaskFailedIfPossible(Long taskId, String message) {
        if (taskId == null) {
            return;
        }
        WorkUploadTaskEntity task = workUploadTaskEntityMapper.selectById(taskId);
        if (task == null || WorkUploadTaskStatusDict.CONFIRMED.getCode().equals(task.getStatus())) {
            return;
        }
        task.setStatus(WorkUploadTaskStatusDict.FAILED.getCode());
        task.setErrorMessage(message);
        task.setUpdatedAt(LocalDateTime.now());
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
     * 查询当前用户作品。
     *
     * @param workId 作品 ID
     * @return 作品
     */
    private WorkEntity requireOwnedWork(Long workId) {
        Long userId = AuthContextHolder.requireUserId();
        if (workId == null) {
            throw new BusinessException("作品不能为空");
        }
        WorkEntity work = workEntityMapper.selectById(workId);
        if (work == null || !userId.equals(work.getUserId())) {
            throw new BusinessException("作品不存在或无访问权限");
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
        item.setDescription(work.getDescription());
        item.setServiceDate(work.getServiceDate());
        item.setSortOrder(work.getSortOrder());
        item.setStatus(work.getStatus());
        item.setReferenceCount(referenceCounts.getOrDefault(work.getId(), 0L));
        item.setTags(workTags.getOrDefault(work.getId(), List.of()));
        item.setCreatedAt(work.getCreatedAt());
        item.setUpdatedAt(work.getUpdatedAt());
        return item;
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
     * @return 摘要
     */
    private MineWorkListResponse.Summary buildSummary(Long userId) {
        MineWorkListResponse.Summary summary = new MineWorkListResponse.Summary();
        List<Map<String, Object>> rows = workEntityMapper.selectMaps(
                new QueryWrapper<WorkEntity>()
                        .select(
                                WORK_COLUMN_MEDIA_TYPE + " AS " + WORK_MEDIA_TYPE_ALIAS,
                                SQL_COUNT_ALL_EXPRESSION + " AS " + COUNT_ALIAS)
                        .eq(WORK_COLUMN_USER_ID, userId)
                        .eq(WORK_COLUMN_STATUS, WorkStatusDict.ACTIVE.getCode())
                        .groupBy(WORK_COLUMN_MEDIA_TYPE)
        );
        if (rows == null || rows.isEmpty()) {
            return summary;
        }
        for (Map<String, Object> row : rows) {
            String mediaType = rowValueAsString(row.get(WORK_MEDIA_TYPE_ALIAS));
            long count = rowValueAsLong(row.get(COUNT_ALIAS));
            summary.setTotalCount(summary.getTotalCount() + count);
            if (MediaTypeDict.IMAGE.getCode().equals(mediaType)) {
                summary.setImageCount(count);
            }
            if (MediaTypeDict.VIDEO.getCode().equals(mediaType)) {
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
                        Collectors.counting()));
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
        return workTagEntityMapper.selectList(
                        Wrappers.lambdaQuery(WorkTagEntity.class)
                                .eq(WorkTagEntity::getUserId, userId)
                                .eq(WorkTagEntity::getTagId, tagId))
                .stream()
                .map(WorkTagEntity::getWorkId)
                .toList();
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
        return portfolioReferenceEntityMapper.selectList(
                Wrappers.lambdaQuery(PortfolioReferenceEntity.class)
                        .eq(PortfolioReferenceEntity::getReferenceType, ReferenceTypeDict.WORK.getCode())
                        .eq(PortfolioReferenceEntity::getReferenceId, workId)
                        .eq(PortfolioReferenceEntity::getIsValid, 1)
        );
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
            relation.setCreatedAt(LocalDateTime.now());
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
        tag.setCreatedAt(LocalDateTime.now());
        tag.setUpdatedAt(LocalDateTime.now());
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
            throw new BusinessException(TAG_NOT_FOUND_MESSAGE);
        }
        WfTagEntity tag = wfTagEntityMapper.selectById(tagId);
        if (tag == null
                || !userId.equals(tag.getUserId())
                || !WfTagStatusDict.ACTIVE.getCode().equals(tag.getStatus())) {
            throw new BusinessException(TAG_NOT_FOUND_MESSAGE);
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
            throw new BusinessException(TAG_COUNT_LIMIT_MESSAGE);
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
            throw new BusinessException(TAG_DUPLICATE_MESSAGE);
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
     * 构造删除提示。
     *
     * @param referenceCount 引用数量
     * @return 提示文案
     */
    private String buildDeleteMessage(long referenceCount) {
        if (referenceCount <= 0L) {
            return "作品未被作品集引用，可以删除";
        }
        return "作品已被 " + referenceCount + " 个作品集引用，请先从作品集中移除";
    }

    /**
     * 构造作品对象键。
     *
     * @param uniqueCode 用户唯一码
     * @param mediaType 媒体类型
     * @param extension 扩展名
     * @return COS 对象键
     */
    private String buildWorkObjectKey(String uniqueCode, String mediaType, String extension) {
        String folder = MediaTypeDict.VIDEO.getCode().equals(mediaType) ? WORK_VIDEO_FOLDER : WORK_IMAGE_FOLDER;
        return uniqueCode + "/" + folder + "/" + generateUuid() + "." + extension;
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
        throw new BusinessException("作品媒体类型不支持");
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
            throw new BusinessException(TAG_NAME_EMPTY_MESSAGE);
        }
        if (value.codePointCount(0, value.length()) > TAG_MAX_LENGTH) {
            throw new BusinessException(TAG_NAME_TOO_LONG_MESSAGE);
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
            throw new BusinessException(TAG_COLOR_INVALID_MESSAGE);
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
                throw new BusinessException(TAG_NAME_TOO_LONG_MESSAGE);
            }
            names.add(name);
        }
        if (names.size() > TAG_MAX_COUNT) {
            throw new BusinessException(WORK_TAG_COUNT_LIMIT_MESSAGE);
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
