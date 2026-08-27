package com.jxc.wefolio.service;

import lombok.Data;

/** Redis 保存的团队创建幂等记录。 */
@Data
public class TeamCreationIdempotencyRecord {

    /** 规范化创建请求的 SHA-256 摘要 */
    private String requestFingerprint;

    /** 首次请求预留的团队唯一码 */
    private String uniqueCode;

    /** 已创建团队 ID，创建完成前为空 */
    private Long teamId;
}
