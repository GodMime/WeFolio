package com.jxc.wefolio.service.payment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import com.jxc.wefolio.dict.PointDebitTaskStatusDict;
import com.jxc.wefolio.entity.PointAccountEntity;
import com.jxc.wefolio.entity.PointDebitTaskEntity;
import com.jxc.wefolio.entity.PointPendingDebitEntity;
import com.jxc.wefolio.mapper.PointAccountEntityMapper;
import com.jxc.wefolio.mapper.PointDebitTaskEntityMapper;
import com.jxc.wefolio.mapper.PointPendingDebitEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 微信待扣任务独立短事务服务。
 */
@Service
@RequiredArgsConstructor
public class PointDebitTaskTransactionService {

    private final PointDebitTaskEntityMapper pointDebitTaskEntityMapper;
    private final PointAccountEntityMapper pointAccountEntityMapper;
    private final PointPendingDebitEntityMapper pointPendingDebitEntityMapper;
    private final WechatVirtualPaymentProperties properties;

    /** 原子领取一条已到期任务。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public PointDebitTaskEntity tryClaim(Long taskId, String executionLeaseToken) {
        LocalDateTime now = pointDebitTaskEntityMapper.selectCurrentTimestamp();
        int claimed = pointDebitTaskEntityMapper.tryClaim(
                taskId, executionLeaseToken, now.plus(properties.getSettlement().getLeaseDuration()), now);
        return claimed == 1 ? pointDebitTaskEntityMapper.selectById(taskId) : null;
    }

    /** 延长当前扣币任务执行租约，令牌失效时立即终止调用方。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void renewLease(Long taskId, String executionLeaseToken) {
        renewLeaseInCurrentTransaction(taskId, executionLeaseToken);
    }

    /** 在调用方当前事务内续租并锁住任务行，不单独提交。 */
    private void renewLeaseInCurrentTransaction(Long taskId, String executionLeaseToken) {
        LocalDateTime now = pointDebitTaskEntityMapper.selectCurrentTimestamp();
        int renewed = pointDebitTaskEntityMapper.renewLease(
                taskId,
                executionLeaseToken,
                now.plus(properties.getSettlement().getLeaseDuration())
        );
        if (renewed != 1) {
            throw new ExecutionLeaseLostException("扣币任务执行租约已被其他执行器接管");
        }
    }

