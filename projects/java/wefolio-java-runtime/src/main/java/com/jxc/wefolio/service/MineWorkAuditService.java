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
import com.jxc.wefolio.model.WorkManualAuditSubmission;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 我的作品审核服务 — 负责主动重审资格、轮次状态流转和对外审核展示。
 */
@Service
public class MineWorkAuditService {

    /** 逻辑未删除值 */
    private static final long NOT_DELETED = 0L;

    /** 数据库轮次递增表达式 */
    private static final String AUDIT_ROUND_INCREMENT_SQL = "audit_round = audit_round + 1";

    /** 乐观锁版本递增表达式 */
    private static final String VERSION_INCREMENT_SQL = "version = version + 1";

    /** 人工审核原因类型 */
    private static final String MANUAL_REVIEW_REASON_CODE = "MANUAL_REVIEW";

    /** 允许用户主动重审的作品状态 */
    private static final Set<String> RESUBMITTABLE_STATUSES = Set.of(
            WorkAuditStatusDict.REJECTED.getCode(),
            WorkAuditStatusDict.REVIEW_REQUIRED.getCode(),
            WorkAuditStatusDict.FAILED.getCode()
    );

    /** 作品 Mapper */
    private final WorkEntityMapper workEntityMapper;

    /** 作品审核轮次配置 */
    private final WorkAuditProperties workAuditProperties;

    /** 用户可读审核原因解析器 */
    private final WorkAuditUserReasonResolver userReasonResolver;

    /** 人工审核编号生成器 */
    private final WorkManualAuditNoGenerator manualAuditNoGenerator;

    /** 创建作品审核服务。 */
    public MineWorkAuditService(
            WorkEntityMapper workEntityMapper,
            WorkAuditProperties workAuditProperties,
            WorkAuditUserReasonResolver userReasonResolver,
            WorkManualAuditNoGenerator manualAuditNoGenerator
    ) {
        this.workEntityMapper = workEntityMapper;
        this.workAuditProperties = workAuditProperties;
        this.userReasonResolver = userReasonResolver;
        this.manualAuditNoGenerator = manualAuditNoGenerator;
    }

    /**
     * 主动将作品提交到下一审核轮次。
     *
     * @param workId 作品 ID
     * @return 重审响应及可选的事务后通知快照
     */
    @Transactional(rollbackFor = Exception.class)
    public ResubmitResult resubmit(Long workId) {
        Long userId = AuthContextHolder.requireUserId();
        WorkEntity current = findOwnedActiveWork(userId, workId);
        validateCanResubmit(current);

        int currentRound = normalizedRound(current.getAuditRound());
        int nextRound = currentRound + 1;
        int maxRounds = workAuditProperties.getMaxRounds();
        boolean manualFinalRound = nextRound == maxRounds;
        String manualAuditNo = manualFinalRound ? manualAuditNoGenerator.generate() : null;
        LocalDateTime submittedAt = LocalDateTime.now();
        List<WorkAuditUserReasonResolver.AuditReason> previousReasons = manualFinalRound
                ? buildAuditView(current).auditReasons() : List.of();
        var updateWrapper = Wrappers.<WorkEntity>lambdaUpdate()
                .set(WorkEntity::getAuditStatus, manualFinalRound
                        ? WorkAuditStatusDict.AUDITING.getCode()
                        : WorkAuditStatusDict.PENDING.getCode())
                .setSql(AUDIT_ROUND_INCREMENT_SQL)
                .set(WorkEntity::getAuditReasonCode, null)
                .set(WorkEntity::getAuditReasonCodes, null)
                .set(WorkEntity::getAuditRejectReason, null)
                .set(WorkEntity::getUpdatedAt, submittedAt)
                .setSql(VERSION_INCREMENT_SQL)
                .eq(WorkEntity::getId, workId)
                .eq(WorkEntity::getUserId, userId)
                .eq(WorkEntity::getStatus, WorkStatusDict.ACTIVE.getCode())
                .in(WorkEntity::getAuditStatus, RESUBMITTABLE_STATUSES)
                .lt(WorkEntity::getAuditRound, maxRounds)
                .isNull(WorkEntity::getManualAuditNo)
                .eq(WorkEntity::getDeleted, NOT_DELETED);
        if (manualFinalRound) {
            updateWrapper
                    .set(WorkEntity::getManualAuditNo, manualAuditNo)
                    .set(WorkEntity::getManualAuditResultAt, null);
        }
        int updated;
        try {
            updated = workEntityMapper.update(null, updateWrapper);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(MineWorkMessage.MANUAL_AUDIT_NO_CONFLICT_MESSAGE);
        }
        if (updated != 1) {
            throwConcurrentStateException(findOwnedActiveWork(userId, workId));
        }

        MineWorkAuditResubmitResponse response = new MineWorkAuditResubmitResponse();
        response.setWorkId(workId);
        response.setAuditStatus(manualFinalRound
                ? WorkAuditStatusDict.AUDITING.getCode()
                : WorkAuditStatusDict.PENDING.getCode());
        response.setAuditRound(nextRound);
        response.setMaxAuditRounds(maxRounds);
        response.setRemainingAuditResubmitCount(remainingCount(nextRound));
        response.setCanResubmitAudit(false);
        WorkManualAuditSubmission notification = manualFinalRound
                ? new WorkManualAuditSubmission(
                        workId,
                        userId,
                        current.getTitle(),
                        current.getMediaType(),
                        current.getMediaObjectKey(),
                        manualAuditNo,
                        nextRound,
                        maxRounds,
                        submittedAt,
                        List.copyOf(previousReasons))
                : null;
        return new ResubmitResult(response, notification);
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
        boolean manualAudit = hasManualAuditNo(work);
        boolean canResubmit = !manualAudit && auditStatus != null
                && RESUBMITTABLE_STATUSES.contains(auditStatus)
                && round < maxRounds;
        if (manualAudit) {
            String manualReason = WorkAuditStatusDict.REJECTED.getCode().equals(auditStatus)
                    ? work.getAuditRejectReason() : null;
            List<WorkAuditUserReasonResolver.AuditReason> manualReasons =
                    WorkAuditStatusDict.REJECTED.getCode().equals(auditStatus)
                            ? List.of(new WorkAuditUserReasonResolver.AuditReason(
                                    MANUAL_REVIEW_REASON_CODE, manualReason))
                            : List.of();
            return new AuditView(
                    round,
                    maxRounds,
                    0,
                    false,
                    manualReason,
                    manualReasons);
        }
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
        if (hasManualAuditNo(work)) {
            throw new BusinessException(MineWorkMessage.AUDIT_RESUBMIT_LIMIT_REACHED_MESSAGE);
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
        if (hasManualAuditNo(latest)) {
            throw new BusinessException(MineWorkMessage.AUDIT_RESUBMIT_LIMIT_REACHED_MESSAGE);
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

    /** 判断作品是否已进入过最终人工审核，编号存在后不得轮换或再次重审。 */
    private boolean hasManualAuditNo(WorkEntity work) {
        return work != null
                && work.getManualAuditNo() != null
                && !work.getManualAuditNo().isBlank();
    }

    /**
     * 重审事务结果。
     *
     * @param response 兼容既有接口的重审响应
     * @param notification 最终人工轮通知快照，自动轮次为空
     */
    public record ResubmitResult(
            MineWorkAuditResubmitResponse response,
            WorkManualAuditSubmission notification
    ) {
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
