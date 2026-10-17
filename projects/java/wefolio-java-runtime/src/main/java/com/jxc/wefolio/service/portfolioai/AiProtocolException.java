package com.jxc.wefolio.service.portfolioai;

/** AI 协议拒绝；只携带代码生成的安全摘要，不包含模型原文或业务值。 */
public class AiProtocolException extends IllegalArgumentException {
    /** 固定错误码。 */
    private final String code;

    /** 以固定错误码和安全摘要创建异常。 */
    public AiProtocolException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** 返回供任务重试分类使用的错误码。 */
    public String getCode() { return code; }
}
