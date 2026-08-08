package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.dict.PointRuleStatusDict;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import com.jxc.wefolio.dto.WorkStorageBillingSettlementResponse;
import com.jxc.wefolio.entity.PointAccountEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.WorkStorageBillingMapper;
import com.jxc.wefolio.mapper.WorkStorageBillingMapper.BillingRule;
import com.jxc.wefolio.mapper.WorkStorageBillingMapper.ExistingBill;
import com.jxc.wefolio.mapper.WorkStorageBillingMapper.NewBill;
import com.jxc.wefolio.mapper.WorkStorageBillingMapper.StorageAggregate;
import com.jxc.wefolio.service.point.DebitCommand;
import com.jxc.wefolio.service.point.PointCommandService;
import com.jxc.wefolio.service.point.SystemDebitResult;
import com.jxc.wefolio.service.point.UserPointMutex;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Map;

/**
 * runtime 月度作品存储结算服务 — runtime 是月费账单与积分的唯一写入者。
 */
@Service
@RequiredArgsConstructor
public class WorkStorageBillingSettlementService {

    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final String STATUS_NO_CHARGE = "NO_CHARGE";
    private static final String STATUS_CHARGED = "CHARGED";
    private static final String STATUS_SKIPPED = "SKIPPED_NON_POSITIVE_BALANCE";

    private final WorkStorageBillingMapper workStorageBillingMapper;
    private final PointService pointService;
    private final PointCommandService pointCommandService;
    private final UserPointMutex userPointMutex;

    /** 在一个事务内重新查询规则、容量和余额并完成单用户账期结算。 */
    @Transactional(rollbackFor = Exception.class)
    public WorkStorageBillingSettlementResponse settle(Long userId, YearMonth billingMonth) {
        if (userId == null || billingMonth == null) {
            throw new BusinessException("用户与账期不能为空");
        }
        return userPointMutex.execute(userId, () -> settleLocked(userId, billingMonth));
    }

    /** 在当前 JVM 用户级互斥区间内执行完整账期结算。 */
    private WorkStorageBillingSettlementResponse settleLocked(Long userId, YearMonth billingMonth) {
        LocalDate month = billingMonth.atDay(1);
        ExistingBill existing = findBill(userId, month);
        if (existing != null) {
            return response(userId, billingMonth, existing.status(),
                    existing.pointsDue(), existing.pointsDeducted(), true);
        }
        StorageAggregate aggregate = loadStorage(userId);
        BillingRule rule = loadRule(billingMonth.atEndOfMonth().atTime(23, 59, 59));
        long unitBytes = Math.multiplyExact(rule.unitCount(), BYTES_PER_MB);
        long billedUnits = aggregate.totalBytes() / unitBytes;
        long pointsDue = Math.multiplyExact(billedUnits, rule.pointsValue());
        PointAccountEntity account = pointService.ensureAccount(userId);
        long balanceBefore = value(account.getBalance());
        String initialStatus = pointsDue == 0L ? STATUS_NO_CHARGE : STATUS_SKIPPED;
        int inserted = workStorageBillingMapper.insertBill(new NewBill(
                userId,
                month,
                aggregate.workCount(),
                aggregate.totalBytes(),
                pointsDue,
                balanceBefore,
                initialStatus,
                "runtime 月度存储结算"
        ));
        if (inserted != 1) {
            ExistingBill concurrent = findBill(userId, month);
            return response(userId, billingMonth, concurrent.status(),
                    concurrent.pointsDue(), concurrent.pointsDeducted(), true);
        }
        if (pointsDue == 0L || balanceBefore <= 0L) {
            return response(userId, billingMonth, initialStatus, pointsDue, 0L, false);
        }
        SystemDebitResult debit = pointCommandService.deductForSystem(new DebitCommand(
                userId,
                rule.id(),
                PointSceneCodeDict.MONTHLY_WORK_STORAGE.getCode(),
                "WORK_STORAGE_MONTHLY_BILL",
                userId + ":" + billingMonth,
                JSON.toJSONString(Map.of(
                        "billingMonth", billingMonth.toString(),
                        "workCount", aggregate.workCount(),
                        "totalFileSizeBytes", aggregate.totalBytes(),
                        "billedUnits", billedUnits,
                        "pointsDue", pointsDue)),
                "MONTHLY_WORK_STORAGE:" + billingMonth + ":" + userId,
                "作品存储月费",
                pointsDue));
        if (!debit.deducted()) {
            return response(userId, billingMonth, STATUS_SKIPPED, pointsDue, 0L, false);
        }
        workStorageBillingMapper.completeBill(
                userId,
                month,
                pointsDue,
                debit.mutation().balanceAfter(),
                debit.mutation().transactionId(),
                STATUS_CHARGED
        );
        return response(userId, billingMonth, STATUS_CHARGED, pointsDue, pointsDue, false);
    }

    /** 查询已存在账单。 */
    private ExistingBill findBill(Long userId, LocalDate month) {
        return workStorageBillingMapper.findBill(userId, month);
    }

    /** 查询用户当前作品容量。 */
    private StorageAggregate loadStorage(Long userId) {
        return workStorageBillingMapper.loadStorage(userId);
    }

    /** 查询账期末生效规则。 */
    private BillingRule loadRule(LocalDateTime effectiveAt) {
        BillingRule rule = workStorageBillingMapper.loadRule(
                PointSceneCodeDict.MONTHLY_WORK_STORAGE.getCode(),
                PointRuleStatusDict.ACTIVE.getCode(),
                effectiveAt);
        if (rule == null || rule.unitCount() <= 0L) {
            throw new BusinessException("作品存储积分规则不存在或非法");
        }
        return rule;
    }

    private WorkStorageBillingSettlementResponse response(
            Long userId, YearMonth month, String status, long due, long deducted, boolean idempotent) {
        return new WorkStorageBillingSettlementResponse(
                userId, month.toString(), status, due, deducted, idempotent);
    }

    private long value(Long number) { return number == null ? 0L : number; }

}
