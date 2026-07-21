package com.jxc.wefolio.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableField;
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

    /** 订单状态：PENDING_PAYMENT 待支付 / PAID 已支付 / PAYMENT_FAILED 支付失败 / CLOSED 已关闭 */
    private String status;

    /** 支付渠道：WECHAT_VIRTUAL_PAYMENT 微信虚拟支付 */
    private String payChannel;

    /** 微信虚拟支付订单 ID */
    private String wechatOrderId;

    /** 渠道订单 ID */
    private String channelOrderId;

    /** 微信支付侧订单 ID */
    private String wxpayOrderId;

    /** 微信查询返回的实际支付分数 */
    private Long paidFee;

    /** 基础充值积分流水 ID */
    private Long pointTransactionId;

    /** 套餐赠送积分订单 ID */
    private Long bonusGiftOrderId;

    /** 最近查单错误码 */
    private String lastQueryErrorCode;

    /** 最近查单脱敏错误信息 */
    private String lastQueryErrorMessage;

    /** 最近查单失败时间 */
    private LocalDateTime lastQueryErrorAt;

    /** 旧普通微信支付预支付标识，仅在兼容代码移除前承接内存对象 */
    @TableField(exist = false)
    private transient String legacyPrepayId;

    /** 旧普通微信支付交易号，仅在兼容代码移除前承接内存对象 */
    @TableField(exist = false)
    private transient String legacyPaymentTransactionId;

    /** 支付完成时间 */
    private LocalDateTime paidAt;

    /** 关闭时间 */
    private LocalDateTime closedAt;

    /** 平台退款完成时间 */
    private LocalDateTime refundedAt;

    /** 待支付订单过期时间 */
    private LocalDateTime expireAt;

    /** 获取旧普通微信支付预支付标识。 */
    public String getPrepayId() {
        return legacyPrepayId;
    }

    /** 设置旧普通微信支付预支付标识。 */
    public void setPrepayId(String prepayId) {
        this.legacyPrepayId = prepayId;
    }

    /** 获取旧普通微信支付交易号。 */
    public String getPaymentTransactionId() {
        return legacyPaymentTransactionId;
    }

    /** 设置旧普通微信支付交易号。 */
    public void setPaymentTransactionId(String paymentTransactionId) {
        this.legacyPaymentTransactionId = paymentTransactionId;
    }

}
