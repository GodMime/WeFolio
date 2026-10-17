package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.service.advancedportfolio.AdvancedPortfolioApplicationService;
import com.jxc.wefolio.service.advancedportfolio.AdvancedTeamMemberPortfolioPreviewService;
import com.jxc.wefolio.dto.PortfolioScheduleQueryRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 高级作品集独立维护路由，控制器只做 HTTP 适配。 */
@RestController
@MaintainerAccess
@RequestMapping("/api/mine/advanced-portfolios")
@RequiredArgsConstructor
public class MineAdvancedPortfolioController {
    /** 高级维护用例编排。 */
    private final AdvancedPortfolioApplicationService application;

    /** 高级团队成员只读预览。 */
    private final AdvancedTeamMemberPortfolioPreviewService members;

    /** 首次显式保存创建基础草稿。 */
    @PostMapping
    public Response<?> create(@RequestBody String raw) { return Response.success(application.create(raw)); }
    /** 读取维护详情。 */
    @GetMapping("/{id}")
    public Response<?> detail(@PathVariable Long id) { return Response.success(application.getDetail(id)); }
    /** 保存最终完整编辑。 */
    @PutMapping("/{id}/draft")
    public Response<?> saveDraft(@PathVariable Long id, @RequestBody String raw) { return Response.success(application.saveDraft(id, raw)); }
    /** 发布已保存草稿。 */
    @PostMapping("/{id}/publish")
    public Response<?> publish(@PathVariable Long id, @RequestBody String raw) { return Response.success(application.publish(id, raw)); }
    /** 删除高级作品集。 */
    @PostMapping("/{id}/delete")
    public Response<Void> delete(@PathVariable Long id) { application.delete(id); return Response.success(); }
    /** 只读草稿预览。 */
    @GetMapping("/{id}/preview")
    public Response<?> preview(@PathVariable Long id) { return Response.success(application.preview(id, false)); }
    /** 只读发布版预览。 */
    @GetMapping("/{id}/published-preview")
    public Response<?> publishedPreview(@PathVariable Long id) { return Response.success(application.preview(id, true)); }
    /** 生成作品集素材上传票据。 */
    @PostMapping("/{id}/asset/upload-ticket")
    public Response<?> uploadTicket(@PathVariable Long id, @RequestBody String raw) { return Response.success(application.uploadTicket(id, raw)); }
    /** 同步准备字体。 */
    @PostMapping("/{id}/fonts/prepare")
    public Response<?> prepareFonts(@PathVariable Long id, @RequestBody String raw) { return Response.success(application.prepareFonts(id, raw)); }
    /** 记录分享已发布版本。 */
    @PostMapping("/{id}/share-records")
    public Response<Void> share(@PathVariable Long id, @RequestBody String raw) { application.shareRecord(id, raw); return Response.success(); }
    /** 生成高级版本小程序码。 */
    @PostMapping("/{id}/miniapp-code")
    public Response<?> miniappCode(@PathVariable Long id) { return Response.success(application.miniappCode(id)); }
    /** 已保存预览档期选项。 */
    @GetMapping("/{id}/schedule-options")
    public Response<?> scheduleOptions(@PathVariable Long id, @RequestParam(required = false) String month,
            @RequestParam String componentKey, @RequestParam(defaultValue = "draft") String scope) {
        return Response.success(application.scheduleOptions(id, month, componentKey, scope));
    }
    /** 已保存预览查档。 */
    @PostMapping("/{id}/schedule-query-preview")
    public Response<?> scheduleQuery(@PathVariable Long id, @RequestBody String raw, @RequestParam(defaultValue = "draft") String scope) {
        return Response.success(application.scheduleQuery(id, raw, scope));
    }
    /** 团队混合来源维护查询。 */
    @GetMapping("/sources")
    public Response<?> sources(@RequestParam String ownerType, @RequestParam Long ownerId,
            @RequestParam(required = false) String sourceKind, @RequestParam(required = false) Long memberUserId,
            @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Response.success(application.listSources(ownerType, ownerId, sourceKind, memberUserId, keyword, page, pageSize));
    }
    /** 普通已发布个人目标查询。 */
    @GetMapping("/hyperlink-targets")
    public Response<?> hyperlinkTargets(@RequestParam(required = false) Long sourcePortfolioId,
            @RequestParam(required = false) Long selectedTargetPortfolioId) {
        return Response.success(application.hyperlinkTargets(sourcePortfolioId, selectedTargetPortfolioId));
    }
    /** 已保存团队配置中的成员发布版预览。 */
    @GetMapping("/{id}/member-portfolios/{childId}/published-preview")
    public Response<?> memberPreview(@PathVariable Long id, @PathVariable Long childId,
            @RequestParam(defaultValue = "draft") String scope) {
        return Response.success(members.preview(id, childId, scope));
    }
    /** 成员发布版只读档期。 */
    @GetMapping("/{id}/member-portfolios/{childId}/schedule-options")
    public Response<?> memberOptions(@PathVariable Long id, @PathVariable Long childId,
            @RequestParam String month, @RequestParam String componentKey,
            @RequestParam(defaultValue = "draft") String scope) {
        return Response.success(members.options(id, childId, month, componentKey, scope));
    }
    /** 成员发布版预览查档。 */
    @PostMapping("/{id}/member-portfolios/{childId}/schedule-query-preview")
    public Response<?> memberQuery(@PathVariable Long id, @PathVariable Long childId,
            @RequestBody PortfolioScheduleQueryRequest request, @RequestParam(defaultValue = "draft") String scope) {
        return Response.success(members.query(id, childId, request, scope));
    }
}
