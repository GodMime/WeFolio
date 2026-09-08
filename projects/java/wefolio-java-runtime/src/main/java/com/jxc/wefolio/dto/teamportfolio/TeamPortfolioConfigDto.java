package com.jxc.wefolio.dto.teamportfolio;

import com.alibaba.fastjson2.JSONObject;
import lombok.Data;

import java.util.List;

/**
 * 团队作品集配置 DTO。
 */
@Data
public class TeamPortfolioConfigDto {

    /** 当前团队编辑器配置能力版本 */
    public static final int EDITOR_SCHEMA_REVISION_CURRENT = 4;

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

    /** 组件配置列表 */
    private List<ComponentEnvelope> components;

    /** 底部导航 */
    private BottomNav bottomNav;

    /**
     * 分享信息。
     */
    @Data
    public static class Share {

        /** 分享标题 */
        private String title;

        /** 分享描述 */
        private String description;

        /** 分享封面地址 */
        private String coverUrl;
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
        private List<ComponentEnvelope> components;
    }

    /**
     * 组件配置封装。
     */
    @Data
    public static class ComponentEnvelope {

        /** 组件实例键 */
        private String componentKey;

        /** 组件类型 */
        private String componentType;

        /** 排序值 */
        private Integer sortOrder;

        /** 是否启用 */
        private Boolean enabled;

        /** 组件私有配置 */
        private JSONObject config;
    }
}
