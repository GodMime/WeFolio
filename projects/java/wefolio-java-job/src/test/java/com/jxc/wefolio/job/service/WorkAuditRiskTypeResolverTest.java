package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.dict.AuditResultDict;
import com.jxc.wefolio.job.dict.WorkAuditReasonCodeDict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 腾讯审核标签到稳定风险类型的转换测试。 */
class WorkAuditRiskTypeResolverTest {

    private final WorkAuditRiskTypeResolver resolver = new WorkAuditRiskTypeResolver();

    @ParameterizedTest
    @CsvSource({
            "Porn,PORN_CONTENT",
            "' porn ',PORN_CONTENT",
            "Ads,ADVERTISING_CONTENT",
            "QUALITY,LOW_QUALITY_CONTENT",
            "Politics,POLITICAL_CONTENT",
            "terrorism,TERRORISM_CONTENT"
    })
    void knownLabelsShouldMapCaseInsensitivelyAfterTrimming(String label, String expectedCode) {
        assertThat(resolver.resolve(AuditResultDict.BLOCK, label, false))
                .isEqualTo(WorkAuditReasonCodeDict.valueOf(expectedCode));
    }

    @Test
    void unknownOrBlankUnsafeLabelShouldUseSafeFallback() {
        assertThat(resolver.resolve(AuditResultDict.REVIEW, "NewUnsafeLabel", false))
                .isEqualTo(WorkAuditReasonCodeDict.OTHER_UNSAFE_CONTENT);
        assertThat(resolver.resolve(AuditResultDict.BLOCK, " ", false))
                .isEqualTo(WorkAuditReasonCodeDict.OTHER_UNSAFE_CONTENT);
        assertThat(resolver.resolve(AuditResultDict.BLOCK, null, false))
                .isEqualTo(WorkAuditReasonCodeDict.OTHER_UNSAFE_CONTENT);
    }

    @Test
    void passedResultShouldClearRiskType() {
        assertThat(resolver.resolve(AuditResultDict.PASS, "Normal", false)).isNull();
    }

    @Test
    void providerFailureOrUnknownTerminalShouldUseServiceError() {
        assertThat(resolver.resolve(AuditResultDict.UNKNOWN, null, true))
                .isEqualTo(WorkAuditReasonCodeDict.AUDIT_SERVICE_ERROR);
        assertThat(resolver.resolve(AuditResultDict.UNKNOWN, "Unknown", false))
                .isEqualTo(WorkAuditReasonCodeDict.AUDIT_SERVICE_ERROR);
    }
}
