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

    /** 非法暂停时长使用的固定异常消息。 */
    private static final String INVALID_PAUSE_DURATION_MESSAGE =
            "job-scheduling.pause-duration 必须大于零";

    /** 暂停窗口不足以覆盖排空等待时使用的固定异常消息。 */
    private static final String PAUSE_DURATION_NOT_LONGER_THAN_DISABLE_TIMEOUT_MESSAGE =
            "job-scheduling.pause-duration 必须大于 job-scheduling.disable-timeout";

    /** 停用接口等待已准入后台工作自然结束的最长时间。 */
    private Duration disableTimeout = Duration.ofSeconds(10);

    /** 发布窗口内拒绝新后台工作的时长。 */
    private Duration pauseDuration = Duration.ofMinutes(10);

    /** 校验安全停用的有界等待参数。 */
    @PostConstruct
    public void validate() {
        if (disableTimeout == null || disableTimeout.isZero() || disableTimeout.isNegative()) {
            throw new IllegalStateException(INVALID_DISABLE_TIMEOUT_MESSAGE);
        }
        if (pauseDuration == null || pauseDuration.isZero() || pauseDuration.isNegative()) {
            throw new IllegalStateException(INVALID_PAUSE_DURATION_MESSAGE);
        }
        if (pauseDuration.compareTo(disableTimeout) <= 0) {
            throw new IllegalStateException(PAUSE_DURATION_NOT_LONGER_THAN_DISABLE_TIMEOUT_MESSAGE);
        }
    }
}
