package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 个人作品集视频轮播候选作品分页响应。
 */
@Data
public class PortfolioVideoCarouselWorkPageResponse {

    /** 当前页码 */
    private int page;

    /** 每页条数 */
    private int pageSize;

    /** 总记录数 */
    private long total;

    /** 是否还有下一页 */
    private boolean hasMore;

    /** 作品标签筛选摘要 */
    private List<FilterTag> filterTags = new ArrayList<>();

    /** 视频作品候选项 */
    private List<WorkItem> works = new ArrayList<>();

    /**
     * 标签筛选项。
     */
    @Data
    public static class FilterTag {

        /** 标签 ID，“全部”为 null */
        private Long tagId;

        /** 标签名称 */
        private String name;

        /** 标签颜色 */
        private String color;

        /** 当前查询条件下的作品数 */
        private long count;

        /** 是否当前选中 */
        private boolean active;
    }

    /**
     * 视频作品候选项。
     */
    @Data
    public static class WorkItem {

        /** 作品 ID */
        private Long workId;

        /** 作品标题 */
        private String title;

        /** 媒体类型，固定为 VIDEO */
        private String mediaType;

        /** 封面访问地址 */
        private String coverUrl;

        /** 视频访问地址 */
        private String mediaUrl;

        /** 视频时长，单位毫秒 */
        private Integer durationMs;

        /** 像素宽度 */
        private Integer width;

        /** 像素高度 */
        private Integer height;

        /** 长宽比 */
        private String aspectRatio;

        /** 作品标签 */
        private List<TagItem> tags = new ArrayList<>();
    }

    /**
     * 作品标签项。
     */
    @Data
    public static class TagItem {

        /** 标签 ID */
        private Long tagId;

        /** 标签名称 */
        private String name;

        /** 标签颜色 */
        private String color;
    }
}
