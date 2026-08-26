package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 问题反馈状态更新请求。
 */
@Data
public class FeedbackStatusUpdateRequest {

    /** 目标反馈状态。 */
    private String status;

    /** 本次团队反馈结果。 */
    private String feedbackResult;
}
