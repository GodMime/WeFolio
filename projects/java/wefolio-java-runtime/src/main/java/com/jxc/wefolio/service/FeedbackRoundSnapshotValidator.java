package com.jxc.wefolio.service;

import com.jxc.wefolio.entity.FeedbackEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.MineFeedbackMessage;
import com.jxc.wefolio.model.FeedbackRoundSnapshot;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 反馈实体轮次 JSON 与冗余字段的统一结构校验器。
 */
@Component
public class FeedbackRoundSnapshotValidator {

    /** 最少反馈轮数。 */
    private static final int MIN_ROUND_COUNT = 1;

    /** 最大反馈轮数。 */
    private static final int MAX_ROUND_COUNT = 3;

    /** 单轮最大附件数。 */
    private static final int MAX_ATTACHMENT_COUNT = 3;

    /** 用户描述与团队结果最大 Unicode 代码点数量。 */
    private static final int MAX_TEXT_CODE_POINT_COUNT = 200;

    /** 对象键最大字符数。 */
    private static final int MAX_OBJECT_KEY_LENGTH = 512;

    /** 轮次 JSON 编解码器。 */
    private final FeedbackRoundCodec feedbackRoundCodec;

    /** 反馈附件媒体规则校验器。 */
    private final FeedbackUploadFileValidator feedbackUploadFileValidator;

    /**
     * 创建统一轮次快照校验器。
     *
     * @param feedbackRoundCodec 轮次 JSON 编解码器
     * @param feedbackUploadFileValidator 反馈附件媒体规则校验器
     */
    public FeedbackRoundSnapshotValidator(
            FeedbackRoundCodec feedbackRoundCodec,
            FeedbackUploadFileValidator feedbackUploadFileValidator
    ) {
        this.feedbackRoundCodec = feedbackRoundCodec;
        this.feedbackUploadFileValidator = feedbackUploadFileValidator;
    }

    /**
     * 解析并校验反馈实体中的完整轮次快照。
     *
     * @param feedback 待校验反馈实体
     * @return 已验证的轮次列表和实际附件总数
     */
    public ValidatedSnapshot validate(FeedbackEntity feedback) {
        if (feedback == null) {
            throw corrupt();
        }
        List<FeedbackRoundSnapshot> rounds = feedbackRoundCodec.parse(feedback.getRoundsJson());
        if (rounds.size() < MIN_ROUND_COUNT
                || rounds.size() > MAX_ROUND_COUNT
                || feedback.getRoundCount() == null
                || feedback.getRoundCount() != rounds.size()) {
            throw corrupt();
        }

        int attachmentCount = 0;
        for (int index = 0; index < rounds.size(); index++) {
            FeedbackRoundSnapshot round = rounds.get(index);
            validateRound(round, index + 1);
            attachmentCount += round.getAttachments().size();
        }
        if (feedback.getAttachmentCount() == null
                || feedback.getAttachmentCount() < 0
                || feedback.getAttachmentCount() != attachmentCount) {
            throw corrupt();
        }
        validateTimedText(feedback.getFeedbackResult(), feedback.getFeedbackResultAt());
        return new ValidatedSnapshot(rounds, attachmentCount);
    }

    /** 校验单轮序号、用户内容、时间和附件结构。 */
    private void validateRound(FeedbackRoundSnapshot round, int expectedRoundNo) {
        if (round == null
                || round.getRoundNo() == null
                || round.getRoundNo() != expectedRoundNo
                || !isValidText(round.getDescription())
                || round.getSubmittedAt() == null
                || round.getAttachments() == null
                || round.getAttachments().size() > MAX_ATTACHMENT_COUNT) {
            throw corrupt();
        }
        FeedbackIdentifierValidator.normalizePrintableAscii(
                round.getIdempotencyKey(), MineFeedbackMessage.ROUNDS_JSON_CORRUPT_MESSAGE);
        validateTimedText(round.getTeamResult(), round.getTeamResultAt());
        for (FeedbackRoundSnapshot.Attachment attachment : round.getAttachments()) {
            validateAttachment(attachment);
        }
    }

    /** 校验附件对象键和媒体元数据。 */
    private void validateAttachment(FeedbackRoundSnapshot.Attachment attachment) {
        if (attachment == null
                || !isPrintableAsciiObjectKey(attachment.getObjectKey())
                || !feedbackUploadFileValidator.isValidSnapshotAttachment(attachment)) {
            throw corrupt();
        }
    }

    /** 校验团队结果与时间同时为空或同时有效。 */
    private void validateTimedText(String text, LocalDateTime timestamp) {
        if (text == null && timestamp == null) {
            return;
        }
        if (!isValidText(text) || timestamp == null) {
            throw corrupt();
        }
    }

    /** 判断文本裁剪后是否为 1 至 200 个 Unicode 代码点。 */
    private boolean isValidText(String text) {
        if (text == null) {
            return false;
        }
        String normalized = FeedbackTextLength.trim(text);
        return !normalized.isEmpty()
                && !FeedbackTextLength.exceeds(normalized, MAX_TEXT_CODE_POINT_COUNT);
    }

    /** 判断对象键是否为长度合法的可打印非空 ASCII。 */
    private boolean isPrintableAsciiObjectKey(String objectKey) {
        if (objectKey == null
                || objectKey.isEmpty()
                || objectKey.length() > MAX_OBJECT_KEY_LENGTH) {
            return false;
        }
        for (int index = 0; index < objectKey.length(); index++) {
            char character = objectKey.charAt(index);
            if (character < 0x21 || character > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /** 构造统一的反馈快照损坏异常。 */
    private BusinessException corrupt() {
        return new BusinessException(MineFeedbackMessage.ROUNDS_JSON_CORRUPT_MESSAGE);
    }

    /**
     * 已通过统一结构校验的轮次快照。
     *
     * @param rounds 按轮次正序排列的快照
     * @param attachmentCount 实际附件总数
     */
    public record ValidatedSnapshot(
            List<FeedbackRoundSnapshot> rounds,
            int attachmentCount
    ) {
    }
}
