package com.jxc.wefolio.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 认证令牌配置 — 独立于微信 AppSecret 的应用层令牌密钥。
 */
@Data
@Component
@ConfigurationProperties(prefix = "auth.token")
public class AuthTokenProperties {

    /** 维护者登录令牌加密密钥 */
    private String secret;
}
