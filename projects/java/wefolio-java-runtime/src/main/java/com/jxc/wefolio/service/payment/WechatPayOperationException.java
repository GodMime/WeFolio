package com.jxc.wefolio.service.payment;

/**
 * 微信支付远端操作或响应异常。
 */
public class WechatPayOperationException extends RuntimeException {

    /**
     * 创建微信支付操作异常。
     *
     * @param message 安全错误文案
     * @param cause SDK 原始异常
     */
    public WechatPayOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
