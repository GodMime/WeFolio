package com.jxc.wefolio.job.dict;

/**
 * 审核服务提供方字典。
 */
public enum AuditProviderDict {

    TENCENT_CI("TENCENT_CI");

    private final String code;

    AuditProviderDict(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
