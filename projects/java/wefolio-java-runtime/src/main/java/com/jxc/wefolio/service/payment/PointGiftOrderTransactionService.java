package com.jxc.wefolio.service.payment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import com.jxc.wefolio.dict.PointGiftOrderStatusDict;
import com.jxc.wefolio.dict.PointTransactionTypeDict;
import com.jxc.wefolio.entity.PointAccountEntity;
import com.jxc.wefolio.entity.PointGiftOrderEntity;
import com.jxc.wefolio.entity.PointTransactionEntity;
import com.jxc.wefolio.mapper.PointAccountEntityMapper;
import com.jxc.wefolio.mapper.PointGiftOrderEntityMapper;
import com.jxc.wefolio.mapper.PointTransactionEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 赠送订单独立短事务服务。
 */
@Service
@RequiredArgsConstructor
public class PointGiftOrderTransactionService {

    /** 赠送流水幂等键前缀。 */
    private static final String GIFT_TRANSACTION_KEY_PREFIX = "wechat-gift:";

    private final PointGiftOrderEntityMapper pointGiftOrderEntityMapper;
    private final PointAccountEntityMapper pointAccountEntityMapper;
    private final PointTransactionEntityMapper pointTransactionEntityMapper;
    private final PointDebitTaskService pointDebitTaskService;
    private final WechatVirtualPaymentProperties properties;

    /** 在独立短事务中领取赠送订单。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public PointGiftOrderEntity tryClaim(Long orderId, String executionLeaseToken) {
        LocalDateTime now = pointGiftOrderEntityMapper.selectCurrentTimestamp();
        int claimed = pointGiftOrderEntityMapper.tryClaim(
                orderId,
                executionLeaseToken,
                now.plus(properties.getSettlement().getLeaseDuration()),
                now
        );
        return claimed == 1 ? pointGiftOrderEntityMapper.selectById(orderId) : null;
    }

    /** 延长当前赠送订单执行租约，令牌失效时立即终止调用方。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void renewLease(Long orderId, String executionLeaseToken) {
        renewLeaseInCurrentTransaction(orderId, executionLeaseToken);
    }

    /** 在调用方当前事务内续租并锁住任务行，不单独提交。 */
    private void renewLeaseInCurrentTransaction(Long orderId, String executionLeaseToken) {
        LocalDateTime now = pointGiftOrderEntityMapper.selectCurrentTimestamp();
        int renewed = pointGiftOrderEntityMapper.renewLease(
                orderId,
                executionLeaseToken,
                now.plus(properties.getSettlement().getLeaseDuration())
        );
        if (renewed != 1) {
            throw new ExecutionLeaseLostException("赠送订单执行租约已被其他执行器接管");
        }
    }

