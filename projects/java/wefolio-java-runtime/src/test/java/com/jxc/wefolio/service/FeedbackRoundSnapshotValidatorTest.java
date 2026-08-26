package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.FeedbackMediaTypeDict;
import com.jxc.wefolio.dict.FeedbackStatusDict;
import com.jxc.wefolio.entity.FeedbackEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.MineFeedbackMessage;
import com.jxc.wefolio.model.FeedbackRoundSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 反馈轮次快照结构校验器测试。
 */
class FeedbackRoundSnapshotValidatorTest {

    /** 由两个 UTF-16 代码单元组成的单个 Unicode 字符。 */
    private static final String EMOJI = "\uD83D\uDE00";

    /** 轮次 JSON 编解码器。 */
    private FeedbackRoundCodec codec;

    /** 被测快照校验器。 */
    private FeedbackRoundSnapshotValidator validator;

    /** 初始化被测对象。 */
    @BeforeEach
    void setUp() {
        codec = new FeedbackRoundCodec();
        validator = new FeedbackRoundSnapshotValidator(codec, new FeedbackUploadFileValidator());
    }

    /** 合法实体应返回已解析轮次和实际附件总数。 */
    @Test
    void validEntityShouldReturnValidatedSnapshot() {
        FeedbackRoundSnapshot first = round(1, "round-1", "问题描述");
        first.setAttachments(List.of(imageAttachment()));
        first.setTeamResult("请补充录屏");
        first.setTeamResultAt(LocalDateTime.of(2026, 8, 25, 11, 0));
        FeedbackRoundSnapshot second = round(2, "round-2", "补充描述");
        second.setAttachments(List.of(videoAttachment()));
        FeedbackEntity feedback = feedback(List.of(first, second));
        feedback.setFeedbackResult("当前团队反馈");
        feedback.setFeedbackResultAt(LocalDateTime.of(2026, 8, 25, 12, 0));

        FeedbackRoundSnapshotValidator.ValidatedSnapshot validated = validator.validate(feedback);

        assertThat(validated.rounds()).hasSize(2);
        assertThat(validated.attachmentCount()).isEqualTo(2);
    }

    /** 轮次数组必须有一至三轮且与冗余轮次数一致。 */
    @Test
    void roundCountAndSequenceShouldBeConsistent() {
        FeedbackEntity empty = feedback(List.of());
        assertCorrupt(empty);

        FeedbackEntity mismatchedCount = feedback(List.of(round(1, "round-1", "问题")));
        mismatchedCount.setRoundCount(2);
        assertCorrupt(mismatchedCount);

        FeedbackRoundSnapshot wrongSequence = round(2, "round-1", "问题");
        assertCorrupt(feedback(List.of(wrongSequence)));

        assertCorrupt(feedback(List.of(
                round(1, "round-1", "一"),
                round(2, "round-2", "二"),
                round(3, "round-3", "三"),
                round(4, "round-4", "四"))));
    }

    /** 每轮幂等键必须是 1 至 64 位可打印非空 ASCII。 */
    @Test
    void roundIdempotencyKeyShouldBePrintableAscii() {
        assertCorrupt(feedback(List.of(round(1, "", "问题"))));
        assertCorrupt(feedback(List.of(round(1, "中文-key", "问题"))));
        assertCorrupt(feedback(List.of(round(1, "key\nbreak", "问题"))));
        assertCorrupt(feedback(List.of(round(1, "k".repeat(65), "问题"))));
    }

    /** 每轮描述裁剪后必须为 1 至 200 个字符。 */
    @Test
    void roundDescriptionShouldRespectLengthBoundary() {
        assertCorrupt(feedback(List.of(round(1, "round-1", " \n "))));
        assertCorrupt(feedback(List.of(round(1, "round-1", "\u00A0\u2007\u202F"))));
        assertCorrupt(feedback(List.of(round(1, "round-1", "字".repeat(201)))));
    }

