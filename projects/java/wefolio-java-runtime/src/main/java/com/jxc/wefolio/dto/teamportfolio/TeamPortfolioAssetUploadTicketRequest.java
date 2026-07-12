package com.jxc.wefolio.dto.teamportfolio;

import lombok.Data;

/**
 * 团队作品集图片素材直传票据请求。
 */
@Data
public class TeamPortfolioAssetUploadTicketRequest {

    /** 前端本地临时 ID。 */
    private String clientId;

    /** 素材类型。 */
    private String assetType;

    /** 图片 MIME 类型。 */
    private String mimeType;

    /** 图片文件字节数。 */
    private Long fileSize;
}
