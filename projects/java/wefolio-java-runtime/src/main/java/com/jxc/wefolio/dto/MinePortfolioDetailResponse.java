package com.jxc.wefolio.dto;

import lombok.Data;
import com.jxc.wefolio.dto.PortfolioFontManifestDto;

/**
 * 我的作品集详情响应。
 */
@Data
public class MinePortfolioDetailResponse {

    /** 当前配置快照的字体资源，保留原 renderData 空值语义。 */
    private PortfolioFontManifestDto fontAssets;


    /** 作品集 ID */
    private Long portfolioId;

    /** 分享编码 */
    private String shareCode;

    /** 归属类型 */
    private String ownerType;

    /** 归属 ID */
    private Long ownerId;

    /** 模板类型 */
    private String templateType;

    /** 维护状态 */
    private String status;

    /** 发布状态 */
    private String publicationStatus;

    /** 草稿版本 */
    private Integer draftRevision;

    /** 正式版本 */
    private Integer publishedRevision;

    /** 当前返回配置 */
    private PortfolioConfigDto config;

    /** 预览渲染模型 */
    private PortfolioRenderDto renderData;
}
