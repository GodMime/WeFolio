package com.jxc.wefolio.job.repo;

import com.jxc.wefolio.job.dict.RechargeOrderStatusDict;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * 过期充值订单关闭仓储。
 */
@Repository
@RequiredArgsConstructor
public class RechargeOrderCloseRepository {

    /** JDBC 操作模板 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 关闭一批已过期且仍待支付的充值订单。
     *
     * <p>状态与已确认支付金额同时校验，不能关闭已确认支付但仍等待本地结算的订单。
     * 先于微信成功事实落库的关单仍可由 runtime 从 CLOSED 状态核对恢复。</p>
     *
     * @param now 本轮固定判断时点
     * @param limit 单批上限
     * @return 实际关闭数量
     */
    public int closeExpiredOrders(LocalDateTime now, int limit) {
        String sql = """
                UPDATE wf_recharge_order
                SET status = ?,
                    closed_at = ?,
                    updated_at = ?,
                    version = version + 1
                WHERE status = ?
                  AND deleted = 0
                  AND expire_at < ?
                  AND (paid_fee IS NULL OR paid_fee <= 0)
                ORDER BY expire_at ASC, id ASC
                LIMIT ?
                """;
        return jdbcTemplate.update(
                sql,
                RechargeOrderStatusDict.CLOSED.getCode(),
                now,
                now,
                RechargeOrderStatusDict.PENDING_PAYMENT.getCode(),
                now,
                limit);
    }
}
