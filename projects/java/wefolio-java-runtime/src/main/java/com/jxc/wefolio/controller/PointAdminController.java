package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.SystemAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.AdminPointGrantRequest;
import com.jxc.wefolio.dto.PointMutationResponse;
import com.jxc.wefolio.service.PointAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台积分控制器 — 一期未接入微信充值时用于内部人工加分。
 */
@SystemAccess
@RestController
@RequiredArgsConstructor
public class PointAdminController {

    /** 后台积分服务 */
    private final PointAdminService pointAdminService;

    /**
     * 后台人工加分。
     *
     * @param secret 请求头中的后台积分密钥
     * @param request 人工加分请求
     * @return 积分变动结果
     */
    @PostMapping("/api/admin/points/grants")
    public Response<PointMutationResponse> grantPoints(
            @RequestHeader(value = "X-Admin-Point-Secret", required = false) String secret,
            @RequestBody AdminPointGrantRequest request
    ) {
        return Response.success(pointAdminService.grantPoints(secret, request));
    }
}
