package com.jxc.wefolio.constant;

/**
 * 积分常量 — 统一维护积分领域跨服务共享的固定参数。
 */
public final class PointConstants {

    /** 低余额提醒阈值，余额低于该值时视为低余额。 */
    public static final long LOW_BALANCE_THRESHOLD = 50L;

    /**
     * 禁止实例化常量类。
     */
    private PointConstants() {
    }
}
