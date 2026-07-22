package com.jxc.wefolio.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 作品审核配置 — 控制一件作品允许进入的审核总轮次。
 */
@Getter
@Component
@ConfigurationProperties(prefix = "wefolio.work-audit")
public class WorkAuditProperties {

    /** 审核总轮次非法提示。 */
    private static final String MAX_ROUNDS_INVALID_MESSAGE = "作品审核总轮次必须大于等于 1";

    /** 默认审核总轮次。 */
    private int maxRounds = 3;

    /**
     * 设置审核总轮次，并在配置绑定阶段拒绝非法值。
     *
     * @param maxRounds 审核总轮次
     */
    public void setMaxRounds(int maxRounds) {
        if (maxRounds < 1) {
            throw new IllegalArgumentException(MAX_ROUNDS_INVALID_MESSAGE);
        }
        this.maxRounds = maxRounds;
    }
}
