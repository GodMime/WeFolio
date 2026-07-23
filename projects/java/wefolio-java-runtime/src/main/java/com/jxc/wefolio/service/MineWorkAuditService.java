package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.config.WorkAuditProperties;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.dto.MineWorkAuditResubmitResponse;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.message.MineWorkMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 我的作品审核服务 — 负责主动重审资格、轮次状态流转和对外审核展示。
 */
@Service
@RequiredArgsConstructor
public class MineWorkAuditService {

    /** 逻辑未删除值 */
    private static final long NOT_DELETED = 0L;

    /** 数据库轮次递增表达式 */
    private static final String AUDIT_ROUND_INCREMENT_SQL = "audit_round = audit_round + 1";

    /** 乐观锁版本递增表达式 */
    private static final String VERSION_INCREMENT_SQL = "version = version + 1";

    /** 允许用户主动重审的作品状态 */
    private static final Set<String> RESUBMITTABLE_STATUSES = Set.of(
            WorkAuditStatusDict.REJECTED.getCode(),
            WorkAuditStatusDict.REVIEW_REQUIRED.getCode(),
            WorkAuditStatusDict.FAILED.getCode()
    );

    private final WorkEntityMapper workEntityMapper;
    private final WorkAuditProperties workAuditProperties;
    private final WorkAuditUserReasonResolver userReasonResolver;

    /**
     * 主动将作品提交到下一审核轮次。
     *
     * @param workId 作品 ID
     * @return 重审后的审核状态
     */
    @Transactional(rollbackFor = Exception.class)
    public MineWorkAuditResubmitResponse resubmit(Long workId) {
        Long userId = AuthContextHolder.requireUserId();
        WorkEntity current = findOwnedActiveWork(userId, workId);
        validateCanResubmit(current);

        int currentRound = normalizedRound(current.getAuditRound());
        int updated = workEntityMapper.update(null, Wrappers.<WorkEntity>lambdaUpdate()
                .set(WorkEntity::getAuditStatus, WorkAuditStatusDict.PENDING.getCode())
                .setSql(AUDIT_ROUND_INCREMENT_SQL)
                .set(WorkEntity::getAuditReasonCode, null)
                .set(WorkEntity::getAuditReasonCodes, null)
                .set(WorkEntity::getAuditRejectReason, null)
                .set(WorkEntity::getUpdatedAt, LocalDateTime.now())
                .setSql(VERSION_INCREMENT_SQL)
                .eq(WorkEntity::getId, workId)
                .eq(WorkEntity::getUserId, userId)
                .eq(WorkEntity::getStatus, WorkStatusDict.ACTIVE.getCode())
                .in(WorkEntity::getAuditStatus, RESUBMITTABLE_STATUSES)
                .lt(WorkEntity::getAuditRound, workAuditProperties.getMaxRounds())
                .eq(WorkEntity::getDeleted, NOT_DELETED));
        if (updated != 1) {
            throwConcurrentStateException(findOwnedActiveWork(userId, workId));
        }

        int nextRound = currentRound + 1;
        MineWorkAuditResubmitResponse response = new MineWorkAuditResubmitResponse();
        response.setWorkId(workId);
        response.setAuditStatus(WorkAuditStatusDict.PENDING.getCode());
        response.setAuditRound(nextRound);
        response.setMaxAuditRounds(workAuditProperties.getMaxRounds());
        response.setRemainingAuditResubmitCount(remainingCount(nextRound));
        response.setCanResubmitAudit(false);
        return response;
    }

    /**
     * 构造列表和详情共用的审核展示信息。
     *
     * @param work 作品
     * @return 审核展示信息
     */
    public AuditView buildAuditView(WorkEntity work) {
        int round = normalizedRound(work == null ? null : work.getAuditRound());
        int maxRounds = workAuditProperties.getMaxRounds();
        String auditStatus = work == null ? null : work.getAuditStatus();
        boolean canResubmit = auditStatus != null
                && RESUBMITTABLE_STATUSES.contains(auditStatus)
                && round < maxRounds;
        return new AuditView(
                round,
                maxRounds,
                Math.max(0, maxRounds - round),
                canResubmit,
                userReasonResolver.resolve(
                        auditStatus,
                        work == null ? null : work.getAuditReasonCode(),
                        round,
                        maxRounds),
                userReasonResolver.resolveAll(
                        auditStatus,
                        work == null ? null : work.getAuditReasonCode(),
                        work == null ? null : work.getAuditReasonCodes(),
                        round,
                        maxRounds)
        );
    }

