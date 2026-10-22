package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.LoginAccess;
import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.MaintainerWechatLoginResponse;
import com.jxc.wefolio.dto.MobileWechatLoginRequest;
import com.jxc.wefolio.service.MobileWechatAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 安卓微信登录 HTTP 适配 — 新增独立路由，不改变小程序认证契约。 */
@RestController
@LoginAccess
@RequiredArgsConstructor
@RequestMapping("/api/auth/mobile")
public class MobileWechatAuthController {

    /** 移动应用授权用例入口。 */
    private final MobileWechatAuthService authService;

    /** 独立的存量小程序身份关联接口，要求已有令牌及本人的新小程序登录码。 */
    @MaintainerAccess
    @PostMapping("/miniapp-link")
    public Response<Void> linkMiniapp(@RequestBody MobileWechatLoginRequest request) {
        authService.linkMiniapp(AuthContextHolder.requireUserId(), request);
        return Response.success();
    }

    /** 查询公开的移动应用登录与注册开关。 */
    @GetMapping("/config")
    public Response<MobileWechatAuthService.Capabilities> config() {
        return Response.success(authService.capabilities());
    }

    /** 将移动应用微信授权码委派给应用服务验证。 */
    @PostMapping("/wechat-login")
    public Response<MaintainerWechatLoginResponse> login(@RequestBody MobileWechatLoginRequest request) {
        return Response.success(authService.login(request));
    }
}
