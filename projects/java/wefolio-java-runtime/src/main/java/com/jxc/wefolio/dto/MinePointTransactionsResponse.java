package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 我的积分流水响应。
 */
@Data
public class MinePointTransactionsResponse {

    /** 当前页码 */
    private long page;

    /** 每页条数 */
    private long pageSize;

    /** 总条数 */
    private long total;

    /** 是否还有下一页 */
    private boolean hasMore;

    /** 流水列表 */
    private List<TransactionItem> records = new ArrayList<>();

    /**
     * 积分流水展示项。
     */
    @Data
    public static class TransactionItem {

        /** 流水 ID */
        private Long transactionId;

        /** 流水类型 */
        private String transactionType;

        /** 流水类型文案 */
        private String transactionTypeText;

        /** 场景编码 */
        private String sceneCode;

        /** 场景文案 */
        private String sceneText;

        /** 积分变动 */
        private Long pointsChange;

        /** 积分变动展示文案 */
        private String pointsText;

        /** 变动前余额 */
        private Long balanceBefore;

        /** 变动后余额 */
        private Long balanceAfter;

        /** 业务类型 */
        private String businessType;

        /** 业务 ID */
        private String businessId;

        /** 备注 */
        private String remark;

        /** 发生时间 */
        private String occurredAt;
    }
}
