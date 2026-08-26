package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.FeedbackUploadTaskStatusDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dto.MineFeedbackUploadTicketRequest;
import com.jxc.wefolio.dto.MineFeedbackUploadTicketResponse;
import com.jxc.wefolio.entity.FeedbackUploadTaskEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.FeedbackUploadTaskEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.message.MineFeedbackMessage;
import com.jxc.wefolio.model.FeedbackRoundSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 反馈附件上传服务，负责文件规则校验、COS 票据签发和附件快照确认。
 */
@Service
public class FeedbackUploadService {

    /** 上传票据有效分钟数。 */
    private static final long TICKET_TTL_MINUTES = 15L;

    /** 反馈附件保存目录。 */
    private static final String FEEDBACK_FOLDER = "others";

    /** COS 对象键路径分隔符。 */
    private static final String PATH_SEPARATOR = "/";

    /** 文件扩展名分隔符。 */
    private static final String EXTENSION_SEPARATOR = ".";

    /** 反馈上传任务 Mapper。 */
    private final FeedbackUploadTaskEntityMapper uploadTaskMapper;

    /** 用户 Mapper。 */
    private final UserEntityMapper userEntityMapper;

    /** COS 服务。 */
    private final CosService cosService;

    /** 文件元数据校验器。 */
    private final FeedbackUploadFileValidator fileValidator;

    /** 服务端时间来源。 */
    private final Clock clock;

    /**
     * 创建生产环境反馈上传服务。
     *
     * @param uploadTaskMapper 反馈上传任务 Mapper
     * @param userEntityMapper 用户 Mapper
     * @param cosService COS 服务
     * @param fileValidator 文件元数据校验器
     */
    @Autowired
    public FeedbackUploadService(
            FeedbackUploadTaskEntityMapper uploadTaskMapper,
            UserEntityMapper userEntityMapper,
            CosService cosService,
            FeedbackUploadFileValidator fileValidator
    ) {
        this(uploadTaskMapper, userEntityMapper, cosService, fileValidator, FeedbackTimeSource.systemClock());
    }

    /**
     * 创建使用指定时钟的反馈上传服务，供同包测试固定时间边界。
     *
     * @param uploadTaskMapper 反馈上传任务 Mapper
     * @param userEntityMapper 用户 Mapper
     * @param cosService COS 服务
     * @param fileValidator 文件元数据校验器
     * @param clock 服务端时间来源
     */
    FeedbackUploadService(
            FeedbackUploadTaskEntityMapper uploadTaskMapper,
            UserEntityMapper userEntityMapper,
            CosService cosService,
            FeedbackUploadFileValidator fileValidator,
            Clock clock
    ) {
        this.uploadTaskMapper = uploadTaskMapper;
        this.userEntityMapper = userEntityMapper;
        this.cosService = cosService;
        this.fileValidator = fileValidator;
        this.clock = clock;
    }

