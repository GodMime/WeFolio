package com.jxc.wefolio.service;

import com.jxc.wefolio.constant.WorkManualAuditConstants;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.dto.WorkManualAuditUpdateRequest;
import com.jxc.wefolio.dto.WorkManualAuditUpdateResponse;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.message.WorkManualAuditMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** 作品人工审核回传服务，负责行锁、幂等和 first-write-wins 结论写入。 */
@Slf4j
@Service
public class WorkManualAuditService {

    /** 人工拒绝原因最大 Unicode 码点数 */
    private static final int MAX_REJECT_REASON_CODE_POINTS = 512;

    /** 逻辑未删除值 */
    private static final long NOT_DELETED = 0L;

    /** 人工审核允许写入的最终状态 */
    private static final Set<String> TARGET_STATUSES = Set.of(
            WorkAuditStatusDict.PASSED.getCode(),
            WorkAuditStatusDict.REJECTED.getCode());

    /** 作品 Mapper */
    private final WorkEntityMapper mapper;

    /** 人工审核首次结论时间来源 */
    private final Clock clock;

    /** 创建使用系统时间的人工审核服务。 */
    @Autowired
    public WorkManualAuditService(WorkEntityMapper mapper) {
        this(mapper, Clock.systemDefaultZone());
    }

    /** 创建使用指定时钟的人工审核服务，供测试固定结论时间。 */
    WorkManualAuditService(WorkEntityMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * 按人工审核编号写入或幂等重放最终结论。
     *
     * @param manualAuditNo 人工审核编号
     * @param request 审核结论请求
     * @return 最小审核结果
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkManualAuditUpdateResponse update(
            String manualAuditNo,
            WorkManualAuditUpdateRequest request
    ) {
        String normalizedNo = normalizeManualAuditNo(manualAuditNo);
        String targetStatus = normalizeStatus(request == null ? null : request.getStatus());
        String reason = normalizeReason(
                request == null ? null : request.getAuditRejectReason(), targetStatus);

        WorkEntity work = mapper.lockByManualAuditNo(normalizedNo);
        validateLockedWork(work, normalizedNo);
        if (WorkAuditStatusDict.AUDITING.getCode().equals(work.getAuditStatus())) {
            return complete(work, normalizedNo, targetStatus, reason);
        }
        if (TARGET_STATUSES.contains(work.getAuditStatus())) {
            if (work.getAuditStatus().equals(targetStatus)
                    && Objects.equals(normalizeStoredReason(work.getAuditRejectReason()), reason)) {
                return response(work, false);
            }
            throw new BusinessException(WorkManualAuditMessage.RESULT_CONFLICT_MESSAGE);
        }
        throw new BusinessException(WorkManualAuditMessage.NOT_FOUND_MESSAGE);
    }

    /** 条件写入首次结论，并同步内存实体生成响应。 */
    private WorkManualAuditUpdateResponse complete(
            WorkEntity work,
            String manualAuditNo,
            String targetStatus,
            String reason
    ) {
        LocalDateTime resultAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
        int updated = mapper.completeManualAudit(
                work.getId(), manualAuditNo, targetStatus, reason, resultAt);
        if (updated != 1) {
            log.warn("作品人工审核条件更新未命中: workId={}, manualAuditNo={}, targetStatus={}",
                    work.getId(), manualAuditNo, targetStatus);
            throw new BusinessException(WorkManualAuditMessage.CONCURRENT_CONFLICT_MESSAGE);
        }
        work.setAuditStatus(targetStatus);
        work.setAuditReasonCode(null);
        work.setAuditReasonCodes(null);
        work.setAuditRejectReason(reason);
        work.setManualAuditResultAt(resultAt);
        return response(work, true);
    }

    /** 校验行锁读取结果仍属于同一条有效人工审核记录。 */
    private void validateLockedWork(WorkEntity work, String manualAuditNo) {
        if (work == null
                || !WorkStatusDict.ACTIVE.getCode().equals(work.getStatus())
                || work.getDeleted() == null
                || work.getDeleted() != NOT_DELETED
                || !manualAuditNo.equals(work.getManualAuditNo())) {
            throw new BusinessException(WorkManualAuditMessage.NOT_FOUND_MESSAGE);
        }
    }

    /** 规范化人工审核编号，并校验其使用共享前缀。 */
    private String normalizeManualAuditNo(String value) {
        String normalized = value == null ? "" : value.strip();
        if (!normalized.startsWith(WorkManualAuditConstants.MANUAL_AUDIT_NO_PREFIX)) {
            throw new BusinessException(WorkManualAuditMessage.NOT_FOUND_MESSAGE);
        }
        return normalized;
    }

    /** 规范化并校验目标状态。 */
    private String normalizeStatus(String value) {
        String normalized = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (!TARGET_STATUSES.contains(normalized)) {
            throw new BusinessException(WorkManualAuditMessage.STATUS_INVALID_MESSAGE);
        }
        return normalized;
    }

    /** 按目标状态规范化并校验人工拒绝原因。 */
    private String normalizeReason(String value, String targetStatus) {
        String normalized = value == null ? "" : value.strip();
        if (WorkAuditStatusDict.PASSED.getCode().equals(targetStatus)) {
            if (!normalized.isEmpty()) {
                throw new BusinessException(
                        WorkManualAuditMessage.PASSED_REASON_NOT_ALLOWED_MESSAGE);
            }
            return null;
        }
        if (normalized.isEmpty()) {
            throw new BusinessException(WorkManualAuditMessage.REJECT_REASON_EMPTY_MESSAGE);
        }
        if (normalized.codePointCount(0, normalized.length())
                > MAX_REJECT_REASON_CODE_POINTS) {
            throw new BusinessException(WorkManualAuditMessage.REJECT_REASON_TOO_LONG_MESSAGE);
        }
        return normalized;
    }

    /** 将已保存原因规范化为幂等比较值。 */
    private String normalizeStoredReason(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    /** 从当前锁定实体构建最小响应。 */
    private WorkManualAuditUpdateResponse response(WorkEntity work, boolean changed) {
        WorkManualAuditUpdateResponse response = new WorkManualAuditUpdateResponse();
        response.setManualAuditNo(work.getManualAuditNo());
        response.setAuditStatus(work.getAuditStatus());
        response.setAuditRejectReason(normalizeStoredReason(work.getAuditRejectReason()));
        response.setManualAuditResultAt(work.getManualAuditResultAt());
        response.setChanged(changed);
        return response;
    }
}
