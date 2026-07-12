package com.jxc.wefolio.service.teamportfolio.component.teamprofile;

import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * 团队资料组件渲染器。
 */
@Component
public class TeamProfileComponentRenderer {

    /** 团队配置键。 */
    private static final String CONFIG_KEY_TEAM = "team";

    /** 团队 ID 配置键。 */
    private static final String CONFIG_KEY_TEAM_ID = "teamId";

    /** 团队资料不匹配提示。 */
    private static final String TEAM_MISMATCH_MESSAGE = "团队资料与当前作品集不匹配";

    /**
     * 返回与输入脱离的团队资料快照。
     *
     * @param normalizedConfig 规范化配置
     * @param context 组件上下文
     * @return 可展示的团队资料
     */
    public JSONObject render(JSONObject normalizedConfig, TeamPortfolioComponentContext context) {
        try {
            JSONObject team = requireTeamJson(normalizedConfig);
            Long teamId = parseTeamId(team);
            if (!teamId.equals(context.teamId())) {
                throw new BusinessException(TEAM_MISMATCH_MESSAGE);
            }
            TeamProfileComponentConfig componentConfig = normalizedConfig.toJavaObject(TeamProfileComponentConfig.class);
            if (componentConfig.getTeam() == null) {
                throw new BusinessException(TEAM_MISMATCH_MESSAGE);
            }
            componentConfig.getTeam().setTeamId(teamId);
            return componentConfig.toJsonObject();
        } catch (RuntimeException exception) {
            throw new BusinessException(TEAM_MISMATCH_MESSAGE);
        }
    }

    /**
     * 获取原始团队 JSON，拒绝缺失和非对象结构。
     *
     * @param normalizedConfig 规范化配置
     * @return 原始团队 JSON
     */
    private JSONObject requireTeamJson(JSONObject normalizedConfig) {
        if (normalizedConfig == null) {
            throw new BusinessException(TEAM_MISMATCH_MESSAGE);
        }
        Object rawTeam = normalizedConfig.get(CONFIG_KEY_TEAM);
        if (!(rawTeam instanceof JSONObject team)) {
            throw new BusinessException(TEAM_MISMATCH_MESSAGE);
        }
        return team;
    }

    /**
     * 从原始团队 JSON 严格解析正整数团队 ID。
     *
     * @param team 原始团队 JSON
     * @return 团队 ID
     */
    private Long parseTeamId(JSONObject team) {
        if (!team.containsKey(CONFIG_KEY_TEAM_ID)) {
            throw new BusinessException(TEAM_MISMATCH_MESSAGE);
        }
        Object rawTeamId = team.get(CONFIG_KEY_TEAM_ID);
        long teamId;
        try {
            if (rawTeamId instanceof Byte value) {
                teamId = value;
            } else if (rawTeamId instanceof Short value) {
                teamId = value;
            } else if (rawTeamId instanceof Integer value) {
                teamId = value;
            } else if (rawTeamId instanceof Long value) {
                teamId = value;
            } else if (rawTeamId instanceof BigInteger value) {
                teamId = value.longValueExact();
            } else if (rawTeamId instanceof BigDecimal value) {
                teamId = value.longValueExact();
            } else {
                throw new BusinessException(TEAM_MISMATCH_MESSAGE);
            }
        } catch (ArithmeticException exception) {
            throw new BusinessException(TEAM_MISMATCH_MESSAGE);
        }
        if (teamId <= 0) {
            throw new BusinessException(TEAM_MISMATCH_MESSAGE);
        }
        return teamId;
    }
}
