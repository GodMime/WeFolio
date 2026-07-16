package com.jxc.wefolio.controller;

import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioAssetUploadTicketRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioAssetUploadTicketResponse;
import com.jxc.wefolio.dto.teamportfolio.TeamContactLeadResponse;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioCreateRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioDetailResponse;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioDraftSaveRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioMaintainableTeamResponse;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioPublishRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioScheduleQueryRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioScheduleQueryResponse;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioShareRecordRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioSummaryResponse;
import com.jxc.wefolio.dto.MinePortfolioDetailResponse;
import com.jxc.wefolio.dto.PortfolioScheduleOptionsResponse;
import com.jxc.wefolio.dto.PortfolioScheduleQueryRequest;
import com.jxc.wefolio.dto.PortfolioScheduleQueryResponse;
import com.jxc.wefolio.service.teamportfolio.MineTeamPortfolioService;
import com.jxc.wefolio.service.teamportfolio.TeamMemberPortfolioPreviewService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAssetService;
import com.jxc.wefolio.service.teamportfolio.component.carousel.TeamCarouselComponentService;
import com.jxc.wefolio.service.teamportfolio.component.memberportfoliogrid.TeamMemberPortfolioGridComponentService;
import com.jxc.wefolio.service.teamportfolio.component.memberportfoliolist.TeamMemberPortfolioListComponentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 标准团队作品集维护端控制器。
 */
@MaintainerAccess
@RestController
@RequiredArgsConstructor
public class MineTeamPortfolioController {

    /** 团队作品集维护服务。 */
    private final MineTeamPortfolioService mineTeamPortfolioService;

    /** 团队作品集素材服务。 */
    private final TeamPortfolioAssetService teamPortfolioAssetService;

    /** 轮播图组件来源服务。 */
    private final TeamCarouselComponentService carouselComponentService;

    /** 双列成员作品集来源服务。 */
    private final TeamMemberPortfolioGridComponentService gridComponentService;

    /** 单列成员作品集来源服务。 */
    private final TeamMemberPortfolioListComponentService listComponentService;

    /** 团队预览下钻成员个人作品集服务。 */
    private final TeamMemberPortfolioPreviewService memberPortfolioPreviewService;

    /**
     * 查询当前用户所属团队的标准团队作品集。
     */
    @GetMapping("/api/mine/team-portfolios")
    public Response<List<TeamPortfolioSummaryResponse>> list() {
        return Response.success(mineTeamPortfolioService.listPortfolios(currentUserId()));
    }

    /**
     * 查询当前用户可维护的有效团队。
     */
    @GetMapping("/api/mine/team-portfolios/maintainable-teams")
    public Response<List<TeamPortfolioMaintainableTeamResponse>> maintainableTeams() {
        return Response.success(mineTeamPortfolioService.listMaintainableTeams(currentUserId()));
    }

    /**
     * 查询标准团队作品集组件库。
     */
    @GetMapping("/api/mine/team-portfolios/component-library")
    public Response<List<MineTeamPortfolioService.ComponentLibraryItem>> componentLibrary() {
        return Response.success(mineTeamPortfolioService.getComponentLibrary());
    }

    /**
     * 为显式指定团队创建标准团队作品集。
     */
    @PostMapping("/api/mine/teams/{teamId}/portfolios/standard")
    public Response<TeamPortfolioDetailResponse> createStandard(
            @PathVariable Long teamId,
            @RequestBody(required = false) TeamPortfolioCreateRequest request
    ) {
        return Response.success(mineTeamPortfolioService.createStandard(teamId, request, currentUserId()));
    }

    /**
     * 获取团队作品集维护详情。
     */
    @GetMapping("/api/mine/team-portfolios/{portfolioId}")
    public Response<TeamPortfolioDetailResponse> detail(@PathVariable Long portfolioId) {
        return Response.success(mineTeamPortfolioService.getDetail(portfolioId, currentUserId()));
    }

    /**
     * 保存团队作品集草稿。
     */
    @PostMapping("/api/mine/team-portfolios/{portfolioId}/draft")
    public Response<TeamPortfolioDetailResponse> saveDraft(
            @PathVariable Long portfolioId,
            @RequestBody TeamPortfolioDraftSaveRequest request
    ) {
        return Response.success(mineTeamPortfolioService.saveDraft(portfolioId, request, currentUserId()));
    }

    /**
     * 发布团队作品集。
     */
    @PostMapping("/api/mine/team-portfolios/{portfolioId}/publish")
    public Response<TeamPortfolioDetailResponse> publish(
            @PathVariable Long portfolioId,
            @RequestBody TeamPortfolioPublishRequest request
    ) {
        return Response.success(mineTeamPortfolioService.publish(portfolioId, request, currentUserId()));
    }

