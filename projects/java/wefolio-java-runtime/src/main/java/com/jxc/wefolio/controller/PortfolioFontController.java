package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dto.MinePortfolioDraftSaveRequest;
import com.jxc.wefolio.dto.PortfolioFontManifestDto;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioDraftSaveRequest;
import com.jxc.wefolio.service.MinePortfolioService;
import com.jxc.wefolio.service.teamportfolio.MineTeamPortfolioService;
import com.jxc.wefolio.service.portfoliofont.PortfolioFontSources;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

/** 维护者字体目录与同步候选字体准备 HTTP 适配。 */
@MaintainerAccess
@RestController
@RequiredArgsConstructor
public class PortfolioFontController {
    /** 字体目录应用能力。 */
    private final PortfolioFontSources sources;
    /** 个人作品集维护入口。 */
    private final MinePortfolioService personal;
    /** 团队作品集维护入口。 */
    private final MineTeamPortfolioService team;

    /** 查询六款固定字体及可选择能力。 */
    @GetMapping("/api/mine/portfolio-fonts")
    public Response<Map<String, Object>> catalog() { return Response.success(sources.catalog()); }

    /** 返回候选个人文字字体，不保存草稿。 */
    @PostMapping("/api/mine/portfolios/{id}/fonts/prepare")
    public Response<PreparedResponse> personal(@PathVariable Long id, @RequestBody MinePortfolioDraftSaveRequest request) {
        return Response.success(new PreparedResponse(personal.prepareFonts(id, request)));
    }

    /** 返回候选团队文字字体，不保存草稿。 */
    @PostMapping("/api/mine/team-portfolios/{id}/fonts/prepare")
    public Response<PreparedResponse> team(@PathVariable long id, @RequestBody TeamPortfolioDraftSaveRequest request) {
        return Response.success(new PreparedResponse(team.prepareFonts(id, request, AuthContextHolder.requireUserId())));
    }

    /** 字体准备响应包装，保持与维护详情字段同名。 */
    public record PreparedResponse(PortfolioFontManifestDto fontAssets) { }
}
