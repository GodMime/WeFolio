package com.jxc.wefolio.job.task;

import com.jxc.wefolio.job.config.RechargeOrderCloseProperties;
import com.jxc.wefolio.job.service.RechargeOrderCloseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 过期充值订单关闭调度任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RechargeOrderCloseJob {

    /** 当前实例是否已有一轮任务在执行 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 过期订单关闭服务 */
    private final RechargeOrderCloseService service;

    /** 任务配置 */
    private final RechargeOrderCloseProperties properties;

    /**
     * 每分钟第 30 秒关闭已过期的待支付订单。
     */
    @Scheduled(
            cron = "${recharge-order-close.cron:30 * * * * ?}",
            zone = "${recharge-order-close.zone:Asia/Shanghai}"
    )
    public void execute() {
        if (!properties.isEnabled()) {
            log.debug("过期充值订单关闭任务未启用，跳过本轮执行");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("上一轮过期充值订单关闭任务尚未结束，跳过本轮执行");
            return;
        }
        try {
            service.closeExpiredOrders();
        } finally {
            running.set(false);
        }
    }
}
