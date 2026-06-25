package com.jxc.wefolio.common.auth;

/**
 * 当前请求认证上下文。
 */
public class AuthContext {

    /** 当前登录用户 ID */
    private final Long userId;

    /** 当前请求使用的登录令牌 */
    private final String token;

    /**
     * 创建认证上下文。
     *
     * @param userId 当前登录用户 ID
     * @param token 当前请求使用的登录令牌
     */
    public AuthContext(Long userId, String token) {
        this.userId = userId;
        this.token = token;
    }

    /**
     * 获取当前登录用户 ID。
     *
     * @return 当前登录用户 ID
     */
    public Long getUserId() {
        return userId;
    }

    /**
     * 获取当前请求使用的登录令牌。
     *
     * @return 当前请求使用的登录令牌
     */
    public String getToken() {
        return token;
    }
}
