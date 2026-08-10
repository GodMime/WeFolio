package com.jxc.wefolio.job.service;

/**
 * 微信虚拟支付待扣任务恢复和执行的一轮分发统计。
 *
 * @param recoveryUserCount 本轮发现缺少活动待扣任务的用户数
 * @param recoveryRequestCount 实际发起的恢复请求数
 * @param recoveryFailedCount 恢复请求失败数
 * @param taskSummary 待扣任务执行统计
 */
public record VirtualPaymentDebitDispatchSummary(
        int recoveryUserCount,
        int recoveryRequestCount,
        int recoveryFailedCount,
        VirtualPaymentTaskDispatchSummary taskSummary
) {
}
