package com.jxc.wefolio.common.auth;

import com.jxc.wefolio.exception.AuthenticationRequiredException;

import java.util.Optional;

/**
 * 当前请求访客认证上下文持有器。
 */
public final class VisitorContextHolder {

    /** 线程隔离的访客认证上下文 */
    private static final ThreadLocal<VisitorContext> CONTEXT = new ThreadLocal<>();

    private VisitorContextHolder() {
    }

    /**
     * 写入当前线程访客认证上下文。
     *
     * @param context 访客认证上下文，为空时清理当前上下文
     */
    public static void set(VisitorContext context) {
        if (context == null) {
            clear();
            return;
        }
        CONTEXT.set(context);
    }

    /**
     * 获取当前线程访客认证上下文。
     *
     * @return 当前访客认证上下文
     */
    public static Optional<VisitorContext> current() {
        return Optional.ofNullable(CONTEXT.get());
    }

    /**
     * 获取当前访客 ID。
     *
     * @return 当前访客 ID
     */
    public static Optional<Long> getVisitorId() {
        return current().map(VisitorContext::getVisitorId);
    }

    /**
     * 获取当前访客 ID，未登录时抛出未登录异常。
     *
     * @return 当前访客 ID
     */
    public static Long requireVisitorId() {
        return getVisitorId().orElseThrow(() -> new AuthenticationRequiredException("访客未登录"));
    }

    /**
     * 获取当前访客稳定 key。
     *
     * @return 当前访客稳定 key
     */
    public static Optional<String> getVisitorKey() {
        return current().map(VisitorContext::getVisitorKey);
    }

    /**
     * 获取当前访客稳定 key，未登录时抛出未登录异常。
     *
     * @return 当前访客稳定 key
     */
    public static String requireVisitorKey() {
        return getVisitorKey().orElseThrow(() -> new AuthenticationRequiredException("访客未登录"));
    }

    /**
     * 清理当前线程访客认证上下文。
     */
    public static void clear() {
        CONTEXT.remove();
    }
}