    /**
     * 为当前登录用户创建反馈附件直传票据。
     *
     * <p>此入口只校验文件本身，不读取反馈数量、状态或轮次。</p>
     *
     * @param request 待上传文件元数据
     * @return 文件顺序与请求一致的 COS 直传票据
     */
    public MineFeedbackUploadTicketResponse createUploadTickets(MineFeedbackUploadTicketRequest request) {
        Long userId = AuthContextHolder.requireUserId();
        List<MineFeedbackUploadTicketRequest.UploadFileItem> files = request == null ? null : request.getFiles();
        List<FeedbackUploadFileValidator.PreparedUploadFile> preparedFiles = fileValidator.prepareFiles(files);
        UserEntity user = userEntityMapper.selectById(userId);
        if (user == null
                || !UserStatusDict.ACTIVE.getCode().equals(user.getStatus())
                || isBlank(user.getUniqueCode())) {
            throw new BusinessException(MineFeedbackMessage.USER_NOT_FOUND_MESSAGE);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        MineFeedbackUploadTicketResponse response = buildLimitsResponse();
        cosService.ensurePostOverwriteProtectionAvailable();
        for (FeedbackUploadFileValidator.PreparedUploadFile preparedFile : preparedFiles) {
            FeedbackUploadTaskEntity task = saveOrReuseTask(userId, user.getUniqueCode(), preparedFile, now);
            long maxBytes = fileValidator.maxBytes(task.getMediaType());
            CosService.PostUploadTicket ticket = cosService.createPostUploadTicket(
                    task.getObjectKey(), task.getMimeType(), maxBytes, task.getExpiresAt(), clock.getZone(), true);
            response.getItems().add(buildTicketItem(task, preparedFile.clientId(), maxBytes, ticket));
        }
        return response;
    }

    /**
     * 校验待确认上传任务并构造不可编辑的反馈附件快照。
     *
     * <p>每个任务均按用户行锁读取，随后通过 COS HEAD 精确复核字节数与 MIME。</p>
     *
     * @param userId 当前用户 ID
     * @param uploadTaskIds 上传任务 ID 列表，空列表表示无附件
     * @return 与任务 ID 输入顺序一致的附件快照
     */
    public List<FeedbackRoundSnapshot.Attachment> validateAndBuildAttachments(
            Long userId,
            List<Long> uploadTaskIds
    ) {
        if (uploadTaskIds == null || uploadTaskIds.isEmpty()) {
            return new ArrayList<>();
        }
        ensureUniqueTaskIds(uploadTaskIds);
        LocalDateTime now = LocalDateTime.now(clock);
        List<FeedbackRoundSnapshot.Attachment> attachments = new ArrayList<>(uploadTaskIds.size());
        for (Long taskId : uploadTaskIds) {
            FeedbackUploadTaskEntity task = requireOwnedPendingTask(userId, taskId);
            if (task.getExpiresAt() == null || !task.getExpiresAt().isAfter(now)) {
                throw new BusinessException(MineFeedbackMessage.UPLOAD_TASK_EXPIRED_MESSAGE);
            }
            CosService.ObjectHead head = readObjectHead(task.getObjectKey());
            if (task.getFileSize() == null
                    || head.contentLength() != task.getFileSize()
                    || head.contentLength() <= 0L
                    || head.contentLength() > fileValidator.maxBytes(task.getMediaType())
                    || !fileValidator.normalizeMime(task.getMimeType())
                            .equals(fileValidator.normalizeMime(head.contentType()))) {
                throw new BusinessException(MineFeedbackMessage.UPLOAD_OBJECT_MISMATCH_MESSAGE);
            }
            attachments.add(buildAttachment(task));
        }
        return attachments;
    }

    /**
     * 将已校验的上传任务关联到指定反馈及轮次。
     *
     * <p>方法不自行开启事务，必须参与调用方创建或追加反馈的事务。</p>
     *
     * @param userId 当前用户 ID
     * @param uploadTaskIds 上传任务 ID 列表，空列表无需处理
     * @param feedbackId 反馈 ID
     * @param roundNo 关联反馈轮次
     */
    public void confirmTasks(Long userId, List<Long> uploadTaskIds, Long feedbackId, int roundNo) {
        if (uploadTaskIds == null || uploadTaskIds.isEmpty()) {
            return;
        }
        ensureUniqueTaskIds(uploadTaskIds);
        for (Long taskId : uploadTaskIds) {
            FeedbackUploadTaskEntity task = requireOwnedPendingTask(userId, taskId);
            LocalDateTime now = LocalDateTime.now(clock);
            if (task.getExpiresAt() == null || !task.getExpiresAt().isAfter(now)) {
                throw new BusinessException(MineFeedbackMessage.UPLOAD_TASK_EXPIRED_MESSAGE);
            }
            int updated = uploadTaskMapper.update(null,
                    Wrappers.<FeedbackUploadTaskEntity>lambdaUpdate()
                            .eq(FeedbackUploadTaskEntity::getId, taskId)
                            .eq(FeedbackUploadTaskEntity::getUserId, userId)
                            .eq(FeedbackUploadTaskEntity::getStatus,
                                    FeedbackUploadTaskStatusDict.PENDING.getCode())
                            .eq(FeedbackUploadTaskEntity::getDeleted, 0L)
                            .gt(FeedbackUploadTaskEntity::getExpiresAt, now)
                            .set(FeedbackUploadTaskEntity::getStatus,
                                    FeedbackUploadTaskStatusDict.CONFIRMED.getCode())
                            .set(FeedbackUploadTaskEntity::getFeedbackId, feedbackId)
                            .set(FeedbackUploadTaskEntity::getRoundNo, roundNo));
            if (updated != 1) {
                throw new BusinessException(MineFeedbackMessage.UPLOAD_TASK_CONFIRM_FAILED_MESSAGE);
            }
        }
    }

    /**
     * 新建任务或复用同用户、同客户端标识的有效任务。
     *
     * @param userId 当前用户 ID
     * @param uniqueCode 用户个人唯一码
     * @param file 归一化文件元数据
     * @param now 当前时间
     * @return 已持久化或并发回读的任务
     */
    private FeedbackUploadTaskEntity saveOrReuseTask(
            Long userId,
            String uniqueCode,
            FeedbackUploadFileValidator.PreparedUploadFile file,
            LocalDateTime now
    ) {
        FeedbackUploadTaskEntity existing = findByUserAndClientId(userId, file.clientId());
        if (existing != null) {
            return requireReusableTask(existing, userId, file, now);
        }
        FeedbackUploadTaskEntity task = buildTask(userId, uniqueCode, file, now.plusMinutes(TICKET_TTL_MINUTES));
        try {
            if (uploadTaskMapper.insert(task) != 1) {
                throw new BusinessException(MineFeedbackMessage.UPLOAD_TASK_SAVE_FAILED_MESSAGE);
            }
            return task;
        } catch (DuplicateKeyException exception) {
            FeedbackUploadTaskEntity raced = findByUserAndClientId(userId, file.clientId());
            if (raced == null) {
                throw new BusinessException(MineFeedbackMessage.UPLOAD_TASK_SAVE_FAILED_MESSAGE, exception);
            }
            return requireReusableTask(raced, userId, file, now);
        }
    }

    /**
     * 按用户和客户端标识读取未删除任务。
     *
     * @param userId 用户 ID
     * @param clientId 客户端文件标识
     * @return 已存在任务，不存在时返回 null
     */
    private FeedbackUploadTaskEntity findByUserAndClientId(Long userId, String clientId) {
        return uploadTaskMapper.selectOne(
                Wrappers.<FeedbackUploadTaskEntity>lambdaQuery()
                        .eq(FeedbackUploadTaskEntity::getUserId, userId)
                        .eq(FeedbackUploadTaskEntity::getClientId, clientId)
                        .eq(FeedbackUploadTaskEntity::getDeleted, 0L)
                        .last("LIMIT 1"));
    }

    /**
     * 校验幂等命中的任务仍可对同一文件重新签票。
     *
     * @param task 已存在任务
     * @param userId 当前用户 ID
     * @param file 当前文件声明
     * @param now 当前时间
     * @return 原任务
     */
    private FeedbackUploadTaskEntity requireReusableTask(
            FeedbackUploadTaskEntity task,
            Long userId,
            FeedbackUploadFileValidator.PreparedUploadFile file,
            LocalDateTime now
    ) {
        boolean reusable = userId.equals(task.getUserId())
                && file.clientId().equals(task.getClientId())
                && FeedbackUploadTaskStatusDict.PENDING.getCode().equals(task.getStatus())
                && task.getExpiresAt() != null
                && task.getExpiresAt().isAfter(now)
                && !isBlank(task.getObjectKey())
                && file.mediaType().equals(task.getMediaType())
                && file.mimeType().equals(fileValidator.normalizeMime(task.getMimeType()))
                && Long.valueOf(file.fileSize()).equals(task.getFileSize())
                && Long.valueOf(file.durationMs()).equals(task.getDurationMs());
        if (!reusable) {
            throw new BusinessException(MineFeedbackMessage.UPLOAD_CLIENT_ID_REUSED_MESSAGE);
        }
        return task;
    }

    /**
     * 构造新的反馈上传任务。
     *
     * @param userId 当前用户 ID
     * @param uniqueCode 用户个人唯一码
     * @param file 归一化文件元数据
     * @param expiresAt 任务过期时间
     * @return 待持久化任务
     */
    private FeedbackUploadTaskEntity buildTask(
            Long userId,
            String uniqueCode,
            FeedbackUploadFileValidator.PreparedUploadFile file,
            LocalDateTime expiresAt
    ) {
        FeedbackUploadTaskEntity task = new FeedbackUploadTaskEntity();
        task.setUserId(userId);
        task.setClientId(file.clientId());
        task.setObjectKey(buildObjectKey(uniqueCode, file.extension()));
        task.setMediaType(file.mediaType());
        task.setMimeType(file.mimeType());
        task.setFileSize(file.fileSize());
        task.setDurationMs(file.durationMs());
        task.setStatus(FeedbackUploadTaskStatusDict.PENDING.getCode());
        task.setExpiresAt(expiresAt);
        return task;
    }

    /**
     * 构造只由服务端决定的反馈附件 COS 对象键。
     *
     * @param uniqueCode 用户个人唯一码
     * @param extension 白名单扩展名
     * @return COS 对象键
     */
    private String buildObjectKey(String uniqueCode, String extension) {
        String objectFileName = UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT);
        return uniqueCode.strip()
                + PATH_SEPARATOR + FEEDBACK_FOLDER + PATH_SEPARATOR
                + objectFileName + EXTENSION_SEPARATOR + extension;
    }

