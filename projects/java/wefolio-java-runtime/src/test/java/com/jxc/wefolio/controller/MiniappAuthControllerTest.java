package com.jxc.wefolio.controller;

import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.annotation.LoginAccess;
import com.jxc.wefolio.dto.FileUploadResponse;
import com.jxc.wefolio.dto.MaintainerWechatLoginRequest;
import com.jxc.wefolio.service.AccountCancellationService;
import com.jxc.wefolio.service.AuthTokenService;
import com.jxc.wefolio.service.CosService;
import com.jxc.wefolio.service.MiniappAuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
    private CosService cosService;

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
    void uploadAvatarRejectsFilesLargerThanFiveMegabytes() {
        byte[] content = new byte[5 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "avatar.jpg",
                "image/jpeg",
                content
        );
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        MiniappAuthController controller = new MiniappAuthController(
                miniappAuthService,
                authTokenService,
                accountCancellationService,
                cosService
        );

        Response<FileUploadResponse> response = controller.uploadAvatar(file);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("头像文件不能超过 5MB");
        verify(cosService, never()).upload(file, "WF8392/others");
    }

    @Test
    void cancelAccountDelegatesToCancellationService() {
        MiniappAuthController controller = new MiniappAuthController(
                miniappAuthService,
                authTokenService,
                accountCancellationService,
                cosService
        );

        Response<Void> response = controller.cancelAccount();

        assertThat(response.isSuccess()).isTrue();
        verify(accountCancellationService).cancelCurrentUser();
    }
}
