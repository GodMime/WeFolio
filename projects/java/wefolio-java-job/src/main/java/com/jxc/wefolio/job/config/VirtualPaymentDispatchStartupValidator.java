package com.jxc.wefolio.job.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 微信虚拟支付分发启用时的跨配置启动校验器。
 */
@Component
@RequiredArgsConstructor
public class VirtualPaymentDispatchStartupValidator {

    /** HTTPS 地址前缀。 */
    private static final String HTTPS_PREFIX = "https://";

    /** 分发启停配置。 */
    private final VirtualPaymentDispatchProperties dispatchProperties;

    /** runtime 地址配置。 */
    private final RuntimeProperties runtimeProperties;

    /** 内部调用密钥配置。 */
    private final AdminPointProperties adminPointProperties;

    /** 启用分发时校验 runtime 地址和内部密钥。 */
    @PostConstruct
    public void validate() {
        if (!dispatchProperties.isEnabled()) {
            return;
        }
        String baseUrl = runtimeProperties.getBaseUrl();
        if (baseUrl == null || !baseUrl.startsWith(HTTPS_PREFIX)) {
            throw new IllegalStateException("WEFOLIO_RUNTIME_BASE_URL 必须配置为 HTTPS 地址");
        }
        String secret = adminPointProperties.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("虚拟支付分发已启用但缺少 ADMIN_POINT_SECRET");
        }
    }
}
