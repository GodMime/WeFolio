package com.jxc.wefolio.message;

/**
 * 全局异常报错信息 — 统一维护框架层异常转换后的客户端提示。
 */
public interface GlobalExceptionMessage {

    /** 请求参数格式错误提示 */
    String REQUEST_PARAMETER_FORMAT_ERROR_MESSAGE = "请求参数格式错误";

    /** 请求体格式错误提示 */
    String REQUEST_BODY_FORMAT_ERROR_MESSAGE = "请求体格式错误";

    /** 参数校验失败提示 */
    String REQUEST_BIND_ERROR_MESSAGE = "参数校验失败";

    /** 缺少必填参数提示 */
    String MISSING_REQUEST_PARAMETER_MESSAGE = "缺少必填参数";

    /** 静态资源不存在提示 */
    String RESOURCE_NOT_FOUND_MESSAGE = "资源不存在";
}
