package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.dict.AuditResultDict;

import java.util.List;

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
 * @param risks 各审核场景命中摘要
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
        String rawPayload,
        List<TencentCiAuditRisk> risks
) {

    public TencentCiAuditResult {
        risks = risks == null ? List.of() : List.copyOf(risks);
    }

    /**
     * 兼容不包含场景明细的结果构造。
     */
    public TencentCiAuditResult(String ciJobId, String ciState, AuditResultDict auditResult,
                                Integer ciResult, String ciLabel, Integer ciScore,
                                boolean terminal, boolean providerFailed, String rawPayload) {
        this(ciJobId, ciState, auditResult, ciResult, ciLabel, ciScore,
                terminal, providerFailed, rawPayload, List.of());
    }
}
