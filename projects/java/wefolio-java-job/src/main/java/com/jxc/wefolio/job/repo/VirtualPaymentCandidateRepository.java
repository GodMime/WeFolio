package com.jxc.wefolio.job.repo;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 微信虚拟支付候选任务只读仓储。
 *
 * <p>任务状态字面量与 runtime 的 PointGiftOrderStatusDict、PointDebitTaskStatusDict 及两个 Mapper
 * 的领取条件保持一致；REFUNDED 和 AVAILABLE 分别对应 RechargeOrderStatusDict、MaintainerWechatSessionStatusDict。
 * job 不依赖 runtime 枚举，状态编码变更时须同步本仓储 SQL。</p>
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

    /** 查询到期充值核对候选；已支付单不再扫描，已确认收款的失败单仍可补偿。 */
    public List<Long> findDueRechargeOrderIds(long afterOrderId, int limit) {
        String sql = """
                SELECT recharge.id
                  FROM wf_recharge_order recharge
                 WHERE recharge.id > ?
                   AND recharge.deleted = 0
                   AND recharge.pay_channel = 'WECHAT_VIRTUAL_PAYMENT'
                   AND (recharge.status IN ('PENDING_PAYMENT', 'CLOSED')
                        OR (recharge.status = 'PAYMENT_FAILED' AND recharge.paid_fee > 0))
                   AND (recharge.next_query_at IS NULL OR recharge.next_query_at <= CURRENT_TIMESTAMP(3))
                   AND EXISTS (
                       SELECT 1 FROM wf_maintainer_wechat_session session
                        WHERE session.user_id = recharge.user_id
                          AND session.status = 'AVAILABLE'
                          AND session.deleted = 0
                   )
                 ORDER BY recharge.id ASC
                 LIMIT ?
                """;
        return jdbcTemplate.queryForList(sql, Long.class, afterOrderId, limit);
    }

    /** 按账户游标查询缺少活动扣币任务的账户，额度不与退款余额恢复共享。 */
    public List<RecoveryCandidate> findAccountsMissingActiveDebitTasks(long afterAccountId, int limit) {
        String sql = """
                SELECT account.id, account.user_id
                  FROM wf_point_account account
                 WHERE account.deleted = 0
                   AND account.id > ?
                   AND account.pending_debit > 0 AND NOT EXISTS (
                           SELECT 1 FROM wf_point_debit_task task
                            WHERE task.user_id = account.user_id
                              AND task.active_flag = 1
                              AND task.deleted = 0
                       )
                 ORDER BY account.id ASC
                 LIMIT ?
                """;
        return findRecoveryCandidates(sql, afterAccountId, limit);
    }

    /** 按独立账户游标查询退款后未同步余额的账户，失败后下一轮仍继续向后扫描。 */
    public List<RecoveryCandidate> findRefundStaleAccounts(long afterAccountId, int limit) {
        String sql = """
                SELECT account.id, account.user_id
                  FROM wf_point_account account
                 WHERE account.deleted = 0
                   AND account.id > ?
                   AND account.wechat_balance_synced_at IS NULL AND EXISTS (
                           SELECT 1 FROM wf_recharge_order recharge
                            WHERE recharge.account_id = account.id
                              AND recharge.user_id = account.user_id
                              AND recharge.status = 'REFUNDED'
                              AND recharge.deleted = 0
                       ) AND EXISTS (
                           SELECT 1 FROM wf_maintainer_wechat_session session
                            WHERE session.user_id = account.user_id
                              AND session.status = 'AVAILABLE'
                              AND session.deleted = 0
                       )
                 ORDER BY account.id ASC
                 LIMIT ?
                """;
        return findRecoveryCandidates(sql, afterAccountId, limit);
    }

    /** 同时返回账户游标和业务用户 ID，不能假定二者具有相同排序。 */
    private List<RecoveryCandidate> findRecoveryCandidates(String sql, long afterAccountId, int limit) {
        return jdbcTemplate.query(sql,
                (resultSet, rowNumber) -> new RecoveryCandidate(resultSet.getLong("id"), resultSet.getLong("user_id")),
                afterAccountId, limit);
    }

    /** 账户恢复候选，账户 ID 用于轮转，用户 ID 用于调用 runtime。 */
    public record RecoveryCandidate(long accountId, long userId) {
    }
}
