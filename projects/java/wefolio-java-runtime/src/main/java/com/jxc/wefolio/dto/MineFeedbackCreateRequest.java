package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 创建问题或追加反馈轮次请求。
 */
@Data
public class MineFeedbackCreateRequest {

    /** 当前提交的幂等键。 */
    private String idempotencyKey;

    /** 当前轮反馈描述。 */
    private String description;

    /** 用户提交本轮反馈时的前端版本，旧客户端未上报时为空。 */
    private String frontendVersion;

    /** 已完成直传的反馈附件任务 ID。 */
    private List<Long> uploadTaskIds = new ArrayList<>();
}
