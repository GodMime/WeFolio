package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.SystemAccess;
import com.jxc.wefolio.service.payment.WechatPayClient;
import com.jxc.wefolio.service.payment.WechatPayNotificationException;
import com.jxc.wefolio.service.payment.WechatPaySignatureException;
import com.jxc.wefolio.service.payment.WechatRechargeNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信支付通知控制器 — 公开接收原始报文，但仅信任官方 SDK 验签后的交易。
 *
 * @deprecated 普通微信支付通知能力待移除，迁移完成后删除
 */
@Slf4j
@SystemAccess
@RestController
@RequiredArgsConstructor
@Deprecated(forRemoval = true)
public class WechatPayNotificationController {

    /** 微信支付通知服务。 */
    private final WechatRechargeNotificationService notificationService;

    /**
     * 处理微信支付充值成功通知。
     *
     * @param serialNumber 微信支付签名序列号或公钥 ID
     * @param signature 微信支付签名
     * @param timestamp 微信支付签名时间戳
     * @param nonce 微信支付签名随机串
     * @param signType 微信支付签名类型
     * @param body 未经重新序列化的原始请求体
     * @return 空 HTTP 响应
     * @deprecated 普通微信支付通知能力待移除
     */
    @Deprecated(forRemoval = true)
    @PostMapping("/api/payment/wechat/recharge/notify")
    public ResponseEntity<Void> notifyRecharge(
            @RequestHeader("Wechatpay-Serial") String serialNumber,
            @RequestHeader("Wechatpay-Signature") String signature,
            @RequestHeader("Wechatpay-Timestamp") String timestamp,
            @RequestHeader("Wechatpay-Nonce") String nonce,
            @RequestHeader(value = "Wechatpay-Signature-Type", required = false) String signType,
            @RequestBody String body
    ) {
        WechatPayClient.NotificationRequest request = new WechatPayClient.NotificationRequest(
                serialNumber, signature, timestamp, nonce, signType, body);
        try {
            notificationService.handle(request);
            return ResponseEntity.ok().build();
        } catch (WechatPaySignatureException exception) {
            log.warn("微信支付通知验签失败: serialNumber={}", serialNumber);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (WechatPayNotificationException exception) {
            log.warn("微信支付通知解析失败: serialNumber={}", serialNumber);
            return ResponseEntity.badRequest().build();
        } catch (Exception exception) {
            log.error("微信支付充值通知处理失败: serialNumber={}, exceptionType={}",
                    serialNumber, exception.getClass().getSimpleName(), exception);
            return ResponseEntity.internalServerError().build();
        }
    }
}
