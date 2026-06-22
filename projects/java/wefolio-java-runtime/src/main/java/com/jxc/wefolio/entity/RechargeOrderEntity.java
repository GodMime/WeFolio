package com.jxc.wefolio.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/**
 * wf_recharge_order — 充值订单表 — 微信支付充值订单
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

    /** 订单状态：PENDING_PAYMENT 待支付 / PAID 已支付 / PAYMENT_FAILED 支付失败 / CLOSED 已关闭 */
    private String status;

    /** 支付渠道：WECHAT_PAY 微信支付 */
    private String payChannel;

    /** 微信预支付标识 */
    private String prepayId;

    /** 微信支付交易号，可空且唯一 */
    private String paymentTransactionId;

    /** 支付完成时间 */
    private LocalDateTime paidAt;

    /** 关闭时间 */
    private LocalDateTime closedAt;

    /** 待支付订单过期时间 */
    private LocalDateTime expireAt;

}
