package com.jxc.wefolio.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 个人作品集超链接内部目标列表响应。
 */
@Data
public class PortfolioHyperlinkTargetResponse {

    /** 可展示的已发布个人作品集。 */
    private List<TargetItem> portfolios = new ArrayList<>();

    /** 不在正常候选中的当前失效选择。 */
    private TargetItem currentSelection;

    /**
     * 内部跳转目标项。
     */
    @Data
    public static class TargetItem {

        /** 作品集 ID。 */
        private Long portfolioId;

        /** 作品集标题。 */
        private String title;

        /** 作品集封面。 */
        private String coverUrl;

        /** 最近更新时间。 */
        private LocalDateTime updatedAt;

        /** 是否允许选择。 */
        private boolean selectable;

        /** 不可选原因。 */
        private String disabledReason;
    }
}
