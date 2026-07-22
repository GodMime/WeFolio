package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.dict.AuditResultDict;
import com.jxc.wefolio.job.dict.WorkAuditReasonCodeDict;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 将腾讯云顶层标签转换为供应商无关的稳定风险类型。
 */
@Component
public class WorkAuditRiskTypeResolver {

    /**
     * 解析稳定风险类型。
     *
     * @param auditResult 归一化审核结果
     * @param ciLabel 腾讯云顶层标签
     * @param providerFailed 供应商任务是否失败
     * @return 稳定风险类型；审核通过时返回空
     */
    public WorkAuditReasonCodeDict resolve(AuditResultDict auditResult, String ciLabel, boolean providerFailed) {
        if (providerFailed || auditResult == null || auditResult == AuditResultDict.UNKNOWN) {
            return WorkAuditReasonCodeDict.AUDIT_SERVICE_ERROR;
        }
        if (auditResult == AuditResultDict.PASS) {
            return null;
        }
        String normalizedLabel = ciLabel == null ? "" : ciLabel.trim().toLowerCase(Locale.ROOT);
        return switch (normalizedLabel) {
            case "porn" -> WorkAuditReasonCodeDict.PORN_CONTENT;
            case "ads" -> WorkAuditReasonCodeDict.ADVERTISING_CONTENT;
            case "quality" -> WorkAuditReasonCodeDict.LOW_QUALITY_CONTENT;
            case "politics" -> WorkAuditReasonCodeDict.POLITICAL_CONTENT;
            case "terrorism" -> WorkAuditReasonCodeDict.TERRORISM_CONTENT;
            default -> WorkAuditReasonCodeDict.OTHER_UNSAFE_CONTENT;
        };
    }
}
