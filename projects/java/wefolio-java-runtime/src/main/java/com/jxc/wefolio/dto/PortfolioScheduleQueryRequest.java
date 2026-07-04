package com.jxc.wefolio.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 作品集档期查询提交请求。
 */
@Data
public class PortfolioScheduleQueryRequest {

    /** 匿名访客摘要，预览模式可为空 */
    private String visitorKey;

    /** 档期查询组件实例键 */
    private String componentKey;

    /** 查询日期 */
    private LocalDate queriedDate;

    /** 档位定义 ID */
    private Long slotDefinitionId;

    /** 事件幂等键 */
    private String idempotencyKey;
}