    /**
     * 删除团队作品集。
     */
    @PostMapping("/api/mine/team-portfolios/{portfolioId}/delete")
    public Response<Void> deletePortfolio(@PathVariable Long portfolioId) {
        mineTeamPortfolioService.deletePortfolio(portfolioId, currentUserId());
        return Response.success();
    }

    /**
     * 预览团队作品集草稿。
     */
    @GetMapping("/api/mine/team-portfolios/{portfolioId}/preview")
    public Response<TeamPortfolioDetailResponse> preview(@PathVariable Long portfolioId) {
        return Response.success(mineTeamPortfolioService.preview(portfolioId, currentUserId()));
    }

    /**
     * 预览团队作品集正式版本。
     */
    @GetMapping("/api/mine/team-portfolios/{portfolioId}/published-preview")
    public Response<TeamPortfolioDetailResponse> publishedPreview(@PathVariable Long portfolioId) {
        return Response.success(mineTeamPortfolioService.previewPublished(portfolioId, currentUserId()));
    }

    /** 预览团队配置引用的成员个人作品集发布版本。 */
    @GetMapping("/api/mine/team-portfolios/{teamPortfolioId}/member-portfolios/{memberPortfolioId}/published-preview")
    public Response<MinePortfolioDetailResponse> memberPortfolioPreview(
            @PathVariable Long teamPortfolioId,
            @PathVariable Long memberPortfolioId,
            @RequestParam(value = "scope", required = false) String scope
    ) {
        return Response.success(memberPortfolioPreviewService.preview(
                teamPortfolioId, memberPortfolioId, scope, currentUserId()));
    }

    /** 查询成员个人作品集发布版预览档期。 */
    @GetMapping("/api/mine/team-portfolios/{teamPortfolioId}/member-portfolios/{memberPortfolioId}/schedule-options")
    public Response<PortfolioScheduleOptionsResponse> memberPortfolioScheduleOptions(
            @PathVariable Long teamPortfolioId,
            @PathVariable Long memberPortfolioId,
            @RequestParam("month") String month,
            @RequestParam("componentKey") String componentKey,
            @RequestParam(value = "scope", required = false) String scope
    ) {
        return Response.success(memberPortfolioPreviewService.scheduleOptions(
                teamPortfolioId, memberPortfolioId, month, componentKey, scope, currentUserId()));
    }

    /** 执行成员个人作品集发布版预览查档。 */
    @PostMapping("/api/mine/team-portfolios/{teamPortfolioId}/member-portfolios/{memberPortfolioId}/schedule-query-preview")
    public Response<PortfolioScheduleQueryResponse> memberPortfolioScheduleQueryPreview(
            @PathVariable Long teamPortfolioId,
            @PathVariable Long memberPortfolioId,
            @RequestBody PortfolioScheduleQueryRequest request,
            @RequestParam(value = "scope", required = false) String scope
    ) {
        return Response.success(memberPortfolioPreviewService.scheduleQueryPreview(
                teamPortfolioId, memberPortfolioId, request, scope, currentUserId()));
    }

    /**
     * 创建团队作品集图片素材直传票据。
     */
    @PostMapping("/api/mine/team-portfolios/{portfolioId}/asset/upload-ticket")
    public Response<TeamPortfolioAssetUploadTicketResponse> assetUploadTicket(
            @PathVariable Long portfolioId,
            @RequestBody TeamPortfolioAssetUploadTicketRequest request
    ) {
        return Response.success(teamPortfolioAssetService.createUploadTicket(portfolioId, request, currentUserId()));
    }

    /**
     * 查询预览范围内的档期组件配置。
     */
    @GetMapping("/api/mine/team-portfolios/{portfolioId}/schedule-options")
    public Response<JSONObject> scheduleOptions(
            @PathVariable Long portfolioId,
            @RequestParam("componentKey") String componentKey,
            @RequestParam(value = "scope", required = false) String scope
    ) {
        return Response.success(mineTeamPortfolioService.scheduleOptions(
                portfolioId, componentKey, scope, currentUserId()));
    }

    /**
     * 执行团队档期查询组件维护预览。
     */
    @PostMapping("/api/mine/team-portfolios/{portfolioId}/schedule-query-preview")
    public Response<TeamPortfolioScheduleQueryResponse> scheduleQueryPreview(
            @PathVariable Long portfolioId,
            @RequestBody TeamPortfolioScheduleQueryRequest request,
            @RequestParam(value = "scope", required = false) String scope
    ) {
        return Response.success(mineTeamPortfolioService.scheduleQueryPreview(
                portfolioId, request, scope, currentUserId()));
    }

    /**
     * 记录团队作品集分享行为。
     */
    @PostMapping("/api/mine/team-portfolios/{portfolioId}/share-records")
    public Response<Void> shareRecord(
            @PathVariable Long portfolioId,
            @RequestBody TeamPortfolioShareRecordRequest request
    ) {
        mineTeamPortfolioService.createShareRecord(portfolioId, request, currentUserId());
        return Response.success();
    }

