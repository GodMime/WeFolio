package com.jxc.wefolio.dto.teamportfolio;

import com.alibaba.fastjson2.JSONObject;
import lombok.Data;

import java.util.List;

/**
 * 团队作品集配置 DTO。
 */
@Data
public class TeamPortfolioConfigDto {

    /** Schema 版本 */
    private String schemaVersion;

    /** 分享信息 */
    private Share share;

    /** 组件配置列表 */
    private List<ComponentEnvelope> components;

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
