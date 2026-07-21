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

    /** 展示字段配置键。 */
    private static final String CONFIG_KEY_VISIBLE_FIELDS = "visibleFields";

    /** 团队头像展示字段。 */
    private static final String VISIBLE_FIELD_AVATAR = "avatar";

    /** 团队名称展示字段。 */
    private static final String VISIBLE_FIELD_TEAM_NAME = "teamName";

    /** 团队简介展示字段。 */
    private static final String VISIBLE_FIELD_INTRO = "intro";

    /** 团队头像字段。 */
    private static final String TEAM_KEY_AVATAR_URL = "avatarUrl";

    /** 团队名称字段。 */
    private static final String TEAM_KEY_TEAM_NAME = "teamName";

    /** 团队简介字段。 */
    private static final String TEAM_KEY_INTRO = "intro";

    /** 隐藏字段在渲染结果中的空值。 */
    private static final String HIDDEN_FIELD_VALUE = "";

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
            JSONObject rendered = componentConfig.toJsonObject();
            applyVisibleFields(rendered);
            return rendered;
        } catch (RuntimeException exception) {
            throw new BusinessException(TEAM_MISMATCH_MESSAGE);
        }
    }

    /**
     * 按展示字段开关清空不应出现在预览页和访客页的团队资料。
     *
     * @param rendered 已脱离配置输入的渲染数据
     */
    private void applyVisibleFields(JSONObject rendered) {
        JSONObject team = rendered.getJSONObject(CONFIG_KEY_TEAM);
        JSONObject visibleFields = rendered.getJSONObject(CONFIG_KEY_VISIBLE_FIELDS);
        hideFieldWhenDisabled(team, visibleFields, VISIBLE_FIELD_AVATAR, TEAM_KEY_AVATAR_URL);
        hideFieldWhenDisabled(team, visibleFields, VISIBLE_FIELD_TEAM_NAME, TEAM_KEY_TEAM_NAME);
        hideFieldWhenDisabled(team, visibleFields, VISIBLE_FIELD_INTRO, TEAM_KEY_INTRO);
    }

    /**
     * 在展示开关关闭时清空对应团队资料字段。
     *
     * @param team 团队资料渲染数据
     * @param visibleFields 展示字段配置
     * @param visibleField 展示开关键
     * @param teamField 团队资料字段键
     */
    private void hideFieldWhenDisabled(
            JSONObject team,
            JSONObject visibleFields,
            String visibleField,
            String teamField
    ) {
        if (!visibleFields.getBooleanValue(visibleField)) {
            team.put(teamField, HIDDEN_FIELD_VALUE);
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
