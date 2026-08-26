package com.jxc.wefolio.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 我的反馈附件直传票据响应。
 */
@Data
public class MineFeedbackUploadTicketResponse {

    /** 单次最多申请票据的附件数量。 */
    private int maxFileCount;

    /** 单张图片允许的最大字节数。 */
    private long imageMaxBytes;

    /** 单个视频允许的最大字节数。 */
    private long videoMaxBytes;

    /** 单个视频允许的最大时长毫秒。 */
    private long videoMaxDurationMs;

    /** 与请求文件顺序一致的直传票据列表。 */
    private List<Item> items = new ArrayList<>();

    /**
     * 单个反馈附件的 COS 表单直传票据。
     */
    @Data
    public static class Item {

        /** 服务端上传任务 ID，提交反馈时用于确认附件。 */
        private Long taskId;

        /** 客户端文件幂等标识，用于回写对应本地文件。 */
        private String clientId;

        /** 附件媒体类型，固定为 IMAGE 或 VIDEO。 */
        private String mediaType;

        /** 服务端生成的 COS 对象键。 */
        private String objectKey;

        /** COS 表单上传地址。 */
        private String uploadUrl;

        /** 前端原样传给上传请求的 COS 表单字段。 */
        private Map<String, String> formData = new LinkedHashMap<>();

        /** 票据和上传任务的过期时间。 */
        private LocalDateTime expiresAt;

        /** 当前附件媒体类型允许的最大字节数。 */
        private long maxBytes;
    }
}
