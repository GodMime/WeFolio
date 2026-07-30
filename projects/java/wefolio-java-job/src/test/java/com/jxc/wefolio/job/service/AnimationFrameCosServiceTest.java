package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.CosProperties;
import com.jxc.wefolio.job.entity.WorkAuditTaskEntity;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectInputStream;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.PutObjectRequest;
import org.apache.http.client.methods.HttpGet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * job 动图审核临时帧 COS 服务测试。
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class AnimationFrameCosServiceTest {

    private static final String CLAIM_TOKEN = "job-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Mock
    private COSClient cosClient;

    private AnimationFrameCosService service;

    @BeforeEach
    void setUp() {
        CosProperties properties = new CosProperties();
        properties.setBucketName("test-bucket");
        service = new AnimationFrameCosService(cosClient, properties);
    }

    @Test
    void generateShouldUseDeterministicGifKeyAndFrameQuery() throws Exception {
        byte[] jpeg = jpeg("audit");
        when(cosClient.getObject(any(GetObjectRequest.class))).thenReturn(cosObject(jpeg));
        WorkAuditTaskEntity task = task(
                101L, "WFA3B1E7A2/work/animation/demo.gif");

        String targetKey = service.generate(task, 5);

        ArgumentCaptor<GetObjectRequest> getCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
        ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(cosClient).getObject(getCaptor.capture());
        verify(cosClient).putObject(putCaptor.capture());
        assertThat(getCaptor.getValue().getCustomQueryParameters())
                .containsKey("imageMogr2/frame/5/strip/format/jpg");
        assertThat(targetKey)
                .isEqualTo("WFA3B1E7A2/work/animation/demo-audit-101-5-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.jpg");
        assertThat(putCaptor.getValue().getKey()).isEqualTo(targetKey);
        assertThat(putCaptor.getValue().getMetadata().getContentType()).isEqualTo("image/jpeg");
        assertThat(putCaptor.getValue().getInputStream().readAllBytes()).isEqualTo(jpeg);
    }

    @Test
    void generateShouldRemoveWebpExtensionFromTargetStem() {
        when(cosClient.getObject(any(GetObjectRequest.class)))
                .thenReturn(cosObject(jpeg("webp")));

        String targetKey = service.generate(
                task(102L, "WFA3B1E7A2/work/animation/demo.webp"), 23);

        assertThat(targetKey)
                .isEqualTo("WFA3B1E7A2/work/animation/demo-audit-102-23-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.jpg");
    }

    @Test
    void generateShouldRejectInvalidFrameAndEmptyResponse() {
        WorkAuditTaskEntity task = task(101L, "WFA3B1E7A2/work/animation/demo.gif");

        assertThatThrownBy(() -> service.generate(task, 0))
                .isInstanceOf(IllegalArgumentException.class);
        verify(cosClient, never()).getObject(any(GetObjectRequest.class));

        when(cosClient.getObject(any(GetObjectRequest.class))).thenReturn(cosObject(new byte[0]));
        assertThatThrownBy(() -> service.generate(task, 1))
                .isInstanceOf(IllegalStateException.class);
        verify(cosClient, never()).putObject(any(PutObjectRequest.class));
    }

    @Test
    void generateShouldRejectNonJpegResponse() {
        WorkAuditTaskEntity task = task(101L, "WFA3B1E7A2/work/animation/demo.gif");
        when(cosClient.getObject(any(GetObjectRequest.class)))
                .thenReturn(cosObject("upstream-error".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> service.generate(task, 1))
                .isInstanceOf(IllegalStateException.class);

        verify(cosClient, never()).putObject(any(PutObjectRequest.class));
    }

    @Test
    void deleteQuietlyShouldSuppressRemoteFailure(CapturedOutput output) {
        doThrow(new RuntimeException("delete failed"))
                .when(cosClient).deleteObject(anyString(), anyString());

        service.deleteQuietly(
                "WFA3B1E7A2/work/animation/demo-audit-101-5.jpg", 101L);

        assertThat(output)
                .contains("WFA3B1E7A2/work/animation/demo-audit-101-5.jpg")
                .contains("101");
    }

    private WorkAuditTaskEntity task(Long taskId, String objectKey) {
        WorkAuditTaskEntity task = new WorkAuditTaskEntity();
        task.setId(taskId);
        task.setMediaObjectKey(objectKey);
        task.setLockedBy(CLAIM_TOKEN);
        return task;
    }

    private COSObject cosObject(byte[] payload) {
        COSObject object = new COSObject();
        object.setObjectContent(new COSObjectInputStream(
                new ByteArrayInputStream(payload),
                new HttpGet("https://cos.example.test/object")));
        return object;
    }

    private byte[] jpeg(String body) {
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        byte[] jpeg = new byte[content.length + 4];
        jpeg[0] = (byte) 0xff;
        jpeg[1] = (byte) 0xd8;
        System.arraycopy(content, 0, jpeg, 2, content.length);
        jpeg[jpeg.length - 2] = (byte) 0xff;
        jpeg[jpeg.length - 1] = (byte) 0xd9;
        return jpeg;
    }
}
