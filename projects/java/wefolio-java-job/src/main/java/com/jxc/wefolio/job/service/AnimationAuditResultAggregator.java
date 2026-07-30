package com.jxc.wefolio.job.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.job.dict.AuditResultDict;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 动图双帧审核结果聚合器。
 */
@Component
public class AnimationAuditResultAggregator {

    /** 数据万象成功状态 */
    private static final String CI_SUCCESS_STATE = "Success";

    /**
     * 按固定优先级聚合两个帧的审核结果。
     *
     * @param frameNumbers 与结果一一对应的帧号
     * @param results 帧审核结果
     * @return 聚合后的作品级审核结果
     */
    public TencentCiAuditResult aggregate(List<Integer> frameNumbers, List<TencentCiAuditResult> results) {
        validateInput(frameNumbers, results);
        List<FrameResult> frameResults = IntStream.range(0, frameNumbers.size())
                .mapToObj(index -> new FrameResult(frameNumbers.get(index), results.get(index)))
                .sorted(Comparator.comparingInt(FrameResult::frameNumber))
                .toList();
        AuditResultDict finalResult = resolveFinalResult(frameResults);
        TencentCiAuditResult representative = frameResults.stream()
                .map(FrameResult::result)
                .filter(result -> result.auditResult() == finalResult)
                .max(Comparator.comparingInt(result -> normalizedScore(result.ciScore())))
                .orElseThrow();
        List<TencentCiAuditRisk> risks = frameResults.stream()
                .flatMap(item -> item.result().risks().stream())
                .toList();
        return new TencentCiAuditResult(
                null,
                CI_SUCCESS_STATE,
                finalResult,
                representative.ciResult(),
                representative.ciLabel(),
                representative.ciScore(),
                true,
                false,
                buildRawPayload(frameResults),
                risks);
    }

    private AuditResultDict resolveFinalResult(List<FrameResult> frameResults) {
        if (frameResults.stream().anyMatch(item -> item.result().auditResult() == AuditResultDict.UNKNOWN)) {
            return AuditResultDict.UNKNOWN;
        }
        return frameResults.stream()
                .map(item -> item.result().auditResult())
                .max(Comparator.comparingInt(this::priority))
                .orElseThrow();
    }

    private String buildRawPayload(List<FrameResult> frameResults) {
        JSONArray payload = new JSONArray();
        for (FrameResult frameResult : frameResults) {
            JSONObject item = new JSONObject();
            item.put("frameNumber", frameResult.frameNumber());
            item.put("rawPayload", parseRawPayload(frameResult.result().rawPayload()));
            payload.add(item);
        }
        return payload.toJSONString();
    }

    private Object parseRawPayload(String rawPayload) {
        if (rawPayload == null) {
            return null;
        }
        try {
            return JSON.parse(rawPayload);
        } catch (RuntimeException ignored) {
            return rawPayload;
        }
    }

    private void validateInput(List<Integer> frameNumbers, List<TencentCiAuditResult> results) {
        if (frameNumbers == null || results == null
                || frameNumbers.size() != 2 || results.size() != 2) {
            throw new IllegalArgumentException("动图审核必须包含两个帧号和两个审核结果");
        }
        if (frameNumbers.get(0).equals(frameNumbers.get(1)) || results.stream().anyMatch(result -> result == null)) {
            throw new IllegalArgumentException("动图审核帧号必须不同且结果不能为空");
        }
    }

    private int priority(AuditResultDict result) {
        return switch (result) {
            case PASS -> 1;
            case REVIEW -> 2;
            case BLOCK -> 3;
            case UNKNOWN -> 0;
        };
    }

    private int normalizedScore(Integer score) {
        return score == null ? Integer.MIN_VALUE : score;
    }

    /** 帧号与其审核结果。 */
    private record FrameResult(int frameNumber, TencentCiAuditResult result) {
    }
}
