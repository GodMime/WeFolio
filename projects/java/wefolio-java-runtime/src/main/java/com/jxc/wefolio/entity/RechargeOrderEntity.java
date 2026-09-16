package com.jxc.wefolio.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/**
 * wf_recharge_order — 充值订单表 — 微信虚拟支付充值订单
 */
@Data
@TableName("wf_recharge_order")
public class RechargeOrderEntity extends BaseEntity {

    /** 商户订单号 */
    private String merchantOrderNo;

    /** 入账积分账户 ID */
    private Long accountId;

    /** 下单用户 ID */
    private Long userId;

    /** 下单时选择的充值档位 ID */
    private Long packageId;

    /** 档位编码、版本、金额和积分计算快照 JSON */
    private String packageSnapshot;

    /** 支付金额，单位分 */
    private Integer amountFen;

    /** 基础到账积分 */
    private Integer basePoints;

    /** 赠送积分 */
    private Integer bonusPoints;

    /** 总到账积分 */
    private Integer totalPoints;

    /** 调起虚拟支付购买的代币数量，等于基础积分 */
    private Long buyQuantity;

    /** 订单状态：PENDING_PAYMENT 待支付 / PAID 已支付 / PAYMENT_FAILED 支付失败 / CLOSED 已关闭 / REFUNDED 已退款 */
    private String status;

    /** 支付渠道：WECHAT_VIRTUAL_PAYMENT 微信虚拟支付 */
    private String payChannel;

    /** 微信虚拟支付订单 ID */
    private String wechatOrderId;

    /** 渠道订单 ID */
    private String channelOrderId;

    /** 微信支付侧订单 ID */
    private String wxpayOrderId;

    /** 微信已核实的实付金额（分）；大于零表示已确认收款，入账仍以 PAID 状态为准。 */
    private Long paidFee;

    /** 基础充值积分流水 ID */
    private Long pointTransactionId;

    /** 套餐赠送积分订单 ID */
    private Long bonusGiftOrderId;

    /** 后台下次核对时间；为空时允许首次扫描。 */
    private LocalDateTime nextQueryAt;

    /** 连续未完成核对次数，用于有界指数退避。 */
    private Integer queryRetryCount;

    /** 最近查单错误码 */
    private String lastQueryErrorCode;

    /** 最近查单脱敏错误信息 */
    private String lastQueryErrorMessage;

    /** 最近查单失败时间 */
    private LocalDateTime lastQueryErrorAt;

    /** 首次核实支付完成的时间；不能单独作为本地积分已入账依据。 */
    private LocalDateTime paidAt;

    /** 关闭时间 */
    private LocalDateTime closedAt;

    /** 平台退款完成时间 */
    private LocalDateTime refundedAt;

    /** 待支付订单过期时间 */
    private LocalDateTime expireAt;

}
