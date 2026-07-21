package com.jxc.wefolio.controller;

import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.annotation.LoginAccess;
import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.common.upload.AvatarUploadResult;
import com.jxc.wefolio.dto.AuthSessionResponse;
import com.jxc.wefolio.dto.FileUploadResponse;
import com.jxc.wefolio.dto.MaintainerWechatLoginRequest;
import com.jxc.wefolio.dto.MaintainerWechatLoginResponse;
import com.jxc.wefolio.dto.MaintainerWechatSessionRefreshRequest;
import com.jxc.wefolio.service.AccountCancellationService;
import com.jxc.wefolio.service.AuthTokenService;
import com.jxc.wefolio.service.MaintainerAvatarService;
import com.jxc.wefolio.service.MiniappAuthService;
import com.jxc.wefolio.service.TrustedClientIpResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 小程序认证控制器 — 提供登录、登录态校验和维护者信息管理接口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class MiniappAuthController {

    /** 小程序登录服务 */
    private final MiniappAuthService miniappAuthService;

    /** 登录令牌认证服务 */
    private final AuthTokenService authTokenService;

    /** 账号注销服务 */
    private final AccountCancellationService accountCancellationService;

    /** 维护者头像上传服务 */
    private final MaintainerAvatarService maintainerAvatarService;

    /** 可信客户端 IP 解析器。 */
    private final TrustedClientIpResolver trustedClientIpResolver;

    /**
     * 维护者微信授权登录。
     *
     * @param request 维护者微信登录请求
     * @return 维护者登录响应
    */
    @LoginAccess
    @PostMapping("/maintainer/wechat-login")
    public Response<MaintainerWechatLoginResponse> maintainerWechatLogin(
            @RequestBody MaintainerWechatLoginRequest request
    ) {
        return Response.success(miniappAuthService.loginMaintainerByWechat(
                request, trustedClientIpResolver.resolveCurrentRequest()));
    }

    /**
     * 使用维护者当前身份刷新微信 session_key，不返回会话密钥。
     *
     * @param request 微信登录 code
     * @return 空成功响应
     */
    @MaintainerAccess
    @PostMapping("/maintainer/wechat-session/refresh")
    public Response<Void> refreshMaintainerWechatSession(
            @RequestBody MaintainerWechatSessionRefreshRequest request
    ) {
        miniappAuthService.refreshMaintainerWechatSession(
                AuthContextHolder.requireUserId(),
                request,
                trustedClientIpResolver.resolveCurrentRequest()
        );
        return Response.success();
    }

    /**
     * 校验登录态。
     *
     * @param authorization Authorization 请求头
     * @return 登录态响应
     */
    @LoginAccess
    @GetMapping("/session")
    public ResponseEntity<Response<AuthSessionResponse>> session(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return authTokenService.resolveAuthenticatedUserId(authorization)
                .map(userId -> ResponseEntity.ok(Response.success(miniappAuthService.buildSession(userId))))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Response.fail("未登录", miniappAuthService.buildSession(null))));
    }

    /**
     * 上传微信头像昵称填写能力返回的头像临时文件，
     * 存入当前用户的 others 目录（{@code {uniqueCode}/others/}）。
     *
     * @param file 头像文件
     * @return 上传后的公开地址
     */
    @MaintainerAccess
    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Response<FileUploadResponse> uploadAvatar(@RequestParam("file") MultipartFile file) {
        AvatarUploadResult result = maintainerAvatarService.uploadAvatar(
                AuthContextHolder.requireUserId(), file);
        return result.success() ? Response.success(result.data()) : Response.fail(result.message());
    }

    /**
     * 注销当前账号。
     *
     * @return 注销结果
     */
    @MaintainerAccess
    @PostMapping("/account/cancel")
    public Response<Void> cancelAccount() {
        accountCancellationService.cancelCurrentUser();
        return Response.success();
    }
}
