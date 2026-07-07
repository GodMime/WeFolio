package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 作品上传完成确认请求。
 */
@Data
public class MineWorkUploadCompleteRequest {

    /** 已上传并准备确认入库的任务 */
    private List<CompleteItem> items = new ArrayList<>();

    /**
     * 单个任务确认参数。
     */
    @Data
    public static class CompleteItem {

        /** 上传任务 ID */
        private Long taskId;

        /** 缩略图或封面图上传任务 ID，小图可不传 */
        private Long coverTaskId;

        /** 作品标题，最长 30 字 */
        private String title;

        /** 作品说明，最长 1000 字 */
        private String description;

        /** 长宽比，例如 16:9、9:16，由小程序端计算提交。 */
        private String aspectRatio;

        /** 标签名称列表 */
        private List<String> tagNames = new ArrayList<>();

        /** 确认入库幂等键 */
        private String idempotencyKey;
    }
}
