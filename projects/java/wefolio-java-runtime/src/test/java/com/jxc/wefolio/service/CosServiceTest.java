package com.jxc.wefolio.service;

import com.jxc.wefolio.config.CosProperties;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.transfer.TransferManager;
import com.qcloud.cos.transfer.Upload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CosServiceTest {

    @Mock
    private TransferManager transferManager;

    @Mock
    private CosProperties cosProperties;

    private CosService cosService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        cosService = new CosService(transferManager, cosProperties);
        when(cosProperties.getBucketName()).thenReturn("test-bucket");
    }

    // ── upload ──────────────────────────────────────────────

    @Test
    void uploadShouldReturnKey() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.jpg", "image/jpeg", "hello".getBytes());

        Upload upload = mock(Upload.class);
        when(transferManager.upload(any(com.qcloud.cos.model.PutObjectRequest.class)))
                .thenReturn(upload);

        String key = cosService.upload(file);

        assertThat(key).isNotEmpty();
        assertThat(key).endsWith(".jpg");
        assertThat(key).doesNotContain("-"); // UUID without dashes
        assertThat(key).hasSize(36); // 32 hex chars + ".jpg"

        verify(upload).waitForUploadResult();
    }

    @Test
    void uploadShouldHandleFileWithoutExtension() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "README", "text/plain", "hello".getBytes());

        Upload upload = mock(Upload.class);
        when(transferManager.upload(any(com.qcloud.cos.model.PutObjectRequest.class)))
                .thenReturn(upload);

        String key = cosService.upload(file);

        assertThat(key).doesNotContain(".");
        assertThat(key).hasSize(32); // 32 hex chars only, no extension
    }

    @Test
    void uploadShouldHandleNullOriginalFilename() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", null, "application/octet-stream", "data".getBytes());

        Upload upload = mock(Upload.class);
        when(transferManager.upload(any(com.qcloud.cos.model.PutObjectRequest.class)))
                .thenReturn(upload);

        String key = cosService.upload(file);

        assertThat(key).doesNotContain(".");
        assertThat(key).hasSize(32);
    }

    @Test
    void uploadShouldWrapCosException() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.png", "image/png", "data".getBytes());

        when(transferManager.upload(any(com.qcloud.cos.model.PutObjectRequest.class)))
                .thenThrow(new RuntimeException("Network error"));

        assertThatThrownBy(() -> cosService.upload(file))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("File upload failed")
                .hasMessageContaining("Network error");
    }

    @Test
    void uploadShouldGenerateUniqueKeys() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.jpg", "image/jpeg", "data".getBytes());

        Upload upload = mock(Upload.class);
        when(transferManager.upload(any(com.qcloud.cos.model.PutObjectRequest.class)))
                .thenReturn(upload);

        String key1 = cosService.upload(file);
        String key2 = cosService.upload(file);

        assertThat(key1).isNotEqualTo(key2);
    }

    // ── download ────────────────────────────────────────────

    @Test
    void downloadShouldReturnInputStream() {
        String key = "abc123.jpg";
        byte[] content = "file content".getBytes();

        COSClient cosClient = mock(COSClient.class);
        COSObject cosObject = new COSObject();
        cosObject.setObjectContent(new ByteArrayInputStream(content));

        when(transferManager.getCOSClient()).thenReturn(cosClient);
        when(cosClient.getObject(any(GetObjectRequest.class))).thenReturn(cosObject);

        InputStream result = cosService.download(key);

        assertThat(result).isNotNull();
        verify(cosClient).getObject(any(GetObjectRequest.class));
    }

    @Test
    void downloadShouldWrapCosException() {
        String key = "nonexistent.jpg";

        COSClient cosClient = mock(COSClient.class);
        when(transferManager.getCOSClient()).thenReturn(cosClient);
        when(cosClient.getObject(any(GetObjectRequest.class)))
                .thenThrow(new RuntimeException("NoSuchKey"));

        assertThatThrownBy(() -> cosService.download(key))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("File download failed")
                .hasMessageContaining("NoSuchKey");
    }

    // ── delete ──────────────────────────────────────────────

    @Test
    void deleteShouldSucceed() {
        String key = "abc123.jpg";
        COSClient cosClient = mock(COSClient.class);

        when(transferManager.getCOSClient()).thenReturn(cosClient);

        cosService.delete(key);

        verify(cosClient).deleteObject("test-bucket", key);
    }

    @Test
    void deleteShouldWrapCosException() {
        String key = "nonexistent.jpg";
        COSClient cosClient = mock(COSClient.class);

        when(transferManager.getCOSClient()).thenReturn(cosClient);
        doThrow(new RuntimeException("AccessDenied")).when(cosClient)
                .deleteObject(anyString(), anyString());

        assertThatThrownBy(() -> cosService.delete(key))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("File delete failed")
                .hasMessageContaining("AccessDenied");
    }
}
