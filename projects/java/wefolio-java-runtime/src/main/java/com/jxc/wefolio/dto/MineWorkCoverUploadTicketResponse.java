package com.jxc.wefolio.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 视频作品封面直传 COS 票据响应。
 */
@Data
public class MineWorkCoverUploadTicketResponse {

    /** 上传任务 ID */
    private Long taskId;

    /** 前端本地临时 ID */
    private String clientId;

    /** 媒体类型：IMAGE */
    private String mediaType;

    /** COS 对象键 */
    private String objectKey;

    /** COS 表单上传地址 */
    private String uploadUrl;

    /** COS 表单字段，前端原样传给 wx.uploadFile 的 formData */
    private Map<String, String> formData = new LinkedHashMap<>();

    /** 票据过期时间 */
    private LocalDateTime expiresAt;

    /** 本票据允许的最大字节数 */
    private long maxBytes;
}
