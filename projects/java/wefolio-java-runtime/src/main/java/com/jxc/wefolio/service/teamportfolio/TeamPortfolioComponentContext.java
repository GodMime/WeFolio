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
 * @param allowAdvancedMemberPortfolios 是否明确允许引用高级个人成员作品集
 * @param unsavedAdvancedCandidate 是否为未保存高级团队候选的只读上下文
 */
public record TeamPortfolioComponentContext(long teamId, long portfolioId, int revision,
                                            Map<Long,WorkEntity> textBackgroundWorks, boolean allowAdvancedMemberPortfolios,
                                            boolean unsavedAdvancedCandidate) {
    /** 已保存高级入口仍要求真实作品集标识，不携带未保存候选许可。 */
    public TeamPortfolioComponentContext(long teamId, long portfolioId, int revision,
            Map<Long,WorkEntity> works, boolean allowAdvancedMemberPortfolios) {
        this(teamId, portfolioId, revision, works, allowAdvancedMemberPortfolios, false);
    }
    /** 兼容普通组件已有的背景预加载上下文；成员范围不扩展。 */
    public TeamPortfolioComponentContext(long teamId, long portfolioId, int revision, Map<Long,WorkEntity> works) {
        this(teamId, portfolioId, revision, works, false, false);
    }

    /** 创建尚未批量加载背景的基础上下文。 */
    public TeamPortfolioComponentContext(long teamId,long portfolioId,int revision) {
        this(teamId,portfolioId,revision,null,false,false);
    }

    /** 服务端创建未保存候选的只读上下文；零值表示无作品集身份，不能用于引用持久化。 */
    public static TeamPortfolioComponentContext unsavedAdvancedCandidate(long teamId, int revision) {
        return new TeamPortfolioComponentContext(teamId, 0, revision, null, true, true);
    }

    /** 仅明确高级候选许可允许零作品集标识，普通构造及负数始终拒绝。 */
    public boolean hasReadOnlyCandidateIdentity() {
        return unsavedAdvancedCandidate && allowAdvancedMemberPortfolios && portfolioId == 0;
    }
}
