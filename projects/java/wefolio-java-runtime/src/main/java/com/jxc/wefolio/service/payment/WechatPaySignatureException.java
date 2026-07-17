package com.jxc.wefolio.service.payment;

/**
 * 微信支付通知验签异常。
 */
public class WechatPaySignatureException extends RuntimeException {

    /**
     * 创建通知验签异常。
     *
     * @param message 安全错误文案
     * @param cause SDK 原始异常
     */
    public WechatPaySignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
