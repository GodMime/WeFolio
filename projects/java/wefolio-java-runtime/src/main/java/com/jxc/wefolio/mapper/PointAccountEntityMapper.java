package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.entity.PointAccountEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;


/**
 * 积分账户表 Mapper
 */
@Mapper
public interface PointAccountEntityMapper extends BaseMapper<PointAccountEntity> {

    /** 使用微信当前余额结算充值，累计充值仅记录本订单基础代币，不再次相加到余额。 */
    @Update("""
            UPDATE wf_point_account
               SET wechat_balance = #{wechatBalance},
                   wechat_present_balance = #{wechatPresentBalance},
                   balance = #{wechatBalance} - pending_debit,
                   total_recharged = total_recharged + #{points},
                   wechat_balance_synced_at = CURRENT_TIMESTAMP(3),
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{accountId}
               AND user_id = #{userId}
               AND deleted = 0
            """)
    int applyRechargeWechatBalance(
            @Param("accountId") Long accountId,
            @Param("userId") Long userId,
            @Param("points") Long points,
            @Param("wechatBalance") Long wechatBalance,
            @Param("wechatPresentBalance") Long wechatPresentBalance
    );

    /**
     * 按维护者语义增加待扣，扣除后余额不得为负。
     *
     * @param accountId 积分账户 ID
     * @param userId 用户 ID
     * @param points 扣减积分
     * @return 更新行数
     */
    @Update("""
            UPDATE wf_point_account
               SET pending_debit = pending_debit + #{points},
                   balance = balance - #{points},
                   total_consumed = total_consumed + #{points},
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{accountId}
               AND user_id = #{userId}
               AND deleted = 0
               AND balance >= #{points}
            """)
    int deductForMaintainer(
            @Param("accountId") Long accountId,
            @Param("userId") Long userId,
            @Param("points") Long points
    );

    /**
     * 按访客语义增加待扣，允许实际可用积分变成负数。
     *
     * @param accountId 积分账户 ID
     * @param userId 用户 ID
     * @param points 扣减积分
     * @return 更新行数
     */
    @Update("""
            UPDATE wf_point_account
               SET pending_debit = pending_debit + #{points},
                   balance = balance - #{points},
                   total_consumed = total_consumed + #{points},
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{accountId}
               AND user_id = #{userId}
               AND deleted = 0
            """)
    int deductForVisitor(
            @Param("accountId") Long accountId,
            @Param("userId") Long userId,
            @Param("points") Long points
    );

    /**
     * 按后台月费语义增加完整待扣，仅扣费前正余额账户允许更新。
     *
     * @param accountId 积分账户 ID
     * @param userId 用户 ID
     * @param points 扣减积分
     * @return 更新行数
     */
    @Update("""
            UPDATE wf_point_account
               SET pending_debit = pending_debit + #{points},
                   balance = balance - #{points},
                   total_consumed = total_consumed + #{points},
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{accountId}
               AND user_id = #{userId}
               AND deleted = 0
               AND balance > 0
            """)
    int deductForSystem(
            @Param("accountId") Long accountId,
            @Param("userId") Long userId,
            @Param("points") Long points
    );

    /**
     * 使用微信权威余额快照重算实际可用积分。
     *
     * @param accountId 积分账户 ID
     * @param wechatBalance 微信总代币余额
     * @param wechatPresentBalance 微信赠送代币余额
     * @return 更新行数
     */
    @Update("""
            UPDATE wf_point_account
               SET wechat_balance = #{wechatBalance},
                   wechat_present_balance = #{wechatPresentBalance},
                   balance = #{wechatBalance} - pending_debit,
                   wechat_balance_synced_at = CURRENT_TIMESTAMP(3),
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{accountId}
               AND deleted = 0
            """)
    int syncWechatBalance(
            @Param("accountId") Long accountId,
            @Param("wechatBalance") Long wechatBalance,
            @Param("wechatPresentBalance") Long wechatPresentBalance
    );

    /**
     * 只识别同一用户、同一账户有退款事实的失效快照，首次未同步账户不属于此范围。
     * SQL 的 REFUNDED 对应 RechargeOrderStatusDict，改编码时须同步 job 的退款恢复候选查询。
     */
    @Select("""
            SELECT account.*
              FROM wf_point_account account
             WHERE account.user_id = #{userId}
               AND account.wechat_balance_synced_at IS NULL
               AND account.deleted = 0
               AND EXISTS (
                   SELECT 1 FROM wf_recharge_order recharge
                    WHERE recharge.account_id = account.id
                      AND recharge.user_id = account.user_id
                      AND recharge.status = 'REFUNDED'
                      AND recharge.deleted = 0
               )
             LIMIT 1
            """)
    PointAccountEntity selectRefundStaleAccount(@Param("userId") Long userId);

    /** 退款通知到达后将微信余额快照标记为待重新同步。 */
    @Update("""
            UPDATE wf_point_account
               SET wechat_balance_synced_at = NULL,
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{accountId}
               AND deleted = 0
            """)
    int markWechatBalanceStale(@Param("accountId") Long accountId);

    /**
     * 赠送成功后同步微信余额并累计本地赠送数。
     *
     * @param accountId 积分账户 ID
     * @param giftedPoints 本次赠送积分
     * @param wechatBalance 微信总代币余额
     * @param wechatPresentBalance 微信赠送代币余额
     * @return 更新行数
     */
    @Update("""
            UPDATE wf_point_account
               SET wechat_balance = #{wechatBalance},
                   wechat_present_balance = #{wechatPresentBalance},
                   balance = #{wechatBalance} - pending_debit,
                   total_gifted = total_gifted + #{giftedPoints},
                   wechat_balance_synced_at = CURRENT_TIMESTAMP(3),
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{accountId}
               AND deleted = 0
            """)
    int applyGiftWechatBalance(
            @Param("accountId") Long accountId,
            @Param("giftedPoints") Long giftedPoints,
            @Param("wechatBalance") Long wechatBalance,
            @Param("wechatPresentBalance") Long wechatPresentBalance
    );

    /**
     * 将微信扣币成功结果与本地待扣聚合在一条条件 SQL 中核销。
     *
     * @param accountId 积分账户 ID
     * @param settledPoints 本次成功核销积分
     * @param wechatBalance 微信扣币后总代币余额
     * @param wechatPresentBalance 微信扣币后赠送代币余额
     * @return 更新行数
     */
    @Update("""
            UPDATE wf_point_account
               SET balance = #{wechatBalance} - (pending_debit - #{settledPoints}),
                   pending_debit = pending_debit - #{settledPoints},
                   wechat_balance = #{wechatBalance},
                   wechat_present_balance = #{wechatPresentBalance},
                   wechat_balance_synced_at = CURRENT_TIMESTAMP(3),
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{accountId}
               AND pending_debit >= #{settledPoints}
               AND deleted = 0
            """)
    int settleWechatDebit(
            @Param("accountId") Long accountId,
            @Param("settledPoints") Long settledPoints,
            @Param("wechatBalance") Long wechatBalance,
            @Param("wechatPresentBalance") Long wechatPresentBalance
    );

    /**
     * 兼容旧积分服务的维护者扣除入口，后续调用点迁移完成后删除。
     *
     * @param accountId 积分账户 ID
     * @param userId 用户 ID
     * @param points 扣减积分
     * @return 更新行数
     */
    default int deductConsumedPoints(Long accountId, Long userId, Long points) {
        return deductForMaintainer(accountId, userId, points);
    }
}
