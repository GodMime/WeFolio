package com.jxc.wefolio.exception;

/**
 * 未登录异常 — 用于需要返回 HTTP 401 的认证失败场景。
 */
public class AuthenticationRequiredException extends RuntimeException {

    /**
     * 创建默认未登录异常。
     */
    public AuthenticationRequiredException() {
        this("未登录");
    }

    /**
     * 创建未登录异常。
     *
     * @param message 异常消息
     */
    public AuthenticationRequiredException(String message) {
        super(message);
    }
}
