package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 充值订单主动同步响应。
 */
@Data
public class RechargeOrderSyncResponse {

    /** 商户订单号。 */
    private String merchantOrderNo;

    /** 本地订单状态。 */
    private String status;

    /** 本地订单状态文案。 */
    private String statusText;

    /** 最新积分余额。 */
    private Long balance;

    /** 是否已经得到终态。 */
    private boolean confirmed;
}
