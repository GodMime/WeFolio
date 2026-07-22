package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 作品主动重新审核响应。
 */
@Data
public class MineWorkAuditResubmitResponse {

    /** 作品 ID */
    private Long workId;

    /** 当前审核状态 */
    private String auditStatus;

    /** 当前审核轮次 */
    private int auditRound;

    /** 配置的审核总轮次 */
    private int maxAuditRounds;

    /** 剩余可主动重审次数 */
    private int remainingAuditResubmitCount;

    /** 当前是否允许主动重审 */
    private boolean canResubmitAudit;
}
