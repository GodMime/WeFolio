package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.FileUploadResponse;
import com.jxc.wefolio.dto.MineTeamCreateRequest;
import com.jxc.wefolio.dto.MineTeamDetailResponse;
import com.jxc.wefolio.dto.MineTeamInvitationResponse;
import com.jxc.wefolio.dto.MineTeamListResponse;
import com.jxc.wefolio.dto.MineTeamMemberCandidateResponse;
import com.jxc.wefolio.dto.MineTeamMemberInviteRequest;
import com.jxc.wefolio.dto.MineTeamUpdateRequest;
import com.jxc.wefolio.service.MineTeamService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void teamEndpointsUseMaintainerAccessAndDelegateToService() throws NoSuchMethodException {
        MineTeamController controller = new MineTeamController(mineTeamService);
        MineTeamListResponse listResponse = new MineTeamListResponse();
        MineTeamDetailResponse detailResponse = new MineTeamDetailResponse();
        MineTeamMemberCandidateResponse candidateResponse = new MineTeamMemberCandidateResponse();
        MineTeamInvitationResponse invitationResponse = new MineTeamInvitationResponse();
        MineTeamCreateRequest createRequest = new MineTeamCreateRequest();
        MineTeamUpdateRequest updateRequest = new MineTeamUpdateRequest();
        MineTeamMemberInviteRequest inviteRequest = new MineTeamMemberInviteRequest();
        FileUploadResponse uploadResponse = new FileUploadResponse();
        MockMultipartFile file = new MockMultipartFile("file", "team.png", "image/png", "png".getBytes());
        when(mineTeamService.listTeams()).thenReturn(listResponse);
        when(mineTeamService.createTeam(createRequest)).thenReturn(detailResponse);
        when(mineTeamService.getTeamDetail(100L)).thenReturn(detailResponse);
        when(mineTeamService.updateTeam(100L, updateRequest)).thenReturn(detailResponse);
        when(mineTeamService.getMemberCandidate(100L, "WF1186")).thenReturn(candidateResponse);
        when(mineTeamService.inviteMember(100L, inviteRequest)).thenReturn(detailResponse);
        when(mineTeamService.getInvitation(31L)).thenReturn(invitationResponse);
        when(mineTeamService.acceptInvitation(31L)).thenReturn(invitationResponse);
        when(mineTeamService.rejectInvitation(31L)).thenReturn(invitationResponse);
        when(mineTeamService.uploadTeamAvatar(100L, file)).thenReturn(uploadResponse);

        Response<MineTeamListResponse> teams = controller.teams();
        Response<MineTeamDetailResponse> created = controller.createTeam(createRequest);
        Response<MineTeamDetailResponse> detail = controller.teamDetail(100L);
        Response<MineTeamDetailResponse> updated = controller.updateTeam(100L, updateRequest);
        Response<MineTeamMemberCandidateResponse> candidate = controller.memberCandidate(100L, "WF1186");
        Response<MineTeamDetailResponse> invited = controller.inviteMember(100L, inviteRequest);
        Response<MineTeamInvitationResponse> invitation = controller.invitation(31L);
        Response<MineTeamInvitationResponse> accepted = controller.acceptInvitation(31L);
        Response<MineTeamInvitationResponse> rejected = controller.rejectInvitation(31L);
        Response<FileUploadResponse> uploaded = controller.uploadTeamAvatar(100L, file);

        assertThat(MineTeamController.class.isAnnotationPresent(MaintainerAccess.class)).isTrue();
        assertGetMapping("teams", "/api/mine/teams");
        assertPostMapping("createTeam", new Class<?>[] {MineTeamCreateRequest.class}, "/api/mine/teams");
        assertGetMapping("teamDetail", new Class<?>[] {Long.class}, "/api/mine/teams/{teamId}");
        assertPutMapping("updateTeam", new Class<?>[] {Long.class, MineTeamUpdateRequest.class}, "/api/mine/teams/{teamId}");
        assertGetMapping("memberCandidate", new Class<?>[] {Long.class, String.class}, "/api/mine/teams/{teamId}/member-candidate");
        assertPostMapping("inviteMember", new Class<?>[] {Long.class, MineTeamMemberInviteRequest.class}, "/api/mine/teams/{teamId}/members");
        assertGetMapping("invitation", new Class<?>[] {Long.class}, "/api/mine/team-invitations/{memberId}");
        assertPostMapping("acceptInvitation", new Class<?>[] {Long.class}, "/api/mine/team-invitations/{memberId}/accept");
        assertPostMapping("rejectInvitation", new Class<?>[] {Long.class}, "/api/mine/team-invitations/{memberId}/reject");
        assertPostMapping("uploadTeamAvatar", new Class<?>[] {Long.class, MultipartFile.class}, "/api/mine/teams/{teamId}/avatar");
        assertThat(MineTeamController.class.getMethod("teamDetail", Long.class)
                .getParameters()[0].isAnnotationPresent(PathVariable.class)).isTrue();
        assertThat(teams.getData()).isSameAs(listResponse);
        assertThat(created.getData()).isSameAs(detailResponse);
        assertThat(detail.getData()).isSameAs(detailResponse);
        assertThat(updated.getData()).isSameAs(detailResponse);
        assertThat(candidate.getData()).isSameAs(candidateResponse);
        assertThat(invited.getData()).isSameAs(detailResponse);
        assertThat(invitation.getData()).isSameAs(invitationResponse);
        assertThat(accepted.getData()).isSameAs(invitationResponse);
        assertThat(rejected.getData()).isSameAs(invitationResponse);
        assertThat(uploaded.getData()).isSameAs(uploadResponse);
        verify(mineTeamService).listTeams();
        verify(mineTeamService).createTeam(createRequest);
        verify(mineTeamService).getTeamDetail(100L);
        verify(mineTeamService).updateTeam(100L, updateRequest);
        verify(mineTeamService).getMemberCandidate(100L, "WF1186");
        verify(mineTeamService).inviteMember(100L, inviteRequest);
        verify(mineTeamService).getInvitation(31L);
        verify(mineTeamService).acceptInvitation(31L);
        verify(mineTeamService).rejectInvitation(31L);
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
}
