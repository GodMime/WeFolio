package com.jxc.wefolio.service;

import com.jxc.wefolio.common.upload.AvatarUploadResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 维护者头像上传服务测试。 */
@ExtendWith(MockitoExtension.class)
class MaintainerAvatarServiceTest {

    @Mock
    private MiniappAuthService miniappAuthService;

    @Mock
    private CosService cosService;

    /** 超限头像必须在查询用户和访问 COS 前返回原有失败消息。 */
    @Test
    void oversizedAvatarShouldReturnOriginalValidationMessageBeforeUpload() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.jpg", "image/jpeg", new byte[200 * 1024 + 1]);
        MaintainerAvatarService service = new MaintainerAvatarService(miniappAuthService, cosService);

        AvatarUploadResult result = service.uploadAvatar(7L, file);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("头像文件不能超过 200KB");
        assertThat(result.data()).isNull();
        verifyNoInteractions(miniappAuthService, cosService);
    }

    /** JPEG 头像必须沿用个人资料兼容对象键并统一使用 jpg 扩展名。 */
    @Test
    void jpegAvatarShouldUseProfileCompatibleObjectKey() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.jpeg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00});
        when(miniappAuthService.getUniqueCodeByUserId(7L)).thenReturn("WF8392");
        when(cosService.uploadToObjectKey(eq(file), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1, String.class));
        when(cosService.publicUrl(anyString()))
                .thenAnswer(invocation -> "https://cos.example.com/" + invocation.getArgument(0, String.class));
        MaintainerAvatarService service = new MaintainerAvatarService(miniappAuthService, cosService);

        AvatarUploadResult result = service.uploadAvatar(7L, file);

        ArgumentCaptor<String> objectKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(cosService).uploadToObjectKey(eq(file), objectKeyCaptor.capture());
        assertThat(objectKeyCaptor.getValue())
                .matches("WF8392/others/avatar-\\d{14}-[a-f0-9]{8}\\.jpg");
        assertThat(result.success()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.data().getKey()).isEqualTo(objectKeyCaptor.getValue());
        assertThat(result.data().getUrl())
                .isEqualTo("https://cos.example.com/" + objectKeyCaptor.getValue());
    }

    /** 请求头 MIME 不可信时必须根据文件魔数生成真实图片扩展名。 */
    @Test
    void pngAvatarShouldUseDetectedExtensionWhenContentTypeIsGeneric() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.bin", "application/octet-stream",
                new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        when(miniappAuthService.getUniqueCodeByUserId(7L)).thenReturn("WF8392");
        when(cosService.uploadToObjectKey(eq(file), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1, String.class));
        when(cosService.publicUrl(anyString()))
                .thenAnswer(invocation -> "https://cos.example.com/"
                        + invocation.getArgument(0, String.class));
        MaintainerAvatarService service = new MaintainerAvatarService(miniappAuthService, cosService);

        AvatarUploadResult result = service.uploadAvatar(7L, file);

        ArgumentCaptor<String> objectKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(cosService).uploadToObjectKey(eq(file), objectKeyCaptor.capture());
        assertThat(objectKeyCaptor.getValue()).endsWith(".png");
        assertThat(result.success()).isTrue();
    }
}
