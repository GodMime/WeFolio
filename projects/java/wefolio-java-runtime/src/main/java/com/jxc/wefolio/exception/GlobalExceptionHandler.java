package com.jxc.wefolio.exception;

import com.jxc.wefolio.common.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常处理器 — 统一封装接口错误响应，并记录原始异常堆栈
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理上传文件超限异常
     *
     * @param e 上传文件超限异常
     * @return 失败响应
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.OK)
    public Response<Void> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        log.error("Upload size exceeded: {}", e.getMessage(), e);
        return Response.fail("File size exceeds the maximum allowed limit");
    }

    /**
     * 处理已知业务异常 — 消息可直接返回给客户端
     *
     * @param e 业务异常
     * @return 失败响应
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Response<Void> handleBusiness(BusinessException e) {
        log.error("Business exception: {}", e.getMessage(), e);
        return Response.fail(e.getMessage());
    }

    /**
     * 处理未预期异常
     *
     * @param e 原始异常
     * @return 失败响应
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public Response<Void> handleGeneral(Exception e) {
        log.error("Unexpected error", e);
        return Response.fail("Internal server error");
    }
}