    /** 构造携带服务端限制的空票据响应。 */
    private MineFeedbackUploadTicketResponse buildLimitsResponse() {
        MineFeedbackUploadTicketResponse response = new MineFeedbackUploadTicketResponse();
        response.setMaxFileCount(FeedbackUploadFileValidator.MAX_FILE_COUNT);
        response.setImageMaxBytes(FeedbackUploadFileValidator.IMAGE_MAX_BYTES);
        response.setVideoMaxBytes(FeedbackUploadFileValidator.VIDEO_MAX_BYTES);
        response.setVideoMaxDurationMs(FeedbackUploadFileValidator.VIDEO_MAX_DURATION_MS);
        return response;
    }

    /**
     * 将持久化任务和 COS 表单信息映射为单项响应。
     *
     * @param task 上传任务
     * @param clientId 客户端文件标识
     * @param maxBytes 媒体字节限制
     * @param ticket COS 表单票据
     * @return 单项票据响应
     */
    private MineFeedbackUploadTicketResponse.Item buildTicketItem(
            FeedbackUploadTaskEntity task,
            String clientId,
            long maxBytes,
            CosService.PostUploadTicket ticket
    ) {
        MineFeedbackUploadTicketResponse.Item item = new MineFeedbackUploadTicketResponse.Item();
        item.setTaskId(task.getId());
        item.setClientId(clientId);
        item.setMediaType(task.getMediaType());
        item.setObjectKey(task.getObjectKey());
        item.setUploadUrl(ticket.uploadUrl());
        item.setFormData(ticket.formData() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(ticket.formData()));
        item.setExpiresAt(task.getExpiresAt());
        item.setMaxBytes(maxBytes);
        return item;
    }

