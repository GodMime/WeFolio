package com.jxc.wefolio.service.payment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import com.jxc.wefolio.dict.PointGiftOrderStatusDict;
import com.jxc.wefolio.entity.PointGiftOrderEntity;
import com.jxc.wefolio.mapper.PointGiftOrderEntityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 赠送订单恢复与定时扫描器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointGiftOrderScanner {

    /** 单轮最多处理订单数。 */
    private static final int SCAN_LIMIT = 100;

    /** 当前 runtime 实例租约标识。 */
    private final String leaseOwner = "runtime-gift-" + UUID.randomUUID();

    /** 防止本实例扫描批次重叠。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final PointGiftOrderEntityMapper pointGiftOrderEntityMapper;
    private final PointGiftOrderProcessor pointGiftOrderProcessor;
    private final WechatVirtualPaymentProperties properties;

    /** 应用启动后恢复已到期赠送订单。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        scan();
    }

    /** 上一轮结束固定延迟后扫描。 */
    @Scheduled(fixedDelayString = "${wechat.virtual-payment.settlement.scan-interval:10s}")
    public void scheduledScan() {
        scan();
    }

    /** 执行一次非重叠扫描。 */
    public void scan() {
        if (!properties.isEnabled() || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            List<Long> orderIds = pointGiftOrderEntityMapper.selectList(
                            Wrappers.lambdaQuery(PointGiftOrderEntity.class)
                                    .select(PointGiftOrderEntity::getId)
                                    .in(PointGiftOrderEntity::getStatus,
                                            PointGiftOrderStatusDict.READY.getCode(),
                                            PointGiftOrderStatusDict.RETRY_WAIT.getCode())
                                    .le(PointGiftOrderEntity::getNextExecuteAt, LocalDateTime.now())
                                    .and(query -> query.isNull(PointGiftOrderEntity::getLeaseUntil)
                                            .or().lt(PointGiftOrderEntity::getLeaseUntil, LocalDateTime.now()))
                                    .orderByAsc(PointGiftOrderEntity::getNextExecuteAt)
                                    .last("LIMIT " + SCAN_LIMIT))
                    .stream()
                    .map(PointGiftOrderEntity::getId)
                    .toList();
            for (Long orderId : orderIds) {
                try {
                    pointGiftOrderProcessor.process(orderId, leaseOwner);
                } catch (RuntimeException exception) {
                    log.warn("赠送订单扫描处理失败 orderId={}", orderId, exception);
                }
            }
        } finally {
            running.set(false);
        }
    }
}
