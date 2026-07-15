package com.jxc.wefolio.job.repo;

import com.jxc.wefolio.job.model.WorkStorageBillingModels.BillingCalculation;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.BillingRule;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.BillingStatus;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.BillCompletion;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.PointAccount;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.PointTransactionWrite;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.SystemMessageWrite;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.UserStorageAggregate;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 作品存储月度结算仓储，集中维护生产 SQL。
 */
@Repository
@RequiredArgsConstructor
public class WorkStorageBillingRepository {

    /** 月度存储规则场景 */
    private static final String RULE_SCENE = "MONTHLY_WORK_STORAGE";

    /** 启用状态 */
    private static final String ACTIVE_STATUS = "ACTIVE";

    /** 消耗流水类型 */
    private static final String TRANSACTION_TYPE_CONSUMPTION = "CONSUMPTION";

    /** 月度账单流水业务类型 */
    private static final String BILLING_BUSINESS_TYPE = "WORK_STORAGE_MONTHLY_BILL";

    /** 低余额消息固定契约 */
    private static final String LOW_BALANCE_MESSAGE_TYPE = "POINT_LOW_BALANCE";
    private static final String POINT_CATEGORY = "POINT";
    private static final String UNREAD_STATUS = "UNREAD";
    private static final String LOW_BALANCE_TITLE = "积分余额不足";
    private static final String POINT_RECHARGE_ACTION = "POINT_RECHARGE";
    private static final String POINT_RECHARGE_URL = "/pages/points/points";
    private static final String POINT_TRANSACTION_BIZ_TYPE = "POINT_TRANSACTION";

    /** JDBC 操作模板 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 查询账期最后一刻生效的规则。
     *
     * @param effectiveAt 生效判断时点
     * @return 生效规则，不存在时为空
     */
    public BillingRule findActiveRule(LocalDateTime effectiveAt) {
        String sql = """
                SELECT id, rule_code, rule_version, unit_count, points_value
                FROM wf_point_rule
                WHERE scene_code = ?
                  AND status = ?
                  AND deleted = 0
                  AND effective_from <= ?
                  AND (effective_to IS NULL OR effective_to > ?)
                ORDER BY rule_version DESC
                LIMIT 1
                """;
        List<BillingRule> rules = jdbcTemplate.query(sql, (rs, rowNum) -> new BillingRule(
                rs.getLong("id"),
                rs.getString("rule_code"),
                rs.getInt("rule_version"),
                rs.getInt("unit_count"),
                rs.getLong("points_value")
        ), RULE_SCENE, ACTIVE_STATUS, effectiveAt, effectiveAt);
        return rules.isEmpty() ? null : rules.getFirst();
    }

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

