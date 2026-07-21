package com.jxc.wefolio.dto.teamportfolio;

import com.alibaba.fastjson2.JSONObject;
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

        /** 组件渲染数据 */
        private JSONObject data;
    }
}
