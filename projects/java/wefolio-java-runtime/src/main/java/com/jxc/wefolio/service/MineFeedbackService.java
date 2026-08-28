package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.FeedbackStatusDict;
import com.jxc.wefolio.dto.MineFeedbackCreationStateResponse;
import com.jxc.wefolio.dto.MineFeedbackDetailResponse;
import com.jxc.wefolio.dto.MineFeedbackListResponse;
import com.jxc.wefolio.entity.FeedbackEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.FeedbackEntityMapper;
import com.jxc.wefolio.message.MineFeedbackMessage;
import com.jxc.wefolio.model.FeedbackRoundSnapshot;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 当前用户问题反馈查询服务。
 */
@Service
public class MineFeedbackService {

    /** 单个用户最大活跃问题数。 */
    private static final int MAX_ACTIVE_COUNT = 3;

    /** 单个问题最大反馈轮数。 */
    private static final int MAX_ROUND_COUNT = 3;

    /** 默认页码。 */
    private static final int DEFAULT_PAGE_NO = 1;

    /** 默认每页数量。 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 最大每页数量。 */
    private static final int MAX_PAGE_SIZE = 50;

    /** 首轮描述摘要最大字符数。 */
    private static final int DESCRIPTION_SUMMARY_MAX_LENGTH = 80;

    /** 创建额度未满时的提示格式。 */
    private static final String AVAILABLE_HINT_FORMAT = "还可提交 %d 个待处理问题";

    /** 创建额度已满时的提示。 */
    private static final String LIMIT_REACHED_HINT = "当前已有 3 个待处理问题，请处理完成后再提交";

    /** 活跃问题状态集合。 */
    private static final List<String> ACTIVE_STATUSES = List.of(
            FeedbackStatusDict.PROCESSING.getCode(),
            FeedbackStatusDict.WAITING_FOLLOW_UP.getCode());

    /** 反馈 Mapper。 */
    private final FeedbackEntityMapper feedbackEntityMapper;

    /** 反馈轮次快照统一校验器。 */
    private final FeedbackRoundSnapshotValidator feedbackRoundSnapshotValidator;

    /** COS 服务。 */
    private final CosService cosService;

    /**
     * 创建当前用户问题反馈查询服务。
     *
     * @param feedbackEntityMapper 反馈 Mapper
     * @param feedbackRoundSnapshotValidator 反馈轮次快照统一校验器
     * @param cosService COS 服务
     */
    public MineFeedbackService(
            FeedbackEntityMapper feedbackEntityMapper,
            FeedbackRoundSnapshotValidator feedbackRoundSnapshotValidator,
            CosService cosService
    ) {
        this.feedbackEntityMapper = feedbackEntityMapper;
        this.feedbackRoundSnapshotValidator = feedbackRoundSnapshotValidator;
        this.cosService = cosService;
    }

    /**
     * 查询当前用户创建问题反馈的额度状态。
     *
     * @return 创建额度状态
     */
    public MineFeedbackCreationStateResponse creationState() {
        Long userId = AuthContextHolder.requireUserId();
        int activeCount = feedbackEntityMapper.countActiveByUserId(userId, ACTIVE_STATUSES);
        boolean canCreate = activeCount < MAX_ACTIVE_COUNT;
        MineFeedbackCreationStateResponse response = new MineFeedbackCreationStateResponse();
        response.setActiveCount(activeCount);
        response.setMaxActiveCount(MAX_ACTIVE_COUNT);
        response.setCanCreate(canCreate);
        response.setHintText(canCreate
                ? AVAILABLE_HINT_FORMAT.formatted(MAX_ACTIVE_COUNT - activeCount)
                : LIMIT_REACHED_HINT);
        return response;
    }

    /**
     * 分页查询当前用户的历史问题反馈。
     *
     * @param pageNo 页码，可空
     * @param pageSize 每页数量，可空
     * @return 历史问题分页响应
     */
    public MineFeedbackListResponse list(Integer pageNo, Integer pageSize) {
        Long userId = AuthContextHolder.requireUserId();
        int normalizedPageNo = pageNo == null || pageNo < DEFAULT_PAGE_NO ? DEFAULT_PAGE_NO : pageNo;
        int normalizedPageSize = pageSize == null || pageSize <= 0
                ? DEFAULT_PAGE_SIZE
                : Math.min(pageSize, MAX_PAGE_SIZE);
        Page<FeedbackEntity> resultPage = feedbackEntityMapper.selectPage(
                new Page<>(normalizedPageNo, normalizedPageSize),
                Wrappers.lambdaQuery(FeedbackEntity.class)
                        .eq(FeedbackEntity::getUserId, userId)
                        .orderByDesc(FeedbackEntity::getUpdatedAt)
                        .orderByDesc(FeedbackEntity::getId));

        MineFeedbackListResponse response = new MineFeedbackListResponse();
        response.setPageNo(normalizedPageNo);
        response.setPageSize(normalizedPageSize);
        response.setTotal(resultPage.getTotal());
        response.setHasMore(resultPage.getCurrent() < resultPage.getPages());
        response.setItems(resultPage.getRecords().stream().map(this::buildListItem).toList());
        return response;
    }

