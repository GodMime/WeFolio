package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 充值页数据响应。
 */
@Data
public class RechargePageResponse {

    /** 当前积分余额。 */
    private Long balance;

    /** 是否低余额。 */
    private boolean lowBalance;

    /** 低余额阈值。 */
    private Long lowBalanceThreshold;

    /** 当前有效充值套餐。 */
    private List<PackageItem> packages = new ArrayList<>();

    /**
     * 充值套餐展示项。
     */
    @Data
    public static class PackageItem {

        /** 套餐 ID。 */
        private Long packageId;

        /** 套餐编码。 */
        private String packageCode;

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
    }
}
