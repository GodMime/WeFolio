package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.model.WorkStorageBillingModels.BillingCalculation;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.BillingRule;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 作品存储月费纯计算器。
 */
@Service
public class WorkStorageBillingCalculator {

    /** 一个 MB 的字节数 */
    public static final long BYTES_PER_MB = 1024L * 1024L;

    /** 非法规则消息 */
    private static final String INVALID_RULE_MESSAGE = "作品存储积分规则非法";

    /**
     * 校验积分规则。
     *
     * @param rule 积分规则
     */
    public void validateRule(BillingRule rule) {
        if (rule == null || rule.id() == null || rule.ruleCode() == null
                || rule.unitCount() <= 0 || rule.pointsValue() <= 0) {
            throw new IllegalArgumentException(INVALID_RULE_MESSAGE);
        }
        Math.multiplyExact((long) rule.unitCount(), BYTES_PER_MB);
    }

    /**
     * 按完整字节数计算费用。
     *
     * @param totalFileSizeBytes 总字节数
     * @param rule 积分规则
     * @return 计算结果
     */
    public BillingCalculation calculate(long totalFileSizeBytes, BillingRule rule) {
        if (totalFileSizeBytes < 0) {
            throw new IllegalArgumentException("作品文件总大小不能为负数");
        }
        validateRule(rule);
        long unitBytes = Math.multiplyExact((long) rule.unitCount(), BYTES_PER_MB);
        long billedUnits = totalFileSizeBytes / unitBytes;
        long pointsDue = Math.multiplyExact(billedUnits, rule.pointsValue());
        return new BillingCalculation(unitBytes, billedUnits, pointsDue, formatDisplayMb(totalFileSizeBytes));
    }

    /**
     * 生成展示用 MB，非零值向下保留两位小数。
     *
     * @param totalFileSizeBytes 总字节数
     * @return MB 字符串
     */
    public String formatDisplayMb(long totalFileSizeBytes) {
        if (totalFileSizeBytes == 0) {
            return "0";
        }
        return BigDecimal.valueOf(totalFileSizeBytes)
                .divide(BigDecimal.valueOf(BYTES_PER_MB), 2, RoundingMode.FLOOR)
                .toPlainString();
    }
}
