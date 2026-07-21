package com.jxc.wefolio.dto.teamportfolio;

import lombok.Data;

import java.time.LocalDate;

/**
 * 团队作品集档期查询请求。
 */
@Data
public class TeamPortfolioScheduleQueryRequest {

    /** 档期查询组件实例键。 */
    private String componentKey;

    /** 查询日期。 */
    private LocalDate queriedDate;

    /** 请求幂等键。 */
    private String idempotencyKey;
}
