package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.LoginAccess;
import com.jxc.wefolio.annotation.TimelineAnonymousAccess;
import com.jxc.wefolio.annotation.VisitorAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.ContactLeadSubmitRequest;
import com.jxc.wefolio.dto.ContactLeadSubmitResponse;
import com.jxc.wefolio.dto.PortfolioScheduleOptionsResponse;
import com.jxc.wefolio.dto.PortfolioScheduleQueryRequest;
import com.jxc.wefolio.dto.PortfolioScheduleQueryResponse;
import com.jxc.wefolio.dto.VisitorAvatarUploadTicketRequest;
import com.jxc.wefolio.dto.VisitorAvatarUploadTicketResponse;
import com.jxc.wefolio.dto.VisitorPortfolioEventRequest;
import com.jxc.wefolio.dto.VisitorPortfolioOpenRequest;
import com.jxc.wefolio.dto.VisitorPortfolioResponse;
import com.jxc.wefolio.dto.VisitorPortfolioScheduleResponse;
import com.jxc.wefolio.dto.VisitorProfileUpdateRequest;
import com.jxc.wefolio.service.ContactLeadService;
import com.jxc.wefolio.service.VisitorPortfolioService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
     * 打开访客作品集。
     *
     * @param shareCode 分享编码
     * @param request 打开请求
     * @return 作品集响应
     */
    @LoginAccess
    @PostMapping("/api/visitor/portfolios/{shareCode}/open")
    public Response<VisitorPortfolioResponse> open(
            @PathVariable String shareCode,
            @RequestBody VisitorPortfolioOpenRequest request
    ) {
        return Response.success(visitorPortfolioService.openPortfolio(shareCode, request));
    }

    /**
     * 创建访客头像直传 COS 票据。
     *
     * @param shareCode 分享编码
     * @param request 票据请求
     * @return 票据响应
     */
    @PostMapping("/api/visitor/portfolios/{shareCode}/visitor-avatar/upload-ticket")
    public Response<VisitorAvatarUploadTicketResponse> createVisitorAvatarUploadTicket(
            @PathVariable String shareCode,
            @RequestBody VisitorAvatarUploadTicketRequest request
    ) {
        return Response.success(visitorPortfolioService.createVisitorAvatarUploadTicket(shareCode, request));
    }

    /**
     * 保存访客头像昵称。
     *
     * @param shareCode 分享编码
     * @param request 保存请求
     * @return 空响应
     */
    @PutMapping("/api/visitor/portfolios/{shareCode}/visitor-profile")
    public Response<Void> updateVisitorProfile(
            @PathVariable String shareCode,
            @RequestBody VisitorProfileUpdateRequest request
    ) {
        visitorPortfolioService.updateVisitorProfile(shareCode, request);
        return Response.success();
    }

    /**
     * 查询访客档期。
     *
     * @param shareCode 分享编码
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @param scope 范围
     * @param visitorKey 旧版客户端兼容参数，服务端已改用访客认证上下文并忽略该值
     * @param idempotencyKey 幂等键
     * @return 档期响应
     */
    @GetMapping("/api/visitor/portfolios/{shareCode}/schedule")
    @TimelineAnonymousAccess
    public Response<VisitorPortfolioScheduleResponse> schedule(
            @PathVariable String shareCode,
            @RequestParam("startDate") String startDate,
            @RequestParam("endDate") String endDate,
            @RequestParam(value = "scope", required = false) String scope,
            @Deprecated
            @RequestParam(value = "visitorKey", required = false) String visitorKey,
            @RequestParam("idempotencyKey") String idempotencyKey
    ) {
        return Response.success(visitorPortfolioService.querySchedule(
                shareCode,
                startDate,
                endDate,
                scope,
                visitorKey,
                idempotencyKey
        ));
    }

    /**
     * 查询档期组件月历选项。
     *
     * @param shareCode 分享编码
     * @param month 月份，格式 yyyy-MM
     * @param componentKey 组件实例键
     * @return 月历选项响应
     */
    @GetMapping("/api/visitor/portfolios/{shareCode}/schedule-options")
    @TimelineAnonymousAccess
    public Response<PortfolioScheduleOptionsResponse> scheduleOptions(
            @PathVariable String shareCode,
            @RequestParam("month") String month,
            @RequestParam("componentKey") String componentKey
    ) {
        return Response.success(visitorPortfolioService.queryScheduleOptions(shareCode, month, componentKey));
    }

    /**
     * 提交档期查询。
     *
     * @param shareCode 分享编码
     * @param request 查询请求
     * @return 查询结果
     */
    @PostMapping("/api/visitor/portfolios/{shareCode}/schedule-query")
    @TimelineAnonymousAccess
    public Response<PortfolioScheduleQueryResponse> scheduleQuery(
            @PathVariable String shareCode,
            @RequestBody PortfolioScheduleQueryRequest request
    ) {
        return Response.success(visitorPortfolioService.submitScheduleQuery(shareCode, request));
    }

    /**
     * 上报访客事件。
     *
     * @param shareCode 分享编码
     * @param request 事件请求
     * @return 空响应
     */
    @PostMapping("/api/visitor/portfolios/{shareCode}/events")
    @TimelineAnonymousAccess
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
    @TimelineAnonymousAccess
    @Deprecated(forRemoval = true)
    public Response<ContactLeadSubmitResponse> contactLead(
            @PathVariable String shareCode,
            @RequestBody ContactLeadSubmitRequest request
    ) {
        return Response.success(contactLeadService.submit(shareCode, request));
    }

    /**
     * 严格校验并提交联系线索。
     *
     * @param shareCode 分享编码
     * @param request 提交请求
     * @return 提交响应
     */
    @PostMapping("/api/visitor/portfolios/{shareCode}/contact-leads/v2")
    @TimelineAnonymousAccess
    public Response<ContactLeadSubmitResponse> contactLeadV2(
            @PathVariable String shareCode,
            @RequestBody ContactLeadSubmitRequest request
    ) {
        return Response.success(contactLeadService.submitV2(shareCode, request));
    }
}
