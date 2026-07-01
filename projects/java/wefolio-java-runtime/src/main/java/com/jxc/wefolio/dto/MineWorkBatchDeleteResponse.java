package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 我的作品批量删除响应。
 */
@Data
public class MineWorkBatchDeleteResponse {

    /** 删除成功数量 */
    private int successCount;

    /** 删除失败数量 */
    private int failedCount;

    /** 单个作品删除结果 */
    private List<Item> items = new ArrayList<>();

    /**
     * 单个作品删除结果。
     */
    @Data
    public static class Item {

        /** 作品 ID */
        private Long workId;

        /** 作品标题 */
        private String title;

        /** 是否删除成功 */
        private boolean success;

        /** 作品集引用次数 */
        private long referenceCount;

        /** 结果提示 */
        private String message;
    }
}
