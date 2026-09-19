package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dto.PortfolioMiniappCodeResponse;
import com.jxc.wefolio.service.miniappcode.PortfolioMiniappCodeApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 作品集小程序码 HTTP 适配层，业务权限与快照编排由应用服务完成。 */
@MaintainerAccess
@RestController
@RequiredArgsConstructor
public class PortfolioMiniappCodeController {

    /** 作品集小程序码应用服务。 */
    private final PortfolioMiniappCodeApplicationService applicationService;

    /** 为当前维护者的已发布个人作品集获取手机端名片绘制资源。 */
    @PostMapping("/api/mine/portfolios/{portfolioId}/miniapp-code")
    public Response<PortfolioMiniappCodeResponse> personal(@PathVariable Long portfolioId) {
        return Response.success(applicationService.generatePersonal(portfolioId, AuthContextHolder.requireUserId()));
    }

    /** 为当前成员可分享的已发布团队作品集获取手机端名片绘制资源。 */
    @PostMapping("/api/mine/team-portfolios/{portfolioId}/miniapp-code")
    public Response<PortfolioMiniappCodeResponse> team(@PathVariable Long portfolioId) {
        return Response.success(applicationService.generateTeam(portfolioId, AuthContextHolder.requireUserId()));
    }
}
