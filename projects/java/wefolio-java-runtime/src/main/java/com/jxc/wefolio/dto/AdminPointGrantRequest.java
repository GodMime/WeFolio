package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 后台人工加分请求。
 */
@Data
public class AdminPointGrantRequest {

    /** 被加分用户唯一码 */
    private String uniqueCode;

    /** 增加积分，必须为正数 */
    private Long points;

    /** 幂等键，同一后台操作必须固定，防止重复加分 */
    private String idempotencyKey;

    /** 脱敏备注，用于积分流水展示 */
    private String remark;
}
