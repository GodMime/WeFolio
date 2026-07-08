package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.MineWorkBatchDeleteCheckResponse;
import com.jxc.wefolio.dto.MineWorkBatchDeleteRequest;
import com.jxc.wefolio.dto.MineWorkBatchDeleteResponse;
import com.jxc.wefolio.dto.MineWorkCoverUploadTicketRequest;
import com.jxc.wefolio.dto.MineWorkCoverUploadTicketResponse;
import com.jxc.wefolio.dto.MineWorkDeleteCheckResponse;
import com.jxc.wefolio.dto.MineWorkDetailResponse;
import com.jxc.wefolio.dto.MineWorkListResponse;
import com.jxc.wefolio.dto.MineWorkSortRequest;
import com.jxc.wefolio.dto.MineWorkSortItemsResponse;
import com.jxc.wefolio.dto.MineWorkTagResponse;
import com.jxc.wefolio.dto.MineWorkTagUpsertRequest;
import com.jxc.wefolio.dto.MineWorkThumbnailUploadTicketRequest;
import com.jxc.wefolio.dto.MineWorkThumbnailUploadTicketResponse;
import com.jxc.wefolio.dto.MineWorkUpdateRequest;
import com.jxc.wefolio.dto.MineWorkUploadCompleteRequest;
import com.jxc.wefolio.dto.MineWorkUploadCompleteResponse;
import com.jxc.wefolio.dto.MineWorkUploadTicketRequest;
import com.jxc.wefolio.dto.MineWorkUploadTicketResponse;
import com.jxc.wefolio.service.MineWorkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的作品控制器 — 提供作品列表、直传上传、编辑、排序和删除检查接口。
 */
@Slf4j
@MaintainerAccess
@RestController
@RequiredArgsConstructor
public class MineWorkController {

    /** 作品标签集合路径 */
    private static final String WORK_TAGS_PATH = "/api/mine/works/tags";

    /** 单个作品标签路径 */
    private static final String WORK_TAG_DETAIL_PATH = "/api/mine/works/tags/{tagId}";

    /** 删除作品标签路径，小程序端使用 POST 规避 DELETE 兼容问题 */
    private static final String WORK_TAG_DELETE_PATH = "/api/mine/works/tags/delete/{tagId}";

    /** 我的作品服务 */
    private final MineWorkService mineWorkService;

