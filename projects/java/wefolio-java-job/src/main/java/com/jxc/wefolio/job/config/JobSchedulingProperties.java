package com.jxc.wefolio.job.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 后台调度安全停用配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "job-scheduling")
public class JobSchedulingProperties {

    /** 非法停用等待时间使用的固定异常消息。 */
    private static final String INVALID_DISABLE_TIMEOUT_MESSAGE =
            "job-scheduling.disable-timeout 必须大于零";

    /** 停用接口等待已准入后台工作自然结束的最长时间。 */
    private Duration disableTimeout = Duration.ofSeconds(10);

    /** 校验安全停用的有界等待参数。 */
    @PostConstruct
    public void validate() {
        if (disableTimeout == null || disableTimeout.isZero() || disableTimeout.isNegative()) {
            throw new IllegalStateException(INVALID_DISABLE_TIMEOUT_MESSAGE);
        }
    }
}
