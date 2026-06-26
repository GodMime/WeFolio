package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 积分试算响应。
 */
@Data
public class PointCalculationResponse {

    /** 积分规则 ID */
    private Long ruleId;

    /** 规则编码 */
    private String ruleCode;

    /** 规则名称 */
    private String ruleName;

    /** 积分场景编码 */
    private String sceneCode;

    /** 积分场景文案 */
    private String sceneText;

    /** 计算模式 */
    private String calcMode;

    /** 流水类型 */
    private String transactionType;

    /** 动作次数 */
    private Integer actionCount;

    /** 单位次数 */
    private Integer unitCount;

    /** 单位积分值 */
    private Long pointsValue;

    /** 计费单位数 */
    private Long billedUnits;

    /** 试算积分变动，扣分为负、加分为正 */
    private Long pointsChange;

    /** 试算前累计余数 */
    private Integer pendingCountBefore;

    /** 试算后累计余数 */
    private Integer pendingCountAfter;
}
