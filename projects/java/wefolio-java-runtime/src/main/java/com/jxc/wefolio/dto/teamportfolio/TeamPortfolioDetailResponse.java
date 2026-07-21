package com.jxc.wefolio.dto.teamportfolio;

import lombok.Data;

/**
 * 团队作品集维护详情响应。
 */
@Data
public class TeamPortfolioDetailResponse {

    /** 作品集 ID。 */
    private Long portfolioId;

    /** 分享编码。 */
    private String shareCode;

    /** 归属类型。 */
    private String ownerType;

    /** 归属团队 ID。 */
    private Long ownerId;

    /** 模板类型。 */
    private String templateType;

    /** 维护状态。 */
    private String status;

    /** 发布状态。 */
    private String publicationStatus;

    /** 草稿版本。 */
    private Integer draftRevision;

    /** 正式版本。 */
    private Integer publishedRevision;

    /** 当前返回的团队配置。 */
    private TeamPortfolioConfigDto config;

    /** 预览渲染模型。 */
    private TeamPortfolioRenderDto renderData;
}
