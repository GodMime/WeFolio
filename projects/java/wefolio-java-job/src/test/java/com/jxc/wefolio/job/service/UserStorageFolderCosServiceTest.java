package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.CosProperties;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.PutObjectRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 历史用户目录占位对象 COS 服务测试。
 */
class UserStorageFolderCosServiceTest {

    @Test
    void existsAndCreateShouldUseExactDirectoryObjectKey() throws Exception {
        COSClient cosClient = mock(COSClient.class);
        CosProperties properties = new CosProperties();
        properties.setBucketName("test-bucket");
        UserStorageFolderCosService service =
                new UserStorageFolderCosService(cosClient, properties);
        when(cosClient.doesObjectExist(
                "test-bucket", "WFA3B1E7A2/work/animation/")).thenReturn(false);

        assertThat(service.exists("WFA3B1E7A2", "work/animation/")).isFalse();
        service.create("WFA3B1E7A2", "work/animation/");

        verify(cosClient).doesObjectExist(
                "test-bucket", "WFA3B1E7A2/work/animation/");
        ArgumentCaptor<PutObjectRequest> captor =
                ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(cosClient).putObject(captor.capture());
        assertThat(captor.getValue().getKey())
                .isEqualTo("WFA3B1E7A2/work/animation/");
        assertThat(captor.getValue().getMetadata().getContentLength()).isZero();
        assertThat(captor.getValue().getMetadata().getContentType())
                .isEqualTo("application/x-directory");
        assertThat(captor.getValue().getInputStream().readAllBytes()).isEmpty();
    }
}
