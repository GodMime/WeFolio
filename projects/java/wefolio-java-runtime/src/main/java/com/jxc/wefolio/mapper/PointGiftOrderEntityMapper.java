package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.entity.PointGiftOrderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 微信代币赠送订单 Mapper。
 *
 * <p>SQL 中的订单状态值对应 PointGiftOrderStatusDict，并与 job 的 VirtualPaymentCandidateRepository
 * 领取条件一致；状态编码变更时须同步这两处查询。</p>
 */
@Mapper
public interface PointGiftOrderEntityMapper extends BaseMapper<PointGiftOrderEntity> {

    /** 条件领取赠送订单租约。 */
    @Update("""
            UPDATE wf_point_gift_order
               SET execution_lease_token = #{executionLeaseToken},
                   lease_until = #{leaseUntil},
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{orderId}
               AND status IN ('READY', 'RETRY_WAIT')
               AND next_execute_at <= #{now}
               AND (lease_until IS NULL OR lease_until < #{now})
               AND deleted = 0
            """)
    int tryClaim(
            @Param("orderId") Long orderId,
            @Param("executionLeaseToken") String executionLeaseToken,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("now") LocalDateTime now
    );

    /** 读取数据库当前毫秒时间，避免不同实例时钟偏差。 */
    @Select("SELECT CURRENT_TIMESTAMP(3)")
    LocalDateTime selectCurrentTimestamp();

    /** 仅允许当前执行租约令牌延长赠送订单租约。 */
    @Update("""
            UPDATE wf_point_gift_order
               SET lease_until = #{leaseUntil},
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{orderId}
               AND execution_lease_token = #{executionLeaseToken}
               AND status IN ('READY', 'RETRY_WAIT')
               AND deleted = 0
            """)
    int renewLease(
            @Param("orderId") Long orderId,
            @Param("executionLeaseToken") String executionLeaseToken,
            @Param("leaseUntil") LocalDateTime leaseUntil
    );

    /** 新会话提前唤醒已成功但未结算的赠送补查，保留成功事实且不抢占有效租约。 */
    @Update("""
            UPDATE wf_point_gift_order
               SET next_execute_at = CURRENT_TIMESTAMP(3),
                   last_failed_at = NULL,
                   execution_lease_token = NULL,
                   lease_until = NULL,
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE user_id = #{userId}
               AND status = 'RETRY_WAIT'
               AND last_error_code IN ('SUCCESS', 'DUPLICATE_SUCCESS')
               AND (lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP(3))
               AND deleted = 0
            """)
    int wakeBalanceConfirmation(@Param("userId") Long userId);

    /** 人工重试失败订单，复用原订单及成功事实；成功标记字面量对应 WechatVirtualPaymentErrorType 枚举名称。 */
    @Update("""
            UPDATE wf_point_gift_order
               SET status = 'RETRY_WAIT',
                   retry_count = CASE
                       WHEN last_error_code IN ('SUCCESS', 'DUPLICATE_SUCCESS') THEN retry_count
                       ELSE 0 END,
                   next_execute_at = CURRENT_TIMESTAMP(3),
                   execution_lease_token = NULL,
                   lease_until = NULL,
                   last_error_code = CASE
                       WHEN last_error_code IN ('SUCCESS', 'DUPLICATE_SUCCESS') THEN last_error_code
                       ELSE NULL END,
                   last_error_message = NULL,
                   last_failed_at = NULL,
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{orderId}
               AND status = 'FAILED'
               AND deleted = 0
            """)
    int resetForManualRetry(@Param("orderId") Long orderId);
}
