package com.jxc.wefolio.job.service;

/** 模型传输或供应商失败分类，错误摘要固定且不含远端原文。 */
public class PortfolioAiCallException extends RuntimeException {
    /** 固定错误码。 */
    private final String code;
    /** 是否使用剩余统一尝试预算重试。 */
    private final boolean retryable;

    /** 创建安全的模型调用失败。 */
    public PortfolioAiCallException(String code, String message, boolean retryable) {
        super(message); this.code = code; this.retryable = retryable;
    }

    /** 返回固定分类码。 */
    public String getCode() { return code; }
    /** 返回是否允许统一自动重试。 */
    public boolean isRetryable() { return retryable; }
}
