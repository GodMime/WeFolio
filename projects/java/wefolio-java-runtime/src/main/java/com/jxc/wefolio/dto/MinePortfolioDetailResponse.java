package com.jxc.wefolio.dto;

import lombok.Data;
import com.jxc.wefolio.dto.PortfolioFontManifestDto;
import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.jxc.wefolio.service.advancedportfolio.AdvancedPortfolioVersionSupport;

/**
 * 我的作品集详情响应。
 */
@Data
public class MinePortfolioDetailResponse {
    /** 高级适配器使用同一结果快照的元数据，不在 HTTP 根暴露内部字段。 */
    @JsonIgnore
    @JSONField(serialize = false, deserialize = false)
    private AdvancedPortfolioVersionSupport.Metadata advancedConfigMetadata;

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
