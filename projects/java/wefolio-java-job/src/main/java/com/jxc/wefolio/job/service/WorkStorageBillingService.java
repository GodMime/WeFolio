package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.WorkStorageBillingProperties;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.BillingRule;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.BillingRunSummary;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.BillingStatus;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.SettlementResult;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.UserStorageAggregate;
import com.jxc.wefolio.job.repo.WorkStorageBillingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 作品存储月度结算批处理服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkStorageBillingService {

    /** 规则不存在或未启用消息 */
    private static final String MISSING_RULE_MESSAGE = "作品存储积分规则不存在或未启用";

    /** 账期末最后一毫秒 */
    private static final LocalTime BILLING_MONTH_LAST_MILLISECOND =
            LocalTime.of(23, 59, 59, 999_000_000);

    /** 结算仓储 */
    private final WorkStorageBillingRepository repository;

    /** 用户账期同步锁服务 */
    private final WorkStorageBillingUserLockService lockService;

    /** 计费计算器 */
    private final WorkStorageBillingCalculator calculator;

    /** 批处理配置 */
    private final WorkStorageBillingProperties properties;

    /**
     * 执行指定账期结算。
     *
     * @param billingMonth 账期首日
     * @param executionId 执行 ID
     * @return 任务汇总
     */
    public BillingRunSummary run(LocalDate billingMonth, String executionId) {
        LocalDateTime effectiveAt = billingMonth
                .withDayOfMonth(billingMonth.lengthOfMonth())
                .atTime(BILLING_MONTH_LAST_MILLISECOND);
        BillingRule rule = repository.findActiveRule(effectiveAt);
        if (rule == null) {
            throw new IllegalStateException(MISSING_RULE_MESSAGE);
        }
        calculator.validateRule(rule);
        int batchSize = normalizedBatchSize();
        long cursor = 0L;
        long scanned = 0L;
        long charged = 0L;
        long partial = 0L;
        long noCharge = 0L;
        long skipped = 0L;
        long failed = 0L;
        long pointsDue = 0L;
        long pointsDeducted = 0L;
        long totalFileSizeBytes = 0L;

        log.info("作品存储月度结算开始: executionId={}, billingMonth={}", executionId, billingMonth);
        while (true) {
            List<UserStorageAggregate> aggregates = repository.findUnbilledUserAggregates(
                    billingMonth, cursor, batchSize);
            if (aggregates.isEmpty()) {
                break;
            }
            for (UserStorageAggregate aggregate : aggregates) {
                cursor = aggregate.userId();
                scanned = Math.addExact(scanned, 1L);
                totalFileSizeBytes = Math.addExact(totalFileSizeBytes, aggregate.totalFileSizeBytes());
                try {
                    SettlementResult result = lockService.settleWithLock(rule, billingMonth, aggregate);
                    if (result.skipped()) {
                        skipped = Math.addExact(skipped, 1L);
                        continue;
                    }
                    if (result.status() == BillingStatus.CHARGED) {
                        charged = Math.addExact(charged, 1L);
                    } else if (result.status() == BillingStatus.PARTIAL) {
                        partial = Math.addExact(partial, 1L);
                    } else if (result.status() == BillingStatus.NO_CHARGE) {
                        noCharge = Math.addExact(noCharge, 1L);
                    }
                    pointsDue = Math.addExact(pointsDue, result.pointsDue());
                    pointsDeducted = Math.addExact(pointsDeducted, result.pointsDeducted());
                } catch (RuntimeException exception) {
                    failed = Math.addExact(failed, 1L);
                    log.error("作品存储单用户结算失败: executionId={}, billingMonth={}, userId={}",
                            executionId, billingMonth, aggregate.userId(), exception);
                }
            }
        }
        BillingRunSummary summary = new BillingRunSummary(
                billingMonth, scanned, charged, partial, noCharge, skipped, failed,
                pointsDue, pointsDeducted, totalFileSizeBytes);
        if (failed > 0) {
            log.error("作品存储月度结算结束且存在失败: executionId={}, summary={}", executionId, summary);
        } else {
            log.info("作品存储月度结算结束: executionId={}, summary={}", executionId, summary);
        }
        return summary;
    }

    private int normalizedBatchSize() {
        if (properties.getBatchSize() <= 0) {
            throw new IllegalArgumentException("作品存储结算批大小必须大于 0");
        }
        return properties.getBatchSize();
    }
}
