package com.jxc.wefolio.job.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** runtime 远程结算地址配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "wefolio.runtime")
public class RuntimeProperties {
    /** runtime HTTPS 根地址。 */
    private String baseUrl;
}
