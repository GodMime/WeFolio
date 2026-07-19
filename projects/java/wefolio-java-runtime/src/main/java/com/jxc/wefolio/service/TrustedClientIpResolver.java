package com.jxc.wefolio.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 可信客户端 IP 解析器 — 只读取容器已经按可信代理配置解析后的远端地址。
 *
 * <p>本类不直接读取任意 {@code X-Forwarded-For} 请求头，避免客户端伪造结算 IP。</p>
 */
@Component
public class TrustedClientIpResolver {

    /** 获取当前请求经过容器可信代理处理后的远端地址。 */
    public String resolveCurrentRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        String remoteAddress = request.getRemoteAddr();
        return remoteAddress == null || remoteAddress.isBlank() ? null : remoteAddress.strip();
    }
}
