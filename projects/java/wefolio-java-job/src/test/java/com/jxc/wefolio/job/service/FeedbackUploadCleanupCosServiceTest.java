package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.CosProperties;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * 反馈附件清理 COS 适配服务测试。
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class FeedbackUploadCleanupCosServiceTest {

    /** 测试 Bucket 名称。 */
    private static final String BUCKET_NAME = "sensitive-test-bucket";

    /** 测试对象键，日志断言确保其不会泄漏。 */
    private static final String OBJECT_KEY = "WFA3B1E7A2/others/sensitive-feedback.jpg";

    /** COS 客户端替身。 */
    @Mock
    private COSClient cosClient;

    /** 被测 COS 清理服务。 */
    private FeedbackUploadCleanupCosService service;

    /** 使用敏感测试值构造被测服务。 */
    @BeforeEach
    void setUp() {
        CosProperties properties = new CosProperties();
        properties.setBucketName(BUCKET_NAME);
        service = new FeedbackUploadCleanupCosService(cosClient, properties);
    }

    /** 验证删除使用准确参数，但日志只记录任务号和对象键摘要。 */
    @Test
    void deleteShouldUseExactBucketAndKeyButOnlyLogTaskAndKeyDigest(CapturedOutput output) {
        service.delete(101L, OBJECT_KEY);

        verify(cosClient).deleteObject(BUCKET_NAME, OBJECT_KEY);
        assertThat(output)
                .contains("taskId=101")
                .containsPattern("objectKeySha256=[0-9a-f]{64}")
                .doesNotContain(OBJECT_KEY)
                .doesNotContain(BUCKET_NAME);
    }

    /** 验证对象已不存在时按幂等成功处理。 */
    @Test
    void missingObjectShouldBeTreatedAsIdempotentSuccess(CapturedOutput output) {
        CosServiceException notFound = new CosServiceException("must-not-leak");
        notFound.setStatusCode(404);
        notFound.setErrorCode("NoSuchKey");
        doThrow(notFound).when(cosClient).deleteObject(BUCKET_NAME, OBJECT_KEY);

        assertThatCode(() -> service.delete(102L, OBJECT_KEY)).doesNotThrowAnyException();

        assertThat(output)
                .contains("taskId=102")
                .containsPattern("objectKeySha256=[0-9a-f]{64}")
                .doesNotContain("must-not-leak")
                .doesNotContain(OBJECT_KEY)
                .doesNotContain(BUCKET_NAME);
    }

    /** 验证其他远端失败只暴露固定本地错误信息。 */
    @Test
    void otherRemoteFailureShouldExposeOnlyFixedLocalMessage(CapturedOutput output) {
        CosServiceException remoteFailure = new CosServiceException("secret-in-remote-message");
        remoteFailure.setStatusCode(500);
        remoteFailure.setErrorCode("InternalError");
        doThrow(remoteFailure).when(cosClient).deleteObject(BUCKET_NAME, OBJECT_KEY);

        assertThatThrownBy(() -> service.delete(103L, OBJECT_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("反馈附件 COS 对象删除失败")
                .hasNoCause();

        assertThat(output)
                .contains("taskId=103")
                .containsPattern("objectKeySha256=[0-9a-f]{64}")
                .doesNotContain("CosServiceException")
                .doesNotContain("secret-in-remote-message")
                .doesNotContain(OBJECT_KEY)
                .doesNotContain(BUCKET_NAME);
    }
}
