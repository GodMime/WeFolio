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
import org.springframework.web.bind.annotation.RestController;

/** 作品人工审核回传控制器，只负责无认证内部接口的 HTTP 适配。 */
@SystemAccess
@RestController
@RequiredArgsConstructor
public class WorkManualAuditController {

    /** 作品人工审核回传服务 */
    private final WorkManualAuditService service;

    /** 按人工审核编号写入或幂等重放最终结论。 */
    @PutMapping(WorkManualAuditConstants.REVIEW_API_PATH_PREFIX + "{manualAuditNo}")
    public Response<WorkManualAuditUpdateResponse> update(
            @PathVariable("manualAuditNo") String manualAuditNo,
            @RequestBody WorkManualAuditUpdateRequest request
    ) {
        return Response.success(service.update(manualAuditNo, request));
    }
}
