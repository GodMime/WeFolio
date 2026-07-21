package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.config.WechatPayConfiguration;
import com.jxc.wefolio.config.WechatPayProperties;
import com.jxc.wefolio.controller.WechatPayNotificationController;
import com.jxc.wefolio.dict.PayChannelDict;
import com.jxc.wefolio.dict.PaymentChannelDict;
import org.junit.jupiter.api.Test;

import java.lang.reflect.AnnotatedElement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 旧普通微信支付待移除标记测试。
 */
@SuppressWarnings("removal")
class LegacyWechatPayDeprecationStructureTest {

    /** 旧普通支付类型和公开方法必须明确标记为待移除。 */
    @Test
    void legacyWechatPayTypesAndMethodsShouldBeDeprecatedForRemoval() throws Exception {
        assertDeprecatedForRemoval(WechatPayProperties.class);
        assertDeprecatedForRemoval(WechatPayConfiguration.class);
        assertDeprecatedForRemoval(WechatPayClient.class);
        assertDeprecatedForRemoval(SdkWechatPayClient.class);
        assertDeprecatedForRemoval(WechatRechargeNotificationService.class);
        assertDeprecatedForRemoval(WechatPayNotificationController.class);
        assertDeprecatedForRemoval(WechatPayNotificationException.class);
        assertDeprecatedForRemoval(WechatPayOperationException.class);
        assertDeprecatedForRemoval(WechatPaySignatureException.class);
        assertDeprecatedForRemoval(PayChannelDict.class);
        assertDeprecatedForRemoval(PaymentChannelDict.class.getField("WECHAT_PAY"));

        assertDeprecatedMethod(WechatPayProperties.class,
                "validateEnabledConfiguration");
        assertDeprecatedMethod(WechatPayConfiguration.class,
                "wechatPayClient", WechatPayProperties.class);
        assertDeprecatedMethod(WechatPayClient.class,
                "prepay", WechatPayClient.PrepayCommand.class);
        assertDeprecatedMethod(WechatPayClient.class,
                "queryByMerchantOrderNo", String.class);
        assertDeprecatedMethod(WechatPayClient.class,
                "parseNotification", WechatPayClient.NotificationRequest.class);
        assertDeprecatedMethod(SdkWechatPayClient.class,
                "prepay", WechatPayClient.PrepayCommand.class);
        assertDeprecatedMethod(SdkWechatPayClient.class,
                "queryByMerchantOrderNo", String.class);
        assertDeprecatedMethod(SdkWechatPayClient.class,
                "parseNotification", WechatPayClient.NotificationRequest.class);
        assertDeprecatedMethod(WechatRechargeNotificationService.class,
                "handle", WechatPayClient.NotificationRequest.class);
        assertDeprecatedMethod(WechatPayNotificationController.class,
                "notifyRecharge", String.class, String.class, String.class,
                String.class, String.class, String.class);
        assertDeprecatedMethod(RechargeOrderTransactionService.class,
                "markPrepayReady", String.class, String.class);
        assertDeprecatedMethod(RechargeOrderTransactionService.class,
                "markPrepayFailed", String.class);
        assertDeprecatedMethod(RechargeOrderTransactionService.class,
                "settle", WechatPayClient.Transaction.class);
    }

    /** 校验方法上的待移除标记。 */
    private void assertDeprecatedMethod(
            Class<?> type,
            String methodName,
            Class<?>... parameterTypes
    ) throws Exception {
        assertDeprecatedForRemoval(type.getDeclaredMethod(methodName, parameterTypes));
    }

    /** 校验元素已标记为待移除。 */
    private void assertDeprecatedForRemoval(AnnotatedElement element) {
        Deprecated annotation = element.getAnnotation(Deprecated.class);
        assertThat(annotation)
                .as("%s 应标记 @Deprecated", element)
                .isNotNull();
        assertThat(annotation.forRemoval())
                .as("%s 应设置 forRemoval=true", element)
                .isTrue();
    }
}
