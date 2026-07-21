package com.jxc.wefolio.service.payment;

import com.wechat.pay.java.core.exception.ValidationException;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayRequest;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayWithRequestPaymentResponse;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.model.TransactionAmount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 官方微信支付 SDK 适配器测试。
 */
@ExtendWith(MockitoExtension.class)
class SdkWechatPayClientTest {

    /** JSAPI 服务模拟。 */
    @Mock
    private JsapiServiceExtension jsapiService;

    /** 通知解析器模拟。 */
    @Mock
    private NotificationParser notificationParser;

    @Test
    void prepayShouldMapCompleteMiniappOrderAndPaymentParameters() {
        PrepayWithRequestPaymentResponse sdkResponse = new PrepayWithRequestPaymentResponse();
        sdkResponse.setTimeStamp("1784273400");
        sdkResponse.setNonceStr("nonce-value");
        sdkResponse.setPackageVal("prepay_id=wx-prepay-1");
        sdkResponse.setSignType("RSA");
        sdkResponse.setPaySign("pay-sign");
        when(jsapiService.prepayWithRequestPayment(any(PrepayRequest.class))).thenReturn(sdkResponse);
        WechatPayClient.PrepayCommand command = new WechatPayClient.PrepayCommand(
                "wx-test-app-id",
                "1900000001",
                "映期Folio-50 元档",
                "WFR20260717153000123A3B7K9M2Q5R",
                5000,
                "openid-1",
                LocalDateTime.of(2026, 7, 17, 15, 45),
                "https://api.we-folio.dingchenyong.top/api/payment/wechat/recharge/notify"
        );

        WechatPayClient.PrepayResult result = client().prepay(command);

        ArgumentCaptor<PrepayRequest> captor = ArgumentCaptor.forClass(PrepayRequest.class);
        org.mockito.Mockito.verify(jsapiService).prepayWithRequestPayment(captor.capture());
        assertThat(captor.getValue()).satisfies(request -> {
            assertThat(request.getAppid()).isEqualTo("wx-test-app-id");
            assertThat(request.getMchid()).isEqualTo("1900000001");
            assertThat(request.getDescription()).isEqualTo("映期Folio-50 元档");
            assertThat(request.getOutTradeNo()).isEqualTo("WFR20260717153000123A3B7K9M2Q5R");
            assertThat(request.getAmount().getTotal()).isEqualTo(5000);
            assertThat(request.getAmount().getCurrency()).isEqualTo("CNY");
            assertThat(request.getPayer().getOpenid()).isEqualTo("openid-1");
            assertThat(request.getTimeExpire()).isEqualTo("2026-07-17T15:45:00+08:00");
        });
        assertThat(result.prepayId()).isEqualTo("wx-prepay-1");
        assertThat(result.packageValue()).isEqualTo("prepay_id=wx-prepay-1");
        assertThat(result.paySign()).isEqualTo("pay-sign");
    }

    /** 验证微信支付订单失效时间会移除纳秒，输出微信要求的秒级 RFC3339 格式。 */
    @Test
    void prepayShouldFormatNanosecondExpiryWithSecondPrecision() {
        PrepayWithRequestPaymentResponse sdkResponse = new PrepayWithRequestPaymentResponse();
        sdkResponse.setTimeStamp("1784273400");
        sdkResponse.setNonceStr("nonce-value");
        sdkResponse.setPackageVal("prepay_id=wx-prepay-1");
        sdkResponse.setSignType("RSA");
        sdkResponse.setPaySign("pay-sign");
        when(jsapiService.prepayWithRequestPayment(any(PrepayRequest.class))).thenReturn(sdkResponse);
        WechatPayClient.PrepayCommand command = new WechatPayClient.PrepayCommand(
                "wx-test-app-id", "1900000001", "映期Folio-50 元档",
                "WFR20260717153000123A3B7K9M2Q5R", 5000, "openid-1",
                LocalDateTime.of(2026, 7, 17, 23, 7, 44, 13_653_716),
                "https://api.we-folio.dingchenyong.top/api/payment/wechat/recharge/notify");

        client().prepay(command);

        ArgumentCaptor<PrepayRequest> captor = ArgumentCaptor.forClass(PrepayRequest.class);
        org.mockito.Mockito.verify(jsapiService).prepayWithRequestPayment(captor.capture());
        assertThat(captor.getValue().getTimeExpire())
                .isEqualTo("2026-07-17T23:07:44+08:00");
    }

    @Test
    void prepayShouldRejectMalformedWechatResponseAsRemoteOperationFailure() {
        PrepayWithRequestPaymentResponse sdkResponse = new PrepayWithRequestPaymentResponse();
        sdkResponse.setPackageVal("invalid-package-value");
        when(jsapiService.prepayWithRequestPayment(any(PrepayRequest.class))).thenReturn(sdkResponse);
        WechatPayClient.PrepayCommand command = new WechatPayClient.PrepayCommand(
                "wx-test-app-id", "1900000001", "映期Folio-50 元档",
                "WFR20260717153000123A3B7K9M2Q5R", 5000, "openid-1",
                LocalDateTime.of(2026, 7, 17, 15, 45),
                "https://api.we-folio.dingchenyong.top/api/payment/wechat/recharge/notify");

        assertThatThrownBy(() -> client().prepay(command))
                .isInstanceOf(WechatPayOperationException.class)
                .hasMessage("微信支付预下单响应无效");
    }

