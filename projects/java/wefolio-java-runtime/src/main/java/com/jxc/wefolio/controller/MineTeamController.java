package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.common.upload.AvatarFileValidator;
import com.jxc.wefolio.dto.FileUploadResponse;
import com.jxc.wefolio.dto.MineTeamCreateRequest;
import com.jxc.wefolio.dto.MineTeamDetailResponse;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 我的团队控制器 — 提供团队列表、创建、维护和图标上传接口。
 *
 * <p>团队相关接口独立放在这里，避免“我的”首页控制器继续膨胀；
 * 对外路径仍保持 {@code /api/mine/teams}，小程序侧无需感知控制器拆分。</p>
 */
@Slf4j
@MaintainerAccess
@RestController
@RequiredArgsConstructor
public class MineTeamController {

    /** 团队图标提示名称 */
    private static final String TEAM_AVATAR_FILE_LABEL = "团队图标";

    /** 我的团队业务服务，负责权限、团队唯一码、COS 目录和成员关系等核心逻辑 */
    private final MineTeamService mineTeamService;

    /**
     * 获取我的团队列表。
     *
     * @return 我的团队列表响应
     */
    @GetMapping("/api/mine/teams")
    public Response<MineTeamListResponse> teams() {
        return Response.success(mineTeamService.listTeams());
    }

    /**
     * 创建团队。
     *
     * @param request 团队创建请求
     * @return 新团队详情
     */
    @PostMapping("/api/mine/teams")
    public Response<MineTeamDetailResponse> createTeam(@RequestBody MineTeamCreateRequest request) {
        log.info("创建团队: name={}, intro={}, avatarUrl={}",
                request.getName(), request.getIntro(), request.getAvatarUrl());
        return Response.success(mineTeamService.createTeam(request));
    }

    /**
     * 获取团队维护详情。
     *
     * @param teamId 团队 ID
     * @return 团队维护详情
     */
    @GetMapping("/api/mine/teams/{teamId}")
    public Response<MineTeamDetailResponse> teamDetail(@PathVariable Long teamId) {
        return Response.success(mineTeamService.getTeamDetail(teamId));
    }

    /**
     * 保存团队资料。
     *
     * @param teamId 团队 ID
     * @param request 团队资料更新请求
     * @return 保存后的团队详情
     */
    @PutMapping("/api/mine/teams/{teamId}")
    public Response<MineTeamDetailResponse> updateTeam(
            @PathVariable Long teamId,
            @RequestBody MineTeamUpdateRequest request
    ) {
        log.info("保存团队资料: teamId={}, name={}, intro={}, avatarUrl={}",
                teamId, request.getName(), request.getIntro(), request.getAvatarUrl());
        return Response.success(mineTeamService.updateTeam(teamId, request));
    }

    /**
     * 查询团队成员候选人。
     *
     * @param teamId 团队 ID
     * @param uniqueCode 个人唯一码
     * @return 成员候选人
     */
    @GetMapping("/api/mine/teams/{teamId}/member-candidate")
    public Response<MineTeamMemberCandidateResponse> memberCandidate(
            @PathVariable Long teamId,
            @RequestParam("uniqueCode") String uniqueCode
    ) {
        return Response.success(mineTeamService.getMemberCandidate(teamId, uniqueCode));
    }

    /**
     * 邀请成员加入团队。
     *
     * @param teamId 团队 ID
     * @param request 成员邀请请求
     * @return 最新团队维护详情
     */
    @PostMapping("/api/mine/teams/{teamId}/members")
    public Response<MineTeamDetailResponse> inviteMember(
            @PathVariable Long teamId,
            @RequestBody MineTeamMemberInviteRequest request
    ) {
        log.info("邀请团队成员: teamId={}, uniqueCode={}, role={}",
                teamId, request.getUniqueCode(), request.getRole());
        return Response.success(mineTeamService.inviteMember(teamId, request));
    }

    /**
     * 获取团队邀请详情。
     *
     * @param memberId 团队成员关系 ID
     * @return 团队邀请详情
     */
    @GetMapping("/api/mine/team-invitations/{memberId}")
    public Response<MineTeamInvitationResponse> invitation(@PathVariable Long memberId) {
        return Response.success(mineTeamService.getInvitation(memberId));
    }

