package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.FileUploadResponse;
import com.jxc.wefolio.dto.MineTeamCreateRequest;
import com.jxc.wefolio.dto.MineTeamDetailResponse;
import com.jxc.wefolio.dto.MineTeamListResponse;
import com.jxc.wefolio.dto.MineTeamUpdateRequest;
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

    /** 团队图标最大大小：5MB，与小程序上传前校验保持一致 */
    private static final long MAX_TEAM_AVATAR_SIZE_BYTES = 5L * 1024L * 1024L;

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
        // 上传文件的空值和大小在 Controller 层先拦截，权限与 COS 目录归属由 Service 统一判断。
        if (file == null || file.isEmpty()) {
            return Response.fail("团队图标不能为空");
        }
        if (file.getSize() > MAX_TEAM_AVATAR_SIZE_BYTES) {
            return Response.fail("团队图标不能超过 5MB");
        }
        log.info("团队图标上传开始: teamId={}, originalFilename={}, size={}",
                teamId, file.getOriginalFilename(), file.getSize());
        return Response.success(mineTeamService.uploadTeamAvatar(teamId, file));
    }
}