    @Test
    void queryShouldMapWechatTransactionWithoutExposingSdkModel() {
        when(jsapiService.queryOrderByOutTradeNo(any())).thenReturn(successTransaction());

        WechatPayClient.Transaction result = client().queryByMerchantOrderNo(
                "WFR20260717153000123A3B7K9M2Q5R");

        assertThat(result.merchantOrderNo()).isEqualTo("WFR20260717153000123A3B7K9M2Q5R");
        assertThat(result.tradeState()).isEqualTo(WechatPayClient.TradeState.SUCCESS);
        assertThat(result.currency()).isEqualTo("CNY");
        assertThat(result.amountFen()).isEqualTo(5000);
    }

    @Test
    void parseNotificationShouldPassOriginalHeadersAndBodyThenNormalizeTransaction() {
        when(notificationParser.parse(any(RequestParam.class), org.mockito.ArgumentMatchers.eq(Transaction.class)))
                .thenReturn(successTransaction());
        WechatPayClient.NotificationRequest notification = new WechatPayClient.NotificationRequest(
                "PUB_KEY_ID_123",
                "signature-value",
                "1784273400",
                "nonce-value",
                "WECHATPAY2-SHA256-RSA2048",
                "{\"id\":\"notification-1\",\"event_type\":\"TRANSACTION.SUCCESS\"}"
        );

        WechatPayClient.Transaction result = client().parseNotification(notification);

        ArgumentCaptor<RequestParam> captor = ArgumentCaptor.forClass(RequestParam.class);
        org.mockito.Mockito.verify(notificationParser).parse(
                captor.capture(), org.mockito.ArgumentMatchers.eq(Transaction.class));
        assertThat(captor.getValue()).satisfies(request -> {
            assertThat(request.getSerialNumber()).isEqualTo("PUB_KEY_ID_123");
            assertThat(request.getSignature()).isEqualTo("signature-value");
            assertThat(request.getMessage()).isEqualTo(
                    "1784273400\nnonce-value\n{\"id\":\"notification-1\","
                            + "\"event_type\":\"TRANSACTION.SUCCESS\"}\n");
            assertThat(request.getBody()).isEqualTo(
                    "{\"id\":\"notification-1\",\"event_type\":\"TRANSACTION.SUCCESS\"}");
            assertThat(request.getSignType()).isEqualTo("WECHATPAY2-SHA256-RSA2048");
        });
        assertThat(result.tradeState()).isEqualTo(WechatPayClient.TradeState.SUCCESS);
        assertThat(result.amountFen()).isEqualTo(5000);
        assertThat(result.successTime()).isEqualTo(LocalDateTime.of(2026, 7, 17, 15, 30, 8));
    }

    @Test
    void parseNotificationShouldTranslateSignatureFailure() {
        when(notificationParser.parse(any(RequestParam.class), org.mockito.ArgumentMatchers.eq(Transaction.class)))
                .thenThrow(new ValidationException("invalid signature"));

        assertThatThrownBy(() -> client().parseNotification(new WechatPayClient.NotificationRequest(
                "PUB_KEY_ID_123", "signature", "1784273400", "nonce", null, "{}")))
                .isInstanceOf(WechatPaySignatureException.class)
                .hasMessage("微信支付通知验签失败");
    }

    @Test
    void parseNotificationShouldRejectSignedNonSuccessEvent() {
        when(notificationParser.parse(any(RequestParam.class), org.mockito.ArgumentMatchers.eq(Transaction.class)))
                .thenReturn(successTransaction());

        assertThatThrownBy(() -> client().parseNotification(new WechatPayClient.NotificationRequest(
                "PUB_KEY_ID_123", "signature", "1784273400", "nonce", null,
                "{\"event_type\":\"TRANSACTION.CLOSED\"}")))
                .isInstanceOf(WechatPayNotificationException.class)
                .hasMessage("微信支付通知不是支付成功事件");
    }

    /**
     * 创建适配器。
     *
     * @return 微信支付适配器
     */
    private SdkWechatPayClient client() {
        return new SdkWechatPayClient(jsapiService, notificationParser, "1900000001");
    }

    /**
     * 构造官方 SDK 支付成功交易。
     *
     * @return SDK 交易对象
     */
    private Transaction successTransaction() {
        Transaction transaction = new Transaction();
        transaction.setAppid("wx-test-app-id");
        transaction.setMchid("1900000001");
        transaction.setOutTradeNo("WFR20260717153000123A3B7K9M2Q5R");
        transaction.setTransactionId("4200000000001");
        transaction.setTradeState(Transaction.TradeStateEnum.SUCCESS);
        transaction.setSuccessTime("2026-07-17T15:30:08+08:00");
        TransactionAmount amount = new TransactionAmount();
        amount.setCurrency("CNY");
        amount.setTotal(5000);
        transaction.setAmount(amount);
        return transaction;
    }
}
