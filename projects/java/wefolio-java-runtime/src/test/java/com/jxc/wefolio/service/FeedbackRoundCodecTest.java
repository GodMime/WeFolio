package com.jxc.wefolio.service;

import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.MineFeedbackMessage;
import com.jxc.wefolio.model.FeedbackRoundSnapshot;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 反馈轮次 JSON 编解码测试。
 */
class FeedbackRoundCodecTest {

    /** 待测试的反馈轮次编解码器。 */
    private final FeedbackRoundCodec codec = new FeedbackRoundCodec();

    /** 空文本和空值应统一解析为空轮次列表。 */
    @Test
    void blankJsonShouldParseAsEmptyRounds() {
        assertThat(codec.parse(null)).isEmpty();
        assertThat(codec.parse("")).isEmpty();
        assertThat(codec.parse("   ")).isEmpty();
    }

    /** 完整轮次应在序列化与反序列化后保持全部业务字段。 */
    @Test
    void completeRoundsShouldRoundTrip() {
        FeedbackRoundSnapshot firstRound = completeRound();

        List<FeedbackRoundSnapshot> parsed = codec.parse(codec.serialize(List.of(firstRound)));

        assertThat(parsed).usingRecursiveFieldByFieldElementComparator()
                .containsExactly(firstRound);
    }

    /** 新版本 JSON 的未知字段不应阻断旧服务读取既有业务字段。 */
    @Test
    void unknownJsonFieldsShouldBeIgnored() {
        String json = """
                [{
                  "roundNo": 1,
                  "idempotencyKey": "round-1",
                  "description": "预览页无法打开",
                  "submittedAt": "2026-08-25T10:42:00.123",
                  "attachments": [{
                    "objectKey": "WFA3B1E7A2/others/0123456789abcdef0123456789abcdef.jpg",
                    "mediaType": "IMAGE",
                    "mimeType": "image/jpeg",
                    "size": 2048,
                    "durationMs": 0,
                    "futureAttachmentField": true
                  }],
                  "futureRoundField": "ignored"
                }]
                """;

        List<FeedbackRoundSnapshot> parsed = codec.parse(json);

        assertThat(parsed).hasSize(1);
        assertThat(parsed.getFirst().getDescription()).isEqualTo("预览页无法打开");
        assertThat(parsed.getFirst().getSubmittedAt())
                .isEqualTo(LocalDateTime.of(2026, 8, 25, 10, 42, 0, 123_000_000));
        assertThat(parsed.getFirst().getAttachments()).singleElement()
                .satisfies(attachment -> assertThat(attachment.getSize()).isEqualTo(2048L));
    }

    /** 损坏 JSON 应转为稳定的反馈业务异常，不暴露底层解析细节。 */
    @Test
    void invalidJsonShouldThrowFeedbackBusinessException() {
        assertThatThrownBy(() -> codec.parse("[{broken-json]"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.ROUNDS_JSON_CORRUPT_MESSAGE);
    }

    /** 归档团队结果时只允许更新团队字段，用户提交快照必须保持不变。 */
    @Test
    void copyWithTeamResultShouldPreserveSubmittedSnapshot() {
        FeedbackRoundSnapshot source = completeRound();
        LocalDateTime resultAt = LocalDateTime.of(2026, 8, 26, 9, 30, 1, 456_000_000);

        FeedbackRoundSnapshot copied = codec.copyWithTeamResult(source, "请补充报错视频", resultAt);

        assertThat(copied).isNotSameAs(source);
        assertThat(copied.getRoundNo()).isEqualTo(source.getRoundNo());
        assertThat(copied.getIdempotencyKey()).isEqualTo(source.getIdempotencyKey());
        assertThat(copied.getDescription()).isEqualTo(source.getDescription());
        assertThat(copied.getSubmittedAt()).isEqualTo(source.getSubmittedAt());
        assertThat(copied.getAttachments()).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(source.getAttachments());
        assertThat(copied.getAttachments()).isNotSameAs(source.getAttachments());
        assertThat(copied.getTeamResult()).isEqualTo("请补充报错视频");
        assertThat(copied.getTeamResultAt()).isEqualTo(resultAt);
        assertThat(source.getTeamResult()).isEqualTo("处理中");
        assertThat(source.getTeamResultAt())
                .isEqualTo(LocalDateTime.of(2026, 8, 25, 12, 0, 0));
    }

    /** 序列化时间必须固定输出三位毫秒，确保数据库快照与前端契约稳定。 */
    @Test
    void serializationShouldUseThreeDigitMilliseconds() {
        String json = codec.serialize(List.of(completeRound()));

        assertThat(json)
                .contains("\"submittedAt\":\"2026-08-25T10:42:00.123\"")
                .contains("\"teamResultAt\":\"2026-08-25T12:00:00.000\"");
    }

    /** 构造包含全部字段的反馈轮次。 */
    private FeedbackRoundSnapshot completeRound() {
        FeedbackRoundSnapshot.Attachment attachment = new FeedbackRoundSnapshot.Attachment();
        attachment.setObjectKey("WFA3B1E7A2/others/0123456789abcdef0123456789abcdef.mp4");
        attachment.setMediaType("VIDEO");
        attachment.setMimeType("video/mp4");
        attachment.setSize(10_485_760L);
        attachment.setDurationMs(32_000L);

        FeedbackRoundSnapshot round = new FeedbackRoundSnapshot();
        round.setRoundNo(1);
        round.setIdempotencyKey("round-1");
        round.setDescription("视频上传后无法预览");
        round.setSubmittedAt(LocalDateTime.of(2026, 8, 25, 10, 42, 0, 123_000_000));
        round.setTeamResult("处理中");
        round.setTeamResultAt(LocalDateTime.of(2026, 8, 25, 12, 0, 0));
        round.setAttachments(List.of(attachment));
        return round;
    }
}
