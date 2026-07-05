package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 访客头像直传 COS 票据创建请求。
 */
@Data
public class VisitorAvatarUploadTicketRequest {

    /** 访客资料短期授权 token */
    private String visitorProfileToken;

    /** 头像 MIME 类型 */
    private String mimeType;

    /** 头像文件字节数 */
    private Long fileSize;
}
