package com.jxc.wefolio.job.controller;

import com.jxc.wefolio.job.common.Response;
import com.jxc.wefolio.job.dto.JobSchedulingDisableResponse;
import com.jxc.wefolio.job.service.JobSchedulingDisableService;
import com.jxc.wefolio.job.service.JobSchedulingDisableService.ExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台调度安全停用 HTTP 适配器。
 */
@RestController
@RequiredArgsConstructor
public class JobSchedulingController {

    /** 当前实例的后台调度停用路径。 */
    private static final String DISABLE_PATH = "/scheduling/disable";

    /** 复用现有 job 运维密钥的请求头名称。 */
    private static final String ADMIN_POINT_SECRET_HEADER = "X-Admin-Point-Secret";

    /** 执行鉴权、取消调度和排空等待的应用服务。 */
    private final JobSchedulingDisableService disableService;

    /**
     * 停止接受后续调度，并等待已经运行的后台工作自然结束。
     *
     * @param secret job 运维密钥
     * @return 当前实例的停用结果
     */
    @PostMapping(DISABLE_PATH)
    public ResponseEntity<Response<JobSchedulingDisableResponse>> disable(
            @RequestHeader(value = ADMIN_POINT_SECRET_HEADER, required = false) String secret
    ) {
        ExecutionResult result = disableService.disable(secret);
        return switch (result.outcome()) {
            case DISABLED -> ResponseEntity.ok(
                    Response.success(result.message(), result.data()));
            case UNAUTHORIZED -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Response.fail(result.message()));
            case TIMEOUT, UNAVAILABLE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Response.fail(result.message(), result.data()));
        };
    }
}
