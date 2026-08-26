package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 我的反馈附件直传票据创建请求。
 */
@Data
public class MineFeedbackUploadTicketRequest {

    /** 待上传的反馈附件元数据列表。 */
    private List<UploadFileItem> files = new ArrayList<>();

    /**
     * 单个待上传反馈附件的客户端声明元数据。
     */
    @Data
    public static class UploadFileItem {

        /** 客户端生成的文件幂等标识，长度为 1 至 64 个字符。 */
        private String clientId;

        /** 客户端读取的原始文件名，用于校验并提取扩展名。 */
        private String fileName;

        /** 附件媒体类型，固定为 IMAGE 或 VIDEO。 */
        private String mediaType;

        /** 客户端声明的文件 MIME 类型。 */
        private String mimeType;

        /** 客户端声明的文件字节数。 */
        private Long fileSize;

        /** 客户端声明的媒体时长毫秒，图片应为 0。 */
        private Long durationMs;
    }
}
