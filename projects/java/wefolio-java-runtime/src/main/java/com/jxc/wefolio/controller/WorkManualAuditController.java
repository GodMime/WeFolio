package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.SystemAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.constant.WorkManualAuditConstants;
import com.jxc.wefolio.dto.WorkManualAuditUpdateRequest;
import com.jxc.wefolio.dto.WorkManualAuditUpdateResponse;
import com.jxc.wefolio.service.WorkManualAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import static com.jxc.wefolio.constant.PointConstants.ADMIN_POINT_SECRET_HEADER;

/** 作品人工审核回传控制器，只负责内部密钥认证接口的 HTTP 适配。 */
@SystemAccess
@RestController
@RequiredArgsConstructor
public class WorkManualAuditController {

    /** 作品人工审核回传服务 */
    private final WorkManualAuditService service;

    /** 按人工审核编号写入或幂等重放最终结论。 */
    @PutMapping(WorkManualAuditConstants.REVIEW_API_PATH_PREFIX + "{manualAuditNo}")
    public Response<WorkManualAuditUpdateResponse> update(
            @RequestHeader(value = ADMIN_POINT_SECRET_HEADER, required = false) String secret,
            @PathVariable("manualAuditNo") String manualAuditNo,
            @RequestBody WorkManualAuditUpdateRequest request
    ) {
        return Response.success(service.update(secret, manualAuditNo, request));
    }
}
