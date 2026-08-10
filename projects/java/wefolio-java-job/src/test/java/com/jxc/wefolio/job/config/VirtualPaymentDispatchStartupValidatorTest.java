package com.jxc.wefolio.job.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 微信虚拟支付分发启动依赖校验测试。
 */
class VirtualPaymentDispatchStartupValidatorTest {

    /** 分发关闭时不要求 runtime 地址和内部密钥。 */
    @Test
    void disabledDispatchShouldAllowEmptyDependencies() {
        assertThatCode(() -> validator(false, null, null).validate())
                .doesNotThrowAnyException();
    }

    /** 分发开启时 runtime 地址必须为 HTTPS。 */
    @Test
    void enabledDispatchShouldRejectNonHttpsRuntimeUrl() {
        assertThatThrownBy(() -> validator(true, "http://runtime.example.com", "secret").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
    }

    /** 分发开启时内部密钥不能为空。 */
    @Test
    void enabledDispatchShouldRejectBlankAdminSecret() {
        assertThatThrownBy(() -> validator(true, "https://runtime.example.com", " ").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_POINT_SECRET");
    }

    /** 分发开启且依赖完整时允许启动。 */
    @Test
    void enabledDispatchShouldAcceptCompleteDependencies() {
        assertThatCode(() -> validator(true, "https://runtime.example.com", "secret").validate())
                .doesNotThrowAnyException();
    }

    /** 构造指定配置的启动校验器。 */
    private VirtualPaymentDispatchStartupValidator validator(
            boolean enabled,
            String runtimeUrl,
            String adminSecret
    ) {
        VirtualPaymentDispatchProperties dispatchProperties = new VirtualPaymentDispatchProperties();
        dispatchProperties.setEnabled(enabled);
        RuntimeProperties runtimeProperties = new RuntimeProperties();
        runtimeProperties.setBaseUrl(runtimeUrl);
        AdminPointProperties adminPointProperties = new AdminPointProperties();
        adminPointProperties.setSecret(adminSecret);
        return new VirtualPaymentDispatchStartupValidator(
                dispatchProperties, runtimeProperties, adminPointProperties);
    }
}
