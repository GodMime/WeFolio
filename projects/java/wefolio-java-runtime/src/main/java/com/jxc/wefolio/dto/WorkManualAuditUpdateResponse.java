package com.jxc.wefolio.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 作品人工审核回传最小响应，不包含用户和媒体信息。 */
@Data
public class WorkManualAuditUpdateResponse {

    /** 人工审核编号。 */
    private String manualAuditNo;

    /** 最终审核状态。 */
    private String auditStatus;

    /** 人工拒绝原因。 */
    private String auditRejectReason;

    /** 首次写入结论时间。 */
    private LocalDateTime manualAuditResultAt;

    /** 本次请求是否实际写入。 */
    private boolean changed;
}
