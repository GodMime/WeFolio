package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.WorkStorageBillingProperties;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.BillingRunSummary;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.UserStorageAggregate;
import com.jxc.wefolio.job.repo.WorkStorageBillingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/** 作品存储月度结算批处理服务 — 只扫描用户并逐用户请求 runtime。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkStorageBillingService {

    private final WorkStorageBillingRepository repository;
    private final RuntimeWorkStorageBillingClient runtimeClient;
    private final WorkStorageBillingProperties properties;

    /** 扫描尚未结算用户并调用 runtime，job 不计算或写入积分。 */
    public BillingRunSummary run(LocalDate billingMonth, String executionId) {
        int batchSize = normalizedBatchSize();
        long cursor = 0L, scanned = 0L, charged = 0L, partial = 0L, noCharge = 0L, skipped = 0L, failed = 0L;
        long pointsDue = 0L, pointsDeducted = 0L, totalFileSizeBytes = 0L;
        while (true) {
            List<UserStorageAggregate> users = repository.findUnbilledUserAggregates(
                    billingMonth, cursor, batchSize);
            if (users.isEmpty()) break;
            for (UserStorageAggregate user : users) {
                cursor = user.userId();
                scanned++;
                totalFileSizeBytes = Math.addExact(totalFileSizeBytes, user.totalFileSizeBytes());
                try {
                    RuntimeWorkStorageBillingClient.SettlementResult result =
                            runtimeClient.settle(user.userId(), billingMonth);
                    pointsDue = Math.addExact(pointsDue, value(result.getPointsDue()));
                    pointsDeducted = Math.addExact(pointsDeducted, value(result.getPointsDeducted()));
                    if ("CHARGED".equals(result.getStatus())) charged++;
                    else if ("PARTIAL".equals(result.getStatus())) partial++;
                    else if ("NO_CHARGE".equals(result.getStatus())) noCharge++;
                    else skipped++;
                } catch (RuntimeException exception) {
                    failed++;
                    log.error("runtime 单用户月度结算失败 executionId={} billingMonth={} userId={}",
                            executionId, billingMonth, user.userId(), exception);
                }
            }
        }
        return new BillingRunSummary(billingMonth, scanned, charged, partial, noCharge, skipped, failed,
                pointsDue, pointsDeducted, totalFileSizeBytes);
    }

    private int normalizedBatchSize() {
        if (properties.getBatchSize() <= 0) {
            throw new IllegalArgumentException("作品存储结算批大小必须大于 0");
        }
        return properties.getBatchSize();
    }

    private long value(Long number) { return number == null ? 0L : number; }
}
