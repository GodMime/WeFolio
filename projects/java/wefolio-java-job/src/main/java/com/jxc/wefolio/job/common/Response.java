package com.jxc.wefolio.job.common;

import lombok.Data;

/**
 * 通用响应封装 — 与 runtime 工程保持一致的接口响应结构。
 *
 * @param <T> 响应数据类型
 */
@Data
public class Response<T> {

    /** 请求是否成功 */
    private boolean success;

    /** 响应消息 */
    private String message;

    /** 响应数据 */
    private T data;

    /**
     * 创建响应对象。
     *
     * @param success 请求是否成功
     * @param message 响应消息
     * @param data 响应数据
     */
    private Response(boolean success, String message, T data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    /**
     * 创建无数据的成功响应。
     *
     * @param <T> 响应数据类型
     * @return 成功响应
     */
    public static <T> Response<T> success() {
        return new Response<>(true, "ok", null);
    }

    /**
     * 创建带数据的成功响应。
     *
     * @param data 响应数据
     * @param <T> 响应数据类型
     * @return 成功响应
     */
    public static <T> Response<T> success(T data) {
        return new Response<>(true, "ok", data);
    }

    /**
     * 创建自定义消息的成功响应。
     *
     * @param message 响应消息
     * @param data 响应数据
     * @param <T> 响应数据类型
     * @return 成功响应
     */
    public static <T> Response<T> success(String message, T data) {
        return new Response<>(true, message, data);
    }

    /**
     * 创建失败响应。
     *
     * @param message 失败消息
     * @param <T> 响应数据类型
     * @return 失败响应
     */
    public static <T> Response<T> fail(String message) {
        return new Response<>(false, message, null);
    }

    /**
     * 创建带数据的失败响应。
     *
     * @param message 失败消息
     * @param data 响应数据
     * @param <T> 响应数据类型
     * @return 失败响应
     */
    public static <T> Response<T> fail(String message, T data) {
        return new Response<>(false, message, data);
    }
}
