package com.jxc.wefolio.controller;

import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.annotation.LoginAccess;
import com.jxc.wefolio.annotation.TimelineAnonymousAccess;
import com.jxc.wefolio.annotation.VisitorAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.VisitorAvatarUploadTicketRequest;
import com.jxc.wefolio.dto.VisitorAvatarUploadTicketResponse;
import com.jxc.wefolio.dto.VisitorProfileUpdateRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamContactLeadSubmitRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioScheduleQueryRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioScheduleQueryResponse;
import com.jxc.wefolio.dto.teamportfolio.VisitorTeamPortfolioEventRequest;
import com.jxc.wefolio.dto.teamportfolio.VisitorTeamPortfolioOpenRequest;
import com.jxc.wefolio.dto.teamportfolio.VisitorTeamPortfolioResponse;
import com.jxc.wefolio.service.teamportfolio.VisitorTeamPortfolioService;
import com.jxc.wefolio.service.teamportfolio.component.contactform.TeamContactFormComponentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 标准团队作品集访客端控制器。
 */
@VisitorAccess
@RestController
@RequiredArgsConstructor
public class VisitorTeamPortfolioController {

    /** 团队作品集访客服务。 */
    private final VisitorTeamPortfolioService visitorTeamPortfolioService;

    /** 打开已发布标准团队作品集。 */
    @LoginAccess
    @PostMapping("/api/visitor/team-portfolios/{shareCode}/open")
    public Response<VisitorTeamPortfolioResponse> open(
            @PathVariable String shareCode,
            @RequestBody VisitorTeamPortfolioOpenRequest request
    ) {
        return Response.success(visitorTeamPortfolioService.openPortfolio(shareCode, request));
    }

    /** 创建访客头像上传票据。 */
    @PostMapping("/api/visitor/team-portfolios/{shareCode}/visitor-avatar/upload-ticket")
    public Response<VisitorAvatarUploadTicketResponse> createVisitorAvatarUploadTicket(
            @PathVariable String shareCode,
            @RequestBody VisitorAvatarUploadTicketRequest request
    ) {
        return Response.success(
                visitorTeamPortfolioService.createVisitorAvatarUploadTicket(shareCode, request));
    }

    /** 更新访客头像昵称。 */
    @PutMapping("/api/visitor/team-portfolios/{shareCode}/visitor-profile")
    public Response<Void> updateVisitorProfile(
            @PathVariable String shareCode,
            @RequestBody VisitorProfileUpdateRequest request
    ) {
        visitorTeamPortfolioService.updateVisitorProfile(shareCode, request);
        return Response.success();
    }

    /** 上报团队作品集访客事件。 */
    @PostMapping("/api/visitor/team-portfolios/{shareCode}/events")
    @TimelineAnonymousAccess
    public Response<Void> event(
            @PathVariable String shareCode,
            @RequestBody VisitorTeamPortfolioEventRequest request
    ) {
        visitorTeamPortfolioService.recordEvent(shareCode, request);
        return Response.success();
    }

    /** 查询已发布团队档期组件选项。 */
    @GetMapping("/api/visitor/team-portfolios/{shareCode}/schedule-options")
    @TimelineAnonymousAccess
    public Response<JSONObject> scheduleOptions(
            @PathVariable String shareCode,
            @RequestParam("componentKey") String componentKey
    ) {
        return Response.success(visitorTeamPortfolioService.queryScheduleOptions(shareCode, componentKey));
    }

    /** 提交团队档期查询。 */
    @PostMapping("/api/visitor/team-portfolios/{shareCode}/schedule-query")
    @TimelineAnonymousAccess
    public Response<TeamPortfolioScheduleQueryResponse> scheduleQuery(
            @PathVariable String shareCode,
            @RequestBody TeamPortfolioScheduleQueryRequest request
    ) {
        return Response.success(visitorTeamPortfolioService.submitScheduleQuery(shareCode, request));
    }

    /** 提交团队预留联系信息。 */
    @PostMapping("/api/visitor/team-portfolios/{shareCode}/contact-leads")
    @TimelineAnonymousAccess
    public Response<TeamContactFormComponentService.SubmitResult> contactLead(
            @PathVariable String shareCode,
            @RequestBody TeamContactLeadSubmitRequest request
    ) {
        return Response.success(visitorTeamPortfolioService.submitContactLead(shareCode, request));
    }
}
