package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.LoginAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.LoginPageConfigResponse;
import com.jxc.wefolio.service.LoginPageConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 登录页配置控制器，提供免登录的展示配置查询。 */
@RestController
@RequiredArgsConstructor
public class LoginPageConfigController {

    /** 登录页公开配置接口路径。 */
    public static final String CONFIG_PATH = "/api/auth/login-page-config";

    /** 登录页配置服务。 */
    private final LoginPageConfigService loginPageConfigService;

    /** 返回公开配置并禁用 HTTP 缓存，便于后端调整配置后重新进入登录页生效。 */
    @LoginAccess
    @GetMapping(CONFIG_PATH)
    public ResponseEntity<Response<LoginPageConfigResponse>> getConfig() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Response.success(loginPageConfigService.getConfig()));
    }
}
