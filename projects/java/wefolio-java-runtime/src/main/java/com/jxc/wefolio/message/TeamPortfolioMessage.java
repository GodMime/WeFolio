package com.jxc.wefolio.message;

/**
 * 团队作品集业务提示文案。
 */
public interface TeamPortfolioMessage {

    /** 团队作品集功能尚未开放 */
    String FEATURE_DISABLED = "团队作品集功能暂未开放";

    /** 团队不存在或当前用户没有访问权限 */
    String NO_ACCESS = "团队不存在或无访问权限";

    /** 当前用户没有团队作品集维护权限 */
    String NO_MAINTAIN_PERMISSION = "无团队作品集维护权限";

    /** 团队作品集不存在或当前用户没有访问权限 */
    String PORTFOLIO_NOT_FOUND = "团队作品集不存在或无访问权限";

    /** 当前 Schema 暂不支持访问 */
    String INVALID_SCHEMA = "当前作品集暂未开放访问";
}
