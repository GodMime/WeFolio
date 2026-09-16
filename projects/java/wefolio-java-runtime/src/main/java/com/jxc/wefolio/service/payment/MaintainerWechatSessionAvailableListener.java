package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.service.point.UserPointMutex;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 维护者微信会话可用监听器 — 会话提交后恢复仍有待扣的活动任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MaintainerWechatSessionAvailableListener {

    /** 扣币活动任务保障服务。 */
    private final PointDebitTaskService pointDebitTaskService;

    /** 已成功赠送的余额补查在新会话可用时提前恢复。 */
    private final PointGiftOrderTransactionService pointGiftOrderTransactionService;

    /** 唤醒与同一用户的执行器串行，避免旧会话执行结束后遗留等待状态。 */
    private final UserPointMutex userPointMutex;

    /** 同时恢复无待扣金额的退款账户。 */
    private final WechatAuthoritativeBalanceSyncService balanceSyncService;

    /** 会话提交后恢复任务，异常只记录且不改变已提交会话。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(MaintainerWechatSessionAvailableEvent event) {
        try {
            balanceSyncService.synchronizeStaleBalanceForUser(event.userId());
        } catch (Exception exception) {
            log.error("维护者会话提交后恢复微信余额失败: userId={}", event.userId(), exception);
        }
        try {
            // 余额同步在锁外自行加锁；这里只持锁等待两个独立唤醒事务提交。
            userPointMutex.execute(event.userId(), () -> {
                wakeTasks(event.userId());
                return null;
            });
        } catch (Exception exception) {
            log.error("维护者会话提交后获取任务唤醒锁失败: userId={}", event.userId(), exception);
        }
    }

    /** 两类任务分别提交和隔离异常，避免一类失败阻断另一类恢复。 */
    private void wakeTasks(Long userId) {
        try {
            pointDebitTaskService.ensureActiveTaskAfterSessionRefresh(userId);
        } catch (Exception exception) {
            log.error("维护者会话提交后恢复待扣任务失败: userId={}", userId, exception);
        }
        try {
            pointGiftOrderTransactionService.wakeBalanceConfirmationAfterSessionRefresh(userId);
        } catch (Exception exception) {
            log.error("维护者会话提交后恢复赠送余额补查失败: userId={}", userId, exception);
        }
    }
}
