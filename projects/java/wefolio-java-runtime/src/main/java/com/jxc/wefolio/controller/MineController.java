package com.jxc.wefolio.controller;

import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.dto.MineDashboardResponse;
import com.jxc.wefolio.dto.MineProfileResponse;
import com.jxc.wefolio.dto.MineProfileUpdateRequest;
import com.jxc.wefolio.service.MineDashboardService;
import com.jxc.wefolio.service.MineProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的首页控制器 — 提供维护者工作台接口。
 */
@MaintainerAccess
@RestController
@RequiredArgsConstructor
public class MineController {

    /** 我的首页服务 */
    private final MineDashboardService mineDashboardService;

    /** 基础信息服务 */
    private final MineProfileService mineProfileService;

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
     * 保存基础信息页资料。
     *
     * @param request 基础信息保存请求
     * @return 保存后的基础信息响应
     */
    @PutMapping("/api/mine/profile")
    public Response<MineProfileResponse> updateProfile(@RequestBody MineProfileUpdateRequest request) {
        return Response.success(mineProfileService.updateProfile(request));
    }
}
