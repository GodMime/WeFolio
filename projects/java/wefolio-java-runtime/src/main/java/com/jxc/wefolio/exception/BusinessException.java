package com.jxc.wefolio.exception;

/**
 * 已知业务异常 — 消息可直接返回给客户端，由 GlobalExceptionHandler 统一捕获。
 * <p>
 * 使用方式：throw new BusinessException("用户未登录");
 */
public class BusinessException extends RuntimeException {

    /**
     * 创建业务异常
     *
     * @param message 用户可读的错误消息
     */
    public BusinessException(String message) {
        super(message);
    }

    /**
     * 创建包含原始异常的业务异常
     *
     * @param message 用户可读的错误消息
     * @param cause   原始异常
     */
    public BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}
