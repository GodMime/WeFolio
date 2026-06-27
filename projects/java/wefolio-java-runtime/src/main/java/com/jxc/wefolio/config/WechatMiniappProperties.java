package com.jxc.wefolio.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 微信小程序配置 — 从环境变量注入 AppID 与 AppSecret
 */
@Data
@Component
@ConfigurationProperties(prefix = "wechat.miniapp")
public class WechatMiniappProperties {

    /** 微信小程序 AppID */
    private String appId;

    /** 微信小程序 AppSecret */
    private String appSecret;

    /** 是否打印微信接口详细日志（含 access_token、手机号等敏感字段），生产环境建议关闭 */
    private boolean logVerbose = false;
}
