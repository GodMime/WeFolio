package com.jxc.wefolio.job.controller;

import com.jxc.wefolio.job.common.Response;
import com.jxc.wefolio.job.dto.WorkStorageBillingExecuteRequest;
import com.jxc.wefolio.job.dto.WorkStorageBillingExecutionResponse;
import com.jxc.wefolio.job.service.WorkStorageBillingExecutionService;
import com.jxc.wefolio.job.service.WorkStorageBillingExecutionService.ExecutionResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 作品存储月度结算主动执行接口。
 */
@RestController
public class WorkStorageBillingController {

    /** 接口固定语义 */
    private static final String EXECUTE_PATH = "/work-storage-billing/execute";
    private static final String ADMIN_POINT_SECRET_HEADER = "X-Admin-Point-Secret";

    /** 作品存储结算手工执行应用服务 */
    private final WorkStorageBillingExecutionService executionService;

    /**
     * 创建主动执行控制器。
     *
     * @param executionService 手工执行应用服务
     */
    public WorkStorageBillingController(WorkStorageBillingExecutionService executionService) {
        this.executionService = executionService;
    }

    /**
     * 异步提交指定或默认账期结算。
     *
     * @param secret 后台积分密钥
     * @param request 可选账期请求
     * @return 异步提交结果
     */
    @PostMapping(EXECUTE_PATH)
    public ResponseEntity<Response<WorkStorageBillingExecutionResponse>> execute(
            @RequestHeader(value = ADMIN_POINT_SECRET_HEADER, required = false) String secret,
            @RequestBody(required = false) WorkStorageBillingExecuteRequest request
    ) {
        ExecutionResult result = executionService.execute(secret, request);
        return switch (result.outcome()) {
            case ACCEPTED -> ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Response.success(result.message(), result.data()));
            case ALREADY_RUNNING -> ResponseEntity.ok(Response.success(result.message(), result.data()));
            case UNAUTHORIZED -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Response.fail(result.message()));
            case BAD_REQUEST -> ResponseEntity.badRequest().body(Response.fail(result.message()));
            case UNAVAILABLE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Response.fail(result.message()));
        };
    }
}
