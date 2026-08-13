package com.jxc.wefolio.job.service;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.job.dict.AuditResultDict;
import com.jxc.wefolio.job.dict.WorkAuditStatusDict;
import com.jxc.wefolio.job.dict.WorkAuditReasonCodeDict;
import com.jxc.wefolio.job.entity.WorkAuditTaskEntity;
import com.jxc.wefolio.job.repo.WorkAuditTaskRepository;
import com.jxc.wefolio.job.repo.WorkAuditWorkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * 作品审核本地数据库短事务服务。
 */
@Service
@RequiredArgsConstructor
public class WorkAuditClaimTransactionService {

    /** 旧审核轮次任务终止原因 */
    private static final String STALE_AUDIT_ROUND_MESSAGE = "作品审核轮次已变化";

    private final WorkAuditWorkRepository workRepository;

    private final WorkAuditTaskRepository taskRepository;

    private final WorkAuditRiskTypeResolver riskTypeResolver;

    private final WorkAuditRiskCollectionResolver riskCollectionResolver;

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
     * 将待审核动图 claim 为审核中，并在同一事务内保存本轮抽样帧。
     *
     * @param workId 作品 ID
     * @param task 待创建的审核任务
     * @param sampledFrames 两个不同帧号
     * @return 创建后的审核任务，未抢占到作品时返回 null
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkAuditTaskEntity claimAndCreatePendingAnimationTask(
            Long workId, WorkAuditTaskEntity task, List<Integer> sampledFrames) {
        List<Integer> stableFrames = validateAndSortSampledFrames(sampledFrames);
        if (!workRepository.claimPendingWork(workId)) {
            return null;
        }
        task.setSampledFrameNumbers(JSON.toJSONString(stableFrames));
        task.setTaskStatus(com.jxc.wefolio.job.dict.WorkAuditTaskStatusDict.PENDING.getCode());
        task.setAttemptCount(0);
        task.setQueryCount(0);
        task.setStartedAt(null);
        task.setLockedBy(null);
        task.setLockedUntil(null);
        return taskRepository.insertTask(task);
    }

