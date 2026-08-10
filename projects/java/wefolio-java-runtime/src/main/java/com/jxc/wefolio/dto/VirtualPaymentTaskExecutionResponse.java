package com.jxc.wefolio.dto;

/**
 * 微信虚拟支付单任务执行响应。
 *
 * @param targetId 目标任务或订单 ID
 * @param taskType 任务类型
 * @param outcome 执行入口结果
 */
public record VirtualPaymentTaskExecutionResponse(
        Long targetId,
        String taskType,
        String outcome
) {
}
