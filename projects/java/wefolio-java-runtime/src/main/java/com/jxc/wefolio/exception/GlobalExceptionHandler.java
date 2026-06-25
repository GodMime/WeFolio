package com.jxc.wefolio.exception;

import com.jxc.wefolio.common.Response;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
     * 处理静态资源不存在异常 — 返回 404，避免普通公网扫描被记录为 500。
     *
     * @param e 静态资源不存在异常
     * @param request 原始 HTTP 请求
     * @return 失败响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Response<Void> handleNoResourceFound(NoResourceFoundException e, HttpServletRequest request) {
        String method = request.getMethod() == null ? e.getHttpMethod().name() : request.getMethod();
        log.warn("Static resource not found: method={} url={}", method, buildOriginalRequestUrl(request));
        return Response.fail("资源不存在");
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

    /**
     * 从反向代理请求头中还原客户端原始访问 URL。
     *
     * @param request HTTP 请求
     * @return 原始访问 URL
     */
    private String buildOriginalRequestUrl(HttpServletRequest request) {
        String scheme = firstHeaderValue(request.getHeader("X-Forwarded-Proto"));
        if (scheme == null || scheme.isBlank()) {
            scheme = request.getScheme();
        }

        String host = firstHeaderValue(request.getHeader("X-Forwarded-Host"));
        if (host == null || host.isBlank()) {
            host = firstHeaderValue(request.getHeader("Host"));
        }
        if (host == null || host.isBlank()) {
            host = request.getServerName();
            int port = request.getServerPort();
            if (port > 0 && port != 80 && port != 443) {
                host = host + ":" + port;
            }
        }

        String queryString = request.getQueryString();
        String querySuffix = queryString == null || queryString.isBlank() ? "" : "?" + queryString;
        return scheme + "://" + host + request.getRequestURI() + querySuffix;
    }

    /**
     * 获取代理链请求头中的第一个值。
     *
     * @param value 请求头原始值
     * @return 第一个非空值
     */
    private String firstHeaderValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.split(",", 2)[0].trim();
    }
}
