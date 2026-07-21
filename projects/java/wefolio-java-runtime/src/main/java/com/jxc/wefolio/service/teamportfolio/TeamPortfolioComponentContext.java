package com.jxc.wefolio.service.teamportfolio;

/**
 * 团队作品集组件构建上下文。
 *
 * @param teamId 团队 ID
 * @param portfolioId 作品集 ID
 * @param revision 作品集版本号
 */
public record TeamPortfolioComponentContext(long teamId, long portfolioId, int revision) {
}
