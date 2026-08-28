package com.jxc.wefolio.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 问题反馈状态更新最小响应，不包含用户身份和轮次原始 JSON。 */
@Data
public class FeedbackStatusUpdateResponse {

    /** 对外问题反馈编号。 */
    private String feedbackNo;

    /** 更新后的反馈状态。 */
    private String status;

    /** 当前团队补充要求或最终反馈结果。 */
    private String feedbackResult;

    /** 当前团队结果更新时间。 */
    private LocalDateTime feedbackResultAt;

    /** 本次请求是否实际修改数据。 */
    private boolean changed;
}
