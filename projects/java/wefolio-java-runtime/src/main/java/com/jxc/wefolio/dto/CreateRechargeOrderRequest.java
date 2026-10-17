package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 创建充值订单请求。
 */
@Data
public class CreateRechargeOrderRequest {

    /** 服务端充值套餐 ID。 */
    private Long packageId;

    /** 当前支付设备新获取的 wx.login 凭证；旧客户端不传时沿用已保存会话。 */
    private String code;
}
