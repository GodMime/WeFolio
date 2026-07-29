package com.jxc.wefolio.dto.teamportfolio;

import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 团队作品集页面渲染 DTO。
 */
@Data
public class TeamPortfolioRenderDto {

    /** 分享编码 */
    private String shareCode;

    /** 作品集 ID */
    private Long portfolioId;

    /** 团队 ID */
    private Long teamId;

    /** 团队名称 */
    private String teamName;

    /** 页面标题 */
    private String title;

    /** 分享信息 */
    private TeamPortfolioConfigDto.Share share;

    /** 是否为预览模式 */
    private boolean preview;

    /** 是否维护中 */
    private boolean underMaintenance;

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

        /** 次级菜单组件；第一菜单保持 null 以便响应省略 */
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

        /** 组件渲染数据 */
        private JSONObject data;
    }
}
