package com.jxc.wefolio.service.payment;

/**
 * 微信支付通知报文解析异常。
 */
public class WechatPayNotificationException extends RuntimeException {

    /**
     * 创建通知报文异常。
     *
     * @param message 安全错误文案
     * @param cause SDK 原始异常
     */
    public WechatPayNotificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
