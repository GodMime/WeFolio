package com.jxc.wefolio.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 我的作品列表响应。
 */
@Data
public class MineWorkListResponse {

    /** 当前页码 */
    private int page;

    /** 每页条数 */
    private int pageSize;

    /** 总记录数 */
    private long total;

    /** 是否还有下一页 */
    private boolean hasMore;

    /** 作品摘要 */
    private Summary summary = new Summary();

    /** 标签筛选项 */
    private List<TagItem> tags = new ArrayList<>();

    /** 作品列表 */
    private List<WorkItem> works = new ArrayList<>();

    /**
     * 作品摘要。
     */
    @Data
    public static class Summary {

        /** 全部作品数 */
        private long totalCount;

        /** 图片作品数 */
        private long imageCount;

        /** 视频作品数 */
        private long videoCount;
    }

    /**
     * 标签筛选项。
     */
    @Data
    public static class TagItem {

        /** 标签 ID，全部标签为空 */
        private Long id;

        /** 标签名称 */
        private String name;

        /** 标签颜色 */
        private String color;

        /** 当前标签作品数 */
        private long count;

        /** 是否当前选中 */
        private boolean active;
    }

    /**
     * 作品列表项。
     */
    @Data
    public static class WorkItem {

        /** 作品 ID */
        private Long id;

        /** 媒体类型：IMAGE / VIDEO */
        private String mediaType;

        /** 作品标题 */
        private String title;

        /** 原始文件名 */
        private String originalFileName;

        /** 媒体访问 URL */
        private String mediaUrl;

        /** 封面访问 URL */
        private String coverUrl;

        /** MIME 类型 */
        private String mimeType;

        /** 文件大小字节数 */
        private Long fileSize;

        /** 视频时长毫秒 */
        private Integer durationMs;

        /** 像素宽度 */
        private Integer width;

        /** 像素高度 */
        private Integer height;

        /** 长宽比，例如 16:9、9:16 */
        private String aspectRatio;

        /** 作品说明 */
        private String description;

        /** 服务日期 */
        private LocalDate serviceDate;

        /** 排序值 */
        private Integer sortOrder;

        /** 作品状态 */
        private String status;

        /** 审核状态：PENDING / AUDITING / PASSED / REJECTED / REVIEW_REQUIRED / FAILED */
        private String auditStatus;

        /** 审核状态展示文案 */
        private String auditStatusText;

        /** 当前审核轮次 */
        private int auditRound;

        /** 配置的审核总轮次 */
        private int maxAuditRounds;

        /** 剩余可主动重审次数 */
        private int remainingAuditResubmitCount;

        /** 当前是否允许主动重审 */
        private boolean canResubmitAudit;

        /** 审核拒绝原因，违规、疑似违规或审核失败时可能存在 */
        private String auditRejectReason;

        /** 当前轮次全部用户可读审核原因，最多 20 个 */
        private List<AuditReasonItem> auditReasons = new ArrayList<>();

        /** 引用次数 */
        private long referenceCount;

        /** 标签列表 */
        private List<TagItem> tags = new ArrayList<>();

        /** 创建时间 */
        private LocalDateTime createdAt;

        /** 更新时间 */
        private LocalDateTime updatedAt;
    }

    /**
     * 用户可读审核原因。
     */
    @Data
    public static class AuditReasonItem {

        /** 稳定风险代码 */
        private String code;

        /** 用户可直接理解的中文原因 */
        private String message;
    }
}
