package com.jxc.wefolio.service;

import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.FeedbackStatusDict;
import com.jxc.wefolio.dto.FeedbackStatusUpdateRequest;
import com.jxc.wefolio.dto.MineFeedbackCreateRequest;
import com.jxc.wefolio.entity.FeedbackEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.FeedbackEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.message.MineFeedbackMessage;
import com.jxc.wefolio.model.FeedbackRoundSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 问题反馈事务服务，负责创建、追加轮次和内部状态流转。
 */
@Service
public class FeedbackTransactionService {

    /** 单个用户最大活跃问题数。 */
    private static final int MAX_ACTIVE_COUNT = 3;

    /** 单个问题最大反馈轮数。 */
    private static final int MAX_ROUND_COUNT = 3;

    /** 单轮最大附件数。 */
    private static final int MAX_ATTACHMENT_COUNT = 3;

    /** 描述与团队反馈结果最大 Unicode 代码点数量。 */
    private static final int MAX_TEXT_CODE_POINT_COUNT = 200;

    /** 对外反馈编号前缀。 */
    private static final String FEEDBACK_NO_PREFIX = "FB";

    /** 反馈编号中的上海时间格式。 */
    private static final DateTimeFormatter FEEDBACK_NO_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 反馈编号随机部分字符集，排除易混淆字符。 */
    private static final String FEEDBACK_NO_RANDOM_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** 反馈编号随机部分长度。 */
    private static final int FEEDBACK_NO_RANDOM_LENGTH = 6;

    /** 反馈编号安全随机数来源。 */
    private static final SecureRandom FEEDBACK_NO_RANDOM = new SecureRandom();

    /** 活跃问题状态集合。 */
    private static final List<String> ACTIVE_STATUSES = List.of(
            FeedbackStatusDict.PROCESSING.getCode(),
            FeedbackStatusDict.WAITING_FOLLOW_UP.getCode());

    /** 反馈 Mapper。 */
    private final FeedbackEntityMapper feedbackEntityMapper;

    /** 用户 Mapper。 */
    private final UserEntityMapper userEntityMapper;

    /** 反馈附件上传服务。 */
    private final FeedbackUploadService feedbackUploadService;

    /** 轮次 JSON 编解码器。 */
    private final FeedbackRoundCodec feedbackRoundCodec;

    /** 反馈轮次快照统一校验器。 */
    private final FeedbackRoundSnapshotValidator feedbackRoundSnapshotValidator;

    /** 业务时间来源。 */
    private final Clock clock;

    /**
     * 创建生产环境事务服务。
     *
     * @param feedbackEntityMapper 反馈 Mapper
     * @param userEntityMapper 用户 Mapper
     * @param feedbackUploadService 反馈附件上传服务
     * @param feedbackRoundCodec 轮次 JSON 编解码器
     * @param feedbackRoundSnapshotValidator 反馈轮次快照统一校验器
     */
    @Autowired
    public FeedbackTransactionService(
            FeedbackEntityMapper feedbackEntityMapper,
            UserEntityMapper userEntityMapper,
            FeedbackUploadService feedbackUploadService,
            FeedbackRoundCodec feedbackRoundCodec,
            FeedbackRoundSnapshotValidator feedbackRoundSnapshotValidator
    ) {
        this(feedbackEntityMapper, userEntityMapper, feedbackUploadService,
                feedbackRoundCodec, feedbackRoundSnapshotValidator, FeedbackTimeSource.systemClock());
    }

