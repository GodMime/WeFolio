package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.TypeReference;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.MineFeedbackMessage;
import com.jxc.wefolio.model.FeedbackRoundSnapshot;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 反馈轮次快照 JSON 编解码器。
 */
@Component
public class FeedbackRoundCodec {

    /** 数据库 JSON 中日期时间的固定格式。 */
    private static final String DATE_TIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS";

    /** 反馈轮次数组的 Fastjson2 类型信息。 */
    private static final TypeReference<List<FeedbackRoundSnapshot>> ROUND_LIST_TYPE = new TypeReference<>() {
    };

    /**
     * 将数据库 JSON 解析为反馈轮次列表。
     *
     * @param json 数据库保存的轮次 JSON，空值按空列表处理
     * @return 可安全用于业务处理的反馈轮次列表
     * @throws BusinessException JSON 损坏或结构无法解析时抛出
     */
    public List<FeedbackRoundSnapshot> parse(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<FeedbackRoundSnapshot> rounds = JSON.parseObject(json, ROUND_LIST_TYPE);
            return rounds == null ? new ArrayList<>() : new ArrayList<>(rounds);
        } catch (JSONException | IllegalArgumentException exception) {
            throw new BusinessException(MineFeedbackMessage.ROUNDS_JSON_CORRUPT_MESSAGE, exception);
        }
    }

    /**
     * 将反馈轮次列表序列化为数据库 JSON。
     *
     * @param rounds 反馈轮次列表，空值按空列表处理
     * @return 使用固定毫秒时间格式的 JSON
     * @throws BusinessException 轮次无法序列化时抛出
     */
    public String serialize(List<FeedbackRoundSnapshot> rounds) {
        try {
            return JSON.toJSONString(
                    rounds == null ? List.of() : rounds,
                    DATE_TIME_FORMAT,
                    JSONWriter.Feature.WriteNulls);
        } catch (JSONException | IllegalArgumentException exception) {
            throw new BusinessException(MineFeedbackMessage.ROUNDS_JSON_CORRUPT_MESSAGE, exception);
        }
    }

    /**
     * 复制一轮用户提交快照并归档团队处理结果。
     *
     * <p>用户描述、提交时间和附件均复制到新对象，避免修改历史轮次原对象。</p>
     *
     * @param source 已提交的原始反馈轮次
     * @param teamResult 团队处理结果
     * @param teamResultAt 团队处理结果时间
     * @return 只更新团队结果字段的新轮次快照
     */
    public FeedbackRoundSnapshot copyWithTeamResult(
            FeedbackRoundSnapshot source,
            String teamResult,
            LocalDateTime teamResultAt
    ) {
        if (source == null) {
            throw new BusinessException(MineFeedbackMessage.ROUNDS_JSON_CORRUPT_MESSAGE);
        }
        FeedbackRoundSnapshot copy = new FeedbackRoundSnapshot();
        copy.setRoundNo(source.getRoundNo());
        copy.setIdempotencyKey(source.getIdempotencyKey());
        copy.setDescription(source.getDescription());
        copy.setSubmittedAt(source.getSubmittedAt());
        copy.setTeamResult(teamResult);
        copy.setTeamResultAt(teamResultAt);
        copy.setAttachments(copyAttachments(source.getAttachments()));
        return copy;
    }

    /** 复制附件列表，避免后续修改污染历史轮次。 */
    private List<FeedbackRoundSnapshot.Attachment> copyAttachments(
            List<FeedbackRoundSnapshot.Attachment> attachments
    ) {
        if (attachments == null) {
            return new ArrayList<>();
        }
        List<FeedbackRoundSnapshot.Attachment> copies = new ArrayList<>(attachments.size());
        for (FeedbackRoundSnapshot.Attachment attachment : attachments) {
            if (attachment == null) {
                copies.add(null);
                continue;
            }
            FeedbackRoundSnapshot.Attachment copy = new FeedbackRoundSnapshot.Attachment();
            copy.setObjectKey(attachment.getObjectKey());
            copy.setMediaType(attachment.getMediaType());
            copy.setMimeType(attachment.getMimeType());
            copy.setSize(attachment.getSize());
            copy.setDurationMs(attachment.getDurationMs());
            copies.add(copy);
        }
        return copies;
    }
}
