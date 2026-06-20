package com.jxc.wefolio.common;

import lombok.Data;

@Data
public class Response<T> {

    private boolean success;
    private String message;
    private T data;

    private Response(boolean success, String message, T data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    /** 成功（无数据） */
    public static <T> Response<T> success() {
        return new Response<>(true, "ok", null);
    }

    /** 成功（带数据） */
    public static <T> Response<T> success(T data) {
        return new Response<>(true, "ok", data);
    }

    /** 成功（自定义消息 + 数据） */
    public static <T> Response<T> success(String message, T data) {
        return new Response<>(true, message, data);
    }

    /** 失败 */
    public static <T> Response<T> fail(String message) {
        return new Response<>(false, message, null);
    }

    /** 失败（带错误码语义，可扩展） */
    public static <T> Response<T> fail(String message, T data) {
        return new Response<>(false, message, data);
    }
}
