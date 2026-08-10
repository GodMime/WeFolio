package com.jxc.wefolio.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jxc.wefolio.dict.PointGiftOrderStatusDict;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * wf_point_gift_order — 微信代币赠送订单 — 来源业务提交后独立处理的幂等赠送任务。
 */
@Data
@TableName("wf_point_gift_order")
public class PointGiftOrderEntity extends BaseEntity {

    /** 稳定微信赠送订单号 */
    private String orderNo;

    /** 积分账户 ID */
    private Long accountId;

    /** 受赠用户 ID */
    private Long userId;

    /** 五类赠送场景编码 */
    private String sceneCode;

    /** 赠送积分 */
    private Long amount;

    /** 来源业务类型 */
    private String businessType;

    /** 来源业务 ID */
    private String businessId;

    /** 不可变来源快照 JSON */
    private String businessSnapshot;

    /** 业务幂等键 */
    private String idempotencyKey;

    /** 赠送订单状态，取值见 {@link PointGiftOrderStatusDict} */
    private String status;

    /** 自动重试次数 */
    private Integer retryCount;

    /** 下次执行时间 */
    private LocalDateTime nextExecuteAt;

    /** 领取实例 */
    private String leaseOwner;

    /** 当前执行租约令牌，每次成功领取订单时重新生成 */
    private String executionLeaseToken;

    /** 租约截止时间 */
    private LocalDateTime leaseUntil;

    /** 成功后的赠送积分流水 ID */
    private Long pointTransactionId;

    /** 微信返回总代币余额 */
    private Long wechatBalanceAfter;

    /** 微信返回赠送代币余额 */
    private Long wechatPresentBalanceAfter;

    /** 最后失败码 */
    private String lastErrorCode;

    /** 最后脱敏失败原因 */
    private String lastErrorMessage;

    /** 最后失败时间 */
    private LocalDateTime lastFailedAt;

    /** 完成时间 */
    private LocalDateTime completedAt;
}
