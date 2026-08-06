package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 个人作品集被其他个人作品集引用时的错误详情。
 */
@Data
public class PortfolioReferenceFailureResponse {

    /** 稳定错误码。 */
    private String errorCode;

    /** 完整引用来源列表。 */
    private List<ReferenceItem> references = new ArrayList<>();

    /**
     * 单个引用来源。
     */
    @Data
    public static class ReferenceItem {

        /** 来源作品集 ID。 */
        private Long sourcePortfolioId;

        /** 来源作用域标题。 */
        private String sourceTitle;

        /** 配置作用域：DRAFT / PUBLISHED。 */
        private String configScope;
    }
}
