package com.jxc.wefolio.constant;

/** 作品人工审核常量，统一人工审核编号前缀与内部回传路径。 */
public final class WorkManualAuditConstants {

    /** 人工审核编号前缀。 */
    public static final String MANUAL_AUDIT_NO_PREFIX = "WA";

    /** 人工审核内部回传路径前缀。 */
    public static final String REVIEW_API_PATH_PREFIX = "/api/internal/work-audits/";

    /** 禁止实例化常量类。 */
    private WorkManualAuditConstants() {
    }
}
