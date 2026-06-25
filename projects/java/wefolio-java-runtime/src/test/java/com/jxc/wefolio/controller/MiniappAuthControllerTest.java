package com.jxc.wefolio.controller;

import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.FileUploadResponse;
import com.jxc.wefolio.service.CosService;
import com.jxc.wefolio.service.MiniappAuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MiniappAuthControllerTest {

    @Mock
    private MiniappAuthService miniappAuthService;

    @Mock
    private CosService cosService;

    @Test
    void uploadAvatarRejectsFilesLargerThanFiveMegabytes() {
        byte[] content = new byte[5 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "avatar.jpg",
                "image/jpeg",
                content
        );
        when(miniappAuthService.resolveAuthenticatedUserId("Bearer wf-dev-user-7")).thenReturn(7L);
        MiniappAuthController controller = new MiniappAuthController(miniappAuthService, cosService);

        Response<FileUploadResponse> response = controller.uploadAvatar(file, "Bearer wf-dev-user-7");

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("头像文件不能超过 5MB");
        verify(cosService, never()).upload(file, "WF8392/others");
    }
}