    private WorkEntity findOwnedActiveWork(Long userId, Long workId) {
        if (workId == null) {
            return null;
        }
        return workEntityMapper.selectOne(Wrappers.<WorkEntity>lambdaQuery()
                .eq(WorkEntity::getId, workId)
                .eq(WorkEntity::getUserId, userId)
                .eq(WorkEntity::getStatus, WorkStatusDict.ACTIVE.getCode())
                .eq(WorkEntity::getDeleted, NOT_DELETED));
    }

    private void validateCanResubmit(WorkEntity work) {
        if (work == null) {
            throw new BusinessException(MineWorkMessage.WORK_NOT_FOUND_MESSAGE);
        }
        throwStatusExceptionIfNeeded(work);
        if (!RESUBMITTABLE_STATUSES.contains(work.getAuditStatus())) {
            throw new BusinessException(MineWorkMessage.AUDIT_RESUBMIT_STATE_CHANGED_MESSAGE);
        }
        if (normalizedRound(work.getAuditRound()) >= workAuditProperties.getMaxRounds()) {
            throw new BusinessException(MineWorkMessage.AUDIT_RESUBMIT_LIMIT_REACHED_MESSAGE);
        }
    }

    private void throwConcurrentStateException(WorkEntity latest) {
        if (latest == null) {
            throw new BusinessException(MineWorkMessage.WORK_NOT_FOUND_MESSAGE);
        }
        throwStatusExceptionIfNeeded(latest);
        if (RESUBMITTABLE_STATUSES.contains(latest.getAuditStatus())
                && normalizedRound(latest.getAuditRound()) >= workAuditProperties.getMaxRounds()) {
            throw new BusinessException(MineWorkMessage.AUDIT_RESUBMIT_LIMIT_REACHED_MESSAGE);
        }
        throw new BusinessException(MineWorkMessage.AUDIT_RESUBMIT_STATE_CHANGED_MESSAGE);
    }

    private void throwStatusExceptionIfNeeded(WorkEntity work) {
        if (WorkAuditStatusDict.PENDING.getCode().equals(work.getAuditStatus())
                || WorkAuditStatusDict.AUDITING.getCode().equals(work.getAuditStatus())) {
            throw new BusinessException(MineWorkMessage.AUDIT_RESUBMIT_IN_PROGRESS_MESSAGE);
        }
        if (WorkAuditStatusDict.PASSED.getCode().equals(work.getAuditStatus())) {
            throw new BusinessException(MineWorkMessage.AUDIT_RESUBMIT_PASSED_MESSAGE);
        }
    }

    private int normalizedRound(Integer auditRound) {
        return auditRound == null ? 1 : Math.max(1, auditRound);
    }

    private int remainingCount(int auditRound) {
        return Math.max(0, workAuditProperties.getMaxRounds() - auditRound);
    }

    /**
     * 列表和详情共用的审核展示值。
     *
     * @param auditRound 当前审核轮次
     * @param maxAuditRounds 配置的审核总轮次
     * @param remainingAuditResubmitCount 剩余主动重审次数
     * @param canResubmitAudit 是否允许主动重审
     * @param auditRejectReason 用户可读审核原因
     * @param auditReasons 当前轮次全部用户可读审核原因
     */
    public record AuditView(
            int auditRound,
            int maxAuditRounds,
            int remainingAuditResubmitCount,
            boolean canResubmitAudit,
            String auditRejectReason,
            List<WorkAuditUserReasonResolver.AuditReason> auditReasons
    ) {
    }
}
