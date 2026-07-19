package com.jxc.wefolio.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * wf_point_pending_debit — 积分消费待扣来源明细 — 每笔消费流水对应一条可核销来源。
 */
@Data
@TableName("wf_point_pending_debit")
public class PointPendingDebitEntity extends BaseEntity {

    /** 积分账户 ID */
    private Long accountId;

    /** 被扣费维护者用户 ID */
    private Long userId;

    /** 唯一关联消费流水 ID */
    private Long pointTransactionId;

    /** 原始待扣金额 */
    private Long originalAmount;

    /** 当前剩余待扣金额 */
    private Long remainingAmount;

    /** 最近核销时间 */
    private LocalDateTime lastSettledAt;
}
