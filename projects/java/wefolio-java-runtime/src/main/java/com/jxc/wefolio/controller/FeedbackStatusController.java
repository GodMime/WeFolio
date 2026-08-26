package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.SystemAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.FeedbackStatusUpdateRequest;
import com.jxc.wefolio.dto.FeedbackStatusUpdateResponse;
import com.jxc.wefolio.service.MineFeedbackApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 问题反馈状态控制器，负责无认证状态更新的 HTTP 适配。 */
@SystemAccess
@RestController
@RequiredArgsConstructor
public class FeedbackStatusController {

    /** 问题反馈应用服务。 */
    private final MineFeedbackApplicationService applicationService;

    /**
     * 按问题编号更新团队处理状态和反馈结果。
     *
     * @param feedbackNo 对外问题反馈编号
     * @param request 状态更新请求
     * @return 不含用户身份和轮次原始 JSON 的最小响应
     */
    @PutMapping("/api/internal/feedbacks/{feedbackNo}")
    public Response<FeedbackStatusUpdateResponse> updateStatus(
            @PathVariable("feedbackNo") String feedbackNo,
            @RequestBody FeedbackStatusUpdateRequest request
    ) {
        return Response.success(applicationService.updateStatus(feedbackNo, request));
    }
}
