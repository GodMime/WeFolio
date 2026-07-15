package com.jxc.wefolio.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jxc.wefolio.dict.PointRuleGroupDict;
import com.jxc.wefolio.dict.PointRuleStatusDict;
import lombok.Data;


/**
 * wf_point_rule — 积分规则表 — 可配置、可按时间生效的积分计算规则
 */
@Data
@TableName("wf_point_rule")
public class PointRuleEntity extends BaseEntity {

    /** 稳定规则编码，仅使用英文大写下划线 */
    private String ruleCode;

    /** 同一规则编码内递增版本 */
    private Integer ruleVersion;

    /** 运营展示名称 */
    private String ruleName;

    /** 流水类型：RECHARGE 充值 / CONSUMPTION 消耗 / REFUND 回退 / GIFT 赠送 */
    private String transactionType;

    /** 可扩展业务场景编码 */
    private String sceneCode;

    /**
     * 规则展示分组。
     *
     * @see PointRuleGroupDict
     */
    private String groupCode;

    /** 计算模式：单次固定、累计阶梯、充值档位、人工调整或月度存储大小 */
    private String calcMode;

    /** 一个计费单位包含的操作次数或存储 MB 数 */
    private Integer unitCount;

    /** 每个计费单位增加或扣除的积分绝对值，0 表示免费 */
    private Long pointsValue;

    /** 适用对象、封顶、取整等扩展参数 JSON */
    private String configJson;

    /** 生效开始时间 */
    private LocalDateTime effectiveFrom;

    /** 生效结束时间，空表示长期有效 */
    private LocalDateTime effectiveTo;

    /**
     * 状态：ACTIVE 启用 / DISABLED 停用。
     *
     * @see PointRuleStatusDict
     */
    private String status;

}
