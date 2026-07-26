package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dto.MinePointOverviewResponse;
import com.jxc.wefolio.dto.MinePointTransactionsResponse;
import com.jxc.wefolio.dto.PointCalculationRequest;
import com.jxc.wefolio.dto.PointCalculationResponse;
import com.jxc.wefolio.service.PointService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的积分控制器 — 提供积分概览、流水明细和积分试算接口。
 */
@MaintainerAccess
@RestController
@RequiredArgsConstructor
@Slf4j
public class MinePointController {

    /** 积分服务 */
    private final PointService pointService;

    /**
     * 获取我的积分概览。
     *
     * @return 积分概览
     */
    @GetMapping("/api/mine/points")
    public Response<MinePointOverviewResponse> overview() {
        return Response.success(pointService.getOverview(AuthContextHolder.requireUserId()));
    }

    /**
     * 分页查询我的积分流水。
     *
     * @param transactionType 流水类型
     * @param sceneCode 场景编码
     * @param page 页码
     * @param pageSize 每页条数
     * @return 积分流水
     */
    @GetMapping("/api/mine/points/transactions")
    public Response<MinePointTransactionsResponse> transactions(
            @RequestParam(value = "transactionType", required = false) String transactionType,
            @RequestParam(value = "sceneCode", required = false) String sceneCode,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize
    ) {
        return Response.success(pointService.listTransactions(
                AuthContextHolder.requireUserId(), transactionType, sceneCode, page, pageSize));
    }

    /**
     * 试算积分变动。
     *
     * @param request 试算请求
     * @return 试算结果
     * @deprecated 当前小程序未接入，保留接口以兼容潜在旧客户端
     */
    @Deprecated(since = "2026-07", forRemoval = false)
    @PostMapping("/api/mine/points/calculate")
    public Response<PointCalculationResponse> calculate(@RequestBody PointCalculationRequest request) {
        Long userId = AuthContextHolder.requireUserId();
        log.warn("调用已弃用积分试算接口: userId={}", userId);
        return Response.success(pointService.calculate(userId, request));
    }
}
