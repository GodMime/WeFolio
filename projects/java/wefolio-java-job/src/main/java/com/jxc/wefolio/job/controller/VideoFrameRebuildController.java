package com.jxc.wefolio.job.controller;

import com.jxc.wefolio.job.common.Response;
import com.jxc.wefolio.job.model.VideoFrameBackfillModels.RebuildRequest;
import com.jxc.wefolio.job.model.VideoFrameBackfillModels.Submission;
import com.jxc.wefolio.job.service.VideoFrameBackfillExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 手动视频帧重建的 HTTP 适配，范围校验及执行互斥由用例负责。 */
@RestController
@RequiredArgsConstructor
public class VideoFrameRebuildController {
    /** job context-path 自动追加，沿用既有运维认证头。 */
    private static final String PATH = "/video-frames/rebuild";
    private static final String SECRET_HEADER = "X-Admin-Point-Secret";
    /** 与历史补帧共用的手动执行入口。 */
    private final VideoFrameBackfillExecutionService execution;

    /** 显式提交一次重建，响应只报告准入结果及用于查日志的执行编号。 */
    @PostMapping(PATH)
    public ResponseEntity<Response<Submission>> rebuild(
            @RequestHeader(value = SECRET_HEADER, required = false) String secret,
            @RequestBody(required = false) RebuildRequest request) {
        VideoFrameBackfillExecutionService.Result result = execution.rebuild(secret, request);
        return switch (result.outcome()) {
            case ACCEPTED -> ResponseEntity.status(HttpStatus.ACCEPTED).body(Response.success(result.message(), result.data()));
            case EXISTING -> ResponseEntity.ok(Response.success(result.message(), result.data()));
            case UNAUTHORIZED -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Response.fail(result.message()));
            case INVALID -> ResponseEntity.badRequest().body(Response.fail(result.message()));
            case UNAVAILABLE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Response.fail(result.message()));
        };
    }
}
