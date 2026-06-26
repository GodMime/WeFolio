package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 积分变动响应 — 统一表示加分、扣分或累计未达扣费阈值的结果。
 */
@Data
public class PointMutationResponse {

    /** 积分流水 ID，未产生流水时为空 */
    private Long transactionId;

    /** 积分账户 ID */
    private Long accountId;

    /** 用户 ID */
    private Long userId;

    /** 积分场景编码 */
    private String sceneCode;

    /** 积分场景文案 */
    private String sceneText;

    /** 本次积分变动，扣分为负、加分为正，未扣费为 0 */
    private Long pointsChange;

    /** 变动前余额 */
    private Long balanceBefore;

    /** 变动后余额 */
    private Long balanceAfter;

    /** 是否命中幂等流水 */
    private boolean idempotent;

    /** 是否实际产生积分变动 */
    private boolean charged;

    /** 本次折算出的计费单位数 */
    private Long billedUnits;

    /** 响应说明 */
    private String message;
}
