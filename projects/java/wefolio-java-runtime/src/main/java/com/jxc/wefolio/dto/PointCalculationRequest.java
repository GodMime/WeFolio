package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 积分试算请求 — 只计算预期消耗或增加，不写账户和流水。
 */
@Data
public class PointCalculationRequest {

    /** 积分场景编码 */
    private String sceneCode;

    /** 本次业务动作次数，默认 1 */
    private Integer actionCount;

    /** 关联业务类型，累计阈值场景用于读取已有计量器 */
    private String businessType;

    /** 关联业务 ID，累计阈值场景用于读取已有计量器 */
    private String businessId;
}