    /** 校验并续租后，使用微信权威余额同步任务对应账户。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public PointAccountEntity syncBalance(
            Long taskId,
            String executionLeaseToken,
            long wechatBalance,
            long wechatPresentBalance
    ) {
        renewLeaseInCurrentTransaction(taskId, executionLeaseToken);
        PointDebitTaskEntity task = pointDebitTaskEntityMapper.selectById(taskId);
        if (task == null || !ownsLease(task, executionLeaseToken)) {
            throw new ExecutionLeaseLostException("扣币任务执行租约已被其他执行器接管");
        }
        if (pointAccountEntityMapper.syncWechatBalance(
                task.getAccountId(), wechatBalance, wechatPresentBalance) != 1) {
            throw new IllegalStateException("微信权威余额同步失败");
        }
        return pointAccountEntityMapper.selectById(task.getAccountId());
    }

    /**
     * 非任务型外部事件使用微信权威余额同步指定账户。
     *
     * <p>本方法不校验任务执行租约，只允许微信回调等没有持久任务上下文的流程调用；
     * 赠送订单和扣币任务必须使用各自携带 {@code executionLeaseToken} 的结算方法。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public PointAccountEntity syncAuthoritativeBalance(
            Long accountId,
            long wechatBalance,
            long wechatPresentBalance
    ) {
        if (pointAccountEntityMapper.syncWechatBalance(
                accountId, wechatBalance, wechatPresentBalance) != 1) {
            throw new IllegalStateException("微信权威余额同步失败");
        }
        return pointAccountEntityMapper.selectById(accountId);
    }

    /**
     * 第一次调用微信扣币前持久化不可变请求金额和会话版本；重试时返回原值。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public PointDebitTaskEntity prepareRequest(
            Long taskId,
            String executionLeaseToken,
            long requestAmount,
            long pendingBefore,
            long wechatBalanceBefore,
            long sessionVersion
    ) {
        PointDebitTaskEntity task = pointDebitTaskEntityMapper.selectById(taskId);
        if (task == null || !ownsLease(task, executionLeaseToken)) {
            throw new ExecutionLeaseLostException("扣币任务执行租约已被其他执行器接管");
        }
        if (task.getRequestAmount() != null) {
            return task;
        }
        int updated = pointDebitTaskEntityMapper.update(null,
                Wrappers.lambdaUpdate(PointDebitTaskEntity.class)
                        .set(PointDebitTaskEntity::getRequestAmount, requestAmount)
                        .set(PointDebitTaskEntity::getPendingBefore, pendingBefore)
                        .set(PointDebitTaskEntity::getWechatBalanceBefore, wechatBalanceBefore)
                        .set(PointDebitTaskEntity::getSessionVersion, sessionVersion)
                        .eq(PointDebitTaskEntity::getId, taskId)
                        .eq(PointDebitTaskEntity::getExecutionLeaseToken, executionLeaseToken)
                        .isNull(PointDebitTaskEntity::getRequestAmount));
        if (updated != 1) {
            throw new IllegalStateException("扣币任务请求参数持久化失败");
        }
        return pointDebitTaskEntityMapper.selectById(taskId);
    }

    /** 微信余额为零时结束本任务并释放活动槽位。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markNoBalance(Long taskId, String executionLeaseToken, long pendingBefore) {
        finishWithoutSettlement(taskId, executionLeaseToken, PointDebitTaskStatusDict.NO_BALANCE,
                pendingBefore, null, null);
    }

    /** 无有效维护者会话时保留活动槽位等待刷新。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markWaitingSession(Long taskId, String executionLeaseToken, String reason) {
        pointDebitTaskEntityMapper.update(null,
                Wrappers.lambdaUpdate(PointDebitTaskEntity.class)
                        .set(PointDebitTaskEntity::getStatus, PointDebitTaskStatusDict.WAITING_SESSION.getCode())
                        .set(PointDebitTaskEntity::getExecutionLeaseToken, null)
                        .set(PointDebitTaskEntity::getLeaseUntil, null)
                        .set(PointDebitTaskEntity::getLastErrorCode,
                                WechatVirtualPaymentErrorType.SESSION_INVALID.name())
                        .set(PointDebitTaskEntity::getLastErrorMessage, safeMessage(reason))
                        .set(PointDebitTaskEntity::getLastFailedAt, LocalDateTime.now())
                        .eq(PointDebitTaskEntity::getId, taskId)
                        .eq(PointDebitTaskEntity::getExecutionLeaseToken, executionLeaseToken));
    }

    /**
     * 微信扣币成功后核销最早待扣来源、同步账户并以成功或部分状态结束本任务。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void completeSuccess(
            Long taskId,
            String executionLeaseToken,
            WechatVirtualPaymentResult result
    ) {
        PointDebitTaskEntity task = pointDebitTaskEntityMapper.selectById(taskId);
        if (task == null || task.getRequestAmount() == null) {
            throw new IllegalStateException("扣币任务状态不允许完成");
        }
        // 先更新并锁住任务租约行，再核销账户和待扣明细，避免结算事务中途被接管。
        renewLeaseInCurrentTransaction(taskId, executionLeaseToken);
        long settledAmount = task.getRequestAmount();
        PointAccountEntity account = pointAccountEntityMapper.selectById(task.getAccountId());
        if (account == null || pointAccountEntityMapper.settleWechatDebit(
                account.getId(), settledAmount, result.balance(), result.presentBalance()) != 1) {
            throw new IllegalStateException("微信扣币结果核销失败");
        }
        settlePendingDetails(account.getId(), settledAmount);
        PointAccountEntity updatedAccount = pointAccountEntityMapper.selectById(account.getId());
        long pendingAfter = value(updatedAccount.getPendingDebit());
        PointDebitTaskStatusDict status = pendingAfter == 0L
                ? PointDebitTaskStatusDict.SUCCEEDED
                : PointDebitTaskStatusDict.PARTIAL;
        Integer activeFlag = null;
        int completed = pointDebitTaskEntityMapper.update(null,
                Wrappers.lambdaUpdate(PointDebitTaskEntity.class)
                        .set(PointDebitTaskEntity::getStatus, status.getCode())
                        .set(PointDebitTaskEntity::getActiveFlag, activeFlag)
                        .set(PointDebitTaskEntity::getSettledAmount, settledAmount)
                        .set(PointDebitTaskEntity::getUsedPresentAmount, result.usedPresentAmount())
                        .set(PointDebitTaskEntity::getPendingAfter, pendingAfter)
                        .set(PointDebitTaskEntity::getWechatBalanceAfter, result.balance())
                        .set(PointDebitTaskEntity::getExecutionLeaseToken, null)
                        .set(PointDebitTaskEntity::getLeaseUntil, null)
                        .set(PointDebitTaskEntity::getCompletedAt, LocalDateTime.now())
                        .eq(PointDebitTaskEntity::getId, taskId)
                        .eq(PointDebitTaskEntity::getExecutionLeaseToken, executionLeaseToken));
        if (completed != 1) {
            throw new IllegalStateException("扣币任务完成状态更新失败");
        }
    }

    /** 临时或永久失败时更新同一任务，远端结果不明时保留活动槽位。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markFailure(Long taskId, String executionLeaseToken, WechatVirtualPaymentResult result) {
        PointDebitTaskEntity task = pointDebitTaskEntityMapper.selectById(taskId);
        if (task == null || !ownsLease(task, executionLeaseToken)) {
            return;
        }
        int nextRetry = value(task.getRetryCount()) + 1;
        boolean retryable = isRetryable(result.errorType())
                && nextRetry <= properties.getSettlement().getMaxRetries();
        pointDebitTaskEntityMapper.update(null,
                Wrappers.lambdaUpdate(PointDebitTaskEntity.class)
                        .set(PointDebitTaskEntity::getStatus, retryable
                                ? PointDebitTaskStatusDict.RETRY_WAIT.getCode()
                                : PointDebitTaskStatusDict.FAILED.getCode())
                        .set(PointDebitTaskEntity::getRetryCount, nextRetry)
                        .set(PointDebitTaskEntity::getNextExecuteAt,
                                LocalDateTime.now().plus(properties.getSettlement().getDelay()))
                        .set(PointDebitTaskEntity::getExecutionLeaseToken, null)
                        .set(PointDebitTaskEntity::getLeaseUntil, null)
                        .set(PointDebitTaskEntity::getLastErrorCode, errorCode(result))
                        .set(PointDebitTaskEntity::getLastErrorMessage, safeMessage(result.errorMessage()))
                        .set(PointDebitTaskEntity::getLastFailedAt, LocalDateTime.now())
                        .eq(PointDebitTaskEntity::getId, taskId)
                        .eq(PointDebitTaskEntity::getExecutionLeaseToken, executionLeaseToken));
    }

    /** 后台人工重试失败任务。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean resetForManualRetry(Long taskId) {
        return pointDebitTaskEntityMapper.resetForManualRetry(taskId) == 1;
    }

    /** 按创建时间和 ID 顺序核销最早待扣来源。 */
    private void settlePendingDetails(Long accountId, long settledAmount) {
        List<PointPendingDebitEntity> details = pointPendingDebitEntityMapper.selectList(
                Wrappers.lambdaQuery(PointPendingDebitEntity.class)
                        .eq(PointPendingDebitEntity::getAccountId, accountId)
                        .gt(PointPendingDebitEntity::getRemainingAmount, 0L)
                        .orderByAsc(PointPendingDebitEntity::getCreatedAt)
                        .orderByAsc(PointPendingDebitEntity::getId));
        long remainingAmount = settledAmount;
        for (PointPendingDebitEntity detail : details) {
            if (remainingAmount == 0L) {
                break;
            }
            long deducted = Math.min(remainingAmount, value(detail.getRemainingAmount()));
            detail.setRemainingAmount(value(detail.getRemainingAmount()) - deducted);
            detail.setLastSettledAt(LocalDateTime.now());
            if (pointPendingDebitEntityMapper.updateById(detail) != 1) {
                throw new IllegalStateException("待扣来源明细核销失败");
            }
            remainingAmount -= deducted;
        }
        if (remainingAmount != 0L) {
            throw new IllegalStateException("待扣来源明细总额不足");
        }
    }

