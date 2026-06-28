package com.jxc.wefolio.exception;

/**
 * 无效登录令牌异常 — 用于区分用户令牌失效与普通业务异常。
 */
public class InvalidAuthTokenException extends BusinessException {

    /**
     * 创建无效登录令牌异常。
     *
     * @param message 用户可读的错误消息
     */
    public InvalidAuthTokenException(String message) {
        super(message);
    }

    /**
     * 创建包含原始异常的无效登录令牌异常。
     *
     * @param message 用户可读的错误消息
     * @param cause 原始异常
     */
    public InvalidAuthTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
