package com.jxc.wefolio.service;

import com.jxc.wefolio.dto.PortfolioClientCapabilities;

/** 标准时间轴声明只扩展新请求指纹，旧请求缺省时返回原指纹原文。 */
public final class PortfolioTimelineRequestFingerprint {
    /** 新声明的固定摘要域，显式零与一严格区分。 */
    private static final String DOMAIN = ":portfolio-timeline-v1:";
    /** 工具类不实例化。 */
    private PortfolioTimelineRequestFingerprint() { }

    /** 调用方沿既有哈希算法散列返回值，不补写历史缺省能力。 */
    public static String include(String original, PortfolioClientCapabilities capabilities) {
        return capabilities != null && capabilities.hasTimelineIntent()
                ? original + DOMAIN + capabilities.getPortfolioTimeline() : original;
    }
}
