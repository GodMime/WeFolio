package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.dict.AuditResultDict;

/**
 * 腾讯云数据万象审核结果摘要。
 *
 * @param ciJobId 腾讯云任务 ID
 * @param ciState 腾讯云任务状态
 * @param auditResult 归一化审核结果
 * @param ciResult 腾讯云结果码
 * @param ciLabel 命中标签
 * @param ciScore 命中分数
 * @param terminal 是否为终态
 * @param providerFailed 腾讯云任务是否失败
 * @param rawPayload 原始响应摘要
 */
public record TencentCiAuditResult(
        String ciJobId,
        String ciState,
        AuditResultDict auditResult,
        Integer ciResult,
        String ciLabel,
        Integer ciScore,
        boolean terminal,
        boolean providerFailed,
        String rawPayload
) {
}
