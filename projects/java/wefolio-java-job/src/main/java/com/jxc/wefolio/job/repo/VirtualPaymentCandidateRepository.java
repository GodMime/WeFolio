package com.jxc.wefolio.job.repo;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 微信虚拟支付候选任务只读仓储。
 */
@Repository
@RequiredArgsConstructor
public class VirtualPaymentCandidateRepository {

    /** 共享数据库只读查询模板。 */
    private final JdbcTemplate jdbcTemplate;

    /** 查询到期且没有有效租约的赠送订单 ID。 */
    public List<Long> findDueGiftOrderIds(int limit) {
        String sql = """
                SELECT id
                  FROM wf_point_gift_order
                 WHERE status IN ('READY', 'RETRY_WAIT')
                   AND next_execute_at <= CURRENT_TIMESTAMP(3)
                   AND (lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP(3))
                   AND deleted = 0
                 ORDER BY next_execute_at ASC, id ASC
                 LIMIT ?
                """;
        return jdbcTemplate.queryForList(sql, Long.class, limit);
    }

    /** 查询到期待处理或运行租约已过期的扣币任务 ID。 */
    public List<Long> findDueDebitTaskIds(int limit) {
        String sql = """
                SELECT id
                  FROM wf_point_debit_task
                 WHERE active_flag = 1
                   AND (
                        (status IN ('WAITING', 'RETRY_WAIT')
                         AND next_execute_at <= CURRENT_TIMESTAMP(3))
                        OR (status = 'RUNNING'
                            AND lease_until < CURRENT_TIMESTAMP(3))
                   )
                   AND deleted = 0
                 ORDER BY next_execute_at ASC, id ASC
                 LIMIT ?
                """;
        return jdbcTemplate.queryForList(sql, Long.class, limit);
    }

    /** 查询有待扣金额但缺少活动扣币任务的用户 ID。 */
    public List<Long> findUsersMissingActiveDebitTasks(int limit) {
        String sql = """
                SELECT account.user_id
                  FROM wf_point_account account
                 WHERE account.pending_debit > 0
                   AND account.deleted = 0
                   AND NOT EXISTS (
                       SELECT 1
                         FROM wf_point_debit_task task
                        WHERE task.user_id = account.user_id
                          AND task.active_flag = 1
                          AND task.deleted = 0
                   )
                 ORDER BY account.id ASC
                 LIMIT ?
                """;
        return jdbcTemplate.queryForList(sql, Long.class, limit);
    }
}
