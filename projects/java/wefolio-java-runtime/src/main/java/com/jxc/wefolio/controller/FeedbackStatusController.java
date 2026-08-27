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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import static com.jxc.wefolio.constant.PointConstants.ADMIN_POINT_SECRET_HEADER;

/** 问题反馈状态控制器，负责内部密钥认证状态更新的 HTTP 适配。 */
@SystemAccess
@RestController
@RequiredArgsConstructor
public class FeedbackStatusController {

    /** 问题反馈应用服务。 */
    private final MineFeedbackApplicationService applicationService;

    /**
     * 按问题编号更新团队处理状态和反馈结果。
     *
     * @param secret 请求头中的后台积分密钥
     * @param feedbackNo 对外问题反馈编号
     * @param request 状态更新请求
     * @return 不含用户身份和轮次原始 JSON 的最小响应
     */
    @PutMapping("/api/internal/feedbacks/{feedbackNo}")
    public Response<FeedbackStatusUpdateResponse> updateStatus(
            @RequestHeader(value = ADMIN_POINT_SECRET_HEADER, required = false) String secret,
            @PathVariable("feedbackNo") String feedbackNo,
            @RequestBody FeedbackStatusUpdateRequest request
    ) {
        return Response.success(applicationService.updateStatus(secret, feedbackNo, request));
    }
}
