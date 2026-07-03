package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.MinePortfolioAssetUploadTicketRequest;
import com.jxc.wefolio.dto.MinePortfolioAssetUploadTicketResponse;
import com.jxc.wefolio.dto.MinePortfolioCreateRequest;
import com.jxc.wefolio.dto.MinePortfolioDetailResponse;
import com.jxc.wefolio.dto.MinePortfolioDraftSaveRequest;
import com.jxc.wefolio.dto.MinePortfolioListResponse;
import com.jxc.wefolio.dto.MinePortfolioPublishRequest;
import com.jxc.wefolio.dto.MinePortfolioShareRecordRequest;
import com.jxc.wefolio.dto.PortfolioComponentLibraryResponse;
import com.jxc.wefolio.service.MinePortfolioService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的作品集控制器 — 提供标准个人作品集维护端接口。
 */
@MaintainerAccess
@RestController
@RequiredArgsConstructor
public class MinePortfolioController {

    /** 我的作品集服务 */
    private final MinePortfolioService minePortfolioService;

    /**
     * 查询作品集列表。
     *
     * @param ownerType 归属类型
     * @return 列表响应
     */
    @GetMapping("/api/mine/portfolios")
    public Response<MinePortfolioListResponse> list(
            @RequestParam(value = "ownerType", required = false) String ownerType
    ) {
        return Response.success(minePortfolioService.listPortfolios(ownerType));
    }

    /**
     * 查询组件库。
     *
     * @return 组件库响应
     */
    @GetMapping("/api/mine/portfolios/component-library")
    public Response<PortfolioComponentLibraryResponse> componentLibrary() {
        return Response.success(minePortfolioService.getComponentLibrary());
    }

    /**
     * 创建标准个人作品集。
     *
     * @param request 创建请求
     * @return 详情响应
     */
    @PostMapping("/api/mine/portfolios/standard-personal")
    public Response<MinePortfolioDetailResponse> createStandardPersonal(
            @RequestBody MinePortfolioCreateRequest request
    ) {
        return Response.success(minePortfolioService.createStandardPersonal(request));
    }

    /**
     * 创建作品集图片素材直传 COS 票据。
     *
     * @param portfolioId 作品集 ID
     * @param request 素材票据创建请求
     * @return 素材票据响应
     */
    @PostMapping("/api/mine/portfolios/{portfolioId}/asset/upload-ticket")
    public Response<MinePortfolioAssetUploadTicketResponse> createAssetUploadTicket(
            @PathVariable Long portfolioId,
            @RequestBody MinePortfolioAssetUploadTicketRequest request
    ) {
        return Response.success(minePortfolioService.createAssetUploadTicket(portfolioId, request));
    }

    /**
     * 获取维护详情。
     *
     * @param portfolioId 作品集 ID
     * @return 详情响应
     */
    @GetMapping("/api/mine/portfolios/{portfolioId}")
    public Response<MinePortfolioDetailResponse> detail(@PathVariable Long portfolioId) {
        return Response.success(minePortfolioService.getDetail(portfolioId));
    }

    /**
     * 保存草稿。
     *
     * @param portfolioId 作品集 ID
     * @param request 保存请求
     * @return 详情响应
     */
    @PutMapping("/api/mine/portfolios/{portfolioId}/draft")
    public Response<MinePortfolioDetailResponse> saveDraft(
            @PathVariable Long portfolioId,
            @RequestBody MinePortfolioDraftSaveRequest request
    ) {
        return Response.success(minePortfolioService.saveDraft(portfolioId, request));
    }

    /**
     * 预览草稿。
     *
     * @param portfolioId 作品集 ID
     * @return 详情响应
     */
    @GetMapping("/api/mine/portfolios/{portfolioId}/preview")
    public Response<MinePortfolioDetailResponse> preview(@PathVariable Long portfolioId) {
        return Response.success(minePortfolioService.preview(portfolioId));
    }

    /**
     * 发布草稿。
     *
     * @param portfolioId 作品集 ID
     * @param request 发布请求
     * @return 详情响应
     */
    @PostMapping("/api/mine/portfolios/{portfolioId}/publish")
    public Response<MinePortfolioDetailResponse> publish(
            @PathVariable Long portfolioId,
            @RequestBody MinePortfolioPublishRequest request
    ) {
        return Response.success(minePortfolioService.publish(portfolioId, request));
    }

    /**
     * 创建分享记录。
     *
     * @param portfolioId 作品集 ID
     * @param request 分享记录请求
     * @return 空响应
     */
    @PostMapping("/api/mine/portfolios/{portfolioId}/share-records")
    public Response<Void> createShareRecord(
            @PathVariable Long portfolioId,
            @RequestBody MinePortfolioShareRecordRequest request
    ) {
        minePortfolioService.createShareRecord(portfolioId, request);
        return Response.success();
    }

    /**
     * 删除作品集。
     *
     * @param portfolioId 作品集 ID
     * @return 空响应
     */
    @PostMapping("/api/mine/portfolios/delete/{portfolioId}")
    public Response<Void> deletePortfolio(@PathVariable Long portfolioId) {
        minePortfolioService.deletePortfolio(portfolioId);
        return Response.success();
    }
}