    /**
     * 查询我的作品列表。
     *
     * @param keyword 搜索关键词
     * @param tagId 标签 ID
     * @param mediaType 媒体类型，可为空
     * @param page 页码
     * @param pageSize 每页数量
     * @return 作品列表
     */
    @GetMapping("/api/mine/works")
    public Response<MineWorkListResponse> works(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "tagId", required = false) Long tagId,
            @RequestParam(value = "mediaType", required = false) String mediaType,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize
    ) {
        return Response.success(mineWorkService.listWorks(keyword, tagId, mediaType, page, pageSize));
    }

    /**
     * 查询我的作品标签。
     *
     * @return 标签列表
     */
    @GetMapping(WORK_TAGS_PATH)
    public Response<MineWorkTagResponse> tags() {
        return Response.success(mineWorkService.listTags());
    }

    /**
     * 新增作品标签。
     *
     * @param request 标签请求
     * @return 新增标签
     */
    @PostMapping(WORK_TAGS_PATH)
    public Response<MineWorkListResponse.TagItem> createTag(@RequestBody MineWorkTagUpsertRequest request) {
        return Response.success(mineWorkService.createTag(request));
    }

    /**
     * 编辑作品标签。
     *
     * @param tagId 标签 ID
     * @param request 标签请求
     * @return 更新后标签
     */
    @PutMapping(WORK_TAG_DETAIL_PATH)
    public Response<MineWorkListResponse.TagItem> updateTag(
            @PathVariable Long tagId,
            @RequestBody MineWorkTagUpsertRequest request
    ) {
        return Response.success(mineWorkService.updateTag(tagId, request));
    }

    /**
     * 删除作品标签。
     *
     * @param tagId 标签 ID
     * @return 空响应
     */
    @PostMapping(WORK_TAG_DELETE_PATH)
    public Response<Void> deleteTag(@PathVariable Long tagId) {
        mineWorkService.deleteTag(tagId);
        return Response.success();
    }

    /**
     * 查询作品详情。
     *
     * @param workId 作品 ID
     * @return 作品详情
     */
    @GetMapping("/api/mine/works/{workId}")
    public Response<MineWorkDetailResponse> detail(@PathVariable Long workId) {
        return Response.success(mineWorkService.getWorkDetail(workId));
    }

    /**
     * 创建作品直传 COS 票据。
     *
     * @param request 票据创建请求
     * @return 票据响应
     */
    @PostMapping("/api/mine/works/upload-tickets")
    public Response<MineWorkUploadTicketResponse> createUploadTickets(
            @RequestBody MineWorkUploadTicketRequest request
    ) {
        int count = request == null || request.getFiles() == null ? 0 : request.getFiles().size();
        log.info("创建作品上传票据: fileCount={}", count);
        return Response.success(mineWorkService.createUploadTickets(request));
    }

    /**
     * 创建视频作品封面直传 COS 票据。
     *
     * @param workId 作品 ID
     * @param request 封面票据创建请求
     * @return 封面票据响应
     */
    @PostMapping("/api/mine/works/{workId}/cover-upload-ticket")
    public Response<MineWorkCoverUploadTicketResponse> createCoverUploadTicket(
            @PathVariable Long workId,
            @RequestBody MineWorkCoverUploadTicketRequest request
    ) {
        log.info("创建视频作品封面上传票据: workId={}, fileSize={}", workId, request == null ? null : request.getFileSize());
        return Response.success(mineWorkService.createCoverUploadTicket(workId, request));
    }

    /**
     * 创建图片作品缩略图直传 COS 票据。
     *
     * @param workId 作品 ID
     * @param request 缩略图票据创建请求
     * @return 缩略图票据响应
     */
    @PostMapping("/api/mine/works/{workId}/thumbnail-upload-ticket")
    public Response<MineWorkThumbnailUploadTicketResponse> createThumbnailUploadTicket(
            @PathVariable Long workId,
            @RequestBody MineWorkThumbnailUploadTicketRequest request
    ) {
        log.info("创建图片作品缩略图上传票据: workId={}, fileSize={}", workId, request == null ? null : request.getFileSize());
        return Response.success(mineWorkService.createThumbnailUploadTicket(workId, request));
    }

    /**
     * 确认作品上传完成。
     *
     * @param request 上传完成请求
     * @return 确认响应
     */
    @PostMapping("/api/mine/works/upload-complete")
    public Response<MineWorkUploadCompleteResponse> completeUpload(
            @RequestBody MineWorkUploadCompleteRequest request
    ) {
        int count = request == null || request.getItems() == null ? 0 : request.getItems().size();
        log.info("确认作品上传完成: itemCount={}", count);
        return Response.success(mineWorkService.completeUpload(request));
    }

    /**
     * 更新作品资料。
     *
     * @param workId 作品 ID
     * @param request 更新请求
     * @return 更新后详情
     */
    @PutMapping("/api/mine/works/{workId}")
    public Response<MineWorkDetailResponse> updateWork(
            @PathVariable Long workId,
            @RequestBody MineWorkUpdateRequest request
    ) {
        log.info("更新作品: workId={}, title={}", workId, request == null ? "" : request.getTitle());
        return Response.success(mineWorkService.updateWork(workId, request));
    }

    /**
     * 调整作品排序。
     *
     * @param request 排序请求
     * @return 空响应
     */
    @PostMapping("/api/mine/works/sort")
    public Response<Void> sortWorks(@RequestBody MineWorkSortRequest request) {
        mineWorkService.sortWorks(request);
        return Response.success();
    }

    /**
     * 查询排序模式作品列表。
     *
     * @param scope 排序范围
     * @param tagId 标签 ID
     * @return 排序作品列表
     */
    @GetMapping("/api/mine/works/sort-items")
    public Response<MineWorkSortItemsResponse> sortItems(
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "tagId", required = false) Long tagId
    ) {
        return Response.success(mineWorkService.listSortItems(scope, tagId));
    }

    /**
     * 删除前检查作品引用。
     *
     * @param workId 作品 ID
     * @return 删除检查结果
     */
    @GetMapping("/api/mine/works/{workId}/delete-check")
    public Response<MineWorkDeleteCheckResponse> checkDeleteWork(@PathVariable Long workId) {
        return Response.success(mineWorkService.checkDeleteWork(workId));
    }

    /**
     * 批量删除前检查作品引用。
     *
     * @param request 批量删除请求
     * @return 批量删除检查结果
     */
    @PostMapping("/api/mine/works/delete-check")
    public Response<MineWorkBatchDeleteCheckResponse> checkDeleteWorks(
            @RequestBody MineWorkBatchDeleteRequest request
    ) {
        return Response.success(mineWorkService.checkDeleteWorks(request));
    }

    /**
     * 删除作品。
     *
     * @param workId 作品 ID
     * @return 空响应
     */
    @PostMapping("/api/mine/works/delete/{workId}")
    public Response<Void> deleteWork(@PathVariable Long workId) {
        mineWorkService.deleteWork(workId);
        return Response.success();
    }

    /**
     * 批量删除作品。
     *
     * @param request 批量删除请求
     * @return 批量删除结果
     */
    @PostMapping("/api/mine/works/delete")
    public Response<MineWorkBatchDeleteResponse> deleteWorks(
            @RequestBody MineWorkBatchDeleteRequest request
    ) {
        return Response.success(mineWorkService.deleteWorks(request));
    }
}
