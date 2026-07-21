package com.jxc.wefolio.service.teamportfolio.component.teamprofile;

import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 团队资料组件引用提取器。
 */
@Component
public class TeamProfileComponentReferenceExtractor {

    /** 团队配置键。 */
    private static final String CONFIG_KEY_TEAM = "team";

    /** 有效引用标识。 */
    private static final int REFERENCE_VALID = 1;

    /**
     * 提取团队资料引用快照。
     *
     * @param componentKey 组件实例键
     * @param componentPath 组件路径
     * @param normalizedConfig 规范化配置
     * @param context 组件上下文
     * @return 团队资料引用
     */
    public List<PortfolioReferenceEntity> extract(
            String componentKey,
            String componentPath,
            JSONObject normalizedConfig,
            TeamPortfolioComponentContext context
    ) {
        PortfolioReferenceEntity reference = new PortfolioReferenceEntity();
        reference.setPortfolioId(context.portfolioId());
        reference.setReferenceType(ReferenceTypeDict.TEAM_PROFILE.getCode());
        reference.setReferenceId(context.teamId());
        reference.setComponentKey(componentKey);
        reference.setComponentPath(componentPath);
        reference.setSortOrder(0);
        reference.setIsValid(REFERENCE_VALID);
        reference.setSnapshotJson(normalizedConfig.getJSONObject(CONFIG_KEY_TEAM).toJSONString());
        return List.of(reference);
    }
}