    /** 完成无需微信扣币的任务。 */
    private void finishWithoutSettlement(
            Long taskId,
            String executionLeaseToken,
            PointDebitTaskStatusDict status,
            long pendingBefore,
            String errorCode,
            String errorMessage
    ) {
        Integer activeFlag = null;
        pointDebitTaskEntityMapper.update(null,
                Wrappers.lambdaUpdate(PointDebitTaskEntity.class)
                        .set(PointDebitTaskEntity::getStatus, status.getCode())
                        .set(PointDebitTaskEntity::getActiveFlag, activeFlag)
                        .set(PointDebitTaskEntity::getPendingBefore, pendingBefore)
                        .set(PointDebitTaskEntity::getPendingAfter, pendingBefore)
                        .set(PointDebitTaskEntity::getExecutionLeaseToken, null)
                        .set(PointDebitTaskEntity::getLeaseUntil, null)
                        .set(PointDebitTaskEntity::getLastErrorCode, errorCode)
                        .set(PointDebitTaskEntity::getLastErrorMessage, errorMessage)
                        .set(PointDebitTaskEntity::getCompletedAt, LocalDateTime.now())
                        .eq(PointDebitTaskEntity::getId, taskId)
                        .eq(PointDebitTaskEntity::getExecutionLeaseToken, executionLeaseToken));
    }

    /** 判断错误是否允许自动重试。 */
    private boolean isRetryable(WechatVirtualPaymentErrorType type) {
        return type == WechatVirtualPaymentErrorType.TRANSIENT
                || type == WechatVirtualPaymentErrorType.RATE_LIMITED
                || type == WechatVirtualPaymentErrorType.UNKNOWN;
    }

    /** 错误码文本。 */
    private String errorCode(WechatVirtualPaymentResult result) {
        return result.errorCode() == null ? result.errorType().name() : String.valueOf(result.errorCode());
    }

    /** 限制失败原因长度。 */
    private String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "微信虚拟支付调用失败";
        }
        String normalized = message.strip();
        return normalized.length() <= 255 ? normalized : normalized.substring(0, 255);
    }

    /** 可空长整数转零。 */
    private long value(Long number) {
        return number == null ? 0L : number;
    }

    /** 可空整数转零。 */
    private int value(Integer number) {
        return number == null ? 0 : number;
    }

    /** 校验当前执行租约令牌。 */
    private boolean ownsLease(PointDebitTaskEntity task, String executionLeaseToken) {
        return executionLeaseToken.equals(task.getExecutionLeaseToken());
    }
}
