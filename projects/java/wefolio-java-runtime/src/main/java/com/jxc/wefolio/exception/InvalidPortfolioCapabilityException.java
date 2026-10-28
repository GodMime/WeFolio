package com.jxc.wefolio.exception;

import com.jxc.wefolio.message.PortfolioTimelineMessage;

/** 标记已校验的作品集能力声明错误，供 HTTP 层从 JSON 绑定包装中恢复稳定提示。 */
public final class InvalidPortfolioCapabilityException extends BusinessException {
    /** 固定文案不包含客户端输入，其它绑定异常仍沿用通用请求体错误。 */
    public InvalidPortfolioCapabilityException() {
        super(PortfolioTimelineMessage.INVALID_CAPABILITY);
    }
}
