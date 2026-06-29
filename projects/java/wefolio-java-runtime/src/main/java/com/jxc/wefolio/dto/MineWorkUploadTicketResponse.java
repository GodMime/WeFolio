package com.jxc.wefolio.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作品直传 COS 票据响应。
 */
@Data
public class MineWorkUploadTicketResponse {

    /** 批量上传批次 ID */
    private String batchId;

    /** 单批最大文件数 */
    private int maxBatchCount;

    /** 图片最大字节数 */
    private long imageMaxBytes;

    /** 视频最大字节数 */
    private long videoMaxBytes;

    /** 视频最大时长毫秒 */
    private int videoMaxDurationMs;

    /** 每个文件的直传票据 */
    private List<Item> items = new ArrayList<>();

    /**
     * 单个文件直传票据。
     */
    @Data
    public static class Item {

        /** 上传任务 ID */
        private Long taskId;

        /** 前端本地临时 ID */
        private String clientId;

        /** 媒体类型：IMAGE / VIDEO */
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
}
