package com.jxc.wefolio.job.service;

/**
 * 作品存储结算异步执行器暂不可用异常。
 */
public class WorkStorageBillingUnavailableException extends RuntimeException {

    /**
     * 创建无底层异常的不可用结果。
     *
     * @param message 安全错误消息
     */
    public WorkStorageBillingUnavailableException(String message) {
        super(message);
    }

    /**
     * 创建异常。
     *
     * @param message 安全错误消息
     * @param cause 原始异常
     */
    public WorkStorageBillingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
