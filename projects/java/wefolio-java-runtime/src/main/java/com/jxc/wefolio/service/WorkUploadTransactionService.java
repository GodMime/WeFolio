package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import com.jxc.wefolio.dict.WfTagStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.dict.WorkUploadTaskStatusDict;
import com.jxc.wefolio.dto.MineWorkUploadCompleteRequest;
import com.jxc.wefolio.dto.MineWorkUploadCompleteResponse;
import com.jxc.wefolio.entity.WfTagEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.entity.WorkTagEntity;
import com.jxc.wefolio.entity.WorkUploadTaskEntity;
import com.jxc.wefolio.exception.BusinessException;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 作品上传确认事务服务 — 在短事务内完成扣积分、建作品、建标签关联和确认任务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkUploadTransactionService {

    /** 作品标题最大长度 */
    private static final int TITLE_MAX_LENGTH = 30;

    /** 作品说明最大长度 */
    private static final int DESCRIPTION_MAX_LENGTH = 1000;

    /** 图片原图可直接复用为缩略图的最大字节数 */
    private static final long THUMB_MAX_BYTES = 100L * 1024L;

    /** 标签最大数量 */
    private static final int TAG_MAX_COUNT = 10;

    /** 单个标签最大长度 */
    private static final int TAG_NAME_MAX_LENGTH = 10;

    /** 标签名称超长提示 */
    private static final String TAG_NAME_TOO_LONG_MESSAGE = "标签名称不能超过 10 个字";

    /** 标签数量超限提示 */
    private static final String TAG_COUNT_LIMIT_MESSAGE = "标签最多保留 10 个";

    /** 作品上传积分业务类型 */
    private static final String BUSINESS_TYPE_WORK_UPLOAD = "WORK_UPLOAD";

    /** 确认幂等键前缀 */
    private static final String CONFIRM_IDEMPOTENCY_PREFIX = "WORK_UPLOAD_CONFIRM:";

    /** 缩略图或封面图已使用提示 */
    private static final String COVER_TASK_USED_MESSAGE = "缩略图或封面图已被使用，请重新上传";

    /** 缩略图或封面图过期提示 */
    private static final String COVER_TASK_EXPIRED_MESSAGE = "缩略图或封面图已过期，请重新上传";

    /** 重复作品提示模板 */
    private static final String DUPLICATE_WORK_MESSAGE_TEMPLATE = "作品已存在：「%s」";

    /** 重复作品兜底提示 */
    private static final String DUPLICATE_WORK_FALLBACK_MESSAGE = "作品已存在，请勿重复上传";

    /** 缩略图或封面图缺失提示 */
    private static final String COVER_REQUIRED_MESSAGE = "缩略图或封面图不能为空";

    /** 上传任务 Mapper */
    private final WorkUploadTaskEntityMapper workUploadTaskEntityMapper;

    /** 作品 Mapper */
    private final WorkEntityMapper workEntityMapper;

    /** 标签 Mapper */
    private final WfTagEntityMapper wfTagEntityMapper;

    /** 作品标签 Mapper */
    private final WorkTagEntityMapper workTagEntityMapper;

    /** 积分服务 */
    private final PointService pointService;

    /**
     * 确认单个上传任务并创建作品。
     *
     * @param userId 当前用户 ID
     * @param task 上传任务
     * @param item 确认参数
     * @return 单个确认结果
     */
    @Transactional(rollbackFor = Exception.class)
    public MineWorkUploadCompleteResponse.Item confirmUploadedTask(
            Long userId,
            WorkUploadTaskEntity task,
            MineWorkUploadCompleteRequest.CompleteItem item
    ) {
        requireOwnedTask(userId, task);
        Long taskId = task.getId();
        if (WorkUploadTaskStatusDict.CONFIRMED.getCode().equals(task.getStatus())) {
            WorkEntity existing = workEntityMapper.selectById(task.getConfirmedWorkId());
            return MineWorkUploadCompleteResponse.Item.success(taskId, existing, "作品已确认");
        }
        if (!WorkUploadTaskStatusDict.CREATED.getCode().equals(task.getStatus())
                && !WorkUploadTaskStatusDict.UPLOADED.getCode().equals(task.getStatus())
                && !WorkUploadTaskStatusDict.FAILED.getCode().equals(task.getStatus())) {
            throw new BusinessException("上传任务状态不可确认");
        }
        if (task.getExpiresAt() != null && task.getExpiresAt().isBefore(LocalDateTime.now())) {
            task.setStatus(WorkUploadTaskStatusDict.EXPIRED.getCode());
            task.setErrorMessage("上传任务已过期");
            workUploadTaskEntityMapper.updateById(task);
            throw new BusinessException("上传任务已过期，请重新选择文件");
        }

        String title = normalizeTitle(item == null ? null : item.getTitle(), task.getOriginalFileName());
        String description = normalizeDescription(item == null ? null : item.getDescription());
        List<String> tagNames = normalizeTagNames(item == null ? null : item.getTagNames());
        WorkUploadTaskEntity coverTask = resolveCoverTask(userId, task, item);
        String coverObjectKey = resolveCoverObjectKey(task, coverTask);
        String coverSha256 = resolveCoverSha256(task, coverTask);
        String sceneCode = resolvePointScene(task.getMediaType());
        String remark = buildPointRemark(task.getMediaType());
        String idempotencyKey = normalizeText(item == null ? null : item.getIdempotencyKey());
        if (idempotencyKey.isBlank()) {
            idempotencyKey = CONFIRM_IDEMPOTENCY_PREFIX + task.getId();
        }
        ensureNoDuplicateWork(userId, task.getFileSha256());

        pointService.consume(
                userId,
                sceneCode,
                BUSINESS_TYPE_WORK_UPLOAD,
                String.valueOf(task.getId()),
                1,
                idempotencyKey,
                remark);

        WorkEntity work = buildWork(task, title, description, coverObjectKey, coverSha256);
        try {
            workEntityMapper.insert(work);
        } catch (DuplicateKeyException e) {
            WorkEntity duplicateWork = findNonDeletedWorkBySha256(userId, task.getFileSha256());
            if (duplicateWork != null) {
                throw duplicateWorkException(duplicateWork, e);
            }
            log.error(
                    "作品保存唯一键冲突未匹配到已存在 SHA-256 作品：userId={} taskId={} mediaSha256={}",
                    userId,
                    task.getId(),
                    task.getFileSha256(),
                    e);
            throw new BusinessException(DUPLICATE_WORK_FALLBACK_MESSAGE, e);
        }
        if (work.getId() == null) {
            throw new BusinessException("作品保存失败，请重试");
        }
        attachTags(userId, work.getId(), tagNames);

        task.setStatus(WorkUploadTaskStatusDict.CONFIRMED.getCode());
        task.setConfirmedWorkId(work.getId());
        if (hasText(coverObjectKey)) {
            task.setCoverObjectKey(coverObjectKey);
        }
        task.setErrorMessage(null);
        workUploadTaskEntityMapper.updateById(task);
        confirmCoverTaskIfNeeded(coverTask, work.getId());
        return MineWorkUploadCompleteResponse.Item.success(taskId, work, "上传成功");
    }

    /**
     * 查询当前用户上传任务。
     *
     * @param userId 当前用户 ID
     * @param task 上传任务
     */
    private void requireOwnedTask(Long userId, WorkUploadTaskEntity task) {
        if (task == null || task.getId() == null) {
            throw new BusinessException("上传任务不能为空");
        }
        if (!userId.equals(task.getUserId())) {
            throw new BusinessException("上传任务不存在");
        }
    }

    /**
     * 构造作品实体。
     *
     * @param task 上传任务
     * @param title 作品标题
     * @param description 作品说明
     * @param coverObjectKey 缩略图或封面图对象键
     * @param coverSha256 缩略图或封面图 SHA-256
     * @return 作品实体
     */
    private WorkEntity buildWork(
            WorkUploadTaskEntity task,
            String title,
            String description,
            String coverObjectKey,
            String coverSha256
    ) {
        WorkEntity work = new WorkEntity();
        work.setUserId(task.getUserId());
        work.setMediaType(task.getMediaType());
        work.setTitle(title);
        work.setOriginalFileName(task.getOriginalFileName());
        work.setMediaObjectKey(task.getObjectKey());
        work.setMediaSha256(task.getFileSha256());
        work.setCoverObjectKey(coverObjectKey);
        work.setCoverSha256(coverSha256);
        work.setMimeType(task.getMimeType());
        work.setFileSize(task.getFileSize());
        work.setDurationMs(task.getDurationMs());
        work.setWidth(task.getWidth());
        work.setHeight(task.getHeight());
        work.setDescription(description);
        work.setSortOrder(0);
        work.setStatus(WorkStatusDict.ACTIVE.getCode());
        return work;
    }

    /**
     * 解析作品封面对象键。
     *
     * @param task 上传任务
     * @param coverTask 缩略图或封面图任务
     * @return 封面对象键
     */
    private String resolveCoverObjectKey(WorkUploadTaskEntity task, WorkUploadTaskEntity coverTask) {
        if (coverTask != null && hasText(coverTask.getObjectKey())) {
            return coverTask.getObjectKey();
        }
        if (hasText(task.getCoverObjectKey())) {
            return task.getCoverObjectKey();
        }
        if (canUseOriginalAsCover(task)) {
            return task.getObjectKey();
        }
        throw new BusinessException(COVER_REQUIRED_MESSAGE);
    }

    /**
     * 解析作品封面 SHA-256。
     *
     * <p>SHA-256 由小程序端计算提交。后端为避免下载 COS 文件带来的性能开销，信任前端值，
     * 小图复用原图时直接复制原文件 SHA-256。</p>
     *
     * @param task 上传任务
     * @param coverTask 缩略图或封面图任务
     * @return 缩略图或封面图 SHA-256
     */
    private String resolveCoverSha256(WorkUploadTaskEntity task, WorkUploadTaskEntity coverTask) {
        if (coverTask != null && hasText(coverTask.getFileSha256())) {
            return coverTask.getFileSha256();
        }
        if (canUseOriginalAsCover(task) && hasText(task.getFileSha256())) {
            return task.getFileSha256();
        }
        throw new BusinessException(COVER_REQUIRED_MESSAGE);
    }

    /**
     * 判断原文件是否可直接作为缩略图。
     *
     * @param task 上传任务
     * @return 是否可复用原文件
     */
    private boolean canUseOriginalAsCover(WorkUploadTaskEntity task) {
        return MediaTypeDict.IMAGE.getCode().equals(task.getMediaType())
                && task.getFileSize() != null
                && task.getFileSize() <= THUMB_MAX_BYTES;
    }

    /**
     * 解析缩略图或封面图任务。
     *
     * @param userId 当前用户 ID
     * @param task 主上传任务
     * @param item 确认参数
     * @return 缩略图或封面图上传任务，可为空
     */
    private WorkUploadTaskEntity resolveCoverTask(
            Long userId,
            WorkUploadTaskEntity task,
            MineWorkUploadCompleteRequest.CompleteItem item
    ) {
        Long coverTaskId = item == null ? null : item.getCoverTaskId();
        if (coverTaskId == null) {
            return null;
        }
        WorkUploadCoverTaskValidator.ensureNotSelfReference(task, coverTaskId);
        WorkUploadTaskEntity coverTask = workUploadTaskEntityMapper.selectById(coverTaskId);
        requireOwnedTask(userId, coverTask);
        WorkUploadCoverTaskValidator.ensureImageCoverTask(coverTask);
        if (WorkUploadTaskStatusDict.CONFIRMED.getCode().equals(coverTask.getStatus())) {
            throw new BusinessException(COVER_TASK_USED_MESSAGE);
        }
        if (coverTask.getExpiresAt() != null && coverTask.getExpiresAt().isBefore(LocalDateTime.now())) {
            coverTask.setStatus(WorkUploadTaskStatusDict.EXPIRED.getCode());
            coverTask.setErrorMessage("封面上传任务已过期");
            workUploadTaskEntityMapper.updateById(coverTask);
            throw new BusinessException(COVER_TASK_EXPIRED_MESSAGE);
        }
        return coverTask;
    }

    /**
     * 缩略图或封面图任务不创建独立作品，确认后绑定到主作品。
     *
     * @param coverTask 缩略图或封面图任务
     * @param workId 主作品 ID
     */
    private void confirmCoverTaskIfNeeded(WorkUploadTaskEntity coverTask, Long workId) {
        if (coverTask == null) {
            return;
        }
        coverTask.setStatus(WorkUploadTaskStatusDict.CONFIRMED.getCode());
        coverTask.setConfirmedWorkId(workId);
        coverTask.setErrorMessage(null);
        workUploadTaskEntityMapper.updateById(coverTask);
    }

    /**
     * 校验当前用户未上传过同一原文件。
     *
     * @param userId 当前用户 ID
     * @param mediaSha256 原文件 SHA-256
     */
    private void ensureNoDuplicateWork(Long userId, String mediaSha256) {
        WorkEntity duplicateWork = findNonDeletedWorkBySha256(userId, mediaSha256);
        if (duplicateWork != null) {
            throw duplicateWorkException(duplicateWork, null);
        }
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
     * 构造重复作品异常。
     *
     * @param duplicateWork 已存在作品
     * @param cause 原始异常，可为空
     * @return 业务异常
     */
    private BusinessException duplicateWorkException(WorkEntity duplicateWork, Throwable cause) {
        String title = duplicateWork == null ? "" : normalizeText(duplicateWork.getTitle());
        String message = title.isBlank()
                ? DUPLICATE_WORK_FALLBACK_MESSAGE
                : String.format(DUPLICATE_WORK_MESSAGE_TEMPLATE, title);
        return cause == null ? new BusinessException(message) : new BusinessException(message, cause);
    }

    /**
     * 附加作品标签。
     *
     * @param userId 当前用户 ID
     * @param workId 作品 ID
     * @param tagNames 标签名称
     */
    private void attachTags(Long userId, Long workId, List<String> tagNames) {
        for (String tagName : tagNames) {
            WfTagEntity tag = findOrCreateTag(userId, tagName);
            WorkTagEntity relation = new WorkTagEntity();
            relation.setUserId(userId);
            relation.setWorkId(workId);
            relation.setTagId(tag.getId());
            workTagEntityMapper.insert(relation);
        }
    }

    /**
     * 查找或创建标签。
     *
     * @param userId 当前用户 ID
     * @param tagName 标签名称
     * @return 标签实体
     */
    private WfTagEntity findOrCreateTag(Long userId, String tagName) {
        WfTagEntity existing = findTag(userId, tagName);
        if (isActiveTag(existing)) {
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
            WfTagEntity duplicate = findTag(userId, tagName);
            if (isActiveTag(duplicate)) {
                return duplicate;
            }
            throw e;
        }
    }

    /**
     * 查询标签。
     *
     * @param userId 当前用户 ID
     * @param tagName 标签名称
     * @return 标签实体，可为空
     */
    private WfTagEntity findTag(Long userId, String tagName) {
        return wfTagEntityMapper.selectOne(
                Wrappers.lambdaQuery(WfTagEntity.class)
                        .eq(WfTagEntity::getUserId, userId)
                        .eq(WfTagEntity::getName, tagName)
                        .eq(WfTagEntity::getStatus, WfTagStatusDict.ACTIVE.getCode())
                        .last("LIMIT 1")
        );
    }

    /**
     * 判断是否启用标签。
     *
     * @param tag 标签实体，可为空
     * @return 是否启用
     */
    private boolean isActiveTag(WfTagEntity tag) {
        return tag != null && WfTagStatusDict.ACTIVE.getCode().equals(tag.getStatus());
    }

    /**
     * 校验当前用户启用标签数量上限。
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
     * 根据媒体类型获取积分场景。
     *
     * @param mediaType 媒体类型
     * @return 积分场景编码
     */
    private String resolvePointScene(String mediaType) {
        if (MediaTypeDict.IMAGE.getCode().equals(mediaType)) {
            return PointSceneCodeDict.UPLOAD_IMAGE.getCode();
        }
        if (MediaTypeDict.VIDEO.getCode().equals(mediaType)) {
            return PointSceneCodeDict.UPLOAD_VIDEO.getCode();
        }
        throw new BusinessException("作品媒体类型不支持");
    }

    /**
     * 构造积分流水备注。
     *
     * @param mediaType 媒体类型
     * @return 备注
     */
    private String buildPointRemark(String mediaType) {
        return MediaTypeDict.VIDEO.getCode().equals(mediaType) ? "上传视频作品" : "上传图片作品";
    }

    /**
     * 归一化标题。
     *
     * @param title 入参标题
     * @param originalFileName 原始文件名
     * @return 标题
     */
    private String normalizeTitle(String title, String originalFileName) {
        String value = normalizeText(title);
        if (value.isBlank()) {
            value = stripExtension(normalizeText(originalFileName));
            if (value.length() > TITLE_MAX_LENGTH) {
                value = value.substring(0, TITLE_MAX_LENGTH);
            }
        }
        if (value.isBlank()) {
            value = "未命名作品";
        }
        if (value.length() > TITLE_MAX_LENGTH) {
            throw new BusinessException("作品标题不能超过 30 字");
        }
        return value;
    }

    /**
     * 归一化说明。
     *
     * @param description 入参说明
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
     * 归一化标签列表。
     *
     * @param tagNames 标签名称
     * @return 去重后的标签名称
     */
    private List<String> normalizeTagNames(List<String> tagNames) {
        if (tagNames == null || tagNames.isEmpty()) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (String rawName : tagNames) {
            String name = normalizeText(rawName);
            if (name.isBlank()) {
                continue;
            }
            if (name.codePointCount(0, name.length()) > TAG_NAME_MAX_LENGTH) {
                throw new BusinessException(TAG_NAME_TOO_LONG_MESSAGE);
            }
            names.add(name);
        }
        if (names.size() > TAG_MAX_COUNT) {
            throw new BusinessException("作品标签最多 10 个");
        }
        return List.copyOf(names);
    }

    /**
     * 去除文件扩展名。
     *
     * @param fileName 文件名
     * @return 不含扩展名的文件名
     */
    private String stripExtension(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index > 0 ? fileName.substring(0, index) : fileName;
    }

    /**
     * 归一化文本。
     *
     * @param value 原值
     * @return 去首尾空格后的文本
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
