package com.jxc.wefolio.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 团队作品集功能开关配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "wefolio.team-portfolio")
public class TeamPortfolioProperties {

    /** 是否启用团队作品集功能 */
    private boolean enabled = false;
}
