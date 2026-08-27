package com.jxc.wefolio.dto;

import lombok.Data;

/** 作品人工审核回传请求。 */
@Data
public class WorkManualAuditUpdateRequest {

    /** 审核结论：PASSED 或 REJECTED。 */
    private String status;

    /** 人工拒绝原因，仅 REJECTED 时必填。 */
    private String auditRejectReason;
}