    /**
     * 创建使用指定时钟的事务服务，供同包测试固定时间。
     *
     * @param feedbackEntityMapper 反馈 Mapper
     * @param userEntityMapper 用户 Mapper
     * @param feedbackUploadService 反馈附件上传服务
     * @param feedbackRoundCodec 轮次 JSON 编解码器
     * @param feedbackRoundSnapshotValidator 反馈轮次快照统一校验器
     * @param clock 业务时间来源
     */
    FeedbackTransactionService(
            FeedbackEntityMapper feedbackEntityMapper,
            UserEntityMapper userEntityMapper,
            FeedbackUploadService feedbackUploadService,
            FeedbackRoundCodec feedbackRoundCodec,
            FeedbackRoundSnapshotValidator feedbackRoundSnapshotValidator,
            Clock clock
    ) {
        this.feedbackEntityMapper = feedbackEntityMapper;
        this.userEntityMapper = userEntityMapper;
        this.feedbackUploadService = feedbackUploadService;
        this.feedbackRoundCodec = feedbackRoundCodec;
        this.feedbackRoundSnapshotValidator = feedbackRoundSnapshotValidator;
        this.clock = clock;
    }

    /**
     * 为当前用户创建问题反馈。
     *
     * @param request 创建请求
     * @return 是否实际写入以及本轮通知所需快照
     */
    @Transactional(rollbackFor = Exception.class)
    public MutationResult create(MineFeedbackCreateRequest request) {
        Long userId = AuthContextHolder.requireUserId();
        String idempotencyKey = normalizeIdempotencyKey(request == null ? null : request.getIdempotencyKey());
        if (userEntityMapper.lockActiveUserById(userId) == null) {
            throw new BusinessException(MineFeedbackMessage.USER_NOT_FOUND_MESSAGE);
        }

        FeedbackEntity existing = feedbackEntityMapper.selectByUserIdAndCreateIdempotencyKey(
                userId, idempotencyKey);
        if (existing != null) {
            List<FeedbackRoundSnapshot> rounds = feedbackRoundSnapshotValidator.validate(existing).rounds();
            return unchanged(existing, findRoundByIdempotencyKey(rounds, idempotencyKey));
        }
        if (feedbackEntityMapper.countActiveByUserId(userId, ACTIVE_STATUSES) >= MAX_ACTIVE_COUNT) {
            throw new BusinessException(MineFeedbackMessage.ACTIVE_LIMIT_REACHED_MESSAGE);
        }

        String description = normalizeDescription(request == null ? null : request.getDescription());
        List<Long> uploadTaskIds = normalizeUploadTaskIds(request == null ? null : request.getUploadTaskIds());
        List<FeedbackRoundSnapshot.Attachment> attachments =
                feedbackUploadService.validateAndBuildAttachments(userId, uploadTaskIds);
        FeedbackRoundSnapshot round = buildRound(1, idempotencyKey, description, attachments);
        FeedbackEntity feedback = buildInitialFeedback(userId, idempotencyKey, round);
        if (feedbackEntityMapper.insert(feedback) != 1 || feedback.getId() == null) {
            throw new BusinessException(MineFeedbackMessage.FEEDBACK_SAVE_FAILED_MESSAGE);
        }
        feedbackUploadService.confirmTasks(userId, uploadTaskIds, feedback.getId(), 1);
        return new MutationResult(feedback, round, true);
    }

