package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 基础信息资料图片直传 COS 票据创建请求。
 */
@Data
public class MineProfileAssetUploadTicketRequest {

    /** 资料图片类型：AVATAR 头像 / WECHAT_QR 微信二维码 */
    private String assetType;

    /** 图片 MIME 类型 */
    private String mimeType;

    /** 图片文件字节数 */
    private Long fileSize;
}
