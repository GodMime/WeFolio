package com.jxc.wefolio.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微信支付官方 SDK 依赖版本契约测试。
 */
class WechatPayDependencyTest {

    @Test
    void pomShouldPinOfficialWechatPaySdkVersion() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom)
                .contains("<wechatpay-java.version>0.2.17</wechatpay-java.version>")
                .contains("<groupId>com.github.wechatpay-apiv3</groupId>")
                .contains("<artifactId>wechatpay-java</artifactId>")
                .contains("<version>${wechatpay-java.version}</version>")
                .doesNotContain("LATEST")
                .doesNotContain("RELEASE");
    }
}
