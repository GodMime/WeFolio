package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.jxc.wefolio.dict.WorkAuditReasonCodeDict;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 将内部稳定审核风险类型转换为用户可直接理解的中文原因。
 */
@Component
@Slf4j
public class WorkAuditUserReasonResolver {

    /** 单轮最多向用户展示的风险类型数量。 */
    private static final int MAX_REASON_COUNT = 20;

    /** 违规兜底文案。 */
    private static final String REJECTED_FALLBACK =
            "作品可能包含不符合平台规范的内容，未通过审核，请调整后重新提交";

    /** 疑似违规兜底文案。 */
    private static final String REVIEW_FALLBACK =
            "作品可能包含不符合平台规范的内容，需要进一步确认，建议调整后重新提交";

    /** 审核失败仍有剩余次数时的文案格式。 */
    private static final String FAILED_RETRY_FORMAT =
            "审核服务暂时未能完成检测，请稍后重新提交（还可重新提交 %d 次）";

    /** 审核失败且达到上限时的文案。 */
    private static final String FAILED_LIMIT_REACHED =
            "审核服务暂时未能完成检测，且已达到审核次数上限";

    /**
     * 解析对外审核原因。
     *
     * @param auditStatus 作品审核状态
     * @param auditReasonCode 稳定风险代码
     * @param auditRound 当前审核轮次
     * @param maxAuditRounds 配置的审核总轮次
     * @return 用户原因；无须展示原因时返回空
     */
    public String resolve(String auditStatus, String auditReasonCode, int auditRound, int maxAuditRounds) {
        WorkAuditStatusDict status = WorkAuditStatusDict.fromCode(auditStatus);
        if (status == WorkAuditStatusDict.FAILED) {
            int remainingCount = Math.max(0, maxAuditRounds - Math.max(1, auditRound));
            return remainingCount > 0
                    ? FAILED_RETRY_FORMAT.formatted(remainingCount)
                    : FAILED_LIMIT_REACHED;
        }
        WorkAuditReasonCodeDict reasonCode = WorkAuditReasonCodeDict.fromCode(auditReasonCode);
        if (status == WorkAuditStatusDict.REJECTED) {
            return rejectedReason(reasonCode);
        }
        if (status == WorkAuditStatusDict.REVIEW_REQUIRED) {
            return reviewReason(reasonCode);
        }
        return null;
    }

    /**
     * 解析当前轮次的全部用户可读审核原因。
     *
     * <p>主原因始终排在首位，以保持新旧客户端对同一作品的原因认知一致。历史数据没有多原因字段、
     * 字段格式异常或包含未知代码时，会忽略无效值并安全回退到主原因。</p>
     *
     * @param auditStatus 作品审核状态
     * @param auditReasonCode 主风险代码
     * @param auditReasonCodes 当前轮次全部风险代码 JSON 数组
     * @param auditRound 当前审核轮次
     * @param maxAuditRounds 配置的审核总轮次
     * @return 用户可读审核原因列表
     */
    public List<AuditReason> resolveAll(
            String auditStatus,
            String auditReasonCode,
            String auditReasonCodes,
            int auditRound,
            int maxAuditRounds
    ) {
        WorkAuditStatusDict status = WorkAuditStatusDict.fromCode(auditStatus);
        if (status == WorkAuditStatusDict.FAILED) {
            String code = WorkAuditReasonCodeDict.AUDIT_SERVICE_ERROR.getCode();
            return List.of(new AuditReason(code, resolve(auditStatus, code, auditRound, maxAuditRounds)));
        }
        if (status != WorkAuditStatusDict.REJECTED && status != WorkAuditStatusDict.REVIEW_REQUIRED) {
            return List.of();
        }

        LinkedHashSet<WorkAuditReasonCodeDict> reasonCodes = new LinkedHashSet<>();
        addKnownReason(reasonCodes, auditReasonCode);
        appendJsonReasons(reasonCodes, auditReasonCodes);
        if (reasonCodes.isEmpty()) {
            reasonCodes.add(WorkAuditReasonCodeDict.OTHER_UNSAFE_CONTENT);
        }

        List<AuditReason> reasons = new ArrayList<>(reasonCodes.size());
        for (WorkAuditReasonCodeDict reasonCode : reasonCodes) {
            if (reasons.size() >= MAX_REASON_COUNT) {
                break;
            }
            reasons.add(new AuditReason(
                    reasonCode.getCode(),
                    resolve(auditStatus, reasonCode.getCode(), auditRound, maxAuditRounds)));
        }
        return List.copyOf(reasons);
    }

    private void appendJsonReasons(
            LinkedHashSet<WorkAuditReasonCodeDict> reasonCodes,
            String auditReasonCodes
    ) {
        if (auditReasonCodes == null || auditReasonCodes.isBlank()) {
            return;
        }
        try {
            JSONArray values = JSON.parseArray(auditReasonCodes);
            for (Object value : values) {
                if (reasonCodes.size() >= MAX_REASON_COUNT) {
                    return;
                }
                if (value instanceof String code) {
                    addKnownReason(reasonCodes, code);
                }
            }
        } catch (RuntimeException exception) {
            log.warn("作品多审核原因 JSON 解析失败，将回退到主原因", exception);
        }
    }

    private void addKnownReason(LinkedHashSet<WorkAuditReasonCodeDict> reasonCodes, String code) {
        WorkAuditReasonCodeDict reasonCode = WorkAuditReasonCodeDict.fromCode(code);
        if (reasonCode != null) {
            reasonCodes.add(reasonCode);
        }
    }

    private String rejectedReason(WorkAuditReasonCodeDict reasonCode) {
        if (reasonCode == null) {
            return REJECTED_FALLBACK;
        }
        return switch (reasonCode) {
            case PORN_CONTENT -> "作品可能包含色情或低俗内容，未通过审核，请调整后重新提交";
            case ADVERTISING_CONTENT -> "作品可能包含广告、二维码或引流信息，未通过审核，请调整后重新提交";
            case LOW_QUALITY_CONTENT -> "作品画面质量较低或内容不清晰，请更换清晰素材后重新提交";
            case POLITICAL_CONTENT -> "作品可能包含政治敏感内容，未通过审核，请调整后重新提交";
            case TERRORISM_CONTENT -> "作品可能包含暴力或恐怖内容，未通过审核，请调整后重新提交";
            case OTHER_UNSAFE_CONTENT, AUDIT_SERVICE_ERROR -> REJECTED_FALLBACK;
        };
    }

    private String reviewReason(WorkAuditReasonCodeDict reasonCode) {
        if (reasonCode == null) {
            return REVIEW_FALLBACK;
        }
        return switch (reasonCode) {
            case PORN_CONTENT -> "作品可能包含色情或低俗内容，需要进一步确认，建议调整后重新提交";
            case ADVERTISING_CONTENT -> "作品可能包含广告、二维码或引流信息，需要进一步确认，建议调整后重新提交";
            case LOW_QUALITY_CONTENT -> "作品画面质量可能较低或内容不清晰，建议更换清晰素材后重新提交";
            case POLITICAL_CONTENT -> "作品可能包含政治敏感内容，需要进一步确认，建议调整后重新提交";
            case TERRORISM_CONTENT -> "作品可能包含暴力或恐怖内容，需要进一步确认，建议调整后重新提交";
            case OTHER_UNSAFE_CONTENT, AUDIT_SERVICE_ERROR -> REVIEW_FALLBACK;
        };
    }

    /**
     * 用户可读审核原因。
     *
     * @param code 稳定风险代码
     * @param message 用户可直接理解的中文原因
     */
    public record AuditReason(String code, String message) {
    }
}
