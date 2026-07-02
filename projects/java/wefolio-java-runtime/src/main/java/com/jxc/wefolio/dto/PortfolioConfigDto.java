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

    /** Schema 版本 */
    private String schemaVersion;

    /** 分享信息 */
    private Share share;

    /** 按 sortOrder 升序渲染的组件列表 */
    private List<Component> components;

    /**
     * 分享信息。
     */
    @Data
    public static class Share {

        /** 分享标题 */
        private String title;

        /** 分享简介 */
        private String intro;

        /** 分享封面 */
        private String coverUrl;

        /** 分享头像 */
        private String avatarUrl;
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
