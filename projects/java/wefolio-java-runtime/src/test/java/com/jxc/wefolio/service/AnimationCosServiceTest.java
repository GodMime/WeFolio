package com.jxc.wefolio.service;

import com.jxc.wefolio.config.CosProperties;
import com.jxc.wefolio.exception.BusinessException;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectInputStream;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.transfer.TransferManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.http.client.methods.HttpGet;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据万象动图能力测试。
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class AnimationCosServiceTest {

    /** COS 传输管理器。 */
    @Mock
    private TransferManager transferManager;

    /** COS 客户端。 */
    @Mock
    private COSClient cosClient;

    /** 被测服务。 */
    private AnimationCosService service;

    /**
     * 初始化被测服务。
     */
    @BeforeEach
    void setUp() {
        CosProperties properties = new CosProperties();
        properties.setBucketName("test-bucket");
        lenient().when(transferManager.getCOSClient()).thenReturn(cosClient);
        service = new AnimationCosService(transferManager, properties);
    }

    /**
     * imageInfo 应解析并归一化 GIF 元数据。
     */
    @Test
    void inspectShouldParseGifMetadataAndUseImageInfoQuery() {
        when(cosClient.getObject(any(GetObjectRequest.class))).thenReturn(cosObject("""
                {"format":"GIF","width":"1080","height":720,"frame_count":"120"}
                """));

        AnimationCosService.AnimationMetadata metadata =
                service.inspect("WFA3B1E7A2/work/animation/demo.gif");

        ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(cosClient).getObject(captor.capture());
        GetObjectRequest request = captor.getValue();
        assertThat(request.getBucketName()).isEqualTo("test-bucket");
        assertThat(request.getKey()).isEqualTo("WFA3B1E7A2/work/animation/demo.gif");
        assertThat(request.getCustomQueryParameters()).containsKey("imageInfo");
        assertThat(metadata.format()).isEqualTo("gif");
        assertThat(metadata.width()).isEqualTo(1080);
        assertThat(metadata.height()).isEqualTo(720);
        assertThat(metadata.frameCount()).isEqualTo(120);
    }

    /**
     * imageInfo 应将 WebP 格式统一为小写。
     */
    @Test
    void inspectShouldNormalizeWebpFormat() {
        when(cosClient.getObject(any(GetObjectRequest.class))).thenReturn(cosObject("""
                {"format":"WebP","width":640,"height":360,"frame_count":2}
                """));

        AnimationCosService.AnimationMetadata metadata =
                service.inspect("WFA3B1E7A2/work/animation/demo.webp");

        assertThat(metadata.format()).isEqualTo("webp");
        assertThat(metadata.frameCount()).isEqualTo(2);
    }

    /**
     * 缺失或非法正整数元数据应拒绝，不能猜测默认值。
     */
    @Test
    void inspectShouldRejectMissingOrInvalidDimensions() {
        when(cosClient.getObject(any(GetObjectRequest.class))).thenReturn(cosObject("""
                {"format":"gif","width":"bad","height":720,"frame_count":2}
                """));

        assertThatThrownBy(() -> service.inspect("WFA3B1E7A2/work/animation/demo.gif"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("动图文件解析失败，请重新上传");
    }

    /**
     * imageInfo 超过边界时应拒绝，避免无界读取远端响应。
     */
    @Test
    void inspectShouldRejectOversizedImageInfoResponse() {
        byte[] oversized = new byte[64 * 1024 + 1];
        when(cosClient.getObject(any(GetObjectRequest.class))).thenReturn(cosObject(oversized));

        assertThatThrownBy(() -> service.inspect("WFA3B1E7A2/work/animation/demo.gif"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("动图文件解析失败，请重新上传");
    }

    /**
     * 首尾合法帧均应通过下载时处理生成 JPG 对象。
     *
     * @throws Exception 读取上传请求或计算摘要失败时抛出
     */
    @Test
    void generateCoverShouldUseFrameQueryAndUploadJpegForBoundaryFrames() throws Exception {
        byte[] firstFrame = jpeg("first");
        byte[] lastFrame = jpeg("last");
        when(cosClient.getObject(any(GetObjectRequest.class)))
                .thenReturn(cosObject(firstFrame), cosObject(lastFrame));

        AnimationCosService.GeneratedFrame first = service.generateCover(
                "WFA3B1E7A2/work/animation/demo.gif",
                "WFA3B1E7A2/work/animation/demo-thumb.jpg",
                1);
        AnimationCosService.GeneratedFrame last = service.generateCover(
                "WFA3B1E7A2/work/animation/demo.gif",
                "WFA3B1E7A2/work/animation/demo-thumb-2.jpg",
                300);

        ArgumentCaptor<GetObjectRequest> getCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
        ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(cosClient, times(2)).getObject(getCaptor.capture());
        verify(cosClient, times(2)).putObject(putCaptor.capture());
        assertThat(getCaptor.getAllValues().get(0).getCustomQueryParameters())
                .containsKey("imageMogr2/frame/1/thumbnail/1280x1280>/strip/format/jpg/size-limit/100k!");
        assertThat(getCaptor.getAllValues().get(1).getCustomQueryParameters())
                .containsKey("imageMogr2/frame/300/thumbnail/1280x1280>/strip/format/jpg/size-limit/100k!");
        PutObjectRequest firstPut = putCaptor.getAllValues().get(0);
        assertThat(firstPut.getBucketName()).isEqualTo("test-bucket");
        assertThat(firstPut.getKey()).isEqualTo("WFA3B1E7A2/work/animation/demo-thumb.jpg");
        assertThat(firstPut.getMetadata().getContentType()).isEqualTo("image/jpeg");
        assertThat(firstPut.getMetadata().getContentLength()).isEqualTo(firstFrame.length);
        assertThat(firstPut.getInputStream().readAllBytes()).isEqualTo(firstFrame);
        assertThat(first.sha256()).isEqualTo(sha256(firstFrame));
        assertThat(last.sha256()).isEqualTo(sha256(lastFrame));
    }

    /**
     * 非法帧号应在调用 COS 前拒绝。
     */
    @Test
    void generateCoverShouldRejectFrameOutsideSupportedRange() {
        assertThatThrownBy(() -> service.generateCover("source.gif", "target.jpg", 0))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.generateCover("source.gif", "target.jpg", 301))
                .isInstanceOf(BusinessException.class);

        verify(cosClient, never()).getObject(any(GetObjectRequest.class));
    }

    /**
     * 空响应或超过 100KB 的响应不得写入目标对象。
     */
    @Test
    void generateCoverShouldRejectEmptyOrOversizedResponseBeforePut() {
        when(cosClient.getObject(any(GetObjectRequest.class)))
                .thenReturn(cosObject(new byte[0]), cosObject(new byte[100 * 1024 + 1]));

        assertThatThrownBy(() -> service.generateCover("source.gif", "target-1.jpg", 1))
                .isInstanceOf(BusinessException.class)
                .hasMessage("动图封面生成失败，请稍后重试");
        assertThatThrownBy(() -> service.generateCover("source.gif", "target-2.jpg", 2))
                .isInstanceOf(BusinessException.class)
                .hasMessage("动图封面生成失败，请稍后重试");

        verify(cosClient, never()).putObject(any(PutObjectRequest.class));
    }

    /**
     * 小体积错误响应不是 JPG 时不得伪装成封面写入 COS。
     */
    @Test
    void generateCoverShouldRejectNonJpegResponseBeforePut() {
        when(cosClient.getObject(any(GetObjectRequest.class)))
                .thenReturn(cosObject("upstream-error".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> service.generateCover("source.gif", "target.jpg", 1))
                .isInstanceOf(BusinessException.class)
                .hasMessage("动图封面生成失败，请稍后重试");

        verify(cosClient, never()).putObject(any(PutObjectRequest.class));
    }

    /**
     * 远端异常日志应包含对象键，且不得包含密钥配置。
     */
    @Test
    void remoteFailureShouldExposeObjectKeyButNotSecret(CapturedOutput output) {
        doThrow(new RuntimeException("remote failed"))
                .when(cosClient).getObject(any(GetObjectRequest.class));

        assertThatThrownBy(() -> service.inspect("WFA3B1E7A2/work/animation/demo.gif"))
                .isInstanceOf(BusinessException.class);

        assertThat(output)
                .contains("WFA3B1E7A2/work/animation/demo.gif")
                .doesNotContain("secret-id", "secret-key");
    }

    /**
     * 补偿删除失败只记录告警，不覆盖原业务结果。
     */
    @Test
    void deleteQuietlyShouldSuppressRemoteFailure(CapturedOutput output) {
        doThrow(new RuntimeException("delete failed"))
                .when(cosClient).deleteObject(anyString(), anyString());

        service.deleteQuietly(
                "WFA3B1E7A2/work/animation/demo-thumb.jpg",
                "upload-confirm");

        assertThat(output)
                .contains("WFA3B1E7A2/work/animation/demo-thumb.jpg")
                .contains("upload-confirm");
    }

    private COSObject cosObject(String payload) {
        return cosObject(payload.getBytes(StandardCharsets.UTF_8));
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

    private String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder builder = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            builder.append(String.format("%02x", value & 0xff));
        }
        return builder.toString();
    }
}
