package com.jxc.wefolio.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 我的作品集列表响应。
 */
@Data
public class MinePortfolioListResponse {

    /** 作品集条目 */
    private List<Item> portfolios;

    /**
     * 作品集列表项。
     */
    @Data
    public static class Item {

        /** 作品集 ID */
        private Long portfolioId;

        /** 分享编码 */
        private String shareCode;

        /** 标题 */
        private String title;

        /** 分享封面地址 */
        private String coverUrl;

        /** 归属类型 */
        private String ownerType;

        /** 模板类型 */
        private String templateType;

        /** 发布状态 */
        private String publicationStatus;

        /** 草稿版本 */
        private Integer draftRevision;

        /** 正式版本 */
        private Integer publishedRevision;

        /** 更新时间 */
        private LocalDateTime updatedAt;
    }
}
