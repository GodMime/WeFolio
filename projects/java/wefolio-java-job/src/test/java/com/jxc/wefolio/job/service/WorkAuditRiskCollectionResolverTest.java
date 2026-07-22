package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.dict.AuditResultDict;
import com.jxc.wefolio.job.dict.WorkAuditReasonCodeDict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 作品审核多风险集合聚合测试。 */
class WorkAuditRiskCollectionResolverTest {

    @Test
    void resolveShouldKeepMainReasonFirstAndDeduplicateByStrongestHit() {
        WorkAuditRiskCollectionResolver resolver = new WorkAuditRiskCollectionResolver(
                new WorkAuditRiskTypeResolver());

        List<WorkAuditReasonCodeDict> result = resolver.resolve(
                AuditResultDict.BLOCK,
                "Porn",
                List.of(
                        new TencentCiAuditRisk("Ads", AuditResultDict.REVIEW, 80),
                        new TencentCiAuditRisk("Ads", AuditResultDict.BLOCK, 75),
                        new TencentCiAuditRisk("Politics", AuditResultDict.BLOCK, 90),
                        new TencentCiAuditRisk("Porn", AuditResultDict.REVIEW, 99)));

        assertThat(result).containsExactly(
                WorkAuditReasonCodeDict.PORN_CONTENT,
                WorkAuditReasonCodeDict.POLITICAL_CONTENT,
                WorkAuditReasonCodeDict.ADVERTISING_CONTENT);
    }

    @Test
    void resolveShouldCollapseUnknownScenesAndIgnoreNormalHits() {
        WorkAuditRiskCollectionResolver resolver = new WorkAuditRiskCollectionResolver(
                new WorkAuditRiskTypeResolver());

        List<WorkAuditReasonCodeDict> result = resolver.resolve(
                AuditResultDict.REVIEW,
                null,
                List.of(
                        new TencentCiAuditRisk("Teenager", AuditResultDict.REVIEW, 88),
                        new TencentCiAuditRisk("Illegal", AuditResultDict.BLOCK, 60),
                        new TencentCiAuditRisk("Porn", AuditResultDict.PASS, 99)));

        assertThat(result).containsExactly(WorkAuditReasonCodeDict.OTHER_UNSAFE_CONTENT);
    }
}
