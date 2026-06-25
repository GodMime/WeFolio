package com.jxc.wefolio.controller;

import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.AuthSessionResponse;
import com.jxc.wefolio.dto.FileUploadResponse;
import com.jxc.wefolio.dto.WechatLoginRequest;
import com.jxc.wefolio.dto.WechatLoginResponse;
import com.jxc.wefolio.service.CosService;
import com.jxc.wefolio.service.MiniappAuthService;
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
 * 小程序登录控制器 — 提供登录和登录态校验接口
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class MiniappAuthController {

    /** 头像文件最大大小：5MB */
    private static final long MAX_AVATAR_SIZE_BYTES = 5L * 1024L * 1024L;

    /** 小程序登录服务 */
    private final MiniappAuthService miniappAuthService;

    /** COS 文件服务 */
    private final CosService cosService;

    /**
     * 微信授权登录
     *
     * @param request 微信登录请求
     * @return 登录响应
     */
    @PostMapping("/wechat-login")
    public Response<WechatLoginResponse> wechatLogin(@RequestBody WechatLoginRequest request) {
        return Response.success(miniappAuthService.loginByWechat(request));
    }

    /**
     * 上传微信头像昵称填写能力返回的头像临时文件，
     * 存入当前用户的 others 目录（{@code {uniqueCode}/others/}）。
     *
     * @param file          头像文件
     * @param authorization Authorization 请求头
     * @return 上传后的公开地址
     */
    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Response<FileUploadResponse> uploadAvatar(
            @RequestParam("file") MultipartFile file,
            @RequestHeader("Authorization") String authorization
    ) {
        Long userId = miniappAuthService.resolveAuthenticatedUserId(authorization);
        if (userId == null) {
            return Response.fail("请先登录");
        }
        if (file == null || file.isEmpty()) {
            return Response.fail("头像文件不能为空");
        }
        if (file.getSize() > MAX_AVATAR_SIZE_BYTES) {
            return Response.fail("头像文件不能超过 5MB");
        }
        String uniqueCode = miniappAuthService.getUniqueCodeByUserId(userId);
        String key = cosService.upload(file, uniqueCode + "/others");
        FileUploadResponse response = new FileUploadResponse();
        response.setKey(key);
        response.setUrl(cosService.publicUrl(key));
        return Response.success(response);
    }

    /**
     * 校验登录态
     *
     * @param authorization Authorization 请求头
     * @return 登录态响应
     */
    @GetMapping("/session")
    public ResponseEntity<Response<AuthSessionResponse>> session(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        Long userId = miniappAuthService.resolveAuthenticatedUserId(authorization);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Response.fail("未登录", miniappAuthService.buildSession(null)));
        }
        return ResponseEntity.ok(Response.success(miniappAuthService.buildSession(userId)));
    }
}
