package com.jxc.wefolio.exception;

import com.jxc.wefolio.common.Response;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局异常处理器日志与 HTTP 状态码测试。
 */
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

    @Test
    void handleBusinessLogsOriginalStackTrace(CapturedOutput output) {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        BusinessException exception = new BusinessException("微信手机号服务请求失败");

        Response<Void> response = handler.handleBusiness(exception);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("微信手机号服务请求失败");
        assertThat(output).contains("ERROR");
        assertThat(output).contains("Business exception: 微信手机号服务请求失败");
        assertThat(output).contains("com.jxc.wefolio.exception.BusinessException: 微信手机号服务请求失败");
        assertThat(output).contains("GlobalExceptionHandlerTest.handleBusinessLogsOriginalStackTrace");
    }

    @Test
    void handleBusinessReturnsBadRequestStatus() throws NoSuchMethodException {
        Method method = GlobalExceptionHandler.class.getMethod("handleBusiness", BusinessException.class);

        ResponseStatus responseStatus = method.getAnnotation(ResponseStatus.class);

        assertThat(responseStatus).isNotNull();
        assertThat(responseStatus.value()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void handleAuthenticationRequiredReturnsUnauthorized(CapturedOutput output) {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        AuthenticationRequiredException exception = new AuthenticationRequiredException();

        ResponseEntity<Response<Void>> response = handler.handleAuthenticationRequired(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).isEqualTo("未登录");
        assertThat(output).contains("WARN");
        assertThat(output).contains("Authentication required: 未登录");
    }

    @Test
    void handleMaxUploadSizeExceededLogsOriginalStackTrace(CapturedOutput output) {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MaxUploadSizeExceededException exception = new MaxUploadSizeExceededException(1024L);

        Response<Void> response = handler.handleMaxUploadSizeExceeded(exception);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("文件大小超过限制");
        assertThat(output).contains("ERROR");
        assertThat(output).contains("Upload size exceeded");
        assertThat(output).contains("org.springframework.web.multipart.MaxUploadSizeExceededException");
        assertThat(output).contains("GlobalExceptionHandlerTest.handleMaxUploadSizeExceededLogsOriginalStackTrace");
    }

    @Test
    void handleMaxUploadSizeExceededReturnsPayloadTooLargeStatus() throws NoSuchMethodException {
        Method method = GlobalExceptionHandler.class.getMethod(
                "handleMaxUploadSizeExceeded",
                MaxUploadSizeExceededException.class
        );

        ResponseStatus responseStatus = method.getAnnotation(ResponseStatus.class);

        assertThat(responseStatus).isNotNull();
        assertThat(responseStatus.value()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void handleNoResourceFoundReturnsNotFoundAndLogsOriginalRequestUrl(CapturedOutput output) throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        NoResourceFoundException exception = new NoResourceFoundException(HttpMethod.GET, "favicon.ico");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/favicon.ico");
        request.addHeader("Host", "api.we-folio.dingchenyong.top");
        request.addHeader("X-Forwarded-Proto", "https");
        request.setQueryString("v=1");
        Method method = GlobalExceptionHandler.class.getMethod(
                "handleNoResourceFound",
                NoResourceFoundException.class,
                HttpServletRequest.class
        );

        @SuppressWarnings("unchecked")
        Response<Void> response = (Response<Void>) method.invoke(handler, exception, request);
        ResponseStatus responseStatus = method.getAnnotation(ResponseStatus.class);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("资源不存在");
        assertThat(responseStatus).isNotNull();
        assertThat(responseStatus.value()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(output).contains("WARN");
        assertThat(output).contains("Static resource not found: method=GET url=https://api.we-folio.dingchenyong.top/favicon.ico?v=1");
        assertThat(output).doesNotContain("Unexpected error");
    }

    @Test
    void handleGeneralReturnsInternalServerErrorStatus() throws NoSuchMethodException {
        Method method = GlobalExceptionHandler.class.getMethod("handleGeneral", Exception.class);

        ResponseStatus responseStatus = method.getAnnotation(ResponseStatus.class);

        assertThat(responseStatus).isNotNull();
        assertThat(responseStatus.value()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