    /**
     * 视频提交成功后，同步更新任务和作品为审核中。
     *
     * @param taskId 任务 ID
     * @param workId 作品 ID
     * @param auditRound 任务所属审核轮次
     * @param lockOwner 本次领取 token
     * @param ciJobId 腾讯云任务 ID
     * @param responsePayload 响应摘要
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean markVideoSubmittedAndKeepWorkAuditing(
            Long taskId, Long workId, Integer auditRound, String lockOwner,
            String ciJobId, String responsePayload) {
        if (!taskRepository.markVideoSubmitted(taskId, lockOwner, ciJobId, responsePayload)) {
            return false;
        }
        return workRepository.updateAuditStatusAndReasonsForRound(
                workId, auditRound, WorkAuditStatusDict.AUDITING, null, null, null);
    }

    /**
     * 视频仍在处理中时，同步更新任务和作品审核中状态。
     *
     * @param taskId 任务 ID
     * @param workId 作品 ID
     * @param auditRound 任务所属审核轮次
     * @param lockOwner 本次领取 token
     * @param ciState 腾讯云状态
     * @param responsePayload 响应摘要
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean markVideoRunningAndKeepWorkAuditing(
            Long taskId, Long workId, Integer auditRound, String lockOwner,
            String ciState, String responsePayload) {
        if (!taskRepository.markVideoRunning(taskId, lockOwner, ciState, responsePayload)) {
            return false;
        }
        return workRepository.updateAuditStatusAndReasonsForRound(
                workId, auditRound, WorkAuditStatusDict.AUDITING, null, null, null);
    }

    /**
     * 视频查询失败但未超上限时，任务回到 RUNNING，作品保持审核中。
     *
     * @param taskId 任务 ID
     * @param workId 作品 ID
     * @param auditRound 任务所属审核轮次
     * @param lockOwner 本次领取 token
     * @param errorMessage 错误摘要
     * @param responsePayload 响应摘要
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean markVideoQueryFailureForNextRunAndKeepWorkAuditing(
            Long taskId, Long workId, Integer auditRound, String lockOwner,
            String errorMessage, String responsePayload) {
        if (!taskRepository.markVideoQueryFailureForNextRun(
                taskId, lockOwner, errorMessage, responsePayload)) {
            return false;
        }
        return workRepository.updateAuditStatusAndReasonsForRound(
                workId, auditRound, WorkAuditStatusDict.AUDITING, null, null, null);
    }

    /**
     * 使用当前领取 token 标记视频任务失败，并只更新同一审核轮次作品。
     *
     * @param taskId 任务 ID
     * @param workId 作品 ID
     * @param auditRound 任务所属审核轮次
     * @param lockOwner 本次领取 token
     * @param errorMessage 错误摘要
     * @param responsePayload 响应摘要
     * @param auditRejectReason 作品审核拒绝原因
     * @return 是否仍持有本次任务领取权且作品轮次匹配
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean markVideoTaskFailedAndUpdateWorkFailed(
            Long taskId, Long workId, Integer auditRound, String lockOwner,
            String errorMessage, String responsePayload, String auditRejectReason) {
        if (!taskRepository.markVideoFailed(taskId, lockOwner, errorMessage, responsePayload)) {
            return false;
        }
        return workRepository.updateAuditStatusAndReasonsForRound(
                workId,
                auditRound,
                WorkAuditStatusDict.FAILED,
                WorkAuditReasonCodeDict.AUDIT_SERVICE_ERROR.getCode(),
                JSON.toJSONString(List.of(WorkAuditReasonCodeDict.AUDIT_SERVICE_ERROR.getCode())),
                auditRejectReason);
    }

    /**
     * 使用当前领取 token 标记视频任务成功，并只更新同一审核轮次作品。
     *
     * @param taskId 任务 ID
     * @param workId 作品 ID
     * @param auditRound 任务所属审核轮次
     * @param lockOwner 本次领取 token
     * @param result 审核结果
     * @param ciState 腾讯云状态
     * @param ciResult 腾讯云结果码
     * @param ciLabel 命中标签
     * @param ciScore 命中分数
     * @param risks 各审核场景命中摘要
     * @param responsePayload 响应摘要
     * @param auditStatus 作品审核状态
     * @param auditRejectReason 作品审核拒绝原因
     * @return 是否仍持有本次任务领取权且作品轮次匹配
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean markVideoTaskSuccessAndUpdateWork(
            Long taskId, Long workId, Integer auditRound, String lockOwner,
            AuditResultDict result, String ciState, Integer ciResult, String ciLabel, Integer ciScore,
            List<TencentCiAuditRisk> risks, String responsePayload, WorkAuditStatusDict auditStatus,
            String auditRejectReason) {
        if (!taskRepository.markVideoSuccess(
                taskId, lockOwner, result, ciState, ciResult, ciLabel, ciScore, responsePayload)) {
            return false;
        }
        WorkAuditReasonCodeDict reasonCode = riskTypeResolver.resolve(result, ciLabel, false);
        List<String> reasonCodes = riskCollectionResolver.resolve(result, ciLabel, risks).stream()
                .map(WorkAuditReasonCodeDict::getCode)
                .toList();
        return workRepository.updateAuditStatusAndReasonsForRound(
                workId,
                auditRound,
                auditStatus,
                reasonCode == null ? null : reasonCode.getCode(),
                reasonCodes.isEmpty() ? null : JSON.toJSONString(reasonCodes),
                auditRejectReason);
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
        workRepository.updateAuditStatusAndReasons(
                workId,
                WorkAuditStatusDict.FAILED,
                WorkAuditReasonCodeDict.AUDIT_SERVICE_ERROR.getCode(),
                JSON.toJSONString(List.of(WorkAuditReasonCodeDict.AUDIT_SERVICE_ERROR.getCode())),
                auditRejectReason);
    }

    /**
     * 根据已递增的尝试次数将动图任务恢复待执行或置为失败终态。
     *
     * @param taskId 任务 ID
     * @param workId 作品 ID
     * @param errorMessage 错误摘要
     * @param responsePayload 响应摘要
     * @param maxAttempts 最大尝试次数
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean retryOrFailAnimationTask(
            Long taskId, Long workId, Integer auditRound, String lockOwner, String errorMessage,
            String responsePayload, int maxAttempts) {
        Integer attemptCount = taskRepository.findAttemptCountById(taskId);
        if (attemptCount == null || attemptCount >= maxAttempts) {
            if (!taskRepository.markAnimationFailed(taskId, lockOwner, errorMessage, responsePayload)) {
                return false;
            }
            return workRepository.updateAuditStatusAndReasonsForRound(
                    workId,
                    auditRound,
                    WorkAuditStatusDict.FAILED,
                    WorkAuditReasonCodeDict.AUDIT_SERVICE_ERROR.getCode(),
                    JSON.toJSONString(List.of(WorkAuditReasonCodeDict.AUDIT_SERVICE_ERROR.getCode())),
                    "动图审核失败且已达到最大重试次数");
        }
        if (!taskRepository.markAnimationRetryPending(taskId, lockOwner, errorMessage, responsePayload)) {
            return false;
        }
        if (workRepository.updateAuditStatusAndReasonsForRound(
                workId, auditRound, WorkAuditStatusDict.AUDITING, null, null, null)) {
            return true;
        }
        taskRepository.markPendingAnimationFailed(
                taskId, STALE_AUDIT_ROUND_MESSAGE, responsePayload);
        return false;
    }

    /**
     * 使用本次领取 token 标记动图任务成功，并只更新同一审核轮次作品。
     *
     * @param taskId 任务 ID
     * @param workId 作品 ID
     * @param auditRound 任务所属审核轮次
     * @param lockOwner 本次领取 token
     * @param result 审核结果
     * @param ciState 腾讯云状态
     * @param ciResult 腾讯云结果码
     * @param ciLabel 命中标签
     * @param ciScore 命中分数
     * @param risks 各审核场景命中摘要
     * @param responsePayload 响应摘要
     * @param auditStatus 作品审核状态
     * @param auditRejectReason 作品审核拒绝原因
     * @return 是否仍持有本次任务领取权
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean markAnimationTaskSuccessAndUpdateWork(
            Long taskId, Long workId, Integer auditRound, String lockOwner,
            AuditResultDict result, String ciState, Integer ciResult, String ciLabel, Integer ciScore,
            List<TencentCiAuditRisk> risks, String responsePayload, WorkAuditStatusDict auditStatus,
            String auditRejectReason) {
        if (!taskRepository.markAnimationSuccess(
                taskId, lockOwner, result, ciState, ciResult, ciLabel, ciScore, responsePayload)) {
            return false;
        }
        WorkAuditReasonCodeDict reasonCode = riskTypeResolver.resolve(result, ciLabel, false);
        List<String> reasonCodes = riskCollectionResolver.resolve(result, ciLabel, risks).stream()
                .map(WorkAuditReasonCodeDict::getCode)
                .toList();
        return workRepository.updateAuditStatusAndReasonsForRound(
                workId,
                auditRound,
                auditStatus,
                reasonCode == null ? null : reasonCode.getCode(),
                reasonCodes.isEmpty() ? null : JSON.toJSONString(reasonCodes),
                auditRejectReason);
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
     * @param risks 各审核场景命中摘要
     * @param responsePayload 响应摘要
     * @param auditStatus 作品审核状态
     * @param auditRejectReason 作品审核拒绝原因
     */
    @Transactional(rollbackFor = Exception.class)
    public void markTaskSuccessAndUpdateWork(Long taskId, Long workId, AuditResultDict result, String ciState,
                                             Integer ciResult, String ciLabel, Integer ciScore,
                                             List<TencentCiAuditRisk> risks, String responsePayload,
                                             WorkAuditStatusDict auditStatus,
                                             String auditRejectReason) {
        taskRepository.markSuccess(taskId, result, ciState, ciResult, ciLabel, ciScore, responsePayload);
        WorkAuditReasonCodeDict reasonCode = riskTypeResolver.resolve(result, ciLabel, false);
        List<String> reasonCodes = riskCollectionResolver.resolve(result, ciLabel, risks).stream()
                .map(WorkAuditReasonCodeDict::getCode)
                .toList();
        workRepository.updateAuditStatusAndReasons(
                workId,
                auditStatus,
                reasonCode == null ? null : reasonCode.getCode(),
                reasonCodes.isEmpty() ? null : JSON.toJSONString(reasonCodes),
                auditRejectReason);
    }

    private List<Integer> validateAndSortSampledFrames(List<Integer> sampledFrames) {
        if (sampledFrames == null || sampledFrames.size() != 2
                || sampledFrames.stream().anyMatch(frame -> frame == null || frame < 1)
                || sampledFrames.get(0).equals(sampledFrames.get(1))) {
            throw new IllegalArgumentException("动图审核必须持久化两个不同的正整数帧号");
        }
        return sampledFrames.stream().sorted(Comparator.naturalOrder()).toList();
    }
}
