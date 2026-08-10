package com.jxc.wefolio.dto;

/**
 * 微信虚拟支付扣币任务恢复响应。
 *
 * @param userId 用户 ID
 * @param taskId 已存在或新建的活动任务 ID，无待扣时为空
 * @param outcome 恢复结果
 */
public record VirtualPaymentDebitTaskRecoveryResponse(
        Long userId,
        Long taskId,
        String outcome
) {
}
