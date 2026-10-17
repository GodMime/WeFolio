package com.jxc.wefolio.service.portfolioai;

import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioAiMessage;

/** 显式配置规范化被拒绝，保持原业务异常和文案，仅增加受控诊断分类。 */
final class PortfolioAiNormalizationException extends BusinessException {
    /** 不包含路径、字段名或实际值的固定错误码。 */
    private final String code;

    /** 继承既有业务错误语义，原因由 Runtime 详情映射器安全解释。 */
    PortfolioAiNormalizationException(String code) {
        super(PortfolioAiMessage.CANDIDATE_INVALID);
        this.code = code;
    }

    /** 返回用于安全展示的固定规范化分类。 */
    String getCode() { return code; }
}
