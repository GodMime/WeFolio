package com.jxc.wefolio.model;

import com.jxc.wefolio.dict.FeedbackMediaTypeDict;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 意见反馈单中的单轮反馈快照。
 */
@Data
public class FeedbackRoundSnapshot {

    /** 反馈轮次，从 1 开始。 */
    private Integer roundNo;

    /** 本轮提交幂等键。 */
    private String idempotencyKey;

    /** 用户提交的反馈描述。 */
    private String description;

    /** 用户提交本轮反馈的时间。 */
    private LocalDateTime submittedAt;

    /** 团队针对本轮反馈给出的处理结果。 */
    private String teamResult;

    /** 团队处理结果的更新时间。 */
    private LocalDateTime teamResultAt;

    /** 本轮反馈包含的附件快照列表。 */
    private List<Attachment> attachments;

    /**
     * 单个反馈附件的不可变业务快照数据。
     */
    @Data
    public static class Attachment {

        /** COS 对象键。 */
        private String objectKey;

        /**
         * 附件媒体类型。
         *
         * @see FeedbackMediaTypeDict
         */
        private String mediaType;

        /** 附件 MIME 类型。 */
        private String mimeType;

        /** 附件字节数。 */
        private long size;

        /** 附件媒体时长毫秒，图片为 0。 */
        private long durationMs;
    }
}
