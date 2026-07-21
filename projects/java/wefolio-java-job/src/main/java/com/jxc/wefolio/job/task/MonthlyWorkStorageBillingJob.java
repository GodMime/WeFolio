package com.jxc.wefolio.job.task;

import com.jxc.wefolio.job.config.WorkStorageBillingProperties;
import com.jxc.wefolio.job.service.WorkStorageBillingExecutionCoordinator;
import com.jxc.wefolio.job.service.WorkStorageBillingExecutionCoordinator.TriggerSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * 每月作品存储积分结算调度器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonthlyWorkStorageBillingJob {

    /** 异步执行协调器 */
    private final WorkStorageBillingExecutionCoordinator coordinator;

    /** 调度配置 */
    private final WorkStorageBillingProperties properties;

    /** 可替换时钟 */
    private final Clock clock;

    /**
     * 每月 1 日凌晨 2 点提交上一个自然月结算。
     */
    @Scheduled(
            cron = "${work-storage-billing.cron:0 0 2 1 * ?}",
            zone = "${work-storage-billing.zone:Asia/Shanghai}"
    )
    public void execute() {
        if (!properties.isEnabled()) {
            log.info("作品存储月度结算调度已关闭");
            return;
        }
        ZoneId zone = ZoneId.of(properties.getZone());
        LocalDate billingMonth = YearMonth.now(clock.withZone(zone)).minusMonths(1).atDay(1);
        coordinator.submit(billingMonth, TriggerSource.SCHEDULED);
    }
}
