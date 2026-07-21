package com.jxc.wefolio.job.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 后台积分密钥配置，与 Runtime 人工加分接口复用同一环境变量。
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.point")
public class AdminPointProperties {

    /** 后台积分密钥 */
    private String secret;
}