    /** 描述和团队结果长度必须按 Unicode 代码点计算。 */
    @Test
    void feedbackTextShouldCountUnicodeCodePoints() {
        validator.validate(feedback(List.of(round(1, "round-1", EMOJI.repeat(200)))));
        assertCorrupt(feedback(List.of(round(1, "round-1", EMOJI.repeat(201)))));

        FeedbackRoundSnapshot acceptedTeamResult = round(1, "round-1", "问题");
        acceptedTeamResult.setTeamResult(EMOJI.repeat(200));
        acceptedTeamResult.setTeamResultAt(LocalDateTime.of(2026, 8, 25, 11, 0));
        validator.validate(feedback(List.of(acceptedTeamResult)));

        FeedbackRoundSnapshot rejectedTeamResult = round(1, "round-1", "问题");
        rejectedTeamResult.setTeamResult(EMOJI.repeat(201));
        rejectedTeamResult.setTeamResultAt(LocalDateTime.of(2026, 8, 25, 11, 0));
        assertCorrupt(feedback(List.of(rejectedTeamResult)));

        FeedbackEntity acceptedCurrentResult = feedback(List.of(round(1, "round-1", "问题")));
        acceptedCurrentResult.setFeedbackResult(EMOJI.repeat(200));
        acceptedCurrentResult.setFeedbackResultAt(LocalDateTime.of(2026, 8, 25, 12, 0));
        validator.validate(acceptedCurrentResult);

        FeedbackEntity rejectedCurrentResult = feedback(List.of(round(1, "round-1", "问题")));
        rejectedCurrentResult.setFeedbackResult(EMOJI.repeat(201));
        rejectedCurrentResult.setFeedbackResultAt(LocalDateTime.of(2026, 8, 25, 12, 0));
        assertCorrupt(rejectedCurrentResult);
    }

    /** 每轮提交时间和附件数组都必须存在，且单轮最多三个附件。 */
    @Test
    void submittedAtAndAttachmentListShouldBeStructurallyValid() {
        FeedbackRoundSnapshot missingTime = round(1, "round-1", "问题");
        missingTime.setSubmittedAt(null);
        assertCorrupt(feedback(List.of(missingTime)));

        FeedbackRoundSnapshot missingAttachments = round(1, "round-1", "问题");
        missingAttachments.setAttachments(null);
        FeedbackEntity missingAttachmentsFeedback = feedback(List.of(round(1, "temporary", "问题")));
        missingAttachmentsFeedback.setRoundsJson(codec.serialize(List.of(missingAttachments)));
        assertCorrupt(missingAttachmentsFeedback);

        FeedbackRoundSnapshot tooMany = round(1, "round-1", "问题");
        tooMany.setAttachments(List.of(
                imageAttachment(), imageAttachment(), imageAttachment(), imageAttachment()));
        assertCorrupt(feedback(List.of(tooMany)));
    }

    /** 附件对象、对象键、媒体类型与 MIME 必须完整且匹配。 */
    @Test
    void attachmentIdentityAndMediaMetadataShouldBeValid() {
        assertCorrupt(feedbackWithAttachment(null));

        FeedbackRoundSnapshot.Attachment blankKey = imageAttachment();
        blankKey.setObjectKey(" ");
        assertCorrupt(feedbackWithAttachment(blankKey));

        FeedbackRoundSnapshot.Attachment invalidMedia = imageAttachment();
        invalidMedia.setMediaType("AUDIO");
        assertCorrupt(feedbackWithAttachment(invalidMedia));

        FeedbackRoundSnapshot.Attachment mismatchedMime = imageAttachment();
        mismatchedMime.setMimeType("video/mp4");
        assertCorrupt(feedbackWithAttachment(mismatchedMime));
    }

    /** 附件大小与视频时长必须符合对应媒体限制。 */
    @Test
    void attachmentSizeAndDurationShouldRespectMediaLimits() {
        FeedbackRoundSnapshot.Attachment emptyImage = imageAttachment();
        emptyImage.setSize(0L);
        assertCorrupt(feedbackWithAttachment(emptyImage));

        FeedbackRoundSnapshot.Attachment oversizedImage = imageAttachment();
        oversizedImage.setSize(10L * 1024 * 1024 + 1L);
        assertCorrupt(feedbackWithAttachment(oversizedImage));

        FeedbackRoundSnapshot.Attachment imageWithDuration = imageAttachment();
        imageWithDuration.setDurationMs(1L);
        assertCorrupt(feedbackWithAttachment(imageWithDuration));

        FeedbackRoundSnapshot.Attachment invalidVideoDuration = videoAttachment();
        invalidVideoDuration.setDurationMs(600_001L);
        assertCorrupt(feedbackWithAttachment(invalidVideoDuration));
    }

