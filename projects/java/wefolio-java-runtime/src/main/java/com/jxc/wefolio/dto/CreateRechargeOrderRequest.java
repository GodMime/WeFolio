package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 创建充值订单请求。
 */
@Data
public class CreateRechargeOrderRequest {

    /** 服务端充值套餐 ID。 */
    private Long packageId;
}
