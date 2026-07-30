package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 作品直传 COS 票据创建请求。
 */
@Data
public class MineWorkUploadTicketRequest {

    /** 批量上传批次 ID，前端未传时由后端生成 */
    private String batchId;

    /** 待上传文件列表，单批最多 9 个 */
    private List<UploadFileItem> files = new ArrayList<>();

    /**
     * 待上传文件元信息。
     */
    @Data
    public static class UploadFileItem {

        /** 前端本地临时 ID，用于回写上传进度 */
        private String clientId;

        /** 媒体类型：IMAGE / VIDEO / ANIMATION */
        private String mediaType;

        /** 原始文件名 */
        private String fileName;

        /** MIME 类型 */
        private String mimeType;

        /** 文件字节数 */
        private Long fileSize;

        /** 文件 SHA-256，由小程序端计算提交 */
        private String sha256;

        /** 视频时长毫秒，图片和动图为空 */
        private Integer durationMs;

        /** 像素宽度 */
        private Integer width;

        /** 像素高度 */
        private Integer height;

        /** 缩略图或封面图对应的主上传任务 ID */
        private Long sourceTaskId;

        /** 创建上传任务幂等键 */
        private String idempotencyKey;
    }
}