    /**
     * 接受团队邀请。
     *
     * @param memberId 团队成员关系 ID
     * @return 团队邀请详情
     */
    @PostMapping("/api/mine/team-invitations/{memberId}/accept")
    public Response<MineTeamInvitationResponse> acceptInvitation(@PathVariable Long memberId) {
        return Response.success(mineTeamService.acceptInvitation(memberId));
    }

    /**
     * 拒绝团队邀请。
     *
     * @param memberId 团队成员关系 ID
     * @return 团队邀请详情
     */
    @PostMapping("/api/mine/team-invitations/{memberId}/reject")
    public Response<MineTeamInvitationResponse> rejectInvitation(@PathVariable Long memberId) {
        return Response.success(mineTeamService.rejectInvitation(memberId));
    }

    /**
     * 发起团队成员信息变更。
     *
     * @param request 成员信息变更请求
     * @return 最新团队维护详情
     */
    @PostMapping("/api/mine/team-member-change-requests")
    public Response<MineTeamDetailResponse> createMemberChangeRequest(
            @RequestBody MineTeamMemberChangeCreateRequest request
    ) {
        log.info("发起团队成员信息变更: teamId={}, memberId={}, role={}",
                request.getTeamId(), request.getMemberId(), request.getRole());
        return Response.success(mineTeamService.createMemberChangeRequest(request));
    }

    /**
     * 获取团队成员信息变更详情。
     *
     * @param request 详情请求
     * @return 变更详情
     */
    @PostMapping("/api/mine/team-member-change-requests/detail")
    public Response<MineTeamMemberChangeDetailResponse> memberChangeRequestDetail(
            @RequestBody MineTeamMemberChangeDetailRequest request
    ) {
        return Response.success(mineTeamService.getMemberChangeRequestDetail(request));
    }

    /**
     * 同意团队成员信息变更。
     *
     * @param request 详情请求
     * @return 变更详情
     */
    @PostMapping("/api/mine/team-member-change-requests/accept")
    public Response<MineTeamMemberChangeDetailResponse> acceptMemberChangeRequest(
            @RequestBody MineTeamMemberChangeDetailRequest request
    ) {
        return Response.success(mineTeamService.acceptMemberChangeRequest(request));
    }

    /**
     * 拒绝团队成员信息变更。
     *
     * @param request 详情请求
     * @return 变更详情
     */
    @PostMapping("/api/mine/team-member-change-requests/reject")
    public Response<MineTeamMemberChangeDetailResponse> rejectMemberChangeRequest(
            @RequestBody MineTeamMemberChangeDetailRequest request
    ) {
        return Response.success(mineTeamService.rejectMemberChangeRequest(request));
    }

    /**
     * 转让团队拥有者。
     *
     * @param request 转让请求
     * @return 最新团队维护详情
     */
    @PostMapping("/api/mine/teams/transfer-owner")
    public Response<MineTeamDetailResponse> transferOwner(@RequestBody MineTeamOwnerTransferRequest request) {
        log.info("转让团队拥有者: teamId={}, memberId={}", request.getTeamId(), request.getMemberId());
        return Response.success(mineTeamService.transferOwner(request));
    }

    /**
     * 移除团队成员。
     *
     * @param request 移除请求
     * @return 最新团队维护详情
     */
    @PostMapping("/api/mine/teams/remove-member")
    public Response<MineTeamDetailResponse> removeMember(@RequestBody MineTeamMemberRemoveRequest request) {
        log.info("移除团队成员: teamId={}, memberId={}", request.getTeamId(), request.getMemberId());
        return Response.success(mineTeamService.removeMember(request));
    }

    /**
     * 上传团队图标。
     *
     * @param teamId 团队 ID
     * @param file 团队图标文件
     * @return 文件上传响应
     */
    @PostMapping(value = "/api/mine/teams/{teamId}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Response<FileUploadResponse> uploadTeamAvatar(
            @PathVariable Long teamId,
            @RequestParam("file") MultipartFile file
    ) {
        // 头像类文件格式在 Controller 层先拦截，权限与 COS 目录归属由 Service 统一判断。
        String validationMessage = AvatarFileValidator.validate(file, TEAM_AVATAR_FILE_LABEL);
        if (validationMessage != null) {
            return Response.fail(validationMessage);
        }
        log.info("团队图标上传开始: teamId={}, originalFilename={}, size={}",
                teamId, file.getOriginalFilename(), file.getSize());
        return Response.success(mineTeamService.uploadTeamAvatar(teamId, file));
    }
}