    /**
     * 校验任务 ID 非空且在当前列表内唯一。
     *
     * @param uploadTaskIds 上传任务 ID 列表
     */
    private void ensureUniqueTaskIds(List<Long> uploadTaskIds) {
        Set<Long> uniqueTaskIds = new HashSet<>();
        for (Long taskId : uploadTaskIds) {
            if (taskId == null) {
                throw new BusinessException(MineFeedbackMessage.UPLOAD_TASK_MISSING_MESSAGE);
            }
            if (!uniqueTaskIds.add(taskId)) {
                throw new BusinessException(MineFeedbackMessage.UPLOAD_TASK_STATUS_INVALID_MESSAGE);
            }
        }
    }

    /**
     * 锁定并校验当前用户的待上传任务。
     *
     * @param userId 当前用户 ID
     * @param taskId 上传任务 ID
     * @return 已锁定任务
     */
    private FeedbackUploadTaskEntity requireOwnedPendingTask(Long userId, Long taskId) {
        FeedbackUploadTaskEntity task = uploadTaskMapper.lockByIdAndUserId(taskId, userId);
        if (task == null) {
            throw new BusinessException(MineFeedbackMessage.UPLOAD_TASK_MISSING_MESSAGE);
        }
        if (!userId.equals(task.getUserId())) {
            throw new BusinessException(MineFeedbackMessage.UPLOAD_TASK_NOT_OWNED_MESSAGE);
        }
        if (!FeedbackUploadTaskStatusDict.PENDING.getCode().equals(task.getStatus())) {
            throw new BusinessException(MineFeedbackMessage.UPLOAD_TASK_STATUS_INVALID_MESSAGE);
        }
        return task;
    }

    /**
     * 安全读取 COS 对象头并屏蔽远端异常细节。
     *
     * @param objectKey COS 对象键
     * @return 非空对象头
     */
    private CosService.ObjectHead readObjectHead(String objectKey) {
        try {
            CosService.ObjectHead head = cosService.headObject(objectKey);
            if (head == null) {
                throw new BusinessException(MineFeedbackMessage.UPLOAD_OBJECT_READ_FAILED_MESSAGE);
            }
            return head;
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(MineFeedbackMessage.UPLOAD_OBJECT_READ_FAILED_MESSAGE, exception);
        }
    }

    /**
     * 将已核验任务映射为轮次附件快照，保留客户端声明的大小和时长。
     *
     * @param task 已核验上传任务
     * @return 附件快照
     */
    private FeedbackRoundSnapshot.Attachment buildAttachment(FeedbackUploadTaskEntity task) {
        FeedbackRoundSnapshot.Attachment attachment = new FeedbackRoundSnapshot.Attachment();
        attachment.setObjectKey(task.getObjectKey());
        attachment.setMediaType(task.getMediaType());
        attachment.setMimeType(fileValidator.normalizeMime(task.getMimeType()));
        attachment.setSize(task.getFileSize());
        attachment.setDurationMs(task.getDurationMs() == null ? 0L : task.getDurationMs());
        return attachment;
    }

    /** 判断文本是否为空。 */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

}
