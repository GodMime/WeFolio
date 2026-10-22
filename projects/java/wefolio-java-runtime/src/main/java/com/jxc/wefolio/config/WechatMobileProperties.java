package com.jxc.wefolio.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 微信移动应用配置 — 默认关闭，凭证仅由服务端环境变量注入。 */
@Data
@Component
@ConfigurationProperties(prefix = "wechat.mobile")
public class WechatMobileProperties {

    /** 是否启用移动应用已有账号登录。 */
    @Value("${WECHAT_MOBILE_ENABLED:false}")
    private boolean enabled;

    /** 微信开放平台移动应用编号。 */
    @Value("${WECHAT_MOBILE_APP_ID:}")
    private String appId;

    /** 微信开放平台移动应用密钥，禁止下发客户端。 */
    @Value("${WECHAT_MOBILE_APP_SECRET:}")
    private String appSecret;

    /** 配置完整时才允许交换授权凭证。 */
    public boolean isConfigured() {
        return enabled && appId != null && !appId.isBlank()
                && appSecret != null && !appSecret.isBlank();
    }
}
