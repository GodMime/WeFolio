package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.entity.PointMeterEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;


/**
 * 积分计量器表 Mapper
 */
@Mapper
public interface PointMeterEntityMapper extends BaseMapper<PointMeterEntity> {

    /**
     * 查询指定业务的积分计量器。
     *
     * @param accountId 积分账户 ID
     * @param ruleCode 规则编码
     * @param businessType 业务类型
     * @param businessId 业务 ID
     * @return 积分计量器
     */
    @Select("""
            SELECT id, account_id, user_id, rule_code, applied_rule_id, business_type,
                   business_id, pending_count, total_count, total_billed_units,
                   version, created_at, updated_at, deleted
              FROM wf_point_meter
             WHERE account_id = #{accountId}
               AND rule_code = #{ruleCode}
               AND business_type = #{businessType}
               AND business_id = #{businessId}
               AND deleted = 0
             LIMIT 1
            """)
    PointMeterEntity selectMeter(
            @Param("accountId") Long accountId,
            @Param("ruleCode") String ruleCode,
            @Param("businessType") String businessType,
            @Param("businessId") String businessId
    );
}
