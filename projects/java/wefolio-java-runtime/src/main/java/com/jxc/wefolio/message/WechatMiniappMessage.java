package com.jxc.wefolio.message;

/**
 * 微信小程序客户端报错信息 — 统一维护微信远端调用和日志处理相关提示。
 */
public interface WechatMiniappMessage {

    /** 日志脱敏失败占位符 */
    String MASK_FAILED_MESSAGE = "[日志脱敏失败]";

    /** 微信服务请求失败文案后缀 */
    String SERVICE_REQUEST_FAILED_SUFFIX = "请求失败";

    /** 微信服务调用异常文案后缀 */
    String SERVICE_CALL_EXCEPTION_SUFFIX = "调用异常";
}
