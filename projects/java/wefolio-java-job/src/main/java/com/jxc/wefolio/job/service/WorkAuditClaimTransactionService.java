package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.entity.WorkAuditTaskEntity;
import com.jxc.wefolio.job.repo.WorkAuditTaskRepository;
import com.jxc.wefolio.job.repo.WorkAuditWorkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 作品审核 claim 与任务创建短事务服务。
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
}
