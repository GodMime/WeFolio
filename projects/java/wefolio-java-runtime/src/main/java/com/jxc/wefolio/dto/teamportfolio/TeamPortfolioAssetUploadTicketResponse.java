package com.jxc.wefolio.dto.teamportfolio;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 团队作品集图片素材直传票据响应。
 */
@Data
public class TeamPortfolioAssetUploadTicketResponse {

    /** 前端本地临时 ID。 */
    private String clientId;

    /** 素材类型。 */
    private String assetType;

    /** COS 对象键。 */
    private String objectKey;

    /** COS 对外访问地址。 */
    private String publicUrl;

    /** COS 表单上传地址。 */
    private String uploadUrl;

    /** 图片 MIME 类型。 */
    private String contentType;

    /** COS 表单字段。 */
    private Map<String, String> formData = new LinkedHashMap<>();

    /** 票据过期时间。 */
    private LocalDateTime expiresAt;

    /** 票据允许的最大字节数。 */
    private long maxBytes;
}
