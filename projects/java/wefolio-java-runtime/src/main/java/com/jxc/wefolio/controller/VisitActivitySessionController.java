package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.VisitorAccess;
import com.jxc.wefolio.annotation.TimelineAnonymousAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.VisitActivityUpdateRequest;
import com.jxc.wefolio.service.VisitorPortfolioService;
import com.jxc.wefolio.service.teamportfolio.VisitorTeamPortfolioService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 个人和团队前台累计接口，仅负责 HTTP 参数委派及响应映射。 */
@RestController
@RequiredArgsConstructor
public class VisitActivitySessionController {
    /** 个人作品集访客用例入口。 */
    private final VisitorPortfolioService personalService;
    /** 团队作品集访客用例入口。 */
    private final VisitorTeamPortfolioService teamService;

    /** 上报当前个人作品集活动会话累计。 */
    @VisitorAccess
    @TimelineAnonymousAccess
    @PutMapping("/api/visitor/portfolios/{shareCode}/visit-sessions/{sessionId}/activity")
    public Response<Long> personal(@PathVariable String shareCode, @PathVariable Long sessionId,
            @RequestBody VisitActivityUpdateRequest request) {
        return Response.success(personalService.recordActivity(shareCode, sessionId, request));
    }

    /** 上报当前团队作品集活动会话累计。 */
    @VisitorAccess
    @TimelineAnonymousAccess
    @PutMapping("/api/visitor/team-portfolios/{shareCode}/visit-sessions/{sessionId}/activity")
    public Response<Long> team(@PathVariable String shareCode, @PathVariable Long sessionId,
            @RequestBody VisitActivityUpdateRequest request) {
        return Response.success(teamService.recordActivity(shareCode, sessionId, request));
    }
}
