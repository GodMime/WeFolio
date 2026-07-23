package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.dict.AuditResultDict;

/**
 * 腾讯云单个审核场景的命中摘要。
 *
 * @param ciLabel 腾讯云审核场景标签
 * @param auditResult 归一化场景结果
 * @param ciScore 场景命中分数
 */
public record TencentCiAuditRisk(
        String ciLabel,
        AuditResultDict auditResult,
        Integer ciScore
) {
}
