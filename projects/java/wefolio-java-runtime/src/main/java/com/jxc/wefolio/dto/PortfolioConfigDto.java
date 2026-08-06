package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 标准个人作品集配置 DTO。
 */
@Data
public class PortfolioConfigDto {

    /** 标准个人作品集首版 Schema */
    public static final String SCHEMA_VERSION_STANDARD_PERSONAL_V1 = "standard-personal-v1";

    /** 当前编辑器配置能力版本 */
    public static final int EDITOR_SCHEMA_REVISION_CURRENT = 3;

    /** 默认页面背景色 */
    public static final String DEFAULT_BACKGROUND_COLOR = "#FFFFFF";

    /** Schema 版本 */
    private String schemaVersion;

    /** 编辑器配置能力版本 */
    private Integer editorSchemaRevision;

    /** 分享信息 */
    private Share share;

    /** 页面样式 */
    private Style style;

    /** 按 sortOrder 升序渲染的组件列表 */
    private List<Component> components;

    /** 底部导航 */
    private BottomNav bottomNav;

    /**
     * 分享信息。
     */
    @Data
    public static class Share {

        /** 分享标题 */
        private String title;

        /** 分享封面 */
        private String coverUrl;

        /** 分享头像 */
        private String avatarUrl;
    }

    /**
     * 页面样式配置。
     */
    @Data
    public static class Style {

        /** 页面背景色 */
        private String backgroundColor;
    }

    /**
     * 底部导航配置。
     */
    @Data
    public static class BottomNav {

        /** 是否启用 */
        private Boolean enabled;

        /** 导航菜单 */
        private List<BottomNavItem> items;
    }

    /**
     * 底部导航菜单。
     */
    @Data
    public static class BottomNavItem {

        /** 菜单实例键 */
        private String key;

        /** 菜单名称 */
        private String title;

        /** 预留图标地址 */
        private String iconUrl;

        /** 次级菜单组件；第一菜单必须为空 */
        private List<Component> components;
    }

    /**
     * 标准组件配置。
     */
    @Data
    public static class Component {

        /** 组件实例键 */
        private String componentKey;

        /** 组件类型 */
        private String componentType;

        /** 排序值 */
        private Integer sortOrder;

        /** 是否启用 */
        private Boolean enabled;

        /** 组件私有配置 */
        private Map<String, Object> config;
    }
}