    /** 实际附件总数必须等于非空且非负的冗余计数。 */
    @Test
    void attachmentCountShouldMatchActualTotal() {
        FeedbackEntity missing = feedbackWithAttachment(imageAttachment());
        missing.setAttachmentCount(null);
        assertCorrupt(missing);

        FeedbackEntity negative = feedbackWithAttachment(imageAttachment());
        negative.setAttachmentCount(-1);
        assertCorrupt(negative);

        FeedbackEntity mismatched = feedbackWithAttachment(imageAttachment());
        mismatched.setAttachmentCount(0);
        assertCorrupt(mismatched);
    }

    /** 历史与当前团队结果必须和各自时间字段成对出现且长度合法。 */
    @Test
    void teamResultAndTimestampShouldRemainConsistent() {
        FeedbackRoundSnapshot missingRoundTime = round(1, "round-1", "问题");
        missingRoundTime.setTeamResult("请补充");
        assertCorrupt(feedback(List.of(missingRoundTime)));

        FeedbackRoundSnapshot missingRoundResult = round(1, "round-1", "问题");
        missingRoundResult.setTeamResultAt(LocalDateTime.of(2026, 8, 25, 11, 0));
        assertCorrupt(feedback(List.of(missingRoundResult)));

        FeedbackEntity missingCurrentTime = feedback(List.of(round(1, "round-1", "问题")));
        missingCurrentTime.setFeedbackResult("当前结果");
        assertCorrupt(missingCurrentTime);

        FeedbackEntity oversizedCurrentResult = feedback(List.of(round(1, "round-1", "问题")));
        oversizedCurrentResult.setFeedbackResult("字".repeat(201));
        oversizedCurrentResult.setFeedbackResultAt(LocalDateTime.of(2026, 8, 25, 12, 0));
        assertCorrupt(oversizedCurrentResult);
    }

    /** 构造包含单个附件的反馈实体。 */
    private FeedbackEntity feedbackWithAttachment(FeedbackRoundSnapshot.Attachment attachment) {
        FeedbackRoundSnapshot round = round(1, "round-1", "问题");
        List<FeedbackRoundSnapshot.Attachment> attachments = new ArrayList<>();
        attachments.add(attachment);
        round.setAttachments(attachments);
        return feedback(List.of(round));
    }

    /** 构造反馈实体并同步冗余计数。 */
    private FeedbackEntity feedback(List<FeedbackRoundSnapshot> rounds) {
        FeedbackEntity feedback = new FeedbackEntity();
        feedback.setStatus(FeedbackStatusDict.PROCESSING.getCode());
        feedback.setRoundsJson(codec.serialize(rounds));
        feedback.setRoundCount(rounds.size());
        feedback.setAttachmentCount(rounds.stream()
                .map(FeedbackRoundSnapshot::getAttachments)
                .filter(items -> items != null)
                .mapToInt(List::size)
                .sum());
        return feedback;
    }

    /** 构造单轮快照。 */
    private FeedbackRoundSnapshot round(int roundNo, String idempotencyKey, String description) {
        FeedbackRoundSnapshot round = new FeedbackRoundSnapshot();
        round.setRoundNo(roundNo);
        round.setIdempotencyKey(idempotencyKey);
        round.setDescription(description);
        round.setSubmittedAt(LocalDateTime.of(2026, 8, 25, 10, roundNo));
        round.setAttachments(new ArrayList<>());
        return round;
    }

    /** 构造合法图片附件。 */
    private FeedbackRoundSnapshot.Attachment imageAttachment() {
        FeedbackRoundSnapshot.Attachment attachment = new FeedbackRoundSnapshot.Attachment();
        attachment.setObjectKey("WFA3B1E7A2/others/photo.jpg");
        attachment.setMediaType(FeedbackMediaTypeDict.IMAGE.getCode());
        attachment.setMimeType("image/jpeg");
        attachment.setSize(1024L);
        attachment.setDurationMs(0L);
        return attachment;
    }

    /** 构造合法视频附件。 */
    private FeedbackRoundSnapshot.Attachment videoAttachment() {
        FeedbackRoundSnapshot.Attachment attachment = new FeedbackRoundSnapshot.Attachment();
        attachment.setObjectKey("WFA3B1E7A2/others/video.mp4");
        attachment.setMediaType(FeedbackMediaTypeDict.VIDEO.getCode());
        attachment.setMimeType("video/mp4");
        attachment.setSize(2048L);
        attachment.setDurationMs(32_000L);
        return attachment;
    }

    /** 断言实体按统一损坏数据文案拒绝。 */
    private void assertCorrupt(FeedbackEntity feedback) {
        assertThatThrownBy(() -> validator.validate(feedback))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.ROUNDS_JSON_CORRUPT_MESSAGE);
    }
}
