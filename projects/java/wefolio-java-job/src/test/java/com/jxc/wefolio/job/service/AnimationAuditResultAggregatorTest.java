package com.jxc.wefolio.job.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.job.dict.AuditResultDict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动图双帧审核结果聚合器测试。
 */
class AnimationAuditResultAggregatorTest {

    private final AnimationAuditResultAggregator aggregator = new AnimationAuditResultAggregator();

    @Test
    void aggregateShouldUseHighestPriorityAndScoreOnlyWithinThatPriority() {
        TencentCiAuditResult pass = result(AuditResultDict.PASS, 0, "Normal", 99, "{\"frame\":5}");
        TencentCiAuditResult review = result(AuditResultDict.REVIEW, 1, "Ads", 50, "{\"frame\":23}");

        TencentCiAuditResult outcome = aggregator.aggregate(List.of(5, 23), List.of(pass, review));

        assertThat(outcome.auditResult()).isEqualTo(AuditResultDict.REVIEW);
        assertThat(outcome.ciScore()).isEqualTo(50);
        assertThat(outcome.ciLabel()).isEqualTo("Ads");
        assertThat(outcome.ciResult()).isEqualTo(1);
    }

    @Test
    void aggregateShouldUseMaximumScoreForEqualHighestPriority() {
        TencentCiAuditResult first = result(AuditResultDict.REVIEW, 1, "Ads", 40, "{}");
        TencentCiAuditResult second = result(AuditResultDict.REVIEW, 1, "Porn", 70, "{}");

        TencentCiAuditResult outcome = aggregator.aggregate(List.of(5, 23), List.of(first, second));

        assertThat(outcome.auditResult()).isEqualTo(AuditResultDict.REVIEW);
        assertThat(outcome.ciScore()).isEqualTo(70);
        assertThat(outcome.ciLabel()).isEqualTo("Porn");
    }

    @Test
    void aggregateShouldLetBlockWinAndMergeRisks() {
        TencentCiAuditRisk reviewRisk = new TencentCiAuditRisk("Ads", AuditResultDict.REVIEW, 60);
        TencentCiAuditRisk blockRisk = new TencentCiAuditRisk("Porn", AuditResultDict.BLOCK, 90);
        TencentCiAuditResult review = result(
                AuditResultDict.REVIEW, 1, "Ads", 60, "{}", List.of(reviewRisk));
        TencentCiAuditResult block = result(
                AuditResultDict.BLOCK, 1, "Porn", 90, "{}", List.of(blockRisk));

        TencentCiAuditResult outcome = aggregator.aggregate(List.of(5, 23), List.of(review, block));

        assertThat(outcome.auditResult()).isEqualTo(AuditResultDict.BLOCK);
        assertThat(outcome.risks()).containsExactly(reviewRisk, blockRisk);
    }

    @Test
    void aggregateShouldNeverPassWhenAnyFrameIsUnknown() {
        TencentCiAuditResult pass = result(AuditResultDict.PASS, 0, "Normal", null, "{}");
        TencentCiAuditResult unknown = result(AuditResultDict.UNKNOWN, null, null, null, "{}");

        TencentCiAuditResult outcome = aggregator.aggregate(List.of(5, 23), List.of(pass, unknown));

        assertThat(outcome.auditResult()).isEqualTo(AuditResultDict.UNKNOWN);
    }

    @Test
    void aggregateShouldKeepEveryRawPayloadInSortedFrameOrder() {
        TencentCiAuditResult highFrame = result(
                AuditResultDict.PASS, 0, "Normal", null, "not-json");
        TencentCiAuditResult lowFrame = result(
                AuditResultDict.PASS, 0, "Normal", null, "{\"JobsDetail\":{\"Result\":0}}");

        TencentCiAuditResult outcome = aggregator.aggregate(
                List.of(23, 5), List.of(highFrame, lowFrame));

        JSONArray payload = JSON.parseArray(outcome.rawPayload());
        assertThat(payload).hasSize(2);
        JSONObject first = payload.getJSONObject(0);
        JSONObject second = payload.getJSONObject(1);
        assertThat(first.getIntValue("frameNumber")).isEqualTo(5);
        assertThat(first.get("rawPayload")).isInstanceOf(JSONObject.class);
        assertThat(second.getIntValue("frameNumber")).isEqualTo(23);
        assertThat(second.getString("rawPayload")).isEqualTo("not-json");
    }

    private TencentCiAuditResult result(AuditResultDict auditResult, Integer ciResult,
                                        String ciLabel, Integer ciScore, String rawPayload) {
        return result(auditResult, ciResult, ciLabel, ciScore, rawPayload, List.of());
    }

    private TencentCiAuditResult result(AuditResultDict auditResult, Integer ciResult,
                                        String ciLabel, Integer ciScore, String rawPayload,
                                        List<TencentCiAuditRisk> risks) {
        return new TencentCiAuditResult(
                null, "Success", auditResult, ciResult, ciLabel, ciScore,
                true, false, rawPayload, risks);
    }
}
