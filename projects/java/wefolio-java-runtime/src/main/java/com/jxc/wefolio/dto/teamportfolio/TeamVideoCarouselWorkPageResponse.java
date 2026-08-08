package com.jxc.wefolio.dto.teamportfolio;

import lombok.Data;

import java.util.List;

/**
 * 团队视频轮播作品候选分页响应。
 */
@Data
public class TeamVideoCarouselWorkPageResponse {

    /** 当前页码。 */
    private int page;

    /** 当前页大小。 */
    private int pageSize;

    /** 候选作品总数。 */
    private long total;

    /** 是否还有后续分页数据。 */
    private boolean hasMore;

    /** 当前页视频作品候选。 */
    private List<WorkItem> works;

    /** 团队视频作品候选项。 */
    @Data
    public static class WorkItem {

        /** 成员用户 ID。 */
        private Long memberUserId;

        /** 当前成员展示名。 */
        private String memberDisplayName;

        /** 作品 ID。 */
        private Long workId;

        /** 作品标题。 */
        private String title;

        /** 媒体类型，固定为 VIDEO。 */
        private String mediaType;

        /** 封面公开地址。 */
        private String coverUrl;

        /** 视频公开地址。 */
        private String mediaUrl;

        /** 视频时长，单位毫秒。 */
        private Integer durationMs;

        /** 视频宽度。 */
        private Integer width;

        /** 视频高度。 */
        private Integer height;

        /** 视频宽高比。 */
        private String aspectRatio;

        /** 作品标签。 */
        private List<TagItem> tags;
    }

    /** 作品标签项。 */
    @Data
    public static class TagItem {

        /** 标签 ID。 */
        private Long tagId;

        /** 标签名称。 */
        private String name;

        /** 标签颜色。 */
        private String color;
    }
}
