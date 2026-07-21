package com.jxc.wefolio.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 本地缓存配置测试。 */
class LocalCachePropertiesTest {

    @Test
    void defaultsMaximumSizeToFiftyThousand() {
        LocalCacheProperties properties = new LocalCacheProperties();

        assertThat(properties.getMaxSize()).isEqualTo(50_000L);
    }

    @Test
    void rejectsNonPositiveMaximumSize() {
        LocalCacheProperties properties = new LocalCacheProperties();

        assertThatThrownBy(() -> properties.setMaxSize(0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("本地缓存最大容量必须大于 0");
    }
}
