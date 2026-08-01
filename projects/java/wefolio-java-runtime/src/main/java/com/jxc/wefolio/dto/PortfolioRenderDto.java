package com.jxc.wefolio.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
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

    /** 页面渲染样式 */
    private Style style;

    /** 渲染组件 */
    private List<Component> components = new ArrayList<>();

    /** 底部导航渲染数据 */
    private BottomNav bottomNav;

    /**
     * 页面渲染样式。
     */
    @Data
    public static class Style {

        /** 页面背景色 */
        private String backgroundColor;

        /** 页面明暗模式：light / dark */
        private String themeMode;
    }

    /**
     * 底部导航渲染数据。
     */
    @Data
    public static class BottomNav {

        /** 是否启用 */
        private boolean enabled;

        /** 导航菜单 */
        private List<BottomNavItem> items = new ArrayList<>();
    }

    /**
     * 底部导航渲染菜单。
     */
    @Data
    public static class BottomNavItem {

        /** 菜单实例键 */
        private String key;

        /** 菜单名称 */
        private String title;

        /** 次级菜单渲染组件；第一菜单保持 null 以便序列化时省略 */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private List<Component> components;
    }

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

        /** 单个作品 */
        private WorkItem work;

        /** 是否展示作品名 */
        private Boolean showTitle;

        /** 是否展示作品说明 */
        private Boolean showDescription;

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

        /** 分割线渲染数据 */
        private Divider divider;
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

        /** 作品原始长宽比 */
        private String aspectRatio;
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

        /** 展示方式：MODAL_CALENDAR 弹层月历 / INLINE_CALENDAR 内联月历 */
        private String displayMode;

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

        /** 展示方式：MODAL_FORM 弹层表单 / INLINE_FORM 直接表单 */
        private String displayMode;

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

        /** 对齐方式：LEFT / CENTER / RIGHT */
        private String alignment;

        /** 字体：SYSTEM / WECHAT_SANS_SS */
        private String fontFamily;

        /** 正文字号，单位 rpx */
        private Integer fontSizeRpx;
    }

    /**
     * 分割线渲染数据。
     */
    @Data
    public static class Divider {

        /** 颜色：BLACK / WHITE / GRAY / TRANSPARENT */
        private String color;

        /** 高度，单位 px */
        private Integer heightPx;
    }
}
