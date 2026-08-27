package com.jxc.wefolio.model;

import com.jxc.wefolio.service.WorkAuditUserReasonResolver;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 最终轮人工审核通知快照，确保事务提交后无需重读作品审核上下文。
 *
 * @param workId 作品 ID
 * @param userId 用户 ID
 * @param title 作品标题
 * @param mediaType 媒体类型
 * @param mediaObjectKey 媒体对象键
 * @param manualAuditNo 人工审核编号
 * @param auditRound 当前审核轮次
 * @param maxAuditRounds 最大审核轮次
 * @param submittedAt 提交时间
 * @param previousReasons 上一轮用户可读审核原因
 */
public record WorkManualAuditSubmission(
        Long workId,
        Long userId,
        String title,
        String mediaType,
        String mediaObjectKey,
        String manualAuditNo,
        int auditRound,
        int maxAuditRounds,
        LocalDateTime submittedAt,
        List<WorkAuditUserReasonResolver.AuditReason> previousReasons
) {
}
