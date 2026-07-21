package com.jxc.wefolio.service;

/**
 * 访客积分滚动扣费窗口处理结果。
 */
public enum BillingWindowResult {

    /** 本次已成功扣费并推进窗口 */
    CHARGED,

    /** 本次仍在滚动窗口内，仅记录访问 */
    SKIPPED_WITHIN_WINDOW
}