    /**
     * 为当前用户拥有的问题追加一轮反馈。
     *
     * @param feedbackId 反馈 ID
     * @param request 追加请求
     * @return 是否实际写入以及本轮通知所需快照
     */
    @Transactional(rollbackFor = Exception.class)
    public MutationResult append(Long feedbackId, MineFeedbackCreateRequest request) {
        Long userId = AuthContextHolder.requireUserId();
        String idempotencyKey = normalizeIdempotencyKey(request == null ? null : request.getIdempotencyKey());
        FeedbackEntity feedback = feedbackEntityMapper.lockByIdAndUserId(feedbackId, userId);
        if (feedback == null) {
            throw new BusinessException(MineFeedbackMessage.FEEDBACK_NOT_FOUND_MESSAGE);
        }
        FeedbackRoundSnapshotValidator.ValidatedSnapshot validated =
                feedbackRoundSnapshotValidator.validate(feedback);
        List<FeedbackRoundSnapshot> rounds = validated.rounds();
        FeedbackRoundSnapshot replayedRound = findRoundByIdempotencyKey(rounds, idempotencyKey);
        if (replayedRound != null) {
            return unchanged(feedback, replayedRound);
        }
        if (!FeedbackStatusDict.WAITING_FOLLOW_UP.getCode().equals(feedback.getStatus())) {
            throw new BusinessException(MineFeedbackMessage.APPEND_STATUS_INVALID_MESSAGE);
        }
        if (rounds.size() >= MAX_ROUND_COUNT) {
            throw new BusinessException(MineFeedbackMessage.ROUND_LIMIT_REACHED_MESSAGE);
        }

        String description = normalizeDescription(request == null ? null : request.getDescription());
        List<Long> uploadTaskIds = normalizeUploadTaskIds(request == null ? null : request.getUploadTaskIds());
        List<FeedbackRoundSnapshot.Attachment> attachments =
                feedbackUploadService.validateAndBuildAttachments(userId, uploadTaskIds);
        int nextRoundNo = rounds.size() + 1;
        int existingAttachmentCount = validated.attachmentCount();
        FeedbackRoundSnapshot nextRound = buildRound(nextRoundNo, idempotencyKey, description, attachments);
        archiveCurrentTeamResult(rounds, feedback);
        rounds.add(nextRound);
        feedback.setRoundsJson(feedbackRoundCodec.serialize(rounds));
        feedback.setRoundCount(nextRoundNo);
        feedback.setAttachmentCount(existingAttachmentCount + attachments.size());
        feedback.setFeedbackResult(null);
        feedback.setFeedbackResultAt(null);
        feedback.setStatus(FeedbackStatusDict.PROCESSING.getCode());
        updateFeedback(feedback);
        feedbackUploadService.confirmTasks(userId, uploadTaskIds, feedback.getId(), nextRoundNo);
        return new MutationResult(feedback, nextRound, true);
    }

    /**
     * 按反馈编号更新处理状态和团队反馈结果。
     *
     * @param feedbackNo 对外反馈编号
     * @param request 内部更新请求
     * @return 是否实际变更以及更新后的反馈
     */
    @Transactional(rollbackFor = Exception.class)
    public MutationResult updateStatus(String feedbackNo, FeedbackStatusUpdateRequest request) {
        String targetStatus = normalizeTargetStatus(request == null ? null : request.getStatus());
        String feedbackResult = normalizeFeedbackResult(request == null ? null : request.getFeedbackResult());
        FeedbackEntity feedback = feedbackEntityMapper.lockByFeedbackNo(normalizeFeedbackNo(feedbackNo));
        if (feedback == null) {
            throw new BusinessException(MineFeedbackMessage.FEEDBACK_NOT_FOUND_MESSAGE);
        }
        List<FeedbackRoundSnapshot> rounds = feedbackRoundSnapshotValidator.validate(feedback).rounds();
        FeedbackRoundSnapshot lastRound = rounds.getLast();
        if (targetStatus.equals(feedback.getStatus())
                && Objects.equals(feedbackResult, feedback.getFeedbackResult())) {
            return unchanged(feedback, lastRound);
        }
        if (FeedbackStatusDict.RESOLVED.getCode().equals(feedback.getStatus())) {
            throw new BusinessException(MineFeedbackMessage.STATUS_TRANSITION_INVALID_MESSAGE);
        }
        if (FeedbackStatusDict.WAITING_FOLLOW_UP.getCode().equals(targetStatus)
                && rounds.size() >= MAX_ROUND_COUNT) {
            throw new BusinessException(MineFeedbackMessage.ROUND_LIMIT_REACHED_MESSAGE);
        }
        if (!isAllowedTransition(feedback.getStatus(), targetStatus)) {
            throw new BusinessException(MineFeedbackMessage.STATUS_TRANSITION_INVALID_MESSAGE);
        }

        feedback.setStatus(targetStatus);
        feedback.setFeedbackResult(feedbackResult);
        feedback.setFeedbackResultAt(LocalDateTime.now(clock));
        updateFeedback(feedback);
        return new MutationResult(feedback, lastRound, true);
    }

