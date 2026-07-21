package com.jxc.wefolio.service.payment;

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

    /** 会话提交后恢复任务，异常只记录且不改变已提交会话。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(MaintainerWechatSessionAvailableEvent event) {
        try {
            pointDebitTaskService.ensureActiveTaskAfterSessionRefresh(event.userId());
        } catch (Exception exception) {
            log.error("维护者会话提交后恢复待扣任务失败: userId={}", event.userId(), exception);
        }
    }
}
