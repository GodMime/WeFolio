package com.jxc.wefolio.controller;

import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.common.upload.AvatarUploadResult;
import com.jxc.wefolio.annotation.LoginAccess;
import com.jxc.wefolio.dto.FileUploadResponse;
import com.jxc.wefolio.dto.MaintainerWechatLoginRequest;
import com.jxc.wefolio.dto.MaintainerWechatLoginPrecheckRequest;
import com.jxc.wefolio.dto.MaintainerWechatLoginPrecheckResponse;
import com.jxc.wefolio.service.AccountCancellationService;
import com.jxc.wefolio.service.AuthTokenService;
import com.jxc.wefolio.service.MaintainerAvatarService;
import com.jxc.wefolio.service.MiniappAuthService;
import com.jxc.wefolio.service.TrustedClientIpResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 小程序认证控制器接口与入参校验测试。
 */
@ExtendWith(MockitoExtension.class)
class MiniappAuthControllerTest {

    @Mock
    private MiniappAuthService miniappAuthService;

    @Mock
    private AuthTokenService authTokenService;

    @Mock
    private AccountCancellationService accountCancellationService;

    @Mock
    private MaintainerAvatarService maintainerAvatarService;

    @Mock
    private TrustedClientIpResolver trustedClientIpResolver;

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    @Test
    void maintainerWechatLoginUsesDedicatedLoginPath() throws NoSuchMethodException {
        Method method = MiniappAuthController.class.getMethod(
                "maintainerWechatLogin",
                MaintainerWechatLoginRequest.class
        );

        PostMapping postMapping = method.getAnnotation(PostMapping.class);

        assertThat(method.isAnnotationPresent(LoginAccess.class)).isTrue();
        assertThat(postMapping).isNotNull();
        assertThat(postMapping.value()).containsExactly("/maintainer/wechat-login");
    }

    @Test
    void maintainerWechatLoginPrecheckUsesDedicatedLoginAccessPath() throws NoSuchMethodException {
        Method method = MiniappAuthController.class.getMethod(
                "precheckMaintainerWechatLogin",
                MaintainerWechatLoginPrecheckRequest.class
        );

        PostMapping postMapping = method.getAnnotation(PostMapping.class);

        assertThat(method.isAnnotationPresent(LoginAccess.class)).isTrue();
        assertThat(postMapping).isNotNull();
        assertThat(postMapping.value()).containsExactly("/maintainer/wechat-login/precheck");
    }

    @Test
    void maintainerWechatLoginPrecheckDelegatesToService() {
        MaintainerWechatLoginPrecheckRequest request = new MaintainerWechatLoginPrecheckRequest();
        request.setCode("precheck-code");
        MaintainerWechatLoginPrecheckResponse serviceResponse = new MaintainerWechatLoginPrecheckResponse();
        serviceResponse.setPhoneAuthorizationRequired(true);
        when(miniappAuthService.precheckMaintainerWechatLogin(request)).thenReturn(serviceResponse);
        MiniappAuthController controller = new MiniappAuthController(
                miniappAuthService,
                authTokenService,
                accountCancellationService,
                maintainerAvatarService,
                trustedClientIpResolver
        );

        Response<MaintainerWechatLoginPrecheckResponse> response =
                controller.precheckMaintainerWechatLogin(request);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isSameAs(serviceResponse);
        verify(miniappAuthService).precheckMaintainerWechatLogin(request);
    }

    @Test
    void uploadAvatarShouldMapServiceValidationFailureWithoutChangingResponseContract() {
        byte[] content = new byte[200 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "avatar.jpg",
                "image/jpeg",
                content
        );
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        when(maintainerAvatarService.uploadAvatar(7L, file))
                .thenReturn(AvatarUploadResult.failure("头像文件不能超过 200KB"));
        MiniappAuthController controller = new MiniappAuthController(
                miniappAuthService,
                authTokenService,
                accountCancellationService,
                maintainerAvatarService,
                trustedClientIpResolver
        );

        Response<FileUploadResponse> response = controller.uploadAvatar(file);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("头像文件不能超过 200KB");
        verify(maintainerAvatarService).uploadAvatar(7L, file);
    }

    @Test
    void uploadAvatarShouldMapServiceSuccessResponse() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "avatar.jpeg",
                "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00}
        );
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        FileUploadResponse uploadResponse = new FileUploadResponse();
        uploadResponse.setKey("WF8392/others/avatar-20260720111252-a1b2c3d4.jpg");
        uploadResponse.setUrl("https://cos.example.com/" + uploadResponse.getKey());
        when(maintainerAvatarService.uploadAvatar(7L, file))
                .thenReturn(AvatarUploadResult.succeeded(uploadResponse));
        MiniappAuthController controller = new MiniappAuthController(
                miniappAuthService,
                authTokenService,
                accountCancellationService,
                maintainerAvatarService,
                trustedClientIpResolver
        );

        Response<FileUploadResponse> response = controller.uploadAvatar(file);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isSameAs(uploadResponse);
        verify(maintainerAvatarService).uploadAvatar(7L, file);
    }

    @Test
    void cancelAccountDelegatesToCancellationService() {
        MiniappAuthController controller = new MiniappAuthController(
                miniappAuthService,
                authTokenService,
                accountCancellationService,
                maintainerAvatarService,
                trustedClientIpResolver
        );

        Response<Void> response = controller.cancelAccount();

        assertThat(response.isSuccess()).isTrue();
        verify(accountCancellationService).cancelCurrentUser();
    }
}
