package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dto.VisitorPortfolioOpenRequest;
import com.jxc.wefolio.dto.VisitorPortfolioResponse;
import com.jxc.wefolio.dto.teamportfolio.VisitorTeamPortfolioOpenRequest;
import com.jxc.wefolio.dto.teamportfolio.VisitorTeamPortfolioResponse;
import com.jxc.wefolio.service.MobileVisitorApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 安卓访客 HTTP 适配 — 新增独立路由，不改变任何现有访客接口。 */
@RestController
@MaintainerAccess
@RequiredArgsConstructor
@RequestMapping("/api/mobile/visitor")
public class MobileVisitorController {
    /** 安卓身份与原业务编排入口。 */
    private final MobileVisitorApplicationService applicationService;

    /** 维护者身份仅来自现有认证切面，不接受客户端指定用户编号。 */
    @PostMapping("/portfolios/{shareCode}/open")
    public Response<VisitorPortfolioResponse> personal(@PathVariable String shareCode,
            @RequestBody VisitorPortfolioOpenRequest request) {
        return Response.success(applicationService.openPersonal(AuthContextHolder.requireUserId(), shareCode, request));
    }

    /** 打开标准团队作品集。 */
    @PostMapping("/team-portfolios/{shareCode}/open")
    public Response<VisitorTeamPortfolioResponse> team(@PathVariable String shareCode,
            @RequestBody VisitorTeamPortfolioOpenRequest request) {
        return Response.success(applicationService.openTeam(AuthContextHolder.requireUserId(), shareCode, request));
    }

    /** 打开高级团队作品集。 */
    @PostMapping("/advanced-team-portfolios/{shareCode}/open")
    public Response<VisitorTeamPortfolioResponse> advancedTeam(@PathVariable String shareCode,
            @RequestBody VisitorTeamPortfolioOpenRequest request) {
        return Response.success(applicationService.openAdvancedTeam(AuthContextHolder.requireUserId(), shareCode, request));
    }
}
