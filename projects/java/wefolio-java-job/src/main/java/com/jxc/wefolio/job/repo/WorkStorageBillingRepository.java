package com.jxc.wefolio.job.repo;

import com.jxc.wefolio.job.model.WorkStorageBillingModels.UserStorageAggregate;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 作品存储月度结算扫描仓储。
 *
 * <p>job 只读取待结算用户；账单、积分账户、积分流水和消息均由 runtime 写入。</p>
 */
@Repository
@RequiredArgsConstructor
public class WorkStorageBillingRepository {

    /** 启用状态。 */
    private static final String ACTIVE_STATUS = "ACTIVE";

    /** JDBC 查询模板。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 按用户 ID 游标查询尚未结算的有效用户作品聚合。
     *
     * @param billingMonth 账期
     * @param afterUserId 起始用户 ID，不包含
     * @param limit 批大小
     * @return 聚合结果
     */
    public List<UserStorageAggregate> findUnbilledUserAggregates(
            LocalDate billingMonth,
            long afterUserId,
            int limit
    ) {
        String sql = """
                SELECT u.id AS user_id,
                       COUNT(w.id) AS work_count,
                       COALESCE(SUM(COALESCE(w.file_size, 0)), 0) AS total_file_size_bytes
                FROM wf_user u
                LEFT JOIN wf_work w
                  ON w.user_id = u.id AND w.deleted = 0
                WHERE u.status = ?
                  AND u.deleted = 0
                  AND u.id > ?
                  AND NOT EXISTS (
                    SELECT 1
                    FROM wf_work_storage_monthly_bill b
                    WHERE b.user_id = u.id
                      AND b.billing_month = ?
                      AND b.deleted = 0
                  )
                GROUP BY u.id
                ORDER BY u.id
                LIMIT ?
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new UserStorageAggregate(
                rs.getLong("user_id"),
                rs.getLong("work_count"),
                rs.getLong("total_file_size_bytes")
        ), ACTIVE_STATUS, afterUserId, billingMonth, limit);
    }
}
