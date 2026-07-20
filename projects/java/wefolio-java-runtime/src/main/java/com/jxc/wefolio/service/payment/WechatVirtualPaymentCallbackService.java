package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import com.jxc.wefolio.message.WechatVirtualPaymentMessage;
import com.jxc.wefolio.service.WechatInteractionLogSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** 微信虚拟支付服务器回调应用服务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatVirtualPaymentCallbackService {

    /** 回调接口路径 */
    private static final String CALLBACK_PATH = "/api/payment/wechat/virtual-payment/notify";

    /** 微信失败回调报文 */
    private static final String FAILURE_RESPONSE =
            "<xml><return_code>FAIL</return_code><return_msg>RETRY</return_msg></xml>";

    /** 微信虚拟支付配置 */
    private final WechatVirtualPaymentProperties properties;

    /** 微信回调消息安全服务 */
    private final WechatVirtualPaymentMessageSecurity messageSecurity;

    /** 微信通知编解码器 */
    private final WechatVirtualPaymentNotificationCodec notificationCodec;

    /** 微信通知业务处理服务 */
    private final WechatVirtualPaymentNotificationService notificationService;

    /** 微信交互日志脱敏器 */
    private final WechatInteractionLogSanitizer logSanitizer;

    /** 校验微信回调地址，同时支持明文和安全模式。 */
    public CallbackResult verify(
            String signature,
            String messageSignature,
            String timestamp,
            String nonce,
            String echo
    ) {
        long startedAt = System.nanoTime();
        if (log.isInfoEnabled()) {
            log.info("微信交互请求 operation=虚拟支付回调地址校验 referenceNo=null userId=null method=GET "
                            + "path={} request={} retryCount=0",
                    CALLBACK_PATH,
                    logSanitizer.sanitizeText("signature=" + signature + "&msg_signature="
                            + messageSignature + "&timestamp=" + timestamp + "&nonce=" + nonce
                            + "&echostr=" + echo));
        }
        if (!properties.isEnabled()) {
            return logVerificationResponse(CallbackResult.notFound(), startedAt, null);
        }
        try {
            if (messageSignature != null && !messageSignature.isBlank()) {
                String decryptedEcho = messageSecurity.decryptMessage(
                        messageSignature, timestamp, nonce, echo);
                return logVerificationResponse(
                        CallbackResult.success(decryptedEcho), startedAt, decryptedEcho);
            }
            if (!messageSecurity.verifyPlaintextSignature(signature, timestamp, nonce)) {
                return logVerificationResponse(
                        CallbackResult.forbidden(WechatVirtualPaymentMessage.INVALID_SIGNATURE_MESSAGE),
                        startedAt, WechatVirtualPaymentMessage.INVALID_SIGNATURE_MESSAGE);
            }
            return logVerificationResponse(CallbackResult.success(echo), startedAt, echo);
        } catch (IllegalArgumentException exception) {
            logInteractionException("虚拟支付回调地址校验", null, startedAt, exception);
            return logVerificationResponse(
                    CallbackResult.forbidden(WechatVirtualPaymentMessage.INVALID_SIGNATURE_MESSAGE),
                    startedAt, WechatVirtualPaymentMessage.INVALID_SIGNATURE_MESSAGE);
        }
    }

    /** 验签、必要时解密，再分发虚拟支付通知。 */
    public CallbackResult notify(
            String body,
            String signature,
            String messageSignature,
            String timestamp,
            String nonce
    ) {
        long startedAt = System.nanoTime();
        if (log.isInfoEnabled()) {
            log.info("微信交互请求 operation=虚拟支付通知回调 referenceNo=null userId=null method=POST "
                            + "path={} request={} retryCount=0",
                    CALLBACK_PATH,
                    logSanitizer.sanitizeText("signature=" + signature + "&msg_signature="
                            + messageSignature + "&timestamp=" + timestamp + "&nonce=" + nonce)
                            + " body=" + logSanitizer.sanitizeXml(body));
        }
        if (!properties.isEnabled()) {
            return logNotificationResponse(CallbackResult.notFound(), null, startedAt);
        }
        String referenceNo = null;
        try {
            String encrypted = notificationCodec.extractEncrypted(body);
            String payload;
            if (encrypted != null) {
                payload = messageSecurity.decryptMessage(messageSignature, timestamp, nonce, encrypted);
            } else {
                if (!messageSecurity.verifyPlaintextSignature(signature, timestamp, nonce)) {
                    return CallbackResult.forbidden(FAILURE_RESPONSE);
                }
                payload = body;
            }
            if (log.isInfoEnabled()) {
                log.info("微信交互解密结果 operation=虚拟支付通知回调 "
                                + "referenceNo=null userId=null payload={}",
                        logSanitizer.sanitizeXml(payload));
            }
            WechatVirtualPaymentNotification notification = notificationCodec.parse(payload);
            referenceNo = notification.orderNo();
            if (log.isInfoEnabled()) {
                log.info("微信交互解析结果 operation=虚拟支付通知回调 referenceNo={} userId=null "
                                + "notificationType={}", referenceNo, notification.type());
            }
            return logNotificationResponse(
                    CallbackResult.success(notificationService.handle(notification)),
                    referenceNo, startedAt);
        } catch (IllegalArgumentException exception) {
            logInteractionException("虚拟支付通知回调", referenceNo, startedAt, exception);
            return logNotificationResponse(
                    CallbackResult.badRequest(FAILURE_RESPONSE), referenceNo, startedAt);
        } catch (RuntimeException exception) {
            logInteractionException("虚拟支付通知回调", referenceNo, startedAt, exception);
            return logNotificationResponse(
                    CallbackResult.internalServerError(FAILURE_RESPONSE), referenceNo, startedAt);
        }
    }

    /** 记录地址校验应答，回显值始终按 echostr 字段脱敏。 */
    private CallbackResult logVerificationResponse(
            CallbackResult result,
            long startedAt,
            String responseBody
    ) {
        if (log.isInfoEnabled()) {
            log.info("微信交互响应 operation=虚拟支付回调地址校验 "
                            + "referenceNo=null userId=null httpStatus={} "
                            + "response={} elapsedMs={} retryCount=0",
                    result.statusCode(), logSanitizer.sanitizeText("echostr=" + responseBody),
                    elapsedMillis(startedAt));
        }
        return result;
    }

    /** 记录通知处理应答。 */
    private CallbackResult logNotificationResponse(
            CallbackResult result,
            String referenceNo,
            long startedAt
    ) {
        if (log.isInfoEnabled()) {
            log.info("微信交互响应 operation=虚拟支付通知回调 referenceNo={} userId=null httpStatus={} "
                            + "response={} elapsedMs={} retryCount=0",
                    referenceNo, result.statusCode(), logSanitizer.sanitizeXml(result.body()),
                    elapsedMillis(startedAt));
        }
        return result;
    }

    /** 记录不包含原始签名或密文的回调异常。 */
    private void logInteractionException(
            String operation,
            String referenceNo,
            long startedAt,
            RuntimeException exception
    ) {
        if (log.isWarnEnabled()) {
            log.warn("微信交互异常 operation={} referenceNo={} userId=null elapsedMs={} retryCount=0 "
                            + "exceptionType={} message={}",
                    operation, referenceNo, elapsedMillis(startedAt), exception.getClass().getSimpleName(),
                    logSanitizer.sanitizeText(exception.getMessage()));
        }
    }

    /** 计算调用耗时毫秒数。 */
    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    /** 不包含 HTTP 框架类型的回调处理结果。 */
    public static final class CallbackResult {

        /** HTTP 状态码 */
        private final int statusCode;

        /** 回调响应正文 */
        private final String body;

        private CallbackResult(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        /** 创建成功结果。 */
        public static CallbackResult success(String body) {
            return new CallbackResult(200, Objects.requireNonNull(body));
        }

        /** 创建签名拒绝结果。 */
        public static CallbackResult forbidden(String body) {
            return new CallbackResult(403, Objects.requireNonNull(body));
        }

        /** 创建请求错误结果。 */
        public static CallbackResult badRequest(String body) {
            return new CallbackResult(400, Objects.requireNonNull(body));
        }

        /** 创建内部错误结果。 */
        public static CallbackResult internalServerError(String body) {
            return new CallbackResult(500, Objects.requireNonNull(body));
        }

        /** 创建未启用结果。 */
        public static CallbackResult notFound() {
            return new CallbackResult(404, null);
        }

        /** @return HTTP 状态码。 */
        public int statusCode() {
            return statusCode;
        }

        /** @return 回调响应正文。 */
        public String body() {
            return body;
        }
    }
}
