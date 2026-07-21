package com.jxc.wefolio.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 作品集打开链路耗时日志配置。 */
@Getter
@Component
@ConfigurationProperties(prefix = "wefolio.performance.portfolio-open")
public class PortfolioOpenPerformanceProperties {

    /** 慢请求阈值。 */
    private Duration slowThreshold = Duration.ofSeconds(2);

    /** 正常请求采样率。 */
    private double normalSampleRate = 0.01D;

    /** 设置慢请求阈值。 */
    public void setSlowThreshold(Duration slowThreshold) {
        if (slowThreshold == null || slowThreshold.isZero() || slowThreshold.isNegative()) {
            throw new IllegalArgumentException("作品集打开慢请求阈值必须大于 0");
        }
        this.slowThreshold = slowThreshold;
    }

    /** 设置正常请求采样率。 */
    public void setNormalSampleRate(double normalSampleRate) {
        if (Double.isNaN(normalSampleRate) || normalSampleRate < 0D || normalSampleRate > 1D) {
            throw new IllegalArgumentException("作品集打开正常日志采样率必须位于 0 到 1 之间");
        }
        this.normalSampleRate = normalSampleRate;
    }
}
