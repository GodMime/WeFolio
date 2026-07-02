package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.VisitorAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.ContactLeadSubmitRequest;
import com.jxc.wefolio.dto.ContactLeadSubmitResponse;
import com.jxc.wefolio.dto.VisitorPortfolioEventRequest;
import com.jxc.wefolio.dto.VisitorPortfolioResponse;
import com.jxc.wefolio.dto.VisitorPortfolioScheduleResponse;
import com.jxc.wefolio.service.ContactLeadService;
import com.jxc.wefolio.service.VisitorPortfolioService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 访客作品集控制器 — 提供公开作品集、档期、事件和联系线索接口。
 */
@VisitorAccess
@RestController
@RequiredArgsConstructor
public class VisitorPortfolioController {

    /** 访客作品集服务 */
    private final VisitorPortfolioService visitorPortfolioService;

    /** 联系线索服务 */
    private final ContactLeadService contactLeadService;

    /**
     * 获取访客作品集。
     *
     * @param shareCode 分享编码
     * @param visitorKey 访客摘要
     * @param sourceType 来源类型
     * @param idempotencyKey 幂等键
     * @return 作品集响应
     */
    @GetMapping("/api/visitor/portfolios/{shareCode}")
    public Response<VisitorPortfolioResponse> portfolio(
            @PathVariable String shareCode,
            @RequestParam("visitorKey") String visitorKey,
            @RequestParam(value = "sourceType", required = false) String sourceType,
            @RequestParam("idempotencyKey") String idempotencyKey
    ) {
        return Response.success(visitorPortfolioService.getPortfolio(shareCode, visitorKey, sourceType, idempotencyKey));
    }

    /**
     * 查询访客档期。
     *
     * @param shareCode 分享编码
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @param scope 范围
     * @param visitorKey 访客摘要
     * @param idempotencyKey 幂等键
     * @return 档期响应
     */
    @GetMapping("/api/visitor/portfolios/{shareCode}/schedule")
    public Response<VisitorPortfolioScheduleResponse> schedule(
            @PathVariable String shareCode,
            @RequestParam("startDate") String startDate,
            @RequestParam("endDate") String endDate,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam("visitorKey") String visitorKey,
            @RequestParam("idempotencyKey") String idempotencyKey
    ) {
        return Response.success(visitorPortfolioService.querySchedule(
                shareCode,
                LocalDate.parse(startDate),
                LocalDate.parse(endDate),
                scope,
                visitorKey,
                idempotencyKey
        ));
    }

    /**
     * 上报访客事件。
     *
     * @param shareCode 分享编码
     * @param request 事件请求
     * @return 空响应
     */
    @PostMapping("/api/visitor/portfolios/{shareCode}/events")
    public Response<Void> event(
            @PathVariable String shareCode,
            @RequestBody VisitorPortfolioEventRequest request
    ) {
        visitorPortfolioService.recordEvent(shareCode, request);
        return Response.success();
    }

    /**
     * 提交联系线索。
     *
     * @param shareCode 分享编码
     * @param request 提交请求
     * @return 提交响应
     */
    @PostMapping("/api/visitor/portfolios/{shareCode}/contact-leads")
    public Response<ContactLeadSubmitResponse> contactLead(
            @PathVariable String shareCode,
            @RequestBody ContactLeadSubmitRequest request
    ) {
        return Response.success(contactLeadService.submit(shareCode, request));
    }
}
