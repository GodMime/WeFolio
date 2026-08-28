package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.common.upload.AvatarUploadResult;
import com.jxc.wefolio.dto.FileUploadResponse;
import com.jxc.wefolio.dto.MineTeamCreateRequest;
import com.jxc.wefolio.dto.MineTeamDetailResponse;
import com.jxc.wefolio.dto.MineTeamIdempotentCreateRequest;
import com.jxc.wefolio.dto.MineTeamInvitationResponse;
import com.jxc.wefolio.dto.MineTeamListResponse;
import com.jxc.wefolio.dto.MineTeamMemberChangeCreateRequest;
import com.jxc.wefolio.dto.MineTeamMemberChangeDetailRequest;
import com.jxc.wefolio.dto.MineTeamMemberChangeDetailResponse;
import com.jxc.wefolio.dto.MineTeamMemberCandidateResponse;
import com.jxc.wefolio.dto.MineTeamMemberInviteRequest;
import com.jxc.wefolio.dto.MineTeamMemberRemoveRequest;
import com.jxc.wefolio.dto.MineTeamUpdateRequest;
import com.jxc.wefolio.dto.MineTeamOwnerTransferRequest;
import com.jxc.wefolio.service.MineTeamService;
import com.jxc.wefolio.service.MineTeamCreationApplicationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 我的团队控制器测试 — 确认团队接口独立承载并委托团队服务。
 */
@ExtendWith(MockitoExtension.class)
class MineTeamControllerTest {

    /** 我的团队服务模拟 */
    @Mock
    private MineTeamService mineTeamService;

    /** 团队创建幂等应用服务模拟 */
    @Mock
    private MineTeamCreationApplicationService creationApplicationService;

    /** 团队 Controller 不得持有业务操作日志。 */
    @Test
    void controllerShouldOnlyDelegateWithoutBusinessLogs() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/jxc/wefolio/controller/MineTeamController.java"));

