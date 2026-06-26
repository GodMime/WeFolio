package com.jxc.wefolio.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 后台积分配置 — 用于一期人工加分接口的内部密钥校验。
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.point")
public class AdminPointProperties {

    /** 后台人工加分接口密钥，生产环境通过环境变量注入 */
    private String secret;
}
