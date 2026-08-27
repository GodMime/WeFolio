package com.jxc.wefolio.service;

import com.jxc.wefolio.dto.MineWorkAuditResubmitResponse;
import com.jxc.wefolio.model.WorkManualAuditSubmission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 作品重审应用服务，负责事务完成后的单次人工审核通知。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MineWorkAuditApplicationService {

    /** 最终轮人工审核提交事件 */
    private static final String SUBMITTED_EVENT = "SUBMITTED";

    /** 作品审核事务服务 */
    private final MineWorkAuditService auditService;

    /** 作品人工审核飞书通知器 */
    private final FeishuWorkAuditNotifier notifier;

    /** 提交下一审核轮次，并在最终人工轮事务完成后尝试通知一次。 */
    public MineWorkAuditResubmitResponse resubmit(Long workId) {
        MineWorkAuditService.ResubmitResult result = auditService.resubmit(workId);
        WorkManualAuditSubmission notification = result.notification();
        if (notification != null) {
            try {
                notifier.notifySubmitted(notification);
            } catch (RuntimeException exception) {
                log.error("作品人工审核飞书通知失败 manualAuditNo={} workId={} round={} event={} exceptionType={}",
                        notification.manualAuditNo(), notification.workId(),
                        notification.auditRound(), SUBMITTED_EVENT,
                        exception.getClass().getSimpleName());
            }
        }
        return result.response();
    }
}
