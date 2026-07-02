package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 作品集封面直传 COS 票据创建请求。
 */
@Data
public class MinePortfolioCoverUploadTicketRequest {

    /** 前端本地临时 ID，用于回写上传结果 */
    private String clientId;

    /** 封面 MIME 类型，仅支持 image/jpeg 与 image/png */
    private String mimeType;

    /** 封面文件字节数，必须不超过 300KB */
    private Long fileSize;
}
