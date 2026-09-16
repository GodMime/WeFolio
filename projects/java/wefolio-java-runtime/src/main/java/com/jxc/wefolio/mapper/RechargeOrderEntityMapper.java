package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.entity.RechargeOrderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;


/**
 * 充值订单表 Mapper
 */
@Mapper
public interface RechargeOrderEntityMapper extends BaseMapper<RechargeOrderEntity> {

    /**
     * 按商户订单号锁定一条充值订单，供通知与主动查单结算复用。
     *
     * @param merchantOrderNo 商户订单号
     * @return 充值订单
     */
    @Select("""
            SELECT *
              FROM wf_recharge_order
             WHERE merchant_order_no = #{merchantOrderNo}
               AND deleted = 0
             LIMIT 1 FOR UPDATE
            """)
    RechargeOrderEntity selectForUpdateByMerchantOrderNo(
            @Param("merchantOrderNo") String merchantOrderNo
    );
    /** 更新后台核对计划，错误详情只保存固定分类，不持久化微信原始敏感应答。 */
    @Update("""
            UPDATE wf_recharge_order
               SET next_query_at = #{nextQueryAt}, query_retry_count = #{retryCount},
                   last_query_error_code = #{errorCode}, last_query_error_message = NULL,
                   last_query_error_at = CASE WHEN #{errorCode} IS NULL THEN NULL ELSE CURRENT_TIMESTAMP(3) END,
                   updated_at = CURRENT_TIMESTAMP(3), version = version + 1
             WHERE id = #{orderId} AND deleted = 0
            """)
    int updateQuerySchedule(@Param("orderId") Long orderId,
                            @Param("nextQueryAt") LocalDateTime nextQueryAt,
                            @Param("retryCount") int retryCount,
                            @Param("errorCode") String errorCode);

}
