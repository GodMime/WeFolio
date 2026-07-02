package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 访客作品集响应。
 */
@Data
public class VisitorPortfolioResponse {

    /** 分享编码 */
    private String shareCode;

    /** 作品集 ID */
    private Long portfolioId;

    /** 正式发布版本 */
    private Integer publishedRevision;

    /** 分享标题 */
    private String title;

    /** 是否维护中 */
    private boolean underMaintenance;

    /** 维护中文案 */
    private MaintenanceText maintenanceText;

    /** 正式发布配置 */
    private PortfolioConfigDto config;

    /** 访问汇总记录 ID */
    private Long visitRecordId;

    /**
     * 维护中文案。
     */
    @Data
    public static class MaintenanceText {

        /** 英文主文案 */
        private String primary;

        /** 中文副文案 */
        private String secondary;
    }
}
