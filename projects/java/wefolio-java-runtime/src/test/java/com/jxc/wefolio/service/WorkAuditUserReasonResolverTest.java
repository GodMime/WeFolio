package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.WorkAuditReasonCodeDict;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 面向用户的作品审核原因测试。 */
class WorkAuditUserReasonResolverTest {

    private final WorkAuditUserReasonResolver resolver = new WorkAuditUserReasonResolver();

    @Test
    void rejectedShouldResolveAllStableRiskTypesToFriendlyChinese() {
        assertThat(resolveRejected(WorkAuditReasonCodeDict.PORN_CONTENT))
                .isEqualTo("作品可能包含色情或低俗内容，未通过审核，请调整后重新提交");
        assertThat(resolveRejected(WorkAuditReasonCodeDict.ADVERTISING_CONTENT))
                .isEqualTo("作品可能包含广告、二维码或引流信息，未通过审核，请调整后重新提交");
        assertThat(resolveRejected(WorkAuditReasonCodeDict.LOW_QUALITY_CONTENT))
                .isEqualTo("作品画面质量较低或内容不清晰，请更换清晰素材后重新提交");
        assertThat(resolveRejected(WorkAuditReasonCodeDict.POLITICAL_CONTENT))
                .isEqualTo("作品可能包含政治敏感内容，未通过审核，请调整后重新提交");
        assertThat(resolveRejected(WorkAuditReasonCodeDict.TERRORISM_CONTENT))
                .isEqualTo("作品可能包含暴力或恐怖内容，未通过审核，请调整后重新提交");
        assertThat(resolveRejected(WorkAuditReasonCodeDict.OTHER_UNSAFE_CONTENT))
                .isEqualTo("作品可能包含不符合平台规范的内容，未通过审核，请调整后重新提交");
        assertThat(resolver.resolve(WorkAuditStatusDict.REJECTED.getCode(), null, 1, 3))
                .isEqualTo("作品可能包含不符合平台规范的内容，未通过审核，请调整后重新提交");
    }

    @Test
    void reviewRequiredShouldResolveAllStableRiskTypesToFriendlyChinese() {
        assertThat(resolveReview(WorkAuditReasonCodeDict.PORN_CONTENT))
                .isEqualTo("作品可能包含色情或低俗内容，需要进一步确认，建议调整后重新提交");
        assertThat(resolveReview(WorkAuditReasonCodeDict.ADVERTISING_CONTENT))
                .isEqualTo("作品可能包含广告、二维码或引流信息，需要进一步确认，建议调整后重新提交");
        assertThat(resolveReview(WorkAuditReasonCodeDict.LOW_QUALITY_CONTENT))
                .isEqualTo("作品画面质量可能较低或内容不清晰，建议更换清晰素材后重新提交");
        assertThat(resolveReview(WorkAuditReasonCodeDict.POLITICAL_CONTENT))
                .isEqualTo("作品可能包含政治敏感内容，需要进一步确认，建议调整后重新提交");
        assertThat(resolveReview(WorkAuditReasonCodeDict.TERRORISM_CONTENT))
                .isEqualTo("作品可能包含暴力或恐怖内容，需要进一步确认，建议调整后重新提交");
        assertThat(resolveReview(WorkAuditReasonCodeDict.OTHER_UNSAFE_CONTENT))
                .isEqualTo("作品可能包含不符合平台规范的内容，需要进一步确认，建议调整后重新提交");
        assertThat(resolver.resolve(WorkAuditStatusDict.REVIEW_REQUIRED.getCode(), "UNKNOWN", 1, 3))
                .isEqualTo("作品可能包含不符合平台规范的内容，需要进一步确认，建议调整后重新提交");
    }

    @Test
    void failedShouldShowRemainingResubmissionsFromCurrentConfiguration() {
        assertThat(resolver.resolve(
                WorkAuditStatusDict.FAILED.getCode(),
                WorkAuditReasonCodeDict.AUDIT_SERVICE_ERROR.getCode(),
                1,
                3))
                .isEqualTo("审核服务暂时未能完成检测，请稍后重新提交（还可重新提交 2 次）");
        assertThat(resolver.resolve(
                WorkAuditStatusDict.FAILED.getCode(),
                WorkAuditReasonCodeDict.AUDIT_SERVICE_ERROR.getCode(),
                3,
                3))
                .isEqualTo("审核服务暂时未能完成检测，且已达到审核次数上限");
    }

