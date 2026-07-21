package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.exception.BusinessException;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * 微信虚拟支付签名器。
 */
@Component
public class WechatVirtualPaymentSigner {

    /** HMAC-SHA256 算法名。 */
    private static final String HMAC_SHA_256 = "HmacSHA256";

    /**
     * 生成服务端接口支付签名。
     *
     * @param appKey 虚拟支付 AppKey
     * @param uri 实际请求路径
     * @param body 已序列化且将原样发送的请求正文
     * @return 小写十六进制签名
     */
    public String paySignature(String appKey, String uri, String body) {
        return hmac(appKey, uri + "&" + body);
    }

    /**
     * 生成用户会话签名。
     *
     * @param sessionKey 微信会话密钥
     * @param body 已序列化且将原样发送的请求正文
     * @return 小写十六进制签名
     */
    public String userSignature(String sessionKey, String body) {
        return hmac(sessionKey, body);
    }

    /** 执行 HMAC-SHA256。 */
    private String hmac(String key, String content) {
        if (key == null || key.isBlank()) {
            throw new BusinessException("微信虚拟支付签名密钥不能为空");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_SHA_256));
            return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("微信虚拟支付签名失败", exception);
        }
    }
}
