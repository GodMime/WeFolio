package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.dict.AuditResultDict;
import com.jxc.wefolio.job.dict.WorkAuditReasonCodeDict;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 将腾讯云多个审核场景聚合为作品级稳定风险类型集合。
 */
@Component
@RequiredArgsConstructor
public class WorkAuditRiskCollectionResolver {

    /** 单轮最多保存的风险类型数量 */
    public static final int MAX_REASON_COUNT = 20;

    private final WorkAuditRiskTypeResolver riskTypeResolver;

    /**
     * 聚合主原因和所有场景命中。
     *
     * @param auditResult 腾讯云整体审核结果
     * @param ciLabel 腾讯云顶层主标签
     * @param risks 各审核场景命中
     * @return 主原因置首的去重稳定风险类型
     */
    public List<WorkAuditReasonCodeDict> resolve(AuditResultDict auditResult, String ciLabel,
                                                  List<TencentCiAuditRisk> risks) {
        WorkAuditReasonCodeDict mainReason = riskTypeResolver.resolve(auditResult, ciLabel, false);
        Map<WorkAuditReasonCodeDict, AggregatedRisk> aggregated = new EnumMap<>(WorkAuditReasonCodeDict.class);
        merge(aggregated, mainReason, auditResult, null);
        if (risks != null) {
            for (TencentCiAuditRisk risk : risks) {
                if (risk == null || !isUnsafeResult(risk.auditResult())) {
                    continue;
                }
                WorkAuditReasonCodeDict reason = riskTypeResolver.resolve(
                        risk.auditResult(), risk.ciLabel(), false);
                merge(aggregated, reason, risk.auditResult(), risk.ciScore());
            }
        }
        List<AggregatedRisk> sorted = new ArrayList<>(aggregated.values());
        sorted.sort(Comparator
                .comparing((AggregatedRisk item) -> item.reason() != mainReason)
                .thenComparing(Comparator.comparingInt(
                        (AggregatedRisk item) -> severity(item.auditResult())).reversed())
                .thenComparing(Comparator.comparingInt(
                        (AggregatedRisk item) -> normalizedScore(item.score())).reversed())
                .thenComparing(item -> item.reason().getCode()));
        return sorted.stream()
                .limit(MAX_REASON_COUNT)
                .map(AggregatedRisk::reason)
                .toList();
    }

    private void merge(Map<WorkAuditReasonCodeDict, AggregatedRisk> aggregated,
                       WorkAuditReasonCodeDict reason, AuditResultDict auditResult, Integer score) {
        if (reason == null || !isUnsafeResult(auditResult)) {
            return;
        }
        aggregated.merge(reason, new AggregatedRisk(reason, auditResult, score), (left, right) ->
                new AggregatedRisk(
                        reason,
                        severity(left.auditResult()) >= severity(right.auditResult())
                                ? left.auditResult() : right.auditResult(),
                        Math.max(normalizedScore(left.score()), normalizedScore(right.score()))));
    }

    private boolean isUnsafeResult(AuditResultDict result) {
        return result == AuditResultDict.BLOCK || result == AuditResultDict.REVIEW;
    }

    private static int severity(AuditResultDict result) {
        if (result == AuditResultDict.BLOCK) {
            return 2;
        }
        if (result == AuditResultDict.REVIEW) {
            return 1;
        }
        return 0;
    }

    private static int normalizedScore(Integer score) {
        return score == null ? -1 : score;
    }

    /** 聚合排序需要保留的内部风险信息。 */
    private record AggregatedRisk(
            WorkAuditReasonCodeDict reason,
            AuditResultDict auditResult,
            Integer score
    ) {
    }
}
