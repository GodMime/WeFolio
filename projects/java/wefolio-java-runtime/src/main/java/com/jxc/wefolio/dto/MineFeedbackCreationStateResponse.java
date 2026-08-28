package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 当前用户创建问题反馈的额度状态。
 */
@Data
public class MineFeedbackCreationStateResponse {

    /** 当前未处理完成的问题数量。 */
    private int activeCount;

    /** 同一用户允许的最大活跃问题数量。 */
    private int maxActiveCount;

    /** 当前是否允许创建新问题。 */
    private boolean canCreate;

    /** 前端直接展示的额度提示。 */
    private String hintText;
}
