package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.List;

/**
 * 标准作品集组件库响应。
 */
@Data
public class PortfolioComponentLibraryResponse {

    /** 组件说明列表 */
    private List<ComponentItem> components;

    /**
     * 组件说明。
     */
    @Data
    public static class ComponentItem {

        /** 组件类型 */
        private String componentType;

        /** 组件名称 */
        private String name;

        /** 组件说明 */
        private String description;
    }
}
