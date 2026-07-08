package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 图片作品缩略图直传 COS 票据创建请求。
 */
@Data
public class MineWorkThumbnailUploadTicketRequest {

    /** 前端本地临时 ID，用于回写上传进度 */
    private String clientId;

    /** 小程序裁剪后的缩略图文件名 */
    private String fileName;

    /** 缩略图 MIME 类型，必须为图片 */
    private String mimeType;

    /** 缩略图文件字节数，必须不超过 100KB */
    private Long fileSize;

    /** 缩略图 SHA-256，由小程序端计算提交 */
    private String sha256;

    /** 缩略图像素宽度 */
    private Integer width;

    /** 缩略图像素高度 */
    private Integer height;

    /** 缩略图比例文本，例如 16:9 */
    private String ratio;

    /** 创建上传任务幂等键 */
    private String idempotencyKey;
}
