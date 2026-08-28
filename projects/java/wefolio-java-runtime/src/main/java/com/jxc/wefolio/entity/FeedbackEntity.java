package com.jxc.wefolio.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jxc.wefolio.dict.FeedbackStatusDict;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * wf_feedback — 意见反馈单表。
 */
@Data
@TableName("wf_feedback")
public class FeedbackEntity extends BaseEntity {

    /** 反馈单号。 */
    private String feedbackNo;

    /** 提交用户 ID。 */
    private Long userId;

    /**
     * 反馈处理状态。
     *
     * @see FeedbackStatusDict
     */
    private String status;

    /** 团队处理结果。 */
    private String feedbackResult;

    /** 团队处理结果更新时间。 */
    private LocalDateTime feedbackResultAt;

    /** 反馈轮次 JSON 快照。 */
    private String roundsJson;

    /** 反馈轮次数量。 */
    private Integer roundCount;

    /** 全部轮次附件数量。 */
    private Integer attachmentCount;

    /** 创建反馈幂等键。 */
    private String createIdempotencyKey;
}
