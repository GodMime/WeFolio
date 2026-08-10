package com.jxc.wefolio.service.payment;

/**
 * 单条持久任务执行入口结果。
 */
public enum TaskExecutionOutcome {

    /** 已成功领取并进入处理流程。 */
    PROCESSED,

    /** runtime 微信虚拟支付功能已关闭，未尝试领取任务。 */
    SKIPPED_DISABLED,

    /** 当前状态、时间或租约不允许领取。 */
    SKIPPED_NOT_CLAIMABLE
}
