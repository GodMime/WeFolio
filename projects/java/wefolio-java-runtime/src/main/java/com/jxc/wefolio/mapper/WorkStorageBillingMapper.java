package com.jxc.wefolio.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 作品存储月度账单 Mapper。
 */
@Mapper
public interface WorkStorageBillingMapper {

    /** 查询用户指定账期的现有账单。 */
    @Select("""
            SELECT billing_status AS status,
                   points_due AS pointsDue,
                   points_deducted AS pointsDeducted
              FROM wf_work_storage_monthly_bill
             WHERE user_id = #{userId}
               AND billing_month = #{billingMonth}
               AND deleted = 0
             LIMIT 1
            """)
    ExistingBill findBill(
            @Param("userId") Long userId,
            @Param("billingMonth") LocalDate billingMonth
    );

    /** 汇总用户当前有效作品数量与文件体积。 */
    @Select("""
            SELECT COUNT(*) AS workCount,
                   COALESCE(SUM(COALESCE(file_size, 0)), 0) AS totalBytes
              FROM wf_work
             WHERE user_id = #{userId}
               AND deleted = 0
            """)
    StorageAggregate loadStorage(@Param("userId") Long userId);

    /** 查询指定时间生效的作品存储计费规则。 */
    @Select("""
            SELECT id,
                   unit_count AS unitCount,
                   points_value AS pointsValue
              FROM wf_point_rule
             WHERE scene_code = #{sceneCode}
               AND status = #{status}
               AND deleted = 0
               AND effective_from <= #{effectiveAt}
               AND (effective_to IS NULL OR effective_to > #{effectiveAt})
             ORDER BY rule_version DESC
             LIMIT 1
            """)
    BillingRule loadRule(
            @Param("sceneCode") String sceneCode,
            @Param("status") String status,
            @Param("effectiveAt") LocalDateTime effectiveAt
    );

    /** 幂等创建月度作品存储账单。 */
    @Insert("""
            INSERT IGNORE INTO wf_work_storage_monthly_bill (
              user_id, billing_month, work_count, total_file_size_bytes,
              points_due, points_deducted, points_shortfall,
              balance_before, balance_after, point_transaction_id,
              billing_status, remark, processed_at,
              created_at, updated_at, deleted, version
            ) VALUES (
              #{bill.userId}, #{bill.billingMonth}, #{bill.workCount}, #{bill.totalBytes},
              #{bill.pointsDue}, 0, #{bill.pointsDue},
              #{bill.balanceBefore}, #{bill.balanceBefore}, NULL,
              #{bill.status}, #{bill.remark}, CURRENT_TIMESTAMP(3),
              CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 0, 0
            )
            """)
    int insertBill(@Param("bill") NewBill bill);

    /** 将已完成扣除的账单更新为已收费。 */
    @Update("""
            UPDATE wf_work_storage_monthly_bill
               SET points_deducted = #{pointsDeducted},
                   points_shortfall = 0,
                   balance_after = #{balanceAfter},
                   point_transaction_id = #{transactionId},
                   billing_status = #{status},
                   processed_at = CURRENT_TIMESTAMP(3),
                   updated_at = CURRENT_TIMESTAMP(3),
                   version = version + 1
             WHERE user_id = #{userId}
               AND billing_month = #{billingMonth}
               AND deleted = 0
            """)
    int completeBill(
            @Param("userId") Long userId,
            @Param("billingMonth") LocalDate billingMonth,
            @Param("pointsDeducted") long pointsDeducted,
            @Param("balanceAfter") long balanceAfter,
            @Param("transactionId") Long transactionId,
            @Param("status") String status
    );

    /** 已存在账单摘要。 */
    record ExistingBill(String status, long pointsDue, long pointsDeducted) { }

    /** 用户作品存储汇总。 */
    record StorageAggregate(long workCount, long totalBytes) { }

    /** 作品存储计费规则摘要。 */
    record BillingRule(Long id, long unitCount, long pointsValue) { }

    /** 新账单写入参数。 */
    record NewBill(
            Long userId,
            LocalDate billingMonth,
            long workCount,
            long totalBytes,
            long pointsDue,
            long balanceBefore,
            String status,
            String remark
    ) { }
}
