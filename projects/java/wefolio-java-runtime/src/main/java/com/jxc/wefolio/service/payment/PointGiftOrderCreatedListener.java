package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 来源业务提交后的赠送首调监听器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointGiftOrderCreatedListener {

    /** 首调执行器标识。 */
    private static final String IMMEDIATE_LEASE_OWNER = "runtime-gift-immediate";

    private final PointGiftOrderProcessor pointGiftOrderProcessor;
    private final WechatVirtualPaymentProperties properties;

    /** 提交后在原请求线程逐条独立尝试赠送，失败不反向影响来源业务。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(PointGiftOrderCreatedEvent event) {
        if (!properties.isEnabled()) {
            return;
        }
        for (Long orderId : event.orderIds()) {
            try {
                pointGiftOrderProcessor.process(orderId, IMMEDIATE_LEASE_OWNER);
            } catch (RuntimeException exception) {
                log.warn("提交后赠送首调失败 orderId={}", orderId, exception);
            }
        }
    }
}