    /** 查询团队作品集访问汇总。 */
    @GetMapping("/api/mine/teams/{teamId}/visits")
    public Response<MineTeamPortfolioService.TeamVisitRecordsResponse> visitRecords(
            @PathVariable Long teamId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize
    ) {
        return Response.success(mineTeamPortfolioService.getVisitRecords(
                teamId, page, pageSize, currentUserId()));
    }

    /** 分页查询团队作品集查档历史。 */
    @GetMapping("/api/mine/teams/{teamId}/schedule-queries")
    public Response<MineTeamPortfolioService.TeamScheduleQueryRecordsResponse> scheduleQueries(
            @PathVariable Long teamId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize
    ) {
        return Response.success(mineTeamPortfolioService.getScheduleQueryRecords(
                teamId, page, pageSize, currentUserId()));
    }

    /** 分页查询团队完整未脱敏预留联系信息。 */
    @GetMapping("/api/mine/teams/{teamId}/contact-leads")
    public Response<TeamContactLeadResponse> contactLeads(
            @PathVariable Long teamId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize
    ) {
        return Response.success(mineTeamPortfolioService.getContactLeads(
                teamId, page, pageSize, currentUserId()));
    }

    /** 更新团队预留联系信息跟进状态。 */
    @PostMapping("/api/mine/teams/{teamId}/contact-leads/{leadId}/follow-status")
    public Response<TeamContactLeadResponse.Item> followStatus(
            @PathVariable Long teamId,
            @PathVariable Long leadId,
            @RequestParam("followStatus") String followStatus,
            @RequestParam(value = "followNote", defaultValue = "") String followNote
    ) {
        return Response.success(mineTeamPortfolioService.updateContactLeadFollowStatus(
                teamId, leadId, followStatus, followNote, currentUserId()));
    }

    /**
     * 查询轮播图可选成员。
     */
    @GetMapping("/api/mine/team-portfolios/{portfolioId}/components/carousel/members")
    public Response<List<TeamCarouselComponentService.MemberOption>> carouselMembers(
            @PathVariable Long portfolioId
    ) {
        return Response.success(carouselComponentService.listMembers(portfolioId, currentUserId()));
    }

    /**
     * 查询轮播图指定成员的可选作品。
     */
    @GetMapping("/api/mine/team-portfolios/{portfolioId}/components/carousel/members/{memberUserId}/works")
    public Response<List<TeamCarouselComponentService.WorkOption>> carouselWorks(
            @PathVariable Long portfolioId,
            @PathVariable Long memberUserId
    ) {
        return Response.success(carouselComponentService.listWorks(portfolioId, memberUserId, currentUserId()));
    }

    /**
     * 查询双列成员作品集可选成员。
     */
    @GetMapping("/api/mine/team-portfolios/{portfolioId}/components/member-portfolio-grid/members")
    public Response<List<TeamMemberPortfolioGridComponentService.MemberOption>> gridMembers(
            @PathVariable Long portfolioId
    ) {
        return Response.success(gridComponentService.listMembers(portfolioId, currentUserId()));
    }

    /**
     * 查询双列组件指定成员的可选作品集。
     */
    @GetMapping("/api/mine/team-portfolios/{portfolioId}/components/member-portfolio-grid/members/{memberUserId}/portfolios")
    public Response<List<TeamMemberPortfolioGridComponentService.PortfolioOption>> gridPortfolios(
            @PathVariable Long portfolioId,
            @PathVariable Long memberUserId
    ) {
        return Response.success(gridComponentService.listPortfolios(portfolioId, memberUserId, currentUserId()));
    }

    /**
     * 查询单列成员作品集可选成员。
     */
    @GetMapping("/api/mine/team-portfolios/{portfolioId}/components/member-portfolio-list/members")
    public Response<List<TeamMemberPortfolioListComponentService.MemberOption>> listMembers(
            @PathVariable Long portfolioId
    ) {
        return Response.success(listComponentService.listMembers(portfolioId, currentUserId()));
    }

    /**
     * 查询单列组件指定成员的可选作品集。
     */
    @GetMapping("/api/mine/team-portfolios/{portfolioId}/components/member-portfolio-list/members/{memberUserId}/portfolios")
    public Response<List<TeamMemberPortfolioListComponentService.PortfolioOption>> listPortfolios(
            @PathVariable Long portfolioId,
            @PathVariable Long memberUserId
    ) {
        return Response.success(listComponentService.listPortfolios(portfolioId, memberUserId, currentUserId()));
    }

    /**
     * 读取当前维护者用户 ID。
     */
    private long currentUserId() {
        return AuthContextHolder.requireUserId();
    }

}
