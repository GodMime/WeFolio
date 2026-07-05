package com.jxc.wefolio.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 访客头像直传 COS 票据响应。
 */
@Data
public class VisitorAvatarUploadTicketResponse {

    /** COS 对象键 */
    private String objectKey;

    /** COS 对外访问地址，保存到访客资料 */
    private String publicUrl;

    /** COS 表单上传地址 */
    private String uploadUrl;

    /** 图片 MIME 类型 */
    private String contentType;

    /** COS 表单字段，前端原样传给 wx.uploadFile 的 formData */
    private Map<String, String> formData = new LinkedHashMap<>();

    /** 票据过期时间 */
    private LocalDateTime expiresAt;

    /** 本票据允许的最大字节数 */
    private long maxBytes;
}