    /** 归档当前团队反馈到上一轮，不修改上一轮用户提交字段。 */
    private void archiveCurrentTeamResult(List<FeedbackRoundSnapshot> rounds, FeedbackEntity feedback) {
        int lastIndex = rounds.size() - 1;
        FeedbackRoundSnapshot archived = feedbackRoundCodec.copyWithTeamResult(
                rounds.get(lastIndex), feedback.getFeedbackResult(), feedback.getFeedbackResultAt());
        rounds.set(lastIndex, archived);
    }

    /** 构造首轮反馈实体。 */
    private FeedbackEntity buildInitialFeedback(
            Long userId,
            String idempotencyKey,
            FeedbackRoundSnapshot round
    ) {
        FeedbackEntity feedback = new FeedbackEntity();
        feedback.setFeedbackNo(generateFeedbackNo(round.getSubmittedAt()));
        feedback.setUserId(userId);
        feedback.setStatus(FeedbackStatusDict.PROCESSING.getCode());
        feedback.setRoundsJson(feedbackRoundCodec.serialize(List.of(round)));
        feedback.setRoundCount(1);
        feedback.setAttachmentCount(round.getAttachments().size());
        feedback.setCreateIdempotencyKey(idempotencyKey);
        return feedback;
    }

    /** 按创建时间和六位随机码生成固定格式反馈单号。 */
    private String generateFeedbackNo(LocalDateTime submittedAt) {
        StringBuilder builder = new StringBuilder()
                .append(FEEDBACK_NO_PREFIX)
                .append(FEEDBACK_NO_TIME_FORMATTER.format(submittedAt));
        for (int index = 0; index < FEEDBACK_NO_RANDOM_LENGTH; index++) {
            builder.append(FEEDBACK_NO_RANDOM_ALPHABET.charAt(
                    FEEDBACK_NO_RANDOM.nextInt(FEEDBACK_NO_RANDOM_ALPHABET.length())));
        }
        return builder.toString();
    }

    /** 构造不可编辑的用户反馈轮次快照。 */
    private FeedbackRoundSnapshot buildRound(
            int roundNo,
            String idempotencyKey,
            String description,
            List<FeedbackRoundSnapshot.Attachment> attachments
    ) {
        FeedbackRoundSnapshot round = new FeedbackRoundSnapshot();
        round.setRoundNo(roundNo);
        round.setIdempotencyKey(idempotencyKey);
        round.setDescription(description);
        round.setSubmittedAt(LocalDateTime.now(clock));
        round.setTeamResult(null);
        round.setTeamResultAt(null);
        round.setAttachments(new ArrayList<>(attachments));
        return round;
    }

    /** 按幂等键查找已提交轮次。 */
    private FeedbackRoundSnapshot findRoundByIdempotencyKey(
            List<FeedbackRoundSnapshot> rounds,
            String idempotencyKey
    ) {
        return rounds.stream()
                .filter(round -> idempotencyKey.equals(round.getIdempotencyKey()))
                .findFirst()
                .orElse(null);
    }

    /** 规范化并校验提交幂等键。 */
    private String normalizeIdempotencyKey(String value) {
        return FeedbackIdentifierValidator.normalizePrintableAscii(
                value, MineFeedbackMessage.IDEMPOTENCY_KEY_INVALID_MESSAGE);
    }

    /** 规范化并校验用户描述。 */
    private String normalizeDescription(String value) {
        String normalized = FeedbackTextLength.trim(value);
        if (normalized.isEmpty()) {
            throw new BusinessException(MineFeedbackMessage.DESCRIPTION_EMPTY_MESSAGE);
        }
        if (FeedbackTextLength.exceeds(normalized, MAX_TEXT_CODE_POINT_COUNT)) {
            throw new BusinessException(MineFeedbackMessage.DESCRIPTION_TOO_LONG_MESSAGE);
        }
        return normalized;
    }

