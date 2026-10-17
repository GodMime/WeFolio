package com.jxc.wefolio.job.controller;

import com.jxc.wefolio.job.common.Response;
import com.jxc.wefolio.job.model.VideoFrameBackfillModels.Request;
import com.jxc.wefolio.job.model.VideoFrameBackfillModels.Submission;
import com.jxc.wefolio.job.service.VideoFrameBackfillExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 历史视频一次性补帧 HTTP 适配，不解析帧或直接访问远端。 */
@RestController
@RequiredArgsConstructor
public class VideoFrameBackfillController {
    /** job context-path 自动追加，沿用运维认证头。 */
    private static final String PATH = "/video-frames/backfill";
    private static final String SECRET_HEADER = "X-Admin-Point-Secret";
    /** 历史运维用例入口。 */
    private final VideoFrameBackfillExecutionService execution;

    /** 显式提交一次历史扫描；执行结果通过执行编号关联日志。 */
    @PostMapping(PATH)
    public ResponseEntity<Response<Submission>> execute(
            @RequestHeader(value = SECRET_HEADER, required = false) String secret,
            @RequestBody(required = false) Request request) {
        VideoFrameBackfillExecutionService.Result result = execution.execute(secret, request);
        return switch (result.outcome()) {
            case ACCEPTED -> ResponseEntity.status(HttpStatus.ACCEPTED).body(Response.success(result.message(), result.data()));
            case EXISTING -> ResponseEntity.ok(Response.success(result.message(), result.data()));
            case UNAUTHORIZED -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Response.fail(result.message()));
            case INVALID -> ResponseEntity.badRequest().body(Response.fail(result.message()));
            case UNAVAILABLE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Response.fail(result.message()));
        };
    }
}
