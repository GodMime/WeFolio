package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 我的作品详情响应。
 */
@Data
public class MineWorkDetailResponse {

    /** 作品详情 */
    private MineWorkListResponse.WorkItem work;

    /** 当前引用列表 */
    private List<ReferenceItem> references = new ArrayList<>();

    /**
     * 作品引用项。
     */
    @Data
    public static class ReferenceItem {

        /** 引用记录 ID */
        private Long id;

        /** 作品集 ID */
        private Long portfolioId;

        /** 组件实例键 */
        private String componentKey;

        /** 组件路径 */
        private String componentPath;

        /** 引用是否有效 */
        private boolean valid;
    }
}
