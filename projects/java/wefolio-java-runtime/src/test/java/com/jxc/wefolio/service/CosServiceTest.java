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
import com.qcloud.cos.model.ciModel.snapshot.CosSnapshotRequest;
import com.qcloud.cos.transfer.TransferManager;
import com.qcloud.cos.transfer.Upload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
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

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
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

    /** COS 服务不得暴露可对任意远程 URL 发起请求的上传入口。 */
    @Test
    void cosServiceShouldNotExposeRemoteUrlUpload() {
        assertThat(CosService.class.getDeclaredMethods())
                .noneMatch(method -> "uploadFromUrl".equals(method.getName()));
    }

    // ── upload ──────────────────────────────────────────────

    @Test
    void createPostUploadTicketShouldRestrictKeyAndSizeInPolicy() {
        when(cosProperties.getSecretId()).thenReturn("AKID_TEST");
        when(cosProperties.getSecretKey()).thenReturn("SECRET_TEST");

        CosService.PostUploadTicket ticket = cosService.createPostUploadTicket(
                "WFA3B1E7A2/work/image/photo.jpg",
                "image/jpeg",
                10 * 1024 * 1024L,
                LocalDateTime.now().plusMinutes(30));

        Map<String, String> formData = ticket.formData();
        String policy = new String(Base64.getDecoder().decode(formData.get("policy")));
        assertThat(ticket.uploadUrl()).isEqualTo("https://cos.we-folio.dingchenyong.top");
        assertThat(ticket.objectKey()).isEqualTo("WFA3B1E7A2/work/image/photo.jpg");
        assertThat(formData).containsEntry("key", "WFA3B1E7A2/work/image/photo.jpg");
        assertThat(formData).containsEntry("q-ak", "AKID_TEST");
        assertThat(formData).containsEntry("q-sign-algorithm", "sha1");
        assertThat(formData).containsEntry("x-cos-acl", "public-read");
        assertThat(formData).containsEntry("Content-Type", "image/jpeg");
        assertThat(formData).containsEntry("success_action_status", "200");
        assertThat(formData.get("q-signature")).isNotBlank();
        assertThat(formData.get("q-signature")).doesNotContain("SECRET_TEST");
        assertThat(policy).contains("\"bucket\":\"test-bucket\"");
        assertThat(policy).contains("\"key\":\"WFA3B1E7A2/work/image/photo.jpg\"");
        assertThat(policy).contains("\"x-cos-acl\":\"public-read\"");
        assertThat(policy).contains("\"Content-Type\":\"image/jpeg\"");
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
    void createPostUploadTicketShouldUseDefaultMiniappDomainWhenUploadBaseUrlIsBlank() {
        when(cosProperties.getSecretId()).thenReturn("AKID_TEST");
        when(cosProperties.getSecretKey()).thenReturn("SECRET_TEST");
        when(cosProperties.getUploadBaseUrl()).thenReturn(" ");

        CosService.PostUploadTicket ticket = cosService.createPostUploadTicket(
                "WFA3B1E7A2/work/image/photo.jpg",
                "image/jpeg",
                1024L,
                LocalDateTime.now().plusMinutes(30));

        assertThat(ticket.uploadUrl()).isEqualTo("https://cos.we-folio.dingchenyong.top");
    }

    @Test
    void createPostUploadTicketShouldUseDefaultMiniappDomainWhenUploadBaseUrlIsCosOriginDomain() {
        when(cosProperties.getSecretId()).thenReturn("AKID_TEST");
        when(cosProperties.getSecretKey()).thenReturn("SECRET_TEST");
        when(cosProperties.getUploadBaseUrl()).thenReturn("https://we-folio-1302927298.cos.ap-guangzhou.myqcloud.com");

        CosService.PostUploadTicket ticket = cosService.createPostUploadTicket(
                "WFA3B1E7A2/work/image/photo.jpg",
                "image/jpeg",
                1024L,
                LocalDateTime.now().plusMinutes(30));

        assertThat(ticket.uploadUrl()).isEqualTo("https://cos.we-folio.dingchenyong.top");
    }

    @Test
    void createPostUploadTicketShouldNotUsePublicBaseUrlForMiniappUploadDomain() {
        when(cosProperties.getSecretId()).thenReturn("AKID_TEST");
        when(cosProperties.getSecretKey()).thenReturn("SECRET_TEST");

        CosService.PostUploadTicket ticket = cosService.createPostUploadTicket(
                "WFA3B1E7A2/work/image/photo.jpg",
                "image/jpeg",
                1024L,
                LocalDateTime.now().plusMinutes(30));

        assertThat(ticket.uploadUrl()).isEqualTo("https://cos.we-folio.dingchenyong.top");
        verify(cosProperties, never()).getPublicBaseUrl();
    }

    @Test
    void createPostUploadTicketShouldEscapeJsonPolicyValues() {
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
    void snapshotVideoFrameToObjectShouldRequestCiSnapshotAndUploadJpeg(CapturedOutput output) throws Exception {
        COSClient cosClient = mock(COSClient.class);
        byte[] imageBytes = "jpeg-frame".getBytes();
        when(transferManager.getCOSClient()).thenReturn(cosClient);
        when(cosClient.getSnapshot(any(CosSnapshotRequest.class)))
                .thenReturn(new ByteArrayInputStream(imageBytes));

        CosService.SnapshotObject result = cosService.snapshotVideoFrameToObject(
                "WFA3B1E7A2/work/video/film.mp4",
                "WFA3B1E7A2/work/video/film-thumb.jpg",
                1500L,
                640,
                360);

        ArgumentCaptor<CosSnapshotRequest> snapshotCaptor = ArgumentCaptor.forClass(CosSnapshotRequest.class);
        ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(cosClient).getSnapshot(snapshotCaptor.capture());
        verify(cosClient).putObject(putCaptor.capture());
        CosSnapshotRequest snapshotRequest = snapshotCaptor.getValue();
        assertThat(snapshotRequest.getBucketName()).isEqualTo("test-bucket");
        assertThat(snapshotRequest.getObjectKey()).isEqualTo("WFA3B1E7A2/work/video/film.mp4");
        assertThat(snapshotRequest.getTime()).isEqualTo("1.500");
        assertThat(snapshotRequest.getFormat()).isEqualTo("jpg");
        assertThat(snapshotRequest.getWidth()).isEqualTo("640");
        assertThat(snapshotRequest.getHeight()).isEqualTo("360");
        PutObjectRequest putRequest = putCaptor.getValue();
        assertThat(putRequest.getBucketName()).isEqualTo("test-bucket");
        assertThat(putRequest.getKey()).isEqualTo("WFA3B1E7A2/work/video/film-thumb.jpg");
        assertThat(putRequest.getMetadata().getContentType()).isEqualTo("image/jpeg");
        assertThat(putRequest.getMetadata().getContentLength()).isEqualTo(imageBytes.length);
        assertThat(result.objectKey()).isEqualTo("WFA3B1E7A2/work/video/film-thumb.jpg");
        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(result.contentLength()).isEqualTo(imageBytes.length);
        assertThat(result.sha256()).isEqualTo(hexSha256(imageBytes));
        assertThat(output)
                .contains("COS video snapshot request: bucketName=test-bucket")
                .contains("sourceKey=WFA3B1E7A2/work/video/film.mp4")
                .contains("targetKey=WFA3B1E7A2/work/video/film-thumb.jpg")
                .contains("frameTimeMs=1500")
                .contains("snapshotTime=1.500")
                .contains("format=jpg")
                .contains("width=640")
                .contains("height=360");
    }

    @Test
    void snapshotVideoFrameToObjectShouldCompressSnapshotWhenOverLimit(CapturedOutput output) throws Exception {
        COSClient cosClient = mock(COSClient.class);
        byte[] imageBytes = createLargeJpeg();
        assertThat(imageBytes.length).isGreaterThan(100 * 1024);
        when(transferManager.getCOSClient()).thenReturn(cosClient);
        when(cosClient.getSnapshot(any(CosSnapshotRequest.class)))
                .thenReturn(new ByteArrayInputStream(imageBytes));

        CosService.SnapshotObject result = cosService.snapshotVideoFrameToObject(
                "WFA3B1E7A2/work/video/film.mp4",
                "WFA3B1E7A2/work/video/film-thumb.jpg",
                1500L,
                640,
                640);

        ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(cosClient).putObject(putCaptor.capture());
        PutObjectRequest putRequest = putCaptor.getValue();
        byte[] uploadedBytes = putRequest.getInputStream().readAllBytes();
        assertThat(uploadedBytes.length).isLessThanOrEqualTo(100 * 1024);
        assertThat(uploadedBytes.length).isLessThan(imageBytes.length);
        assertThat(putRequest.getMetadata().getContentLength()).isEqualTo(uploadedBytes.length);
        assertThat(result.contentLength()).isEqualTo(uploadedBytes.length);
        assertThat(result.sha256()).isEqualTo(hexSha256(uploadedBytes));
        assertThat(output)
                .contains("COS video snapshot compressed")
                .contains("originalSize=" + imageBytes.length)
                .contains("compressedSize=" + uploadedBytes.length);
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
    void uploadToObjectKeyShouldUseExactKey() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.jpeg", "image/jpeg", "hello".getBytes());
        String objectKey = "WF8392/others/avatar-20260720111252-a1b2c3d4.jpg";
        Upload upload = mock(Upload.class);
        when(transferManager.upload(any(PutObjectRequest.class))).thenReturn(upload);

        String result = cosService.uploadToObjectKey(file, objectKey);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(transferManager).upload(captor.capture());
        verify(upload).waitForUploadResult();
        assertThat(captor.getValue().getKey()).isEqualTo(objectKey);
        assertThat(result).isEqualTo(objectKey);
    }

    /** 未提供 Content-Type 时必须根据对象键扩展名补齐 COS 元数据。 */
    @Test
    void uploadToObjectKeyShouldInferContentTypeFromObjectKeyWhenHeaderMissing() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar", null, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        String objectKey = "WF8392/others/avatar-20260720111252-a1b2c3d4.png";
        Upload upload = mock(Upload.class);
        when(transferManager.upload(any(PutObjectRequest.class))).thenReturn(upload);

        cosService.uploadToObjectKey(file, objectKey);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(transferManager).upload(captor.capture());
        assertThat(captor.getValue().getMetadata().getContentType()).isEqualTo("image/png");
    }

    /** 通用二进制 Content-Type 不得覆盖头像对象键对应的真实 MIME。 */
    @Test
    void uploadToObjectKeyShouldPreferAvatarObjectKeyTypeOverGenericHeader() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.bin", "application/octet-stream",
                new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'});
        String objectKey = "WF8392/others/avatar-20260720111252-a1b2c3d4.webp";
        Upload upload = mock(Upload.class);
        when(transferManager.upload(any(PutObjectRequest.class))).thenReturn(upload);

        cosService.uploadToObjectKey(file, objectKey);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(transferManager).upload(captor.capture());
        assertThat(captor.getValue().getMetadata().getContentType()).isEqualTo("image/webp");
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

    private String hexSha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder builder = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            builder.append(String.format("%02x", value & 0xff));
        }
        return builder.toString();
    }

    private byte[] createLargeJpeg() throws Exception {
        BufferedImage image = new BufferedImage(640, 640, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int red = (x * 13 + y * 7) & 0xff;
                int green = (x * 5 + y * 17) & 0xff;
                int blue = (x * 19 + y * 3) & 0xff;
                image.setRGB(x, y, new Color(red, green, blue).getRGB());
            }
        }
        return writeJpeg(image, 1.0f);
    }

    private byte[] writeJpeg(BufferedImage image, float quality) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            writer.write(null, new IIOImage(image, null, null), param);
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
