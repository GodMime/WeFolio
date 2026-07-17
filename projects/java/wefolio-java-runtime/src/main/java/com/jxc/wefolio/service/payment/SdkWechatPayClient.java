package com.jxc.wefolio.service.payment;

import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.message.RechargeMessage;
import com.wechat.pay.java.core.exception.MalformedMessageException;
import com.wechat.pay.java.core.exception.ValidationException;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.Amount;
import com.wechat.pay.java.service.payments.jsapi.model.Payer;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayRequest;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayWithRequestPaymentResponse;
import com.wechat.pay.java.service.payments.jsapi.model.QueryOrderByOutTradeNoRequest;
import com.wechat.pay.java.service.payments.model.TransactionAmount;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 基于官方 wechatpay-java 的微信支付适配器。
 */
@Slf4j
public class SdkWechatPayClient implements WechatPayClient {

    /** 人民币币种编码。 */
    private static final String CURRENCY_CNY = "CNY";

    /** 小程序预支付标识前缀。 */
    private static final String PREPAY_ID_PREFIX = "prepay_id=";

    /** 支付成功通知事件类型。 */
    private static final String TRANSACTION_SUCCESS_EVENT = "TRANSACTION.SUCCESS";

    /** 上海时区。 */
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");

    /** 微信支付要求的秒级 RFC3339 时间格式。 */
    private static final DateTimeFormatter WECHAT_RFC3339_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /** 官方 JSAPI 服务。 */
    private final JsapiServiceExtension jsapiService;

    /** 官方通知解析器。 */
    private final NotificationParser notificationParser;

    /** 商户号。 */
    private final String merchantId;

    /**
     * 创建微信支付 SDK 适配器。
     *
     * @param jsapiService 官方 JSAPI 服务
     * @param notificationParser 官方通知解析器
     * @param merchantId 商户号
     */
    public SdkWechatPayClient(
            JsapiServiceExtension jsapiService,
            NotificationParser notificationParser,
            String merchantId
    ) {
        this.jsapiService = jsapiService;
        this.notificationParser = notificationParser;
        this.merchantId = merchantId;
    }

    @Override
    public PrepayResult prepay(PrepayCommand command) {
        PrepayRequest request = new PrepayRequest();
        request.setAppid(command.appId());
        request.setMchid(command.merchantId());
        request.setDescription(command.description());
        request.setOutTradeNo(command.merchantOrderNo());
        request.setTimeExpire(command.expireAt().atZone(SHANGHAI_ZONE)
                .format(WECHAT_RFC3339_TIME_FORMATTER));
        request.setNotifyUrl(command.notifyUrl());
        Amount amount = new Amount();
        amount.setTotal(command.amountFen());
        amount.setCurrency(CURRENCY_CNY);
        request.setAmount(amount);
        Payer payer = new Payer();
        payer.setOpenid(command.payerOpenId());
        request.setPayer(payer);
        log.info("微信支付预下单开始: merchantOrderNo={}, amountFen={}, expireAt={}",
                command.merchantOrderNo(), command.amountFen(), command.expireAt());
        PrepayWithRequestPaymentResponse response = jsapiService.prepayWithRequestPayment(request);
        log.info("微信支付预下单完成: merchantOrderNo={}, signType={}",
                command.merchantOrderNo(), response.getSignType());
        return new PrepayResult(
                extractPrepayId(response.getPackageVal()),
                response.getTimeStamp(),
                response.getNonceStr(),
                response.getPackageVal(),
                response.getSignType(),
                response.getPaySign()
        );
    }

    @Override
    public WechatPayClient.Transaction queryByMerchantOrderNo(String merchantOrderNo) {
        QueryOrderByOutTradeNoRequest request = new QueryOrderByOutTradeNoRequest();
        request.setMchid(merchantId);
        request.setOutTradeNo(merchantOrderNo);
        log.info("微信支付主动查单开始: merchantOrderNo={}", merchantOrderNo);
        com.wechat.pay.java.service.payments.model.Transaction transaction =
                jsapiService.queryOrderByOutTradeNo(request);
        WechatPayClient.Transaction result = normalizeTransaction(transaction);
        log.info("微信支付主动查单完成: merchantOrderNo={}, tradeState={}",
                merchantOrderNo, result.tradeState());
        return result;
    }

    @Override
    public WechatPayClient.Transaction parseNotification(NotificationRequest request) {
        RequestParam requestParam = new RequestParam.Builder()
                .serialNumber(request.serialNumber())
                .signature(request.signature())
                .timestamp(request.timestamp())
                .nonce(request.nonce())
                .signType(request.signType())
                .body(request.body())
                .build();
        try {
            com.wechat.pay.java.service.payments.model.Transaction transaction =
                    notificationParser.parse(
                            requestParam,
                            com.wechat.pay.java.service.payments.model.Transaction.class
                    );
            validateNotificationEvent(request.body());
            return normalizeTransaction(transaction);
        } catch (ValidationException exception) {
            throw new WechatPaySignatureException(
                    RechargeMessage.NOTIFICATION_SIGNATURE_INVALID_MESSAGE, exception);
        } catch (MalformedMessageException exception) {
            throw new WechatPayNotificationException(
                    RechargeMessage.NOTIFICATION_INVALID_MESSAGE, exception);
        }
    }

    /**
     * 在官方 SDK 完成验签解密后，确认原始通知确实为支付成功事件。
     *
     * @param body 原始通知请求体
     */
    private void validateNotificationEvent(String body) {
        try {
            String eventType = JSONObject.parseObject(body).getString("event_type");
            if (!TRANSACTION_SUCCESS_EVENT.equals(eventType)) {
                throw new WechatPayNotificationException(
                        RechargeMessage.NOTIFICATION_NOT_SUCCESS_MESSAGE, null);
            }
        } catch (WechatPayNotificationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new WechatPayNotificationException(
                    RechargeMessage.NOTIFICATION_INVALID_MESSAGE, exception);
        }
    }

    /**
     * 标准化官方 SDK 交易对象。
     */
    private WechatPayClient.Transaction normalizeTransaction(
            com.wechat.pay.java.service.payments.model.Transaction transaction
    ) {
        TransactionAmount amount = transaction.getAmount();
        return new WechatPayClient.Transaction(
                transaction.getAppid(),
                transaction.getMchid(),
                transaction.getOutTradeNo(),
                transaction.getTransactionId(),
                transaction.getTradeState() == null
                        ? null
                        : TradeState.valueOf(transaction.getTradeState().name()),
                amount == null ? null : amount.getCurrency(),
                amount == null ? null : amount.getTotal(),
                parseSuccessTime(transaction.getSuccessTime())
        );
    }

    /**
     * 解析微信 RFC3339 支付成功时间。
     */
    private LocalDateTime parseSuccessTime(String successTime) {
        if (successTime == null || successTime.isBlank()) {
            return null;
        }
        return OffsetDateTime.parse(successTime).atZoneSameInstant(SHANGHAI_ZONE).toLocalDateTime();
    }

    /**
     * 从小程序 package 参数中提取 prepay_id。
     */
    private String extractPrepayId(String packageValue) {
        if (packageValue == null || !packageValue.startsWith(PREPAY_ID_PREFIX)) {
            throw new WechatPayOperationException(
                    RechargeMessage.PREPAY_RESPONSE_INVALID_MESSAGE, null);
        }
        String prepayId = packageValue.substring(PREPAY_ID_PREFIX.length());
        if (prepayId.isBlank()) {
            throw new WechatPayOperationException(
                    RechargeMessage.PREPAY_RESPONSE_INVALID_MESSAGE, null);
        }
        return prepayId;
    }
}
