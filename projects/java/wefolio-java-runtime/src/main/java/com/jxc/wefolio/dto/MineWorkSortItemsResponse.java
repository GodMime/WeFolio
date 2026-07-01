package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 我的作品排序列表响应。
 */
@Data
public class MineWorkSortItemsResponse {

    /** 排序范围：ALL 全部 / TAG 标签内 */
    private String scope;

    /** 标签 ID，仅 TAG 范围使用 */
    private Long tagId;

    /** 当前排序范围作品总数 */
    private long total;

    /** 排序作品项 */
    private List<SortWorkItem> works = new ArrayList<>();

    /**
     * 排序作品项。
     */
    @Data
    public static class SortWorkItem {

        /** 作品 ID */
        private Long id;

        /** 媒体类型 */
        private String mediaType;

        /** 作品标题 */
        private String title;

        /** 封面访问 URL */
        private String coverUrl;

        /** 当前排序值 */
        private Integer sortOrder;
    }
}
