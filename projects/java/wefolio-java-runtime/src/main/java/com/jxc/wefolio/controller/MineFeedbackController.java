package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.MineFeedbackCreateRequest;
import com.jxc.wefolio.dto.MineFeedbackCreationStateResponse;
import com.jxc.wefolio.dto.MineFeedbackDetailResponse;
import com.jxc.wefolio.dto.MineFeedbackListResponse;
import com.jxc.wefolio.dto.MineFeedbackUploadTicketRequest;
import com.jxc.wefolio.dto.MineFeedbackUploadTicketResponse;
import com.jxc.wefolio.service.FeedbackUploadService;
import com.jxc.wefolio.service.MineFeedbackApplicationService;
import com.jxc.wefolio.service.MineFeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 当前用户问题反馈控制器，负责反馈 HTTP 参数绑定与响应映射。 */
@MaintainerAccess
@RestController
@RequiredArgsConstructor
public class MineFeedbackController {

    /** 问题反馈查询服务。 */
    private final MineFeedbackService mineFeedbackService;

    /** 问题反馈上传票据服务。 */
    private final FeedbackUploadService feedbackUploadService;

    /** 问题反馈应用服务。 */
    private final MineFeedbackApplicationService applicationService;

    /**
     * 查询当前用户创建问题的额度状态。
     *
     * @return 创建额度状态
     */
    @GetMapping("/api/mine/feedbacks/creation-state")
    public Response<MineFeedbackCreationStateResponse> creationState() {
        return Response.success(mineFeedbackService.creationState());
    }

    /**
     * 为当前提交申请反馈附件直传票据。
     *
     * @param request 文件元数据请求
     * @return 上传票据响应
     */
    @PostMapping("/api/mine/feedbacks/upload-tickets")
    public Response<MineFeedbackUploadTicketResponse> uploadTickets(
            @RequestBody MineFeedbackUploadTicketRequest request
    ) {
        return Response.success(feedbackUploadService.createUploadTickets(request));
    }

    /**
     * 创建当前用户的问题反馈。
     *
     * @param request 创建请求
     * @return 创建后的反馈详情
     */
    @PostMapping("/api/mine/feedbacks")
    public Response<MineFeedbackDetailResponse> create(
            @RequestBody MineFeedbackCreateRequest request
    ) {
        return Response.success(applicationService.create(request));
    }

    /**
     * 分页查询当前用户的历史问题反馈。
     *
     * @param pageNo 页码，可空
     * @param pageSize 每页数量，可空
     * @return 历史问题分页响应
     */
    @GetMapping("/api/mine/feedbacks")
    public Response<MineFeedbackListResponse> list(
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize
    ) {
        return Response.success(mineFeedbackService.list(pageNo, pageSize));
    }

    /**
     * 查询当前用户拥有的问题反馈详情。
     *
     * @param feedbackId 反馈 ID
     * @return 反馈详情
     */
    @GetMapping("/api/mine/feedbacks/{feedbackId}")
    public Response<MineFeedbackDetailResponse> detail(
            @PathVariable("feedbackId") Long feedbackId
    ) {
        return Response.success(mineFeedbackService.detail(feedbackId));
    }

    /**
     * 为当前用户拥有的问题追加一轮反馈。
     *
     * @param feedbackId 反馈 ID
     * @param request 追加请求
     * @return 追加后的反馈详情
     */
    @PostMapping("/api/mine/feedbacks/{feedbackId}/rounds")
    public Response<MineFeedbackDetailResponse> append(
            @PathVariable("feedbackId") Long feedbackId,
            @RequestBody MineFeedbackCreateRequest request
    ) {
        return Response.success(applicationService.append(feedbackId, request));
    }
}
