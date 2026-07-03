package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 作品集图片素材直传 COS 票据创建请求。
 */
@Data
public class MinePortfolioAssetUploadTicketRequest {

    /** 前端本地临时 ID，用于回写上传结果 */
    private String clientId;

    /** 作品集素材类型，如封面、个人头像 */
    private String assetType;

    /** 图片 MIME 类型，仅支持 image/jpeg 与 image/png */
    private String mimeType;

    /** 图片文件字节数，必须不超过素材大小限制 */
    private Long fileSize;
}
