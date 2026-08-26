package com.jxc.wefolio.dto;

import com.jxc.wefolio.dict.FeedbackMediaTypeDict;
import com.jxc.wefolio.dict.FeedbackStatusDict;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 当前用户的问题反馈详情。
 */
@Data
public class MineFeedbackDetailResponse {

    /** 反馈主键。 */
    private Long id;

    /** 对外反馈编号。 */
    private String feedbackNo;

    /**
     * 当前处理状态。
     *
     * @see FeedbackStatusDict
     */
    private String status;

    /** 当前处理状态文案。 */
    private String statusText;

    /** 当前尚未归档的团队反馈或最终结果。 */
    private String feedbackResult;

    /** 当前团队反馈或最终结果更新时间。 */
    private LocalDateTime feedbackResultAt;

    /** 已提交轮次数量。 */
    private int roundCount;

    /** 已提交附件总数。 */
    private int attachmentCount;

    /** 问题创建时间。 */
    private LocalDateTime createdAt;

    /** 问题最近更新时间。 */
    private LocalDateTime updatedAt;

    /** 当前是否可以追加下一轮反馈。 */
    private boolean canAppendRound;

    /** 按轮次正序排列的反馈时间线。 */
    private List<RoundItem> rounds = new ArrayList<>();

    /**
     * 一轮反馈详情。
     */
    @Data
    public static class RoundItem {

        /** 轮次序号。 */
        private int roundNo;

        /** 用户反馈描述。 */
        private String description;

        /** 本轮提交时间。 */
        private LocalDateTime submittedAt;

        /** 已归档到本轮的团队反馈。 */
        private String teamResult;

        /** 已归档团队反馈的时间。 */
        private LocalDateTime teamResultAt;

        /** 本轮附件。 */
        private List<AttachmentItem> attachments = new ArrayList<>();
    }

    /**
     * 反馈附件详情。
     */
    @Data
    public static class AttachmentItem {

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

        /** 视频时长毫秒，图片为零。 */
        private long durationMs;

        /** 附件公开访问地址。 */
        private String url;
    }
}
