package com.jxc.wefolio.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 本地进程内缓存配置。 */
@Getter
@Component
@Profile("test")
@ConfigurationProperties(prefix = "cache.local")
public class LocalCacheProperties {

    /** 默认最大缓存条目数。 */
    public static final long DEFAULT_MAX_SIZE = 50_000L;

    /** 最大缓存条目数。 */
    private long maxSize = DEFAULT_MAX_SIZE;

    /** 设置最大缓存条目数。 */
    public void setMaxSize(long maxSize) {
        if (maxSize <= 0L) {
            throw new IllegalArgumentException("本地缓存最大容量必须大于 0");
        }
        this.maxSize = maxSize;
    }
}
