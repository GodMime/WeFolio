package com.jxc.wefolio.job.service;

/** 模型传输或供应商失败分类，错误摘要固定且不含远端原文。 */
public class PortfolioAiCallException extends RuntimeException {
    /** 固定错误码。 */
    private final String code;
    /** 是否使用剩余统一尝试预算重试。 */
    private final boolean retryable;
    /** 已解析响应的计量信息；失败正文不随异常传递，未收到可信计量时为空。 */
    private final PortfolioAiModelClient.Completion completion;

    /** 创建安全的模型调用失败。 */
    public PortfolioAiCallException(String code, String message, boolean retryable) {
        this(code, message, retryable, null);
    }

    /** 模型正文不完整时仍保留合法计量，供独立后台预算结算。 */
    public PortfolioAiCallException(String code, String message, boolean retryable,
            PortfolioAiModelClient.Completion completion) {
        super(message); this.code = code; this.retryable = retryable;
        this.completion = completion;
    }

    /** 返回固定分类码。 */
    public String getCode() { return code; }
    /** 返回是否允许统一自动重试。 */
    public boolean isRetryable() { return retryable; }
    /** 返回不含失败正文的用量；调用方仍须验证数值是否合法。 */
    public PortfolioAiModelClient.Completion getCompletion() { return completion; }
}
