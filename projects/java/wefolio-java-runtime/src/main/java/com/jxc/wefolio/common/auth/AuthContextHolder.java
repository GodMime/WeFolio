package com.jxc.wefolio.common.auth;

import com.jxc.wefolio.exception.AuthenticationRequiredException;

import java.util.Optional;

/**
 * 当前请求认证上下文持有器。
 */
public final class AuthContextHolder {

    /** 线程隔离的认证上下文 */
    private static final ThreadLocal<AuthContext> CONTEXT = new ThreadLocal<>();

    private AuthContextHolder() {
    }

    /**
     * 写入当前线程认证上下文。
     *
     * @param context 认证上下文，为空时清理当前上下文
     */
    public static void set(AuthContext context) {
        if (context == null) {
            clear();
            return;
        }
        CONTEXT.set(context);
    }

    /**
     * 获取当前线程认证上下文。
     *
     * @return 当前认证上下文
     */
    public static Optional<AuthContext> current() {
        return Optional.ofNullable(CONTEXT.get());
    }

    /**
     * 获取当前登录用户 ID。
     *
     * @return 当前登录用户 ID
     */
    public static Optional<Long> getUserId() {
        return current().map(AuthContext::getUserId);
    }

    /**
     * 获取当前登录用户 ID，未登录时抛出未登录异常。
     *
     * @return 当前登录用户 ID
     */
    public static Long requireUserId() {
        return getUserId().orElseThrow(() -> new AuthenticationRequiredException("用户未登录"));
    }

    /**
     * 清理当前线程认证上下文。
     */
    public static void clear() {
        CONTEXT.remove();
    }
}
