package com.jxc.wefolio.job.controller;

import com.jxc.wefolio.job.common.Response;
import com.jxc.wefolio.job.dto.UserStorageFolderRepairExecutionResponse;
import com.jxc.wefolio.job.service.UserStorageFolderRepairExecutionService;
import com.jxc.wefolio.job.service.UserStorageFolderRepairExecutionService.ExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 历史用户 COS 目录修复 HTTP 适配器。
 */
@RestController
@RequiredArgsConstructor
public class UserStorageFolderRepairController {

    /** 固定接口路径与请求头 */
    private static final String REPAIR_PATH = "/user-storage/folders/repair";
    private static final String ADMIN_POINT_SECRET_HEADER = "X-Admin-Point-Secret";

    private final UserStorageFolderRepairExecutionService executionService;

    /**
     * 异步提交无业务入参的历史目录修复。
     *
     * @param secret job 运维密钥
     * @return 异步提交结果
     */
    @PostMapping(REPAIR_PATH)
    public ResponseEntity<Response<UserStorageFolderRepairExecutionResponse>> repair(
            @RequestHeader(value = ADMIN_POINT_SECRET_HEADER, required = false) String secret
    ) {
        ExecutionResult result = executionService.execute(secret);
        return switch (result.outcome()) {
            case ACCEPTED -> ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Response.success(result.message(), result.data()));
            case ALREADY_RUNNING -> ResponseEntity.ok(
                    Response.success(result.message(), result.data()));
            case UNAUTHORIZED -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Response.fail(result.message()));
            case UNAVAILABLE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Response.fail(result.message()));
        };
    }
}
