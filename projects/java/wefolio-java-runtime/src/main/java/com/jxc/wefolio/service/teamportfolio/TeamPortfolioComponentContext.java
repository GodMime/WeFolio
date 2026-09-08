package com.jxc.wefolio.service.teamportfolio;

import com.jxc.wefolio.entity.WorkEntity;
import java.util.Map;

/**
 * 团队作品集组件构建上下文。
 *
 * @param teamId 团队 ID
 * @param portfolioId 作品集 ID
 * @param revision 作品集版本号
 * @param textBackgroundWorks 本次渲染已校验权限的文字背景，空值表示尚未加载
 */
public record TeamPortfolioComponentContext(long teamId, long portfolioId, int revision,
                                            Map<Long,WorkEntity> textBackgroundWorks) {
    /** 创建尚未批量加载背景的基础上下文。 */
    public TeamPortfolioComponentContext(long teamId,long portfolioId,int revision) {
        this(teamId,portfolioId,revision,null);
    }
}
