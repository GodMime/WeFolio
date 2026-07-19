package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.SystemAccess;
import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import com.jxc.wefolio.service.WechatInteractionLogSanitizer;
import com.jxc.wefolio.service.payment.WechatVirtualPaymentMessageSecurity;
import com.jxc.wefolio.service.payment.WechatVirtualPaymentNotification;
import com.jxc.wefolio.service.payment.WechatVirtualPaymentNotificationCodec;
import com.jxc.wefolio.service.payment.WechatVirtualPaymentNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 微信虚拟支付服务器回调入口。 */
@Slf4j
@SystemAccess
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payment/wechat/virtual-payment/notify")
public class WechatVirtualPaymentNotificationController {

    private final WechatVirtualPaymentProperties properties;
    private final WechatVirtualPaymentMessageSecurity messageSecurity;
    private final WechatVirtualPaymentNotificationCodec notificationCodec;
    private final WechatVirtualPaymentNotificationService notificationService;
    private final WechatInteractionLogSanitizer logSanitizer;

    /** 微信回调地址校验，同时支持明文和安全模式。 */
    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verify(
            @RequestParam(value = "signature", required = false) String signature,
            @RequestParam(value = "msg_signature", required = false) String messageSignature,
            @RequestParam("timestamp") String timestamp,
            @RequestParam("nonce") String nonce,
            @RequestParam("echostr") String echo
    ) {
        long startedAt = System.nanoTime();
        log.info("微信交互请求 operation=虚拟支付回调地址校验 referenceNo=null userId=null method=GET "
                        + "path=/api/payment/wechat/virtual-payment/notify request={} retryCount=0",
                logSanitizer.sanitizeText("signature=" + signature + "&msg_signature="
                        + messageSignature + "&timestamp=" + timestamp + "&nonce=" + nonce
                        + "&echostr=" + echo));
        if (!properties.isEnabled()) {
            return logVerificationResponse(ResponseEntity.notFound().build(), startedAt, null);
        }
        try {
            if (messageSignature != null && !messageSignature.isBlank()) {
                String decryptedEcho = messageSecurity.decryptMessage(
                        messageSignature, timestamp, nonce, echo);
                return logVerificationResponse(ResponseEntity.ok(decryptedEcho), startedAt, decryptedEcho);
            }
            if (!messageSecurity.verifyPlaintextSignature(signature, timestamp, nonce)) {
                return logVerificationResponse(
                        ResponseEntity.status(403).body("签名无效"), startedAt, "签名无效");
            }
            return logVerificationResponse(ResponseEntity.ok(echo), startedAt, echo);
        } catch (IllegalArgumentException exception) {
            logInteractionException("虚拟支付回调地址校验", null, startedAt, exception);
            return logVerificationResponse(
                    ResponseEntity.status(403).body("签名无效"), startedAt, "签名无效");
        }
    }

    /** 验签、必要时解密，再分发虚拟支付通知。 */
    @PostMapping(consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE,
            MediaType.TEXT_PLAIN_VALUE},
            produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> notify(
            @RequestBody String body,
            @RequestParam(value = "signature", required = false) String signature,
            @RequestParam(value = "msg_signature", required = false) String messageSignature,
            @RequestParam("timestamp") String timestamp,
            @RequestParam("nonce") String nonce
    ) {
        long startedAt = System.nanoTime();
        log.info("微信交互请求 operation=虚拟支付通知回调 referenceNo=null userId=null method=POST "
                        + "path=/api/payment/wechat/virtual-payment/notify request={} retryCount=0",
                logSanitizer.sanitizeText("signature=" + signature + "&msg_signature="
                        + messageSignature + "&timestamp=" + timestamp + "&nonce=" + nonce)
                        + " body=" + logSanitizer.sanitizeXml(body));
        if (!properties.isEnabled()) {
            return logNotificationResponse(ResponseEntity.notFound().build(), null, startedAt);
        }
        String referenceNo = null;
        try {
            String encrypted = notificationCodec.extractEncrypted(body);
            String payload;
            if (encrypted != null) {
                payload = messageSecurity.decryptMessage(messageSignature, timestamp, nonce, encrypted);
            } else {
                if (!messageSecurity.verifyPlaintextSignature(signature, timestamp, nonce)) {
                    return ResponseEntity.status(403).body(failureResponse());
                }
                payload = body;
            }
            log.info("微信交互解密结果 operation=虚拟支付通知回调 referenceNo=null userId=null payload={}",
                    logSanitizer.sanitizeXml(payload));
            WechatVirtualPaymentNotification notification = notificationCodec.parse(payload);
            referenceNo = notification.orderNo();
            log.info("微信交互解析结果 operation=虚拟支付通知回调 referenceNo={} userId=null "
                            + "notificationType={}", referenceNo, notification.type());
            return logNotificationResponse(
                    ResponseEntity.ok(notificationService.handle(notification)), referenceNo, startedAt);
        } catch (IllegalArgumentException exception) {
            logInteractionException("虚拟支付通知回调", referenceNo, startedAt, exception);
            return logNotificationResponse(
                    ResponseEntity.badRequest().body(failureResponse()), referenceNo, startedAt);
        } catch (RuntimeException exception) {
            logInteractionException("虚拟支付通知回调", referenceNo, startedAt, exception);
            return logNotificationResponse(
                    ResponseEntity.internalServerError().body(failureResponse()), referenceNo, startedAt);
        }
    }

    /** 记录地址校验应答，回显值始终按 echostr 字段脱敏。 */
    private ResponseEntity<String> logVerificationResponse(
            ResponseEntity<String> response,
            long startedAt,
            String responseBody
    ) {
        log.info("微信交互响应 operation=虚拟支付回调地址校验 referenceNo=null userId=null httpStatus={} "
                        + "response={} elapsedMs={} retryCount=0",
                response.getStatusCode().value(),
                logSanitizer.sanitizeText("echostr=" + responseBody), elapsedMillis(startedAt));
        return response;
    }

    /** 记录通知处理应答。 */
    private ResponseEntity<String> logNotificationResponse(
            ResponseEntity<String> response,
            String referenceNo,
            long startedAt
    ) {
        log.info("微信交互响应 operation=虚拟支付通知回调 referenceNo={} userId=null httpStatus={} "
                        + "response={} elapsedMs={} retryCount=0",
                referenceNo, response.getStatusCode().value(),
                logSanitizer.sanitizeXml(response.getBody()), elapsedMillis(startedAt));
        return response;
    }

    /** 记录不包含原始签名或密文的回调异常。 */
    private void logInteractionException(
            String operation,
            String referenceNo,
            long startedAt,
            RuntimeException exception
    ) {
        log.warn("微信交互异常 operation={} referenceNo={} userId=null elapsedMs={} retryCount=0 "
                        + "exceptionType={} message={}",
                operation, referenceNo, elapsedMillis(startedAt), exception.getClass().getSimpleName(),
                logSanitizer.sanitizeText(exception.getMessage()));
    }

    /** 计算调用耗时毫秒数。 */
    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    /** 不回显原始报文的失败应答。 */
    private String failureResponse() {
        return "<xml><return_code>FAIL</return_code><return_msg>RETRY</return_msg></xml>";
    }
}
