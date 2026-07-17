package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.entity.RechargeOrderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;


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
}
