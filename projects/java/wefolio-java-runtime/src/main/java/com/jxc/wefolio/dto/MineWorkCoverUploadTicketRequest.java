package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 视频作品封面直传 COS 票据创建请求。
 */
@Data
public class MineWorkCoverUploadTicketRequest {

    /** 前端本地临时 ID，用于回写上传进度 */
    private String clientId;

    /** 用户选择的本地封面文件名 */
    private String fileName;

    /** 封面 MIME 类型，必须为图片 */
    private String mimeType;

    /** 封面文件字节数，必须不超过 100KB */
    private Long fileSize;

    /** 封面 SHA-256，由小程序端计算提交 */
    private String sha256;

    /** 封面像素宽度 */
    private Integer width;

    /** 封面像素高度 */
    private Integer height;

    /** 创建上传任务幂等键 */
    private String idempotencyKey;
}
