package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 充值记录分页响应。
 */
@Data
public class RechargeOrdersResponse {

    /** 当前页码。 */
    private long page;

    /** 每页条数。 */
    private long pageSize;

    /** 总条数。 */
    private long total;

    /** 是否有下一页。 */
    private boolean hasMore;

    /** 充值记录。 */
    private List<RecordItem> records = new ArrayList<>();

    /**
     * 充值记录展示项。
     */
    @Data
    public static class RecordItem {

        /** 商户订单号。 */
        private String merchantOrderNo;

        /** 套餐名称。 */
        private String packageName;

        /** 支付金额，单位分。 */
        private Integer amountFen;

        /** 基础积分。 */
        private Integer basePoints;

        /** 赠送积分。 */
        private Integer bonusPoints;

        /** 总到账积分。 */
        private Integer totalPoints;

        /** 订单状态。 */
        private String status;

        /** 订单状态文案。 */
        private String statusText;

        /** 创建时间。 */
        private String createdAt;

        /** 支付完成时间。 */
        private String paidAt;
    }
}
