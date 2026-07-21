package com.jxc.wefolio.job.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 作品存储月度结算内部模型。
 */
public final class WorkStorageBillingModels {

    private WorkStorageBillingModels() {
    }

    /**
     * 生效积分规则。
     *
     * @param id 规则 ID
     * @param ruleCode 规则编码
     * @param ruleVersion 规则版本
     * @param unitCount 每个计费单位包含的 MB 数
     * @param pointsValue 每个单位积分值
     */
    public record BillingRule(Long id, String ruleCode, int ruleVersion, int unitCount, long pointsValue) {
    }

    /**
     * 用户作品聚合结果。
     *
     * @param userId 用户 ID
     * @param workCount 作品数
     * @param totalFileSizeBytes 总字节数
     */
    public record UserStorageAggregate(long userId, long workCount, long totalFileSizeBytes) {
    }

    /**
     * 计费计算结果。
     *
     * @param unitBytes 单位字节数
     * @param billedUnits 计费单位数
     * @param pointsDue 应扣积分
     * @param displayMb 展示用 MB
     */
    public record BillingCalculation(long unitBytes, long billedUnits, long pointsDue, String displayMb) {
    }

    /**
     * 积分账户锁定快照。
     *
     * @param id 账户 ID
     * @param balance 当前余额
     */
    public record PointAccount(long id, long balance) {
    }

    /**
     * 积分流水写入模型。
     *
     * @param billId 月度账单 ID
     * @param accountId 积分账户 ID
     * @param userId 用户 ID
     * @param ruleId 规则 ID
     * @param pointsChange 积分变动负数值
     * @param balanceBefore 扣分前余额
     * @param balanceAfter 扣分后余额
     * @param calculationSnapshot 完整计算快照 JSON
     * @param idempotencyKey 流水幂等键
     * @param remark 流水备注
     * @param occurredAt 发生时间
     */
    public record PointTransactionWrite(
            long billId,
            long accountId,
            long userId,
            long ruleId,
            long pointsChange,
            long balanceBefore,
            long balanceAfter,
            String calculationSnapshot,
            String idempotencyKey,
            String remark,
            LocalDateTime occurredAt
    ) {
    }

    /**
     * 低余额系统消息写入模型。
     *
     * @param userId 用户 ID
     * @param pointTransactionId 积分流水 ID
     * @param idempotencyKey 消息幂等键
     * @param content 消息正文
     * @param createdAt 创建时间
     */
    public record SystemMessageWrite(
            long userId,
            long pointTransactionId,
            String idempotencyKey,
            String content,
            LocalDateTime createdAt
    ) {
    }

    /** 账单状态 */
    public enum BillingStatus {
        NO_CHARGE,
        CHARGED,
        PARTIAL
    }

    /**
     * 月度账单完成模型。
     *
     * @param billId 账单 ID
     * @param status 账单状态
     * @param pointsDue 应扣积分
     * @param pointsDeducted 实扣积分
     * @param pointsShortfall 欠扣积分
     * @param balanceBefore 扣分前余额
     * @param balanceAfter 扣分后余额
     * @param pointTransactionId 积分流水 ID，可为空
     * @param remark 计费备注
     * @param processedAt 完成时间
     */
    public record BillCompletion(
            long billId,
            BillingStatus status,
            long pointsDue,
            long pointsDeducted,
            long pointsShortfall,
            long balanceBefore,
            long balanceAfter,
            Long pointTransactionId,
            String remark,
            LocalDateTime processedAt
    ) {
    }

    /**
     * 单用户结算结果。
     *
     * @param status 账单状态，跳过时为空
     * @param skipped 是否幂等跳过
     * @param pointsDue 应扣积分
     * @param pointsDeducted 实扣积分
     * @param totalFileSizeBytes 总字节数
     */
    public record SettlementResult(
            BillingStatus status,
            boolean skipped,
            long pointsDue,
            long pointsDeducted,
            long totalFileSizeBytes
    ) {

        /** 创建幂等跳过结果。 */
        public static SettlementResult skipped(long totalFileSizeBytes) {
            return new SettlementResult(null, true, 0, 0, totalFileSizeBytes);
        }
    }

    /**
     * 任务汇总。
     *
     * @param billingMonth 账期
     * @param scanned 扫描用户数
     * @param charged 足额数
     * @param partial 部分扣除数
     * @param noCharge 无需扣分数
     * @param skipped 幂等跳过数
     * @param failed 失败数
     * @param pointsDue 应扣总积分
     * @param pointsDeducted 实扣总积分
     * @param totalFileSizeBytes 总字节数
     */
    public record BillingRunSummary(
            LocalDate billingMonth,
            long scanned,
            long charged,
            long partial,
            long noCharge,
            long skipped,
            long failed,
            long pointsDue,
            long pointsDeducted,
            long totalFileSizeBytes
    ) {
    }
}
