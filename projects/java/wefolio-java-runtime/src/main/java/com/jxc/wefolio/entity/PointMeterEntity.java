package com.jxc.wefolio.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/**
 * wf_point_meter — 积分计量器表 — 阶梯计费规则的累计计数器
 */
@Data
@TableName("wf_point_meter")
public class PointMeterEntity extends BaseEntity {

    /** 被扣费积分账户 ID */
    private Long accountId;

    /** 被扣费用户 ID */
    private Long userId;

    /** 稳定规则编码，规则调整后仍延续累计值 */
    private String ruleCode;

    /** 最近一次计量采用的规则 ID */
    private Long appliedRuleId;

    /** 计量业务类型 */
    private String businessType;

    /** 计量业务 ID，如个人作品集 ID */
    private String businessId;

    /** 尚未转换为扣费单位的累计余数 */
    private Integer pendingCount;

    /** 累计计量次数 */
    private Long totalCount;

    /** 已扣费单位数 */
    private Long totalBilledUnits;

}
