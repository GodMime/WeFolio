package com.jxc.wefolio.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/**
 * wf_point_transaction — 积分流水表 — 不可变账本，充值、消耗、回退、赠送记录
 */
@Data
@TableName("wf_point_transaction")
public class PointTransactionEntity extends BaseEntity {

    /** 积分账户 ID */
    private Long accountId;

    /** 冗余用户 ID，方便用户侧查询 */
    private Long userId;

    /** 本次采用的积分规则 ID，充值或人工调整可空 */
    private Long ruleId;

    /** 流水类型：RECHARGE / CONSUMPTION / REFUND / GIFT */
    private String transactionType;

    /** 可扩展业务场景编码 */
    private String sceneCode;

    /** 本次变动值，增加为正消耗为负，不得为 0 */
    private Long pointsChange;

    /** 变动前余额 */
    private Long balanceBefore;

    /** 变动后余额 */
    private Long balanceAfter;

    /** 关联业务类型 */
    private String businessType;

    /** 关联业务 ID，允许数值或外部订单号 */
    private String businessId;

    /** 规则编码、版本、模式、单位数、积分值和计算输入快照 JSON */
    private String calculationSnapshot;

    /** 业务幂等键，防止重复扣分或入账 */
    private String idempotencyKey;

    /** 脱敏备注 */
    private String remark;

    /** 业务发生时间 */
    private LocalDateTime occurredAt;

}
