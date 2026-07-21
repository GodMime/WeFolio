package com.jxc.wefolio.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 微信交互日志脱敏组件测试。 */
class WechatInteractionLogSanitizerTest {

    private final WechatInteractionLogSanitizer sanitizer = new WechatInteractionLogSanitizer();

    @Test
    void shouldSanitizeNestedJsonSecretsAndIdentifiers() {
        String json = "{\"access_token\":\"token-secret\",\"sessionKey\":\"session-secret\","
                + "\"data\":{\"openid\":\"openid-secret-1234\",\"items\":[{"
                + "\"phoneNumber\":\"+8613812348000\",\"signature\":\"signature-secret\"}]}}";

        String sanitized = sanitizer.sanitizeJson(json);

        assertThat(sanitized)
                .contains("\"access_token\":\"***\"")
                .contains("\"sessionKey\":\"***\"")
                .contains("\"openid\":\"***1234\"")
                .contains("\"phoneNumber\":\"***8000\"")
                .contains("\"signature\":\"***\"")
                .doesNotContain("token-secret")
                .doesNotContain("session-secret")
                .doesNotContain("openid-secret-1234")
                .doesNotContain("+8613812348000")
                .doesNotContain("signature-secret");
    }

    @Test
    void shouldSanitizeSensitiveUrlParameters() {
        String url = "https://api.weixin.qq.com/xpay/query_order?access_token=token-secret"
                + "&pay_sig=pay-signature&signature=user-signature"
                + "&openid=openid-secret-1234&order_id=WFR001";

        String sanitized = sanitizer.sanitizeUrl(url);

        assertThat(sanitized)
                .contains("access_token=***")
                .contains("pay_sig=***")
                .contains("signature=***")
                .contains("openid=***1234")
                .contains("order_id=WFR001")
                .doesNotContain("token-secret")
                .doesNotContain("pay-signature")
                .doesNotContain("user-signature")
                .doesNotContain("openid-secret-1234");
    }

    @Test
    void shouldSanitizeXmlSecretsAndIdentifiers() {
        String xml = "<xml><Encrypt><![CDATA[encrypted-secret]]></Encrypt>"
                + "<openid><![CDATA[openid-secret-1234]]></openid>"
                + "<phoneNumber>13812348000</phoneNumber><order_id>WFR001</order_id></xml>";

        String sanitized = sanitizer.sanitizeXml(xml);

        assertThat(sanitized)
                .contains("<Encrypt>***</Encrypt>")
                .contains("<openid>***1234</openid>")
                .contains("<phoneNumber>***8000</phoneNumber>")
                .contains("<order_id>WFR001</order_id>")
                .doesNotContain("encrypted-secret")
                .doesNotContain("openid-secret-1234")
                .doesNotContain("13812348000");
    }

    @Test
    void shouldSanitizeUnstructuredTextConservatively() {
        String text = "request failed access_token=token-secret, session_key=session-secret, "
                + "openid=openid-secret-1234";

        String sanitized = sanitizer.sanitizeText(text);

        assertThat(sanitized)
                .contains("access_token=***")
                .contains("session_key=***")
                .contains("openid=***1234")
                .doesNotContain("token-secret")
                .doesNotContain("session-secret")
                .doesNotContain("openid-secret-1234");
    }
}
