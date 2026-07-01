package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 我的作品排序请求。
 */
@Data
public class MineWorkSortRequest {

    /** 排序范围：ALL 全部作品 / TAG 标签内 */
    private String scope;

    /** 标签 ID，仅 TAG 范围使用 */
    private Long tagId;

    /** 排序项列表 */
    private List<Item> items = new ArrayList<>();

    /**
     * 单个作品排序项。
     */
    @Data
    public static class Item {

        /** 作品 ID */
        private Long workId;

        /** 排序值 */
        private Integer sortOrder;
    }
}
