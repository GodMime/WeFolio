package com.jxc.wefolio.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jxc.wefolio.dict.PointDebitTaskStatusDict;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * wf_point_debit_task — 微信代币扣币历史任务 — 同一逻辑任务复用稳定微信订单号重试。
 */
@Data
@TableName("wf_point_debit_task")
public class PointDebitTaskEntity extends BaseEntity {

    /** 本地任务号，同时作为微信扣币订单号 */
    private String taskNo;

    /** 积分账户 ID */
    private Long accountId;

    /** 被扣费维护者用户 ID */
    private Long userId;

    /** 任务状态，取值见 {@link PointDebitTaskStatusDict} */
    private String status;

    /** 当前阻塞后续任务时为 1，历史任务为空 */
    private Integer activeFlag;

    /** 已持久化的微信请求金额 */
    private Long requestAmount;

    /** 本任务成功核销金额 */
    private Long settledAmount;

    /** 微信返回的赠送余额消耗 */
    private Long usedPresentAmount;

    /** 处理前待扣金额 */
    private Long pendingBefore;

    /** 处理后待扣金额 */
    private Long pendingAfter;

    /** 已执行自动重试次数 */
    private Integer retryCount;

    /** 下次执行时间 */
    private LocalDateTime nextExecuteAt;

    /** 任务租约实例 */
    private String leaseOwner;

    /** 任务租约截止时间 */
    private LocalDateTime leaseUntil;

    /** 本次调用使用的维护者会话版本 */
    private Long sessionVersion;

    /** 调用前微信余额 */
    private Long wechatBalanceBefore;

    /** 调用后微信余额 */
    private Long wechatBalanceAfter;

    /** 最后失败码 */
    private String lastErrorCode;

    /** 最后脱敏失败原因 */
    private String lastErrorMessage;

    /** 最后失败时间 */
    private LocalDateTime lastFailedAt;

    /** 首次开始时间 */
    private LocalDateTime startedAt;

    /** 完成时间 */
    private LocalDateTime completedAt;
}
