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

    /** 人工重试失败订单，复用原微信订单号。 */
    @Update("""
            UPDATE wf_point_gift_order
               SET status = 'RETRY_WAIT',
                   retry_count = 0,
                   next_execute_at = CURRENT_TIMESTAMP(3),
                   execution_lease_token = NULL,
                   lease_until = NULL,
                   last_error_code = NULL,
                   last_error_message = NULL,
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{orderId}
               AND status = 'FAILED'
               AND deleted = 0
            """)
    int resetForManualRetry(@Param("orderId") Long orderId);
}
