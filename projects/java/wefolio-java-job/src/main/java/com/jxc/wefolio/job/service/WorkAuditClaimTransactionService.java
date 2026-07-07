package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.dict.AuditResultDict;
import com.jxc.wefolio.job.dict.WorkAuditStatusDict;
import com.jxc.wefolio.job.entity.WorkAuditTaskEntity;
import com.jxc.wefolio.job.repo.WorkAuditTaskRepository;
import com.jxc.wefolio.job.repo.WorkAuditWorkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 作品审核本地数据库短事务服务。
 */
@Service
@RequiredArgsConstructor
public class WorkAuditClaimTransactionService {

    private final WorkAuditWorkRepository workRepository;

    private final WorkAuditTaskRepository taskRepository;

    /**
     * 将待审核作品 claim 为审核中，并在同一事务内创建审核任务。
     *
     * @param workId 作品 ID
     * @param task 待创建的审核任务
     * @return 创建后的审核任务，未抢占到作品时返回 null
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkAuditTaskEntity claimAndCreateSubmittingTask(Long workId, WorkAuditTaskEntity task) {
        if (!workRepository.claimPendingWork(workId)) {
            return null;
        }
        return taskRepository.insertTask(task);
    }

    /**
     * 视频提交成功后，同步更新任务和作品为审核中。
     *
     * @param taskId 任务 ID
     * @param workId 作品 ID
     * @param ciJobId 腾讯云任务 ID
     * @param responsePayload 响应摘要
     */
    @Transactional(rollbackFor = Exception.class)
    public void markVideoSubmittedAndUpdateWorkAuditing(Long taskId, Long workId, String ciJobId,
                                                        String responsePayload) {
        taskRepository.markVideoSubmitted(taskId, ciJobId, responsePayload);
        workRepository.updateAuditStatusAndRejectReason(workId, WorkAuditStatusDict.AUDITING, null);
    }

    /**
     * 视频仍在处理中时，同步更新任务和作品审核中状态。
     *
     * @param taskId 任务 ID
     * @param workId 作品 ID
     * @param ciState 腾讯云状态
     * @param responsePayload 响应摘要
     */
    @Transactional(rollbackFor = Exception.class)
    public void markVideoRunningAndKeepWorkAuditing(Long taskId, Long workId, String ciState, String responsePayload) {
        taskRepository.markVideoRunning(taskId, ciState, responsePayload);
        workRepository.updateAuditStatusAndRejectReason(workId, WorkAuditStatusDict.AUDITING, null);
    }

    /**
     * 视频查询失败但未超上限时，任务回到 RUNNING，作品保持审核中。
     *
     * @param taskId 任务 ID
     * @param workId 作品 ID
     * @param errorMessage 错误摘要
     * @param responsePayload 响应摘要
     */
    @Transactional(rollbackFor = Exception.class)
    public void markQueryFailureForNextRunAndKeepWorkAuditing(Long taskId, Long workId, String errorMessage,
                                                              String responsePayload) {
        taskRepository.markQueryFailureForNextRun(taskId, errorMessage, responsePayload);
        workRepository.updateAuditStatusAndRejectReason(workId, WorkAuditStatusDict.AUDITING, null);
    }

    /**
     * 标记任务失败并更新作品失败原因。
     *
     * @param taskId 任务 ID
     * @param workId 作品 ID
     * @param errorMessage 任务错误摘要
     * @param responsePayload 响应摘要
     * @param auditRejectReason 作品审核拒绝原因
     */
    @Transactional(rollbackFor = Exception.class)
    public void markTaskFailedAndUpdateWorkFailed(Long taskId, Long workId, String errorMessage,
                                                  String responsePayload, String auditRejectReason) {
        taskRepository.markFailed(taskId, errorMessage, responsePayload);
        workRepository.updateAuditStatusAndRejectReason(workId, WorkAuditStatusDict.FAILED, auditRejectReason);
    }

    /**
     * 标记任务成功终态并更新作品审核状态。
     *
     * @param taskId 任务 ID
     * @param workId 作品 ID
     * @param result 审核结果
     * @param ciState 腾讯云状态
     * @param ciResult 腾讯云结果码
     * @param ciLabel 命中标签
     * @param ciScore 命中分数
     * @param responsePayload 响应摘要
     * @param auditStatus 作品审核状态
     * @param auditRejectReason 作品审核拒绝原因
     */
    @Transactional(rollbackFor = Exception.class)
    public void markTaskSuccessAndUpdateWork(Long taskId, Long workId, AuditResultDict result, String ciState,
                                             Integer ciResult, String ciLabel, Integer ciScore,
                                             String responsePayload, WorkAuditStatusDict auditStatus,
                                             String auditRejectReason) {
        taskRepository.markSuccess(taskId, result, ciState, ciResult, ciLabel, ciScore, responsePayload);
        workRepository.updateAuditStatusAndRejectReason(workId, auditStatus, auditRejectReason);
    }
}
