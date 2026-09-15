package com.jxc.wefolio.aspect;

import org.springframework.core.Ordered;

/**
 * 请求处理切面顺序。
 *
 * <p>认证前预留优先级区间，供需要观察认证失败请求的切面使用。</p>
 */
final class AspectOrders {

    /** 相邻切面职责之间预留的优先级间隔。 */
    private static final int PRECEDENCE_GAP = 1000;

    /** 认证切面顺序。 */
    static final int AUTHENTICATION = Ordered.HIGHEST_PRECEDENCE + PRECEDENCE_GAP;

    /** 弃用接口日志切面顺序。 */
    static final int DEPRECATED_ENDPOINT_LOGGING = AUTHENTICATION + PRECEDENCE_GAP;

    /** 工具类禁止实例化。 */
    private AspectOrders() {
    }
}