        assertThat(source).doesNotContain("@Slf4j", "log.info(");
    }

    @Test
    void teamEndpointsUseMaintainerAccessAndDelegateToService() throws NoSuchMethodException {
        MineTeamController controller = new MineTeamController(mineTeamService, creationApplicationService);
        MineTeamListResponse listResponse = new MineTeamListResponse();
        MineTeamDetailResponse detailResponse = new MineTeamDetailResponse();
        MineTeamMemberCandidateResponse candidateResponse = new MineTeamMemberCandidateResponse();
        MineTeamInvitationResponse invitationResponse = new MineTeamInvitationResponse();
        MineTeamMemberChangeDetailResponse changeDetailResponse = new MineTeamMemberChangeDetailResponse();
        MineTeamCreateRequest createRequest = new MineTeamCreateRequest();
        MineTeamIdempotentCreateRequest idempotentCreateRequest = new MineTeamIdempotentCreateRequest();
        MineTeamUpdateRequest updateRequest = new MineTeamUpdateRequest();
        MineTeamMemberInviteRequest inviteRequest = new MineTeamMemberInviteRequest();
        MineTeamMemberChangeCreateRequest changeRequest = new MineTeamMemberChangeCreateRequest();
        MineTeamMemberChangeDetailRequest changeDetailRequest = new MineTeamMemberChangeDetailRequest();
        MineTeamOwnerTransferRequest transferRequest = new MineTeamOwnerTransferRequest();
        MineTeamMemberRemoveRequest removeRequest = new MineTeamMemberRemoveRequest();
        FileUploadResponse uploadResponse = new FileUploadResponse();
        MockMultipartFile file = new MockMultipartFile("file", "team.png", "image/png", pngBytes());
        when(mineTeamService.listTeams()).thenReturn(listResponse);
        when(mineTeamService.createTeam(createRequest)).thenReturn(detailResponse);
        when(creationApplicationService.create(idempotentCreateRequest)).thenReturn(detailResponse);
        when(mineTeamService.getTeamDetail(100L)).thenReturn(detailResponse);
        when(mineTeamService.updateTeam(100L, updateRequest)).thenReturn(detailResponse);
        when(mineTeamService.getMemberCandidate(100L, "WF1186")).thenReturn(candidateResponse);
        when(mineTeamService.inviteMember(100L, inviteRequest)).thenReturn(detailResponse);
        when(mineTeamService.getInvitation(31L)).thenReturn(invitationResponse);
        when(mineTeamService.acceptInvitation(31L)).thenReturn(invitationResponse);
        when(mineTeamService.rejectInvitation(31L)).thenReturn(invitationResponse);
        when(mineTeamService.createMemberChangeRequest(changeRequest)).thenReturn(detailResponse);
        when(mineTeamService.getMemberChangeRequestDetail(changeDetailRequest)).thenReturn(changeDetailResponse);
        when(mineTeamService.acceptMemberChangeRequest(changeDetailRequest)).thenReturn(changeDetailResponse);
        when(mineTeamService.rejectMemberChangeRequest(changeDetailRequest)).thenReturn(changeDetailResponse);
        when(mineTeamService.transferOwner(transferRequest)).thenReturn(detailResponse);
        when(mineTeamService.removeMember(removeRequest)).thenReturn(detailResponse);
        when(mineTeamService.uploadTeamAvatar(100L, file))
                .thenReturn(AvatarUploadResult.succeeded(uploadResponse));

        Response<MineTeamListResponse> teams = controller.teams();
        Response<MineTeamDetailResponse> created = controller.createTeam(createRequest);
        Response<MineTeamDetailResponse> idempotentCreated = controller.createTeamV2(idempotentCreateRequest);
        Response<MineTeamDetailResponse> detail = controller.teamDetail(100L);
        Response<MineTeamDetailResponse> updated = controller.updateTeam(100L, updateRequest);
        Response<MineTeamMemberCandidateResponse> candidate = controller.memberCandidate(100L, "WF1186");
        Response<MineTeamDetailResponse> invited = controller.inviteMember(100L, inviteRequest);
        Response<MineTeamInvitationResponse> invitation = controller.invitation(31L);
        Response<MineTeamInvitationResponse> accepted = controller.acceptInvitation(31L);
        Response<MineTeamInvitationResponse> rejected = controller.rejectInvitation(31L);
        Response<MineTeamDetailResponse> changeCreated = controller.createMemberChangeRequest(changeRequest);
        Response<MineTeamMemberChangeDetailResponse> changeDetail = controller.memberChangeRequestDetail(changeDetailRequest);
        Response<MineTeamMemberChangeDetailResponse> changeAccepted = controller.acceptMemberChangeRequest(changeDetailRequest);
        Response<MineTeamMemberChangeDetailResponse> changeRejected = controller.rejectMemberChangeRequest(changeDetailRequest);
        Response<MineTeamDetailResponse> transferred = controller.transferOwner(transferRequest);
        Response<MineTeamDetailResponse> removed = controller.removeMember(removeRequest);
        Response<FileUploadResponse> uploaded = controller.uploadTeamAvatar(100L, file);

        assertThat(MineTeamController.class.isAnnotationPresent(MaintainerAccess.class)).isTrue();
        assertGetMapping("teams", "/api/mine/teams");
        assertPostMapping("createTeam", new Class<?>[] {MineTeamCreateRequest.class}, "/api/mine/teams");
        assertPostMapping("createTeamV2", new Class<?>[] {MineTeamIdempotentCreateRequest.class},
                "/api/mine/teams/v2");
        assertThat(MineTeamController.class.getMethod("createTeam", MineTeamCreateRequest.class)
                .isAnnotationPresent(Deprecated.class)).isTrue();
        assertGetMapping("teamDetail", new Class<?>[] {Long.class}, "/api/mine/teams/{teamId}");
        assertPutMapping("updateTeam", new Class<?>[] {Long.class, MineTeamUpdateRequest.class}, "/api/mine/teams/{teamId}");
        assertGetMapping("memberCandidate", new Class<?>[] {Long.class, String.class}, "/api/mine/teams/{teamId}/member-candidate");
        assertPostMapping("inviteMember", new Class<?>[] {Long.class, MineTeamMemberInviteRequest.class}, "/api/mine/teams/{teamId}/members");
        assertGetMapping("invitation", new Class<?>[] {Long.class}, "/api/mine/team-invitations/{memberId}");
        assertPostMapping("acceptInvitation", new Class<?>[] {Long.class}, "/api/mine/team-invitations/{memberId}/accept");
        assertPostMapping("rejectInvitation", new Class<?>[] {Long.class}, "/api/mine/team-invitations/{memberId}/reject");
        assertPostMapping("createMemberChangeRequest",
                new Class<?>[] {MineTeamMemberChangeCreateRequest.class},
                "/api/mine/team-member-change-requests");
        assertPostMapping("memberChangeRequestDetail",
                new Class<?>[] {MineTeamMemberChangeDetailRequest.class},
                "/api/mine/team-member-change-requests/detail");
        assertPostMapping("acceptMemberChangeRequest",
                new Class<?>[] {MineTeamMemberChangeDetailRequest.class},
                "/api/mine/team-member-change-requests/accept");
        assertPostMapping("rejectMemberChangeRequest",
                new Class<?>[] {MineTeamMemberChangeDetailRequest.class},
                "/api/mine/team-member-change-requests/reject");
        assertPostMapping("transferOwner", new Class<?>[] {MineTeamOwnerTransferRequest.class}, "/api/mine/teams/transfer-owner");
        assertPostMapping("removeMember", new Class<?>[] {MineTeamMemberRemoveRequest.class}, "/api/mine/teams/remove-member");
        assertPostMapping("uploadTeamAvatar", new Class<?>[] {Long.class, MultipartFile.class}, "/api/mine/teams/{teamId}/avatar");
        assertThat(MineTeamController.class.getMethod("teamDetail", Long.class)
                .getParameters()[0].isAnnotationPresent(PathVariable.class)).isTrue();
        assertRequestBody("createMemberChangeRequest", MineTeamMemberChangeCreateRequest.class);
        assertRequestBody("memberChangeRequestDetail", MineTeamMemberChangeDetailRequest.class);
        assertRequestBody("acceptMemberChangeRequest", MineTeamMemberChangeDetailRequest.class);
        assertRequestBody("rejectMemberChangeRequest", MineTeamMemberChangeDetailRequest.class);
        assertRequestBody("transferOwner", MineTeamOwnerTransferRequest.class);
        assertRequestBody("removeMember", MineTeamMemberRemoveRequest.class);
        assertThat(teams.getData()).isSameAs(listResponse);
        assertThat(created.getData()).isSameAs(detailResponse);
        assertThat(idempotentCreated.getData()).isSameAs(detailResponse);
        assertThat(detail.getData()).isSameAs(detailResponse);
        assertThat(updated.getData()).isSameAs(detailResponse);
        assertThat(candidate.getData()).isSameAs(candidateResponse);
        assertThat(invited.getData()).isSameAs(detailResponse);
        assertThat(invitation.getData()).isSameAs(invitationResponse);
        assertThat(accepted.getData()).isSameAs(invitationResponse);
        assertThat(rejected.getData()).isSameAs(invitationResponse);
        assertThat(changeCreated.getData()).isSameAs(detailResponse);
        assertThat(changeDetail.getData()).isSameAs(changeDetailResponse);
        assertThat(changeAccepted.getData()).isSameAs(changeDetailResponse);
        assertThat(changeRejected.getData()).isSameAs(changeDetailResponse);
        assertThat(transferred.getData()).isSameAs(detailResponse);
        assertThat(removed.getData()).isSameAs(detailResponse);
        assertThat(uploaded.getData()).isSameAs(uploadResponse);
        verify(mineTeamService).listTeams();
        verify(mineTeamService).createTeam(createRequest);
        verify(creationApplicationService).create(idempotentCreateRequest);
        verify(mineTeamService).getTeamDetail(100L);
        verify(mineTeamService).updateTeam(100L, updateRequest);
        verify(mineTeamService).getMemberCandidate(100L, "WF1186");
        verify(mineTeamService).inviteMember(100L, inviteRequest);
        verify(mineTeamService).getInvitation(31L);
        verify(mineTeamService).acceptInvitation(31L);
        verify(mineTeamService).rejectInvitation(31L);
        verify(mineTeamService).createMemberChangeRequest(changeRequest);
        verify(mineTeamService).getMemberChangeRequestDetail(changeDetailRequest);
        verify(mineTeamService).acceptMemberChangeRequest(changeDetailRequest);
        verify(mineTeamService).rejectMemberChangeRequest(changeDetailRequest);
        verify(mineTeamService).transferOwner(transferRequest);
        verify(mineTeamService).removeMember(removeRequest);
        verify(mineTeamService).uploadTeamAvatar(100L, file);
    }

    @Test
    void uploadTeamAvatarMapsFileSizeValidationFailure() {
        byte[] content = new byte[200 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("file", "team.png", "image/png", content);
        when(mineTeamService.uploadTeamAvatar(100L, file))
                .thenReturn(AvatarUploadResult.failure("团队图标不能超过 200KB"));
        MineTeamController controller = new MineTeamController(mineTeamService, creationApplicationService);

        Response<FileUploadResponse> response = controller.uploadTeamAvatar(100L, file);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("团队图标不能超过 200KB");
        verify(mineTeamService).uploadTeamAvatar(100L, file);
    }

    @Test
    void uploadTeamAvatarMapsImageTypeValidationFailure() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "team.png",
                "image/png",
                "not an image".getBytes()
        );
        when(mineTeamService.uploadTeamAvatar(100L, file))
                .thenReturn(AvatarUploadResult.failure("团队图标仅支持 JPG、PNG、GIF、WebP 格式"));
        MineTeamController controller = new MineTeamController(mineTeamService, creationApplicationService);

        Response<FileUploadResponse> response = controller.uploadTeamAvatar(100L, file);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("团队图标仅支持 JPG、PNG、GIF、WebP 格式");
        verify(mineTeamService).uploadTeamAvatar(100L, file);
    }

    /**
     * 断言 GET 映射路径。
     *
     * @param methodName 方法名
     * @param path 路径
     * @throws NoSuchMethodException 方法不存在时抛出
     */
    private void assertGetMapping(String methodName, String path) throws NoSuchMethodException {
        assertGetMapping(methodName, new Class<?>[0], path);
    }

    /**
     * 断言 GET 映射路径。
     *
     * @param methodName 方法名
     * @param parameterTypes 参数类型
     * @param path 路径
     * @throws NoSuchMethodException 方法不存在时抛出
     */
    private void assertGetMapping(String methodName, Class<?>[] parameterTypes, String path) throws NoSuchMethodException {
        GetMapping mapping = MineTeamController.class.getMethod(methodName, parameterTypes).getAnnotation(GetMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly(path);
    }

    /**
     * 断言 POST 映射路径。
     *
     * @param methodName 方法名
     * @param parameterTypes 参数类型
     * @param path 路径
     * @throws NoSuchMethodException 方法不存在时抛出
     */
    private void assertPostMapping(String methodName, Class<?>[] parameterTypes, String path) throws NoSuchMethodException {
        PostMapping mapping = MineTeamController.class.getMethod(methodName, parameterTypes).getAnnotation(PostMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly(path);
    }

    /**
     * 断言 PUT 映射路径。
     *
     * @param methodName 方法名
     * @param parameterTypes 参数类型
     * @param path 路径
     * @throws NoSuchMethodException 方法不存在时抛出
     */
    private void assertPutMapping(String methodName, Class<?>[] parameterTypes, String path) throws NoSuchMethodException {
        PutMapping mapping = MineTeamController.class.getMethod(methodName, parameterTypes).getAnnotation(PutMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly(path);
    }

    /**
     * 断言单参数接口参数来自请求体。
     *
     * @param methodName 方法名
     * @param parameterType 参数类型
     * @throws NoSuchMethodException 方法不存在时抛出
     */
    private void assertRequestBody(String methodName, Class<?> parameterType) throws NoSuchMethodException {
        assertThat(MineTeamController.class.getMethod(methodName, parameterType)
                .getParameters()[0].isAnnotationPresent(RequestBody.class)).isTrue();
    }

    /**
     * 构造最小 PNG 文件头，用于通过头像图片魔数校验。
     *
     * @return PNG 文件头字节
     */
    private byte[] pngBytes() {
        return new byte[] {
                (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'
        };
    }
}
