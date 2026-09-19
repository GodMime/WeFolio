package com.jxc.wefolio.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 官方原码环境配置，名片由手机端绘制。 */
@Data
@Component
@ConfigurationProperties(prefix = "portfolio.miniapp-code")
public class PortfolioMiniappCodeProperties {
    /** 正式版默认环境，体验配置与正式缓存隔离。 */
    private String envVersion = "release";
    /** 默认检查已发布页面路径。 */
    private boolean checkPath = true;
}