    /**
     * 查询当前用户拥有的问题反馈详情。
     *
     * @param feedbackId 反馈 ID
     * @return 解析后的问题详情
     */
    public MineFeedbackDetailResponse detail(Long feedbackId) {
        Long userId = AuthContextHolder.requireUserId();
        FeedbackEntity feedback = feedbackEntityMapper.selectByIdAndUserId(feedbackId, userId);
        if (feedback == null) {
            throw new BusinessException(MineFeedbackMessage.FEEDBACK_NOT_FOUND_MESSAGE);
        }
        FeedbackRoundSnapshotValidator.ValidatedSnapshot validated =
                feedbackRoundSnapshotValidator.validate(feedback);
        List<FeedbackRoundSnapshot> rounds = validated.rounds();
        FeedbackStatusDict status = requireStatus(feedback.getStatus());

        MineFeedbackDetailResponse response = new MineFeedbackDetailResponse();
        response.setId(feedback.getId());
        response.setFeedbackNo(feedback.getFeedbackNo());
        response.setStatus(feedback.getStatus());
        response.setStatusText(status.getDisplayName());
        response.setFeedbackResult(feedback.getFeedbackResult());
        response.setFeedbackResultAt(feedback.getFeedbackResultAt());
        response.setRoundCount(rounds.size());
        response.setAttachmentCount(validated.attachmentCount());
        response.setCreatedAt(feedback.getCreatedAt());
        response.setUpdatedAt(feedback.getUpdatedAt());
        response.setCanAppendRound(
                FeedbackStatusDict.WAITING_FOLLOW_UP.getCode().equals(feedback.getStatus())
                        && rounds.size() < MAX_ROUND_COUNT);
        response.setRounds(rounds.stream().map(this::buildRoundItem).toList());
        return response;
    }

    /** 构造历史问题摘要项。 */
    private MineFeedbackListResponse.Item buildListItem(FeedbackEntity feedback) {
        FeedbackRoundSnapshotValidator.ValidatedSnapshot validated =
                feedbackRoundSnapshotValidator.validate(feedback);
        List<FeedbackRoundSnapshot> rounds = validated.rounds();
        FeedbackStatusDict status = requireStatus(feedback.getStatus());
        MineFeedbackListResponse.Item item = new MineFeedbackListResponse.Item();
        item.setId(feedback.getId());
        item.setFeedbackNo(feedback.getFeedbackNo());
        item.setStatus(feedback.getStatus());
        item.setStatusText(status.getDisplayName());
        item.setDescriptionSummary(summarize(rounds.getFirst().getDescription()));
        item.setRoundCount(rounds.size());
        item.setAttachmentCount(validated.attachmentCount());
        item.setCreatedAt(feedback.getCreatedAt());
        item.setUpdatedAt(feedback.getUpdatedAt());
        return item;
    }

    /** 构造详情中的单轮时间线项。 */
    private MineFeedbackDetailResponse.RoundItem buildRoundItem(FeedbackRoundSnapshot source) {
        MineFeedbackDetailResponse.RoundItem item = new MineFeedbackDetailResponse.RoundItem();
        item.setRoundNo(source.getRoundNo());
        item.setDescription(source.getDescription());
        item.setSubmittedAt(source.getSubmittedAt());
        item.setTeamResult(source.getTeamResult());
        item.setTeamResultAt(source.getTeamResultAt());
        item.setAttachments(source.getAttachments().stream().map(this::buildAttachmentItem).toList());
        return item;
    }

    /** 构造详情中的公开附件项。 */
    private MineFeedbackDetailResponse.AttachmentItem buildAttachmentItem(
            FeedbackRoundSnapshot.Attachment source
    ) {
        MineFeedbackDetailResponse.AttachmentItem item = new MineFeedbackDetailResponse.AttachmentItem();
        item.setMediaType(source.getMediaType());
        item.setMimeType(source.getMimeType());
        item.setSize(source.getSize());
        item.setDurationMs(source.getDurationMs());
        item.setUrl(cosService.publicUrl(source.getObjectKey()));
        return item;
    }

    /** 获取有效反馈状态字典项。 */
    private FeedbackStatusDict requireStatus(String statusCode) {
        FeedbackStatusDict status = FeedbackStatusDict.fromCode(statusCode);
        if (status == null) {
            throw new BusinessException(MineFeedbackMessage.STATUS_INVALID_MESSAGE);
        }
        return status;
    }

    /** 截取列表所需的首轮描述摘要。 */
    private String summarize(String description) {
        if (description == null) {
            throw new BusinessException(MineFeedbackMessage.ROUNDS_JSON_CORRUPT_MESSAGE);
        }
        return FeedbackTextLength.truncate(description, DESCRIPTION_SUMMARY_MAX_LENGTH);
    }
}
