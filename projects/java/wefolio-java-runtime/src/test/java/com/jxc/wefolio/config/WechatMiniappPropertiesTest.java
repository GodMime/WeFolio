package com.jxc.wefolio.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** 微信小程序 HTTP 超时配置测试。 */
class WechatMiniappPropertiesTest {

    @Test
    void defaultsAndYamlOverridesShouldBeStable() throws IOException {
        WechatMiniappProperties properties = new WechatMiniappProperties();

        assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(Files.readString(Path.of("src/main/resources/application.yml")))
                .contains("connect-timeout: ${WECHAT_MINIAPP_CONNECT_TIMEOUT:1s}")
                .contains("read-timeout: ${WECHAT_MINIAPP_READ_TIMEOUT:3s}");
    }

    @Test
    void nonPositiveTimeoutsShouldBeRejected() {
        WechatMiniappProperties properties = new WechatMiniappProperties();

        assertThatIllegalArgumentException().isThrownBy(
                () -> properties.setConnectTimeout(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(
                () -> properties.setReadTimeout(Duration.ofMillis(-1)));
    }
}
