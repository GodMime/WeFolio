package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.SystemAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.AdminPointGrantRequest;
import com.jxc.wefolio.service.point.GiftOrderResult;
import org.springframework.web.bind.annotation.PathVariable;
import com.jxc.wefolio.service.PointAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import static com.jxc.wefolio.constant.PointConstants.ADMIN_POINT_SECRET_HEADER;

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
    public Response<GiftOrderResult> grantPoints(
            @RequestHeader(value = ADMIN_POINT_SECRET_HEADER, required = false) String secret,
            @RequestBody AdminPointGrantRequest request
    ) {
        return Response.success(pointAdminService.grantPoints(secret, request));
    }

    /** 人工重试原赠送订单。 */
    @PostMapping("/api/admin/points/gift-orders/{orderNo}/retry")
    public Response<Boolean> retryGiftOrder(
            @RequestHeader(value = ADMIN_POINT_SECRET_HEADER, required = false) String secret,
            @PathVariable String orderNo
    ) {
        return Response.success(pointAdminService.retryGiftOrder(secret, orderNo));
    }

    /** 人工重试原扣币任务。 */
    @PostMapping("/api/admin/points/debit-tasks/{taskId}/retry")
    public Response<Boolean> retryDebitTask(
            @RequestHeader(value = ADMIN_POINT_SECRET_HEADER, required = false) String secret,
            @PathVariable Long taskId
    ) {
        return Response.success(pointAdminService.retryDebitTask(secret, taskId));
    }
}
