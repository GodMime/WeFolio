package com.jxc.wefolio.job.service;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.BillingCalculation;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.BillingRule;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.BillingStatus;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.BillCompletion;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.PointAccount;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.PointTransactionWrite;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.SettlementResult;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.SystemMessageWrite;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.UserStorageAggregate;
import com.jxc.wefolio.job.repo.WorkStorageBillingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单用户作品存储结算事务服务。
 */
@Service
@RequiredArgsConstructor
public class WorkStorageBillingTransactionService {

    /** 流水幂等键账期格式 */
    private static final DateTimeFormatter IDEMPOTENCY_MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");

    /** 展示账期格式 */
    private static final DateTimeFormatter DISPLAY_MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 流水幂等键前缀 */
    private static final String TRANSACTION_IDEMPOTENCY_PREFIX = "WORK_STORAGE:";

    /** 低余额消息幂等键前缀 */
    private static final String LOW_BALANCE_IDEMPOTENCY_PREFIX = "POINT_LOW_BALANCE:";

    /** 低余额阈值 */
    private static final long LOW_BALANCE_THRESHOLD = 50L;

    /** 低余额消息正文 */
    private static final String LOW_BALANCE_CONTENT =
            "当前积分余额已低于 50，请及时充值，避免影响作品维护和客户访问。";

    /** 月度存储大小计算模式 */
    private static final String MONTHLY_STORAGE_CALC_MODE = "MONTHLY_STORAGE_SIZE";

    /** 结算仓储 */
    private final WorkStorageBillingRepository repository;

    /** 计费计算器 */
    private final WorkStorageBillingCalculator calculator;

    /** 结算时钟，固定使用配置时区 */
    private final Clock clock;

    /**
     * 在独立事务中结算单个用户。
     *
     * @param rule 积分规则
     * @param billingMonth 账期首日
     * @param aggregate 用户作品聚合
     * @return 结算结果
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public SettlementResult settleUser(
            BillingRule rule,
            LocalDate billingMonth,
            UserStorageAggregate aggregate
    ) {
        BillingCalculation calculation = calculator.calculate(aggregate.totalFileSizeBytes(), rule);
        LocalDateTime now = LocalDateTime.now(clock);
        Long billId = repository.tryInsertBill(
                aggregate.userId(), billingMonth, aggregate, calculation, now);
        if (billId == null) {
            return SettlementResult.skipped(aggregate.totalFileSizeBytes());
        }
        if (calculation.pointsDue() == 0) {
            String remark = noChargeRemark(billingMonth, calculation.displayMb());
            repository.completeBill(new BillCompletion(
                    billId, BillingStatus.NO_CHARGE, 0, 0, 0,
                    0, 0, null, remark, now));
            return new SettlementResult(
                    BillingStatus.NO_CHARGE, false, 0, 0, aggregate.totalFileSizeBytes());
        }

        repository.ensurePointAccount(aggregate.userId(), now);
        PointAccount account = repository.lockPointAccount(aggregate.userId());
        if (account == null) {
            throw new IllegalStateException("积分账户创建后不存在");
        }
        long balanceBefore = account.balance();
        long pointsDeducted = Math.min(calculation.pointsDue(), balanceBefore);
        long pointsShortfall = calculation.pointsDue() - pointsDeducted;
        long balanceAfter = balanceBefore - pointsDeducted;
        BillingStatus status = pointsShortfall == 0 ? BillingStatus.CHARGED : BillingStatus.PARTIAL;
        String remark = chargeRemark(
                billingMonth, calculation.displayMb(), calculation.pointsDue(), pointsDeducted, status);
        Long transactionId = null;
        if (pointsDeducted > 0) {
            if (!repository.updatePointAccount(account.id(), pointsDeducted, balanceBefore, now)) {
                throw new IllegalStateException("积分账户扣减失败");
            }
            transactionId = repository.insertPointTransaction(new PointTransactionWrite(
                    billId,
                    account.id(),
                    aggregate.userId(),
                    rule.id(),
                    -pointsDeducted,
                    balanceBefore,
                    balanceAfter,
                    snapshot(rule, billingMonth, aggregate, calculation, pointsDeducted, pointsShortfall),
                    transactionIdempotencyKey(billingMonth, aggregate.userId()),
                    remark,
                    now
            ));
            if (balanceAfter < LOW_BALANCE_THRESHOLD) {
                repository.insertLowBalanceMessage(new SystemMessageWrite(
                        aggregate.userId(),
                        transactionId,
                        LOW_BALANCE_IDEMPOTENCY_PREFIX + transactionId,
                        LOW_BALANCE_CONTENT,
                        now
                ));
            }
        }
        repository.completeBill(new BillCompletion(
                billId, status, calculation.pointsDue(), pointsDeducted, pointsShortfall,
                balanceBefore, balanceAfter, transactionId, remark, now));
        return new SettlementResult(
                status, false, calculation.pointsDue(), pointsDeducted, aggregate.totalFileSizeBytes());
    }

    private String snapshot(
            BillingRule rule,
            LocalDate billingMonth,
            UserStorageAggregate aggregate,
            BillingCalculation calculation,
            long pointsDeducted,
            long pointsShortfall
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("billingMonth", DISPLAY_MONTH_FORMAT.format(billingMonth));
        snapshot.put("calcMode", MONTHLY_STORAGE_CALC_MODE);
        snapshot.put("points", calculation.pointsDue());
        snapshot.put("billedUnits", calculation.billedUnits());
        snapshot.put("ruleId", rule.id());
        snapshot.put("ruleCode", rule.ruleCode());
        snapshot.put("ruleVersion", rule.ruleVersion());
        snapshot.put("unitCount", rule.unitCount());
        snapshot.put("unitBytes", calculation.unitBytes());
        snapshot.put("pointsValue", rule.pointsValue());
        snapshot.put("workCount", aggregate.workCount());
        snapshot.put("totalFileSizeBytes", aggregate.totalFileSizeBytes());
        snapshot.put("displayMb", calculation.displayMb());
        snapshot.put("pointsDue", calculation.pointsDue());
        snapshot.put("pointsDeducted", pointsDeducted);
        snapshot.put("pointsShortfall", pointsShortfall);
        return JSON.toJSONString(snapshot);
    }

    private String transactionIdempotencyKey(LocalDate billingMonth, long userId) {
        return TRANSACTION_IDEMPOTENCY_PREFIX
                + IDEMPOTENCY_MONTH_FORMAT.format(billingMonth)
                + ":"
                + userId;
    }

    private String noChargeRemark(LocalDate billingMonth, String displayMb) {
        return DISPLAY_MONTH_FORMAT.format(billingMonth)
                + " 作品总大小 " + displayMb + " MB，无需扣分";
    }

    private String chargeRemark(
            LocalDate billingMonth,
            String displayMb,
            long pointsDue,
            long pointsDeducted,
            BillingStatus status
    ) {
        String prefix = DISPLAY_MONTH_FORMAT.format(billingMonth)
                + " 作品总大小 " + displayMb + " MB，应扣 " + pointsDue + " 积分，";
        if (status == BillingStatus.PARTIAL) {
            return prefix + "积分不足，实际扣除 " + pointsDeducted + " 积分";
        }
        return prefix + "实际扣除 " + pointsDeducted + " 积分";
    }
}