    /**
     * 通过唯一键抢占账单。
     *
     * <p>先插入满足 CHECK 约束的占位账单，再由调用方在同一事务内完成积分处理并调用
     * {@link #completeBill(BillCompletion)} 更新为最终状态；任一步骤失败时整笔事务回滚。</p>
     *
     * @return 新账单 ID；唯一键已存在时返回空
     */
    public Long tryInsertBill(
            long userId,
            LocalDate billingMonth,
            UserStorageAggregate aggregate,
            BillingCalculation calculation,
            LocalDateTime now
    ) {
        String sql = """
                INSERT IGNORE INTO wf_work_storage_monthly_bill (
                  user_id, billing_month, work_count, total_file_size_bytes,
                  points_due, points_deducted, points_shortfall,
                  balance_before, balance_after, point_transaction_id,
                  billing_status, remark, processed_at,
                  created_at, updated_at, deleted, version
                ) VALUES (?, ?, ?, ?, ?, 0, ?, 0, 0, NULL, ?, '', ?, ?, ?, 0, 0)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        int inserted = jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setDate(2, Date.valueOf(billingMonth));
            statement.setLong(3, aggregate.workCount());
            statement.setLong(4, aggregate.totalFileSizeBytes());
            statement.setLong(5, calculation.pointsDue());
            statement.setLong(6, calculation.pointsDue());
            statement.setString(7, calculation.pointsDue() == 0
                    ? BillingStatus.NO_CHARGE.name()
                    : BillingStatus.PARTIAL.name());
            statement.setTimestamp(8, Timestamp.valueOf(now));
            statement.setTimestamp(9, Timestamp.valueOf(now));
            statement.setTimestamp(10, Timestamp.valueOf(now));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return inserted == 1 && key != null ? key.longValue() : null;
    }

    /** 确保零余额积分账户存在。 */
    public void ensurePointAccount(long userId, LocalDateTime now) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO wf_point_account (
                      user_id, balance, total_recharged, total_gifted, total_consumed,
                      version, created_at, updated_at, deleted
                    ) VALUES (?, 0, 0, 0, 0, 0, ?, ?, 0)
                    """, userId, now, now);
        } catch (DuplicateKeyException ignored) {
            // 并发创建时复用唯一键胜出的账户，随后统一通过 SELECT FOR UPDATE 锁定。
        }
    }

    /** 使用行锁读取积分账户。 */
    public PointAccount lockPointAccount(long userId) {
        List<PointAccount> accounts = jdbcTemplate.query("""
                SELECT id, balance
                FROM wf_point_account
                WHERE user_id = ? AND deleted = 0
                FOR UPDATE
                """, (rs, rowNum) -> new PointAccount(rs.getLong("id"), rs.getLong("balance")), userId);
        return accounts.isEmpty() ? null : accounts.getFirst();
    }

    /** 原子更新积分余额和累计消耗。 */
    public boolean updatePointAccount(
            long accountId,
            long points,
            long expectedBalance,
            LocalDateTime now
    ) {
        int updated = jdbcTemplate.update("""
                UPDATE wf_point_account
                SET balance = balance - ?,
                    total_consumed = total_consumed + ?,
                    version = version + 1,
                    updated_at = ?
                WHERE id = ?
                  AND balance = ?
                  AND balance >= ?
                  AND deleted = 0
                """, points, points, now, accountId, expectedBalance, points);
        return updated == 1;
    }

    /** 写入不可变积分流水并返回 ID。 */
    public long insertPointTransaction(PointTransactionWrite write) {
        String sql = """
                INSERT INTO wf_point_transaction (
                  account_id, user_id, rule_id, transaction_type, scene_code,
                  points_change, balance_before, balance_after,
                  business_type, business_id, calculation_snapshot,
                  idempotency_key, remark, occurred_at,
                  created_at, updated_at, deleted, version
                ) VALUES (?, ?, ?, ?, ?,
                          ?, ?, ?, ?, ?, CAST(? AS JSON),
                          ?, ?, ?, ?, ?, 0, 0)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, write.accountId());
            statement.setLong(2, write.userId());
            statement.setLong(3, write.ruleId());
            statement.setString(4, TRANSACTION_TYPE_CONSUMPTION);
            statement.setString(5, RULE_SCENE);
            statement.setLong(6, write.pointsChange());
            statement.setLong(7, write.balanceBefore());
            statement.setLong(8, write.balanceAfter());
            statement.setString(9, BILLING_BUSINESS_TYPE);
            statement.setString(10, Long.toString(write.billId()));
            statement.setString(11, write.calculationSnapshot());
            statement.setString(12, write.idempotencyKey());
            statement.setString(13, write.remark());
            statement.setTimestamp(14, Timestamp.valueOf(write.occurredAt()));
            statement.setTimestamp(15, Timestamp.valueOf(write.occurredAt()));
            statement.setTimestamp(16, Timestamp.valueOf(write.occurredAt()));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("积分流水写入后未返回主键");
        }
        return key.longValue();
    }

    /** 写入低余额系统消息。 */
    public void insertLowBalanceMessage(SystemMessageWrite write) {
        jdbcTemplate.update("""
                INSERT INTO wf_system_message (
                  user_id, message_type, category, read_status, title, content,
                  action_type, action_url, biz_type, biz_id, idempotency_key,
                  created_at, updated_at, deleted, version
                ) VALUES (?, ?, ?, ?, ?, ?,
                          ?, ?, ?, ?, ?,
                          ?, ?, 0, 0)
                """, write.userId(), LOW_BALANCE_MESSAGE_TYPE, POINT_CATEGORY, UNREAD_STATUS,
                LOW_BALANCE_TITLE, write.content(), POINT_RECHARGE_ACTION, POINT_RECHARGE_URL,
                POINT_TRANSACTION_BIZ_TYPE, write.pointTransactionId(), write.idempotencyKey(),
                write.createdAt(), write.createdAt());
    }

    /** 完成月度账单。 */
    public void completeBill(BillCompletion completion) {
        int updated = jdbcTemplate.update("""
                UPDATE wf_work_storage_monthly_bill
                SET points_due = ?,
                    points_deducted = ?,
                    points_shortfall = ?,
                    balance_before = ?,
                    balance_after = ?,
                    point_transaction_id = ?,
                    billing_status = ?,
                    remark = ?,
                    processed_at = ?,
                    updated_at = ?,
                    version = version + 1
                WHERE id = ? AND deleted = 0
                """, completion.pointsDue(), completion.pointsDeducted(), completion.pointsShortfall(),
                completion.balanceBefore(), completion.balanceAfter(), completion.pointTransactionId(),
                completion.status().name(), completion.remark(), completion.processedAt(),
                completion.processedAt(), completion.billId());
        if (updated != 1) {
            throw new IllegalStateException("作品存储月度账单完成失败");
        }
    }
}
