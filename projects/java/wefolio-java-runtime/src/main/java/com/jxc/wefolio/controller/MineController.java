package com.jxc.wefolio.controller;

import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.dto.MineDashboardResponse;
import com.jxc.wefolio.dto.MineProfileAssetUploadTicketRequest;
import com.jxc.wefolio.dto.MineProfileAssetUploadTicketResponse;
import com.jxc.wefolio.dto.MineProfileResponse;
import com.jxc.wefolio.dto.MineProfileUpdateRequest;
import com.jxc.wefolio.dto.MineVisitRecordsResponse;
import com.jxc.wefolio.service.MineDashboardService;
import com.jxc.wefolio.service.MineProfileService;
import com.jxc.wefolio.service.MineVisitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的首页控制器 — 提供维护者工作台接口。
 */
@Slf4j
@MaintainerAccess
@RestController
@RequiredArgsConstructor
public class MineController {

    /** 我的首页服务 */
    private final MineDashboardService mineDashboardService;

    /** 基础信息服务 */
    private final MineProfileService mineProfileService;

    /** 访问记录服务 */
    private final MineVisitService mineVisitService;

    /**
     * 获取我的首页数据。
     *
     * @return 我的首页响应
     */
    @GetMapping("/api/mine/dashboard")
    public Response<MineDashboardResponse> dashboard() {
        return Response.success(mineDashboardService.getDashboard());
    }

    /**
     * 获取基础信息页资料。
     *
     * @return 基础信息响应
     */
    @GetMapping("/api/mine/profile")
    public Response<MineProfileResponse> profile() {
        return Response.success(mineProfileService.getProfile());
    }

    /**
     * 创建基础信息资料图片直传 COS 票据。
     *
     * @param request 票据创建请求
     * @return 直传票据响应
     */
    @PostMapping("/api/mine/profile/assets/upload-ticket")
    public Response<MineProfileAssetUploadTicketResponse> createProfileAssetUploadTicket(
            @RequestBody MineProfileAssetUploadTicketRequest request
    ) {
        log.info("创建基础信息资料图片直传票据: assetType={}, mimeType={}, fileSize={}",
                request == null ? null : request.getAssetType(),
                request == null ? null : request.getMimeType(),
                request == null ? null : request.getFileSize());
        return Response.success(mineProfileService.createProfileAssetUploadTicket(request));
    }

    /**
     * 获取访问记录页数据。
     *
     * @return 访问记录页响应
     */
    @GetMapping("/api/mine/visits")
    public Response<MineVisitRecordsResponse> visits() {
        return Response.success(mineVisitService.getVisitRecords());
    }

    /**
     * 获取访问记录事件明细。
     *
     * @param recordId 访问汇总记录 ID
     * @param pageNo 页码，从 1 开始
     * @param pageSize 每页事件数量
     * @return 访问事件时间线
     */
    @GetMapping("/api/mine/visits/{recordId}/events")
    public Response<MineVisitRecordsResponse.EventTimeline> visitEvents(
            @PathVariable Long recordId,
            @RequestParam(required = false) Integer pageNo,
            @RequestParam(required = false) Integer pageSize
    ) {
        return Response.success(mineVisitService.getVisitEvents(recordId, pageNo, pageSize));
    }

    /**
     * 标记访问记录已跟进。
     *
     * @param recordId 访问汇总记录 ID
     * @return 更新后的访问明细
     */
    @PutMapping("/api/mine/visits/{recordId}/followed")
    public Response<MineVisitRecordsResponse.Record> markVisitFollowed(@PathVariable Long recordId) {
        log.info("标记访问记录已跟进: recordId={}", recordId);
        return Response.success(mineVisitService.markVisitFollowed(recordId));
    }

    /**
     * 保存基础信息页资料。
     *
     * @param request 基础信息保存请求
     * @return 保存后的基础信息响应
     */
    @PutMapping("/api/mine/profile")
    public Response<MineProfileResponse> updateProfile(@RequestBody MineProfileUpdateRequest request) {
        log.info("保存基础信息: nickname={}, avatarUrl={}, profession={}, city={}, intro={}, tags={}",
                request.getNickname(), request.getAvatarUrl(), request.getProfession(),
                request.getCity(), request.getIntro(), request.getTags());
        return Response.success(mineProfileService.updateProfile(request));
    }
}
