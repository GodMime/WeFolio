package com.jxc.wefolio.dto.teamportfolio;

import lombok.Data;

import java.util.List;

/**
 * 团队单个作品候选分页响应。
 */
@Data
public class TeamSingleWorkPageResponse {

    /** 当前页码。 */
    private int page;

    /** 当前页大小。 */
    private int pageSize;

    /** 候选作品总数。 */
    private long total;

    /** 是否还有后续分页数据。 */
    private boolean hasMore;

    /** 当前页作品候选。 */
    private List<WorkItem> works;

    /** 当前配置引用的有效作品。 */
    private WorkItem selectedWork;

    /**
     * 团队单个作品候选项。
     */
    @Data
    public static class WorkItem {

        /** 作品 ID。 */
        private Long workId;

        /** 作品标题。 */
        private String title;

        /** 作品说明。 */
        private String description;

        /** 媒体类型。 */
        private String mediaType;

        /** 封面公开地址。 */
        private String coverUrl;

        /** 原媒体公开地址。 */
        private String mediaUrl;

        /** 媒体宽度。 */
        private Integer width;

        /** 媒体高度。 */
        private Integer height;

        /** 媒体宽高比。 */
        private String aspectRatio;

        /** 视频时长，单位毫秒。 */
        private Integer durationMs;
    }
}
