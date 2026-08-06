package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 访问记录统计响应 — 包含顶部统计和近 7 日访问趋势。
 */
@Data
public class MineVisitStatisticsResponse {

    /** 团队范围是否完整。 */
    private Boolean scopeComplete;

    /** 团队范围降级原因，完整时为空字符串。 */
    private String scopeReason;

    /** 顶部访问统计 */
    private MineVisitRecordsResponse.Summary summary;

    /** 近 7 日访问趋势 */
    private MineVisitRecordsResponse.Trend trend;
}
