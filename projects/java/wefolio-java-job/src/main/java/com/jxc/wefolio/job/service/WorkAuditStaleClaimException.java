package com.jxc.wefolio.job.service;

/** 迟到结果失去作品写入权，用异常回滚整个短事务。 */
public class WorkAuditStaleClaimException extends RuntimeException {
    /** 无敏感数据的失效说明。 */
    public WorkAuditStaleClaimException() {
        super("视频审核领取权、源身份或审核轮次已失效");
    }
}
