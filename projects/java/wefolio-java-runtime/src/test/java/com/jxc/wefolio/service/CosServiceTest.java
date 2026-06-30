package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.config.CosProperties;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.transfer.TransferManager;
import com.qcloud.cos.transfer.Upload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

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
        lenient().when(cosProperties.getBucketName()).thenReturn("test-bucket");
    }

    // ── upload ──────────────────────────────────────────────

    @Test
    void createPostUploadTicketShouldRestrictKeyAndSizeInPolicy() {
        when(cosProperties.getRegion()).thenReturn("ap-guangzhou");
        when(cosProperties.getSecretId()).thenReturn("AKID_TEST");
        when(cosProperties.getSecretKey()).thenReturn("SECRET_TEST");

        CosService.PostUploadTicket ticket = cosService.createPostUploadTicket(
                "WFA3B1E7A2/work/image/photo.jpg",
                "image/jpeg",
                10 * 1024 * 1024L,
                LocalDateTime.now().plusMinutes(30));

        Map<String, String> formData = ticket.formData();
        String policy = new String(Base64.getDecoder().decode(formData.get("policy")));
        assertThat(ticket.uploadUrl()).isEqualTo("https://test-bucket.cos.ap-guangzhou.myqcloud.com");
        assertThat(ticket.objectKey()).isEqualTo("WFA3B1E7A2/work/image/photo.jpg");
        assertThat(formData).containsEntry("key", "WFA3B1E7A2/work/image/photo.jpg");
        assertThat(formData).containsEntry("q-ak", "AKID_TEST");
        assertThat(formData).containsEntry("q-sign-algorithm", "sha1");
        assertThat(formData).containsEntry("x-cos-acl", "public-read");
        assertThat(formData).containsEntry("success_action_status", "200");
        assertThat(formData.get("q-signature")).isNotBlank();
        assertThat(formData.get("q-signature")).doesNotContain("SECRET_TEST");
        assertThat(policy).contains("\"bucket\":\"test-bucket\"");
        assertThat(policy).contains("\"key\":\"WFA3B1E7A2/work/image/photo.jpg\"");
        assertThat(policy).contains("\"x-cos-acl\":\"public-read\"");
        assertThat(policy).contains("[\"content-length-range\",0,10485760]");
        assertThat(policy).contains("\"success_action_status\":\"200\"");
    }

    @Test
    void createPostUploadTicketShouldUseUploadBaseUrlForMiniappUploadDomain() {
        when(cosProperties.getSecretId()).thenReturn("AKID_TEST");
        when(cosProperties.getSecretKey()).thenReturn("SECRET_TEST");
        when(cosProperties.getUploadBaseUrl()).thenReturn("https://cos-upload.we-folio.dingchenyong.top/");

        CosService.PostUploadTicket ticket = cosService.createPostUploadTicket(
                "WFA3B1E7A2/work/image/photo.jpg",
                "image/jpeg",
                1024L,
                LocalDateTime.now().plusMinutes(30));

        assertThat(ticket.uploadUrl()).isEqualTo("https://cos-upload.we-folio.dingchenyong.top");
    }

    @Test
    void createPostUploadTicketShouldIgnorePublicBaseUrlForMiniappUploadDomain() {
        when(cosProperties.getRegion()).thenReturn("ap-guangzhou");
        when(cosProperties.getSecretId()).thenReturn("AKID_TEST");
        when(cosProperties.getSecretKey()).thenReturn("SECRET_TEST");

        CosService.PostUploadTicket ticket = cosService.createPostUploadTicket(
                "WFA3B1E7A2/work/image/photo.jpg",
                "image/jpeg",
                1024L,
                LocalDateTime.now().plusMinutes(30));

        assertThat(ticket.uploadUrl()).isEqualTo("https://test-bucket.cos.ap-guangzhou.myqcloud.com");
        verify(cosProperties, never()).getPublicBaseUrl();
    }

    @Test
    void createPostUploadTicketShouldEscapeJsonPolicyValues() {
        when(cosProperties.getRegion()).thenReturn("ap-guangzhou");
        when(cosProperties.getSecretId()).thenReturn("AKID_\"TEST\\");
        when(cosProperties.getSecretKey()).thenReturn("SECRET_TEST");
        String objectKey = "WFA3B1E7A2/work/image/photo\"quote\\slash.jpg";

        CosService.PostUploadTicket ticket = cosService.createPostUploadTicket(
                objectKey,
                "image/jpeg",
                1024L,
                LocalDateTime.now().plusMinutes(30));

        String policy = new String(Base64.getDecoder().decode(ticket.formData().get("policy")));
        JSONObject policyObject = JSON.parseObject(policy);
        JSONArray conditions = policyObject.getJSONArray("conditions");
        assertThat(conditions.stream()
                .filter(JSONObject.class::isInstance)
                .map(JSONObject.class::cast)
                .map(condition -> condition.getString("key"))
                .filter(value -> value != null)
                .findFirst())
                .contains(objectKey);
        assertThat(conditions.stream()
                .filter(JSONObject.class::isInstance)
                .map(JSONObject.class::cast)
                .map(condition -> condition.getString("q-ak"))
                .filter(value -> value != null)
                .findFirst())
                .contains("AKID_\"TEST\\");
    }

    @Test
    void createPostUploadTicketShouldBackdateKeyTimeForClockSkew() {
        when(cosProperties.getRegion()).thenReturn("ap-guangzhou");
        when(cosProperties.getSecretId()).thenReturn("AKID_TEST");
        when(cosProperties.getSecretKey()).thenReturn("SECRET_TEST");
        long beforeEpochSecond = System.currentTimeMillis() / 1000;

        CosService.PostUploadTicket ticket = cosService.createPostUploadTicket(
                "WFA3B1E7A2/work/image/photo.jpg",
                "image/jpeg",
                1024L,
                LocalDateTime.now().plusMinutes(15));

        String[] keyTimeParts = ticket.formData().get("q-key-time").split(";");
        assertThat(Long.parseLong(keyTimeParts[0])).isLessThanOrEqualTo(beforeEpochSecond - 55);
    }

    @Test
    void createPostUploadTicketShouldRejectPastExpiration() {
        assertThatThrownBy(() -> cosService.createPostUploadTicket(
                "WFA3B1E7A2/work/image/photo.jpg",
                "image/jpeg",
                1024L,
                LocalDateTime.now().minusMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("上传票据过期时间不能早于当前时间");
    }

    @Test
    void headObjectShouldReturnContentTypeAndLength() {
        COSClient cosClient = mock(COSClient.class);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType("video/mp4");
        metadata.setContentLength(4096L);
        when(transferManager.getCOSClient()).thenReturn(cosClient);
        when(cosClient.getObjectMetadata("test-bucket", "WFA3B1E7A2/work/video/film.mp4"))
                .thenReturn(metadata);

        CosService.ObjectHead head = cosService.headObject("WFA3B1E7A2/work/video/film.mp4");

        assertThat(head.contentType()).isEqualTo("video/mp4");
        assertThat(head.contentLength()).isEqualTo(4096L);
    }

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

    // ── storage init ───────────────────────────────────────

    @Test
    void initTeamStorageShouldCreateTeamFolders() {
        COSClient cosClient = mock(COSClient.class);
        when(transferManager.getCOSClient()).thenReturn(cosClient);

        cosService.initTeamStorage("TM2048");

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(cosClient, times(3)).putObject(captor.capture());
        List<String> keys = captor.getAllValues().stream()
                .map(PutObjectRequest::getKey)
                .toList();
        assertThat(keys).containsExactly("TM2048/", "TM2048/others/", "TM2048/protfolio/");
        assertThat(keys).noneMatch(key -> key.contains("/work/"));
        assertThat(captor.getAllValues())
                .allSatisfy(request -> assertThat(request.getMetadata().getContentType())
                        .isEqualTo("application/x-directory"));
    }

    @Test
    void initUserStorageShouldThrowWhenFolderCreationFails() {
        COSClient cosClient = mock(COSClient.class);
        when(transferManager.getCOSClient()).thenReturn(cosClient);
        doThrow(new RuntimeException("AccessDenied")).when(cosClient).putObject(any(PutObjectRequest.class));

        assertThatThrownBy(() -> cosService.initUserStorage("WFA3B1E7A2"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("COS folder creation failed")
                .hasRootCauseMessage("AccessDenied");
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
