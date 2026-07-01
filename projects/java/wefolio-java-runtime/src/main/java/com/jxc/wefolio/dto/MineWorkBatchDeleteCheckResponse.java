package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 我的作品批量删除检查响应。
 */
@Data
public class MineWorkBatchDeleteCheckResponse {

    /** 提交作品总数 */
    private int total;

    /** 可删除作品数 */
    private int deletableCount;

    /** 被作品集引用阻塞的作品数 */
    private int blockedCount;

    /** 单个作品检查结果 */
    private List<Item> items = new ArrayList<>();

    /**
     * 单个作品删除检查结果。
     */
    @Data
    public static class Item {

        /** 作品 ID */
        private Long workId;

        /** 作品标题 */
        private String title;

        /** 是否可删除 */
        private boolean canDelete;

        /** 作品集引用次数 */
        private long referenceCount;

        /** 检查提示 */
        private String message;
    }
}
