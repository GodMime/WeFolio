package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 作品集统一渲染 DTO — 预览端和访客端共享的页面展示模型。
 */
@Data
public class PortfolioRenderDto {

    /** 分享编码 */
    private String shareCode;

    /** 作品集 ID */
    private Long portfolioId;

    /** 页面标题 */
    private String title;

    /** 分享信息 */
    private PortfolioConfigDto.Share share;

    /** 是否预览模式 */
    private boolean preview;

    /** 是否维护中 */
    private boolean underMaintenance;

    /** 维护中文案 */
    private VisitorPortfolioResponse.MaintenanceText maintenanceText;

    /** 访问汇总记录 ID */
    private Long visitRecordId;

    /** 渲染组件 */
    private List<Component> components = new ArrayList<>();

    /**
     * 渲染组件。
     */
    @Data
    public static class Component {

        /** 组件实例键 */
        private String componentKey;

        /** 组件类型 */
        private String componentType;

        /** 组件名称 */
        private String name;

        /** 排序值 */
        private Integer sortOrder;

        /** 组件标题 */
        private String title;

        /** 轮播或扁平作品列表 */
        private List<WorkItem> works = new ArrayList<>();

        /** 作品集展示标签 */
        private List<DisplayGroup> groups = new ArrayList<>();

        /** 个人资料渲染数据 */
        private Profile profile;

        /** 档期查询渲染数据 */
        private ScheduleQuery scheduleQuery;

        /** 二维码联系渲染数据 */
        private QrContact qrContact;

        /** 联系表单渲染数据 */
        private ContactForm contactForm;

        /** 文字说明渲染数据 */
        private TextSection textSection;
    }

    /**
     * 作品集展示标签。
     */
    @Data
    public static class DisplayGroup {

        /** 展示标签标识 */
        private String groupKey;

        /** 展示标签名称 */
        private String name;

        /** 排序值 */
        private Integer sortOrder;

        /** 展示作品 */
        private List<WorkItem> works = new ArrayList<>();
    }

    /**
     * 展示作品项。
     */
    @Data
    public static class WorkItem {

        /** 作品 ID */
        private Long workId;

        /** 作品标题 */
        private String title;

        /** 媒体类型：IMAGE / VIDEO */
        private String mediaType;

        /** 封面地址 */
        private String coverUrl;

        /** 媒体地址 */
        private String mediaUrl;

        /** 视频时长毫秒 */
        private Integer durationMs;

        /** 作品说明 */
        private String description;
    }

    /**
     * 个人资料渲染数据。
     */
    @Data
    public static class Profile {

        /** 头像地址 */
        private String avatarUrl;

        /** 展示名称 */
        private String displayName;

        /** 职业身份 */
        private String profession;

        /** 服务城市 */
        private String city;

        /** 个人简介 */
        private String bio;

        /** 个人标签 */
        private List<Tag> tags = new ArrayList<>();

        /** 微信二维码地址 */
        private String wechatQrUrl;

        /** 展示字段开关 */
        private Map<String, Object> visibleFields;
    }

    /**
     * 个人标签。
     */
    @Data
    public static class Tag {

        /** 标签名称 */
        private String name;

        /** 标签颜色 */
        private String color;
    }

    /**
     * 档期查询渲染数据。
     */
    @Data
    public static class ScheduleQuery {

        /** 组件标题 */
        private String title;

        /** 查询说明 */
        private String description;

        /** 查询范围 */
        private Map<String, Object> queryRange;
    }

    /**
     * 二维码联系渲染数据。
     */
    @Data
    public static class QrContact {

        /** 组件标题 */
        private String title;

        /** 说明文案 */
        private String description;

        /** 二维码来源 */
        private String qrUrlSource;

        /** 二维码地址 */
        private String qrUrl;
    }

    /**
     * 联系表单渲染数据。
     */
    @Data
    public static class ContactForm {

        /** 组件标题 */
        private String title;

        /** 说明文案 */
        private String description;

        /** 展示字段 */
        private List<String> fields = new ArrayList<>();
    }

    /**
     * 文字说明渲染数据。
     */
    @Data
    public static class TextSection {

        /** 标题，可为空 */
        private String title;

        /** 正文 */
        private String content;
    }
}