    @Test
    void nonFailureStatesShouldNotExposeAnyReason() {
        for (WorkAuditStatusDict status : new WorkAuditStatusDict[] {
                WorkAuditStatusDict.PENDING,
                WorkAuditStatusDict.AUDITING,
                WorkAuditStatusDict.PASSED
        }) {
            assertThat(resolver.resolve(
                    status.getCode(),
                    WorkAuditReasonCodeDict.PORN_CONTENT.getCode(),
                    1,
                    3)).isNull();
        }
    }

    @Test
    void friendlyReasonShouldNotContainTencentTechnicalDetails() {
        String reason = resolver.resolve(
                WorkAuditStatusDict.REJECTED.getCode(),
                WorkAuditReasonCodeDict.PORN_CONTENT.getCode(),
                1,
                3);

        assertThat(reason)
                .doesNotContain("腾讯云", "Porn", "result=", "score=", "jobId", "Exception");
    }

    @Test
    void resolveAllShouldKeepMainReasonFirstAndRemoveInvalidDuplicates() {
        List<WorkAuditUserReasonResolver.AuditReason> reasons = resolver.resolveAll(
                WorkAuditStatusDict.REJECTED.getCode(),
                WorkAuditReasonCodeDict.PORN_CONTENT.getCode(),
                "[\"ADVERTISING_CONTENT\",\"PORN_CONTENT\",\"ADVERTISING_CONTENT\",\"UNKNOWN\",7]",
                1,
                3);

        assertThat(reasons).extracting(WorkAuditUserReasonResolver.AuditReason::code)
                .containsExactly("PORN_CONTENT", "ADVERTISING_CONTENT");
        assertThat(reasons).extracting(WorkAuditUserReasonResolver.AuditReason::message)
                .allMatch(message -> message.startsWith("作品可能"));
    }

    @Test
    void resolveAllShouldFallbackSafelyForMalformedAndLegacyValues() {
        assertThat(resolver.resolveAll(
                WorkAuditStatusDict.REVIEW_REQUIRED.getCode(),
                WorkAuditReasonCodeDict.ADVERTISING_CONTENT.getCode(),
                "not-json",
                1,
                3)).extracting(WorkAuditUserReasonResolver.AuditReason::code)
                .containsExactly("ADVERTISING_CONTENT");

        assertThat(resolver.resolveAll(
                WorkAuditStatusDict.REJECTED.getCode(), null, "[]", 1, 3))
                .extracting(WorkAuditUserReasonResolver.AuditReason::code)
                .containsExactly("OTHER_UNSAFE_CONTENT");
    }

    @Test
    void resolveAllShouldUseServiceErrorForFailedAndNothingForVisibleSuccessStates() {
        assertThat(resolver.resolveAll(
                WorkAuditStatusDict.FAILED.getCode(), null, null, 2, 3))
                .singleElement()
                .satisfies(reason -> {
                    assertThat(reason.code()).isEqualTo("AUDIT_SERVICE_ERROR");
                    assertThat(reason.message()).contains("还可重新提交 1 次");
                });
        assertThat(resolver.resolveAll(
                WorkAuditStatusDict.PASSED.getCode(),
                WorkAuditReasonCodeDict.PORN_CONTENT.getCode(),
                "[\"PORN_CONTENT\"]",
                1,
                3)).isEmpty();
    }

    private String resolveRejected(WorkAuditReasonCodeDict reasonCode) {
        return resolver.resolve(WorkAuditStatusDict.REJECTED.getCode(), reasonCode.getCode(), 1, 3);
    }

    private String resolveReview(WorkAuditReasonCodeDict reasonCode) {
        return resolver.resolve(WorkAuditStatusDict.REVIEW_REQUIRED.getCode(), reasonCode.getCode(), 1, 3);
    }
}
