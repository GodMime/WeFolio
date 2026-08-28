package com.jxc.wefolio.exception;

import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.message.GlobalExceptionMessage;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器 — 统一封装接口错误响应，并记录原始异常堆栈
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 缺省参数类型名称 */
    private static final String UNKNOWN_PARAMETER_TYPE = "unknown";

    /** User-Agent 请求头名称 */
    private static final String USER_AGENT_HEADER = "User-Agent";

    /** 腾讯安全扫描请求的 User-Agent 前缀 */
    private static final String TENCENT_SECURITY_TEAM_USER_AGENT_PREFIX = "Tencent Security Team";

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
     * 处理携带新版客户端增量详情的作品集业务异常。
     *
     * @param e 作品集结构化业务异常
     * @return 失败响应
     */
    @ExceptionHandler(PortfolioValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Response<Object> handlePortfolioValidation(PortfolioValidationException e) {
        log.error("Portfolio validation exception: {}", e.getMessage(), e);
        return Response.fail(e.getMessage(), e.getData());
    }

    /**
     * 处理已知业务异常 — 消息可直接返回给客户端
     *
     * @param e 业务异常
     * @param request 原始 HTTP 请求
     * @return 失败响应
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Response<Void> handleBusiness(BusinessException e, HttpServletRequest request) {
        if (!logTencentSecurityScanWarning(request, e)) {
            log.error("Business exception: {}", e.getMessage(), e);
        }
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
    public ResponseEntity<Response<Void>> handleNoResourceFound(NoResourceFoundException e, HttpServletRequest request) {
        String method = request.getMethod() == null ? e.getHttpMethod().name() : request.getMethod();
        log.warn("Static resource not found: method={} url={}", method, buildOriginalRequestUrl(request));
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Response.fail(GlobalExceptionMessage.RESOURCE_NOT_FOUND_MESSAGE));
    }

    /**
     * 处理请求参数类型转换异常 — 客户端传入格式错误时返回 400，避免落入 500。
     *
     * @param e 参数类型转换异常
     * @return 失败响应
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Response<Void> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException e) {
        Class<?> requiredType = e.getRequiredType();
        String requiredTypeName = requiredType == null ? UNKNOWN_PARAMETER_TYPE : requiredType.getSimpleName();
        log.warn("Request parameter type mismatch: name={}, requiredType={}", e.getName(), requiredTypeName);
        if (e.getName() == null || e.getName().isBlank()) {
            return Response.fail(GlobalExceptionMessage.REQUEST_PARAMETER_FORMAT_ERROR_MESSAGE);
        }
        return Response.fail(GlobalExceptionMessage.REQUEST_PARAMETER_FORMAT_ERROR_MESSAGE + "：" + e.getName());
    }

    /**
     * 处理请求体 JSON 解析异常 — 客户端提交非法 JSON 时返回 400。
     *
     * @param e 请求体不可读异常
     * @return 失败响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Response<Void> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("Request body not readable: {}", e.getMessage());
        return Response.fail(GlobalExceptionMessage.REQUEST_BODY_FORMAT_ERROR_MESSAGE);
    }

    /**
     * 处理参数绑定和校验异常 — 表单或查询参数校验失败时返回 400。
     *
     * @param e 参数绑定异常
     * @return 失败响应
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Response<Void> handleBindException(BindException e) {
        log.warn("Request bind validation failed: object={}", e.getObjectName());
        String message = e.getAllErrors().stream()
                .map(ObjectError::getDefaultMessage)
                .filter(errorMessage -> errorMessage != null && !errorMessage.isBlank())
                .findFirst()
                .map(errorMessage -> GlobalExceptionMessage.REQUEST_BIND_ERROR_MESSAGE + "：" + errorMessage)
                .orElse(GlobalExceptionMessage.REQUEST_BIND_ERROR_MESSAGE);
        return Response.fail(message);
    }

    /**
     * 处理缺少必填请求参数异常 — 缺少 query/form 参数时返回 400。
     *
     * @param e 缺少必填参数异常
     * @return 失败响应
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Response<Void> handleMissingServletRequestParameter(MissingServletRequestParameterException e) {
        log.warn("Missing request parameter: name={}, type={}", e.getParameterName(), e.getParameterType());
        return Response.fail(GlobalExceptionMessage.MISSING_REQUEST_PARAMETER_MESSAGE + "：" + e.getParameterName());
    }

    /**
     * 处理未预期异常
     *
     * @param e 原始异常
     * @param request 原始 HTTP 请求
     * @return 失败响应
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Response<Void> handleGeneral(Exception e, HttpServletRequest request) {
        if (!logTencentSecurityScanWarning(request, e)) {
            log.error("Unexpected error", e);
        }
        return Response.fail("Internal server error");
    }

    /**
     * 腾讯安全扫描请求只记录不含异常详情和堆栈的告警日志。
     *
     * @param request 原始 HTTP 请求
     * @param exception 原始异常
     * @return 是否已记录腾讯安全扫描告警
     */
    private boolean logTencentSecurityScanWarning(HttpServletRequest request, Exception exception) {
        if (!isTencentSecurityTeamRequest(request)) {
            return false;
        }
        log.warn("Tencent security scan request rejected: method={} uri={} exceptionType={}",
                request.getMethod(), request.getRequestURI(), exception.getClass().getSimpleName());
        return true;
    }

    /**
     * 判断请求是否来自腾讯安全扫描 User-Agent。
     *
     * @param request 原始 HTTP 请求
     * @return User-Agent 是否以腾讯安全团队标识开头
     */
    private boolean isTencentSecurityTeamRequest(HttpServletRequest request) {
        String userAgent = request.getHeader(USER_AGENT_HEADER);
        return userAgent != null && userAgent.startsWith(TENCENT_SECURITY_TEAM_USER_AGENT_PREFIX);
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