    /**
     * 微信赠送成功或重复成功后，在同一独立事务中同步账户、写流水并完成订单。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void completeSuccess(
            Long orderId,
            String executionLeaseToken,
            WechatVirtualPaymentResult result
    ) {
        PointGiftOrderEntity order = pointGiftOrderEntityMapper.selectById(orderId);
        if (order == null || PointGiftOrderStatusDict.SUCCEEDED.getCode().equals(order.getStatus())) {
            return;
        }
        // 先更新并锁住任务租约行，再修改账户和流水，避免结算事务中途被接管。
        renewLeaseInCurrentTransaction(orderId, executionLeaseToken);
        PointAccountEntity account = pointAccountEntityMapper.selectById(order.getAccountId());
        if (account == null) {
            throw new IllegalStateException("赠送订单对应积分账户不存在");
        }
        long balanceBefore = value(account.getBalance());
        int accountUpdated = pointAccountEntityMapper.applyGiftWechatBalance(
                account.getId(), order.getAmount(), result.balance(), result.presentBalance());
        if (accountUpdated != 1) {
            throw new IllegalStateException("赠送成功后的积分账户同步失败");
        }
        long balanceAfter = Math.subtractExact(result.balance(), value(account.getPendingDebit()));
        PointTransactionEntity transaction = buildGiftTransaction(order, balanceBefore, balanceAfter);
        pointTransactionEntityMapper.insert(transaction);

        int completed = pointGiftOrderEntityMapper.update(null,
                Wrappers.lambdaUpdate(PointGiftOrderEntity.class)
                        .set(PointGiftOrderEntity::getStatus, PointGiftOrderStatusDict.SUCCEEDED.getCode())
                        .set(PointGiftOrderEntity::getPointTransactionId, transaction.getId())
                        .set(PointGiftOrderEntity::getWechatBalanceAfter, result.balance())
                        .set(PointGiftOrderEntity::getWechatPresentBalanceAfter, result.presentBalance())
                        .set(PointGiftOrderEntity::getExecutionLeaseToken, null)
                        .set(PointGiftOrderEntity::getLeaseUntil, null)
                        .set(PointGiftOrderEntity::getCompletedAt, LocalDateTime.now())
                        .eq(PointGiftOrderEntity::getId, orderId)
                        .eq(PointGiftOrderEntity::getExecutionLeaseToken, executionLeaseToken)
                        .in(PointGiftOrderEntity::getStatus,
                                PointGiftOrderStatusDict.READY.getCode(),
                                PointGiftOrderStatusDict.RETRY_WAIT.getCode()));
        if (completed != 1) {
            throw new IllegalStateException("赠送订单完成状态更新失败");
        }
        PointAccountEntity updatedAccount = pointAccountEntityMapper.selectById(account.getId());
        pointDebitTaskService.ensureActiveTask(updatedAccount);
    }

    /** 按失败分类更新同一订单，不创建新微信订单号。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markFailure(
            Long orderId,
            String executionLeaseToken,
            WechatVirtualPaymentResult result
    ) {
        PointGiftOrderEntity order = pointGiftOrderEntityMapper.selectById(orderId);
        if (order == null || PointGiftOrderStatusDict.SUCCEEDED.getCode().equals(order.getStatus())
                || !ownsLease(order, executionLeaseToken)) {
            return;
        }
        int nextRetryCount = value(order.getRetryCount()) + 1;
        boolean retryable = isRetryable(result.errorType())
                && nextRetryCount <= properties.getSettlement().getMaxRetries();
        pointGiftOrderEntityMapper.update(null,
                Wrappers.lambdaUpdate(PointGiftOrderEntity.class)
                        .set(PointGiftOrderEntity::getStatus, retryable
                                ? PointGiftOrderStatusDict.RETRY_WAIT.getCode()
                                : PointGiftOrderStatusDict.FAILED.getCode())
                        .set(PointGiftOrderEntity::getRetryCount, nextRetryCount)
                        .set(PointGiftOrderEntity::getNextExecuteAt,
                                LocalDateTime.now().plus(properties.getSettlement().getDelay()))
                        .set(PointGiftOrderEntity::getExecutionLeaseToken, null)
                        .set(PointGiftOrderEntity::getLeaseUntil, null)
                        .set(PointGiftOrderEntity::getLastErrorCode, errorCode(result))
                        .set(PointGiftOrderEntity::getLastErrorMessage, safeMessage(result.errorMessage()))
                        .set(PointGiftOrderEntity::getLastFailedAt, LocalDateTime.now())
                        .eq(PointGiftOrderEntity::getId, orderId)
                        .eq(PointGiftOrderEntity::getExecutionLeaseToken, executionLeaseToken));
    }

    /** 后台人工重试失败订单。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean resetForManualRetry(Long orderId) {
        return pointGiftOrderEntityMapper.resetForManualRetry(orderId) == 1;
    }

    /** 构造不可变赠送流水。 */
    private PointTransactionEntity buildGiftTransaction(
            PointGiftOrderEntity order,
            long balanceBefore,
            long balanceAfter
    ) {
        PointTransactionEntity transaction = new PointTransactionEntity();
        transaction.setAccountId(order.getAccountId());
        transaction.setUserId(order.getUserId());
        transaction.setTransactionType(PointTransactionTypeDict.GIFT.getCode());
        transaction.setSceneCode(order.getSceneCode());
        transaction.setPointsChange(order.getAmount());
        transaction.setBalanceBefore(balanceBefore);
        transaction.setBalanceAfter(balanceAfter);
        transaction.setBusinessType(order.getBusinessType());
        transaction.setBusinessId(order.getBusinessId());
        transaction.setCalculationSnapshot(order.getBusinessSnapshot());
        transaction.setIdempotencyKey(GIFT_TRANSACTION_KEY_PREFIX + order.getOrderNo());
        transaction.setRemark("微信代币赠送成功");
        transaction.setOccurredAt(LocalDateTime.now());
        return transaction;
    }

    /** 判断错误是否允许自动重试。 */
    private boolean isRetryable(WechatVirtualPaymentErrorType errorType) {
        return errorType == WechatVirtualPaymentErrorType.TRANSIENT
                || errorType == WechatVirtualPaymentErrorType.RATE_LIMITED
                || errorType == WechatVirtualPaymentErrorType.UNKNOWN;
    }

    /** 生成可持久化错误码。 */
    private String errorCode(WechatVirtualPaymentResult result) {
        return result.errorCode() == null ? result.errorType().name() : String.valueOf(result.errorCode());
    }

    /** 限制远端失败原因长度。 */
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
    private boolean ownsLease(PointGiftOrderEntity order, String executionLeaseToken) {
        return executionLeaseToken.equals(order.getExecutionLeaseToken());
    }
}
