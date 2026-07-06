package com.jxc.wefolio.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 作品集档期查询提交请求。
 */
@Data
public class PortfolioScheduleQueryRequest {

    /** 旧版客户端兼容字段，访客端服务端已改用认证上下文并覆盖该值；预览模式可为空 */
    @Deprecated
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
