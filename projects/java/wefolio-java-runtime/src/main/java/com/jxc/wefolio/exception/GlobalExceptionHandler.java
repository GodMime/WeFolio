package com.jxc.wefolio.exception;

import com.jxc.wefolio.common.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public Response<Void> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        log.error("Upload size exceeded: {}", e.getMessage(), e);
        return Response.fail("文件大小超过限制");
    }

    /**
     * 处理未登录异常 — 必须返回 401 以触发小程序重新登录流程
     *
     * @param e 未登录异常
     * @return 未登录响应
     */
    @ExceptionHandler(AuthenticationRequiredException.class)
    public ResponseEntity<Response<Void>> handleAuthenticationRequired(AuthenticationRequiredException e) {
        log.warn("Authentication required: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Response.fail(e.getMessage()));
    }

    /**
     * 处理已知业务异常 — 消息可直接返回给客户端
     *
     * @param e 业务异常
     * @return 失败响应
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
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
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Response<Void> handleGeneral(Exception e) {
        log.error("Unexpected error", e);
        return Response.fail("Internal server error");
    }
}
