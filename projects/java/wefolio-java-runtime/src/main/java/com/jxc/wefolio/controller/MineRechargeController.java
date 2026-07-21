package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dto.CreateRechargeOrderRequest;
import com.jxc.wefolio.dto.CreateRechargeOrderResponse;
import com.jxc.wefolio.dto.RechargeOrderSyncResponse;
import com.jxc.wefolio.dto.RechargeOrdersResponse;
import com.jxc.wefolio.dto.RechargePageResponse;
import com.jxc.wefolio.service.payment.RechargeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的充值控制器 — 提供套餐、建单、记录和主动查单接口。
 */
@MaintainerAccess
@RestController
@RequiredArgsConstructor
public class MineRechargeController {

    /** 充值应用服务。 */
    private final RechargeService rechargeService;

    /**
     * 获取充值页数据。
     *
     * @return 充值页数据
     */
    @GetMapping("/api/mine/recharges")
    public Response<RechargePageResponse> page() {
        return Response.success(rechargeService.getPage(AuthContextHolder.requireUserId()));
    }

    /**
     * 创建充值订单。
     *
     * @param request 仅包含套餐 ID 的请求
     * @return 小程序调起支付参数
     */
    @PostMapping("/api/mine/recharges/orders")
    public Response<CreateRechargeOrderResponse> createOrder(
            @RequestBody CreateRechargeOrderRequest request
    ) {
        return Response.success(rechargeService.createOrder(AuthContextHolder.requireUserId(), request));
    }

    /**
     * 分页查询当前用户充值记录。
     *
     * @param page 页码
     * @param pageSize 每页条数
     * @return 充值记录
     */
    @GetMapping("/api/mine/recharges/orders")
    public Response<RechargeOrdersResponse> orders(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        return Response.success(rechargeService.listOrders(
                AuthContextHolder.requireUserId(), page, pageSize));
    }

    /**
     * 主动同步当前用户的一笔充值订单。
     *
     * @param merchantOrderNo 商户订单号
     * @return 同步后的订单状态
     */
    @PostMapping("/api/mine/recharges/orders/{merchantOrderNo}/sync")
    public Response<RechargeOrderSyncResponse> syncOrder(
            @PathVariable String merchantOrderNo
    ) {
        return Response.success(rechargeService.syncOrder(
                AuthContextHolder.requireUserId(), merchantOrderNo));
    }
}