    /** 复制并校验本轮上传任务 ID。 */
    private List<Long> normalizeUploadTaskIds(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return new ArrayList<>();
        }
        if (taskIds.size() > MAX_ATTACHMENT_COUNT) {
            throw new BusinessException(MineFeedbackMessage.ATTACHMENT_COUNT_INVALID_MESSAGE);
        }
        Set<Long> uniqueIds = new HashSet<>();
        for (Long taskId : taskIds) {
            if (taskId == null) {
                throw new BusinessException(MineFeedbackMessage.UPLOAD_TASK_MISSING_MESSAGE);
            }
            if (!uniqueIds.add(taskId)) {
                throw new BusinessException(MineFeedbackMessage.UPLOAD_TASK_DUPLICATED_MESSAGE);
            }
        }
        return new ArrayList<>(taskIds);
    }

    /** 规范化内部目标状态。 */
    private String normalizeTargetStatus(String value) {
        String normalized = value == null ? "" : value.strip();
        if (!FeedbackStatusDict.WAITING_FOLLOW_UP.getCode().equals(normalized)
                && !FeedbackStatusDict.RESOLVED.getCode().equals(normalized)) {
            throw new BusinessException(MineFeedbackMessage.STATUS_INVALID_MESSAGE);
        }
        return normalized;
    }

    /** 规范化内部反馈结果。 */
    private String normalizeFeedbackResult(String value) {
        String normalized = FeedbackTextLength.trim(value);
        if (normalized.isEmpty()) {
            throw new BusinessException(MineFeedbackMessage.FEEDBACK_RESULT_EMPTY_MESSAGE);
        }
        if (FeedbackTextLength.exceeds(normalized, MAX_TEXT_CODE_POINT_COUNT)) {
            throw new BusinessException(MineFeedbackMessage.FEEDBACK_RESULT_TOO_LONG_MESSAGE);
        }
        return normalized;
    }

    /** 规范化反馈编号，空编号按不存在处理。 */
    private String normalizeFeedbackNo(String value) {
        return value == null ? "" : value.strip();
    }

    /** 判断当前状态能否流转到目标状态。 */
    private boolean isAllowedTransition(String currentStatus, String targetStatus) {
        if (FeedbackStatusDict.PROCESSING.getCode().equals(currentStatus)) {
            return FeedbackStatusDict.WAITING_FOLLOW_UP.getCode().equals(targetStatus)
                    || FeedbackStatusDict.RESOLVED.getCode().equals(targetStatus);
        }
        return FeedbackStatusDict.WAITING_FOLLOW_UP.getCode().equals(currentStatus)
                && (FeedbackStatusDict.WAITING_FOLLOW_UP.getCode().equals(targetStatus)
                || FeedbackStatusDict.RESOLVED.getCode().equals(targetStatus));
    }

    /** 更新反馈行并校验乐观锁结果。 */
    private void updateFeedback(FeedbackEntity feedback) {
        if (feedbackEntityMapper.updateById(feedback) != 1) {
            throw new BusinessException(MineFeedbackMessage.FEEDBACK_SAVE_FAILED_MESSAGE);
        }
    }

    /** 构造未发生写入的幂等结果。 */
    private MutationResult unchanged(FeedbackEntity feedback, FeedbackRoundSnapshot round) {
        if (round == null) {
            throw new BusinessException(MineFeedbackMessage.ROUNDS_JSON_CORRUPT_MESSAGE);
        }
        return new MutationResult(feedback, round, false);
    }

    /**
     * 反馈事务变更结果。
     *
     * @param feedback 当前反馈实体
     * @param submittedRound 当前请求对应轮次
     * @param changed 本次是否实际写入数据
     */
    public record MutationResult(
            FeedbackEntity feedback,
            FeedbackRoundSnapshot submittedRound,
            boolean changed
    ) {
    }
}
