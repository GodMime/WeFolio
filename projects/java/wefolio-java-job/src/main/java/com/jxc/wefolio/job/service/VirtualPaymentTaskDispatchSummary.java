package com.jxc.wefolio.job.service;

/**
 * 单类微信虚拟支付候选任务的一轮分发统计。
 *
 * @param candidateCount 本轮发现的候选任务数
 * @param requestCount 实际发起的 runtime 请求数
 * @param processedCount runtime 已领取并处理的任务数
 * @param skippedCount runtime 未领取或功能关闭而跳过的任务数
 * @param failedCount runtime 请求或响应解析失败的任务数
 */
public record VirtualPaymentTaskDispatchSummary(
        int candidateCount,
        int requestCount,
        int processedCount,
        int skippedCount,
        int failedCount
) {
}
