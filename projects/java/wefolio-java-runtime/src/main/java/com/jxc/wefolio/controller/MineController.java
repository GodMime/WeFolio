package com.jxc.wefolio.controller;

import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.MineDashboardResponse;
import com.jxc.wefolio.dto.MineProfileResponse;
import com.jxc.wefolio.dto.MineProfileUpdateRequest;
import com.jxc.wefolio.service.MineDashboardService;
import com.jxc.wefolio.service.MineProfileService;
import com.jxc.wefolio.service.MiniappAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的首页控制器 — 提供维护者工作台接口
 */
@RestController
@RequiredArgsConstructor
public class MineController {

    /** 小程序登录服务 */
    private final MiniappAuthService miniappAuthService;

    /** 我的首页服务 */
    private final MineDashboardService mineDashboardService;

    /** 基础信息服务 */
    private final MineProfileService mineProfileService;

    /**
     * 获取我的首页数据
     *
     * @param authorization Authorization 请求头
     * @return 我的首页响应
     */
    @GetMapping("/api/mine/dashboard")
    public ResponseEntity<Response<MineDashboardResponse>> dashboard(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        Long userId = miniappAuthService.resolveAuthenticatedUserId(authorization);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Response.fail("未登录"));
        }
        return ResponseEntity.ok(Response.success(mineDashboardService.getDashboard(userId)));
    }

    /**
     * 获取基础信息页资料
     *
     * @param authorization Authorization 请求头
     * @return 基础信息响应
     */
    @GetMapping("/api/mine/profile")
    public ResponseEntity<Response<MineProfileResponse>> profile(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        Long userId = miniappAuthService.resolveAuthenticatedUserId(authorization);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Response.fail("未登录"));
        }
        return ResponseEntity.ok(Response.success(mineProfileService.getProfile(userId)));
    }

    /**
     * 保存基础信息页资料
     *
     * @param authorization Authorization 请求头
     * @param request       基础信息保存请求
     * @return 保存后的基础信息响应
     */
    @PutMapping("/api/mine/profile")
    public ResponseEntity<Response<MineProfileResponse>> updateProfile(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody MineProfileUpdateRequest request
    ) {
        Long userId = miniappAuthService.resolveAuthenticatedUserId(authorization);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Response.fail("未登录"));
        }
        return ResponseEntity.ok(Response.success(mineProfileService.updateProfile(userId, request)));
    }
}
