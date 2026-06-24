package com.jxc.wefolio.exception;

import com.jxc.wefolio.common.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

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
    void handleMaxUploadSizeExceededLogsOriginalStackTrace(CapturedOutput output) {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MaxUploadSizeExceededException exception = new MaxUploadSizeExceededException(1024L);

        Response<Void> response = handler.handleMaxUploadSizeExceeded(exception);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("File size exceeds the maximum allowed limit");
        assertThat(output).contains("ERROR");
        assertThat(output).contains("Upload size exceeded");
        assertThat(output).contains("org.springframework.web.multipart.MaxUploadSizeExceededException");
        assertThat(output).contains("GlobalExceptionHandlerTest.handleMaxUploadSizeExceededLogsOriginalStackTrace");
    }
}
