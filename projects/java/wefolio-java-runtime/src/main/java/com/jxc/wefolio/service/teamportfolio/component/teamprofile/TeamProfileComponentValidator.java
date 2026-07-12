package com.jxc.wefolio.service.teamportfolio.component.teamprofile;

import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.TeamEntityMapper;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * 团队资料组件配置校验器。
 */
@Component
@RequiredArgsConstructor
public class TeamProfileComponentValidator {

    /** 团队正常状态。 */
    private static final String TEAM_STATUS_ACTIVE = "ACTIVE";

    /** 团队配置键。 */
    private static final String CONFIG_KEY_TEAM = "team";

    /** 团队 ID 配置键。 */
    private static final String CONFIG_KEY_TEAM_ID = "teamId";

    /** 团队资料不匹配提示。 */
    private static final String TEAM_MISMATCH_MESSAGE = "团队资料与当前作品集不匹配";

    /** 团队资料不可用提示。 */
    private static final String TEAM_UNAVAILABLE_MESSAGE = "团队资料不存在或不可用";

    /** 团队数据访问器。 */
    private final TeamEntityMapper teamEntityMapper;

    /**
     * 校验团队资料组件并使用当前团队数据生成快照。
     *
     * @param config 原始配置
     * @param context 组件上下文
     * @return 规范化团队资料配置
     */
    public JSONObject normalizeAndValidate(JSONObject config, TeamPortfolioComponentContext context) {
        TeamProfileComponentConfig componentConfig = toComponentConfig(config);
        TeamProfileComponentConfig.TeamSnapshot configuredTeam = componentConfig.getTeam();
        Long configuredTeamId = configuredTeam == null ? null : configuredTeam.getTeamId();
        if (configuredTeamId != null && !configuredTeamId.equals(context.teamId())) {
            throw new BusinessException(TEAM_MISMATCH_MESSAGE);
        }
        TeamEntity team = teamEntityMapper.selectById(context.teamId());
        if (team == null || !TEAM_STATUS_ACTIVE.equals(team.getStatus())) {
            throw new BusinessException(TEAM_UNAVAILABLE_MESSAGE);
        }
        TeamProfileComponentConfig.TeamSnapshot snapshot = new TeamProfileComponentConfig.TeamSnapshot();
        snapshot.setTeamId(team.getId());
        snapshot.setAvatarUrl(team.getAvatarUrl());
        snapshot.setTeamName(team.getName());
        snapshot.setIntro(team.getIntro());
        TeamProfileComponentConfig normalizedConfig = new TeamProfileComponentConfig();
        normalizedConfig.setTeam(snapshot);
        return normalizedConfig.toJsonObject();
    }

    /**
     * 将客户端 JSON 转换为团队资料配置模型。
     *
     * @param config 原始配置
     * @return 团队资料配置模型
     */
    private TeamProfileComponentConfig toComponentConfig(JSONObject config) {
        if (config == null) {
            return new TeamProfileComponentConfig();
        }
        try {
            if (!config.containsKey(CONFIG_KEY_TEAM)) {
                return config.toJavaObject(TeamProfileComponentConfig.class);
            }
            Object rawTeam = config.get(CONFIG_KEY_TEAM);
            if (!(rawTeam instanceof JSONObject team)) {
                throw new BusinessException(TEAM_MISMATCH_MESSAGE);
            }
            Long teamId = parseTeamId(team);
            TeamProfileComponentConfig componentConfig = config.toJavaObject(TeamProfileComponentConfig.class);
            if (componentConfig.getTeam() == null) {
                throw new BusinessException(TEAM_MISMATCH_MESSAGE);
            }
            componentConfig.getTeam().setTeamId(teamId);
            return componentConfig;
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(TEAM_MISMATCH_MESSAGE);
        }
    }

    /**
     * 从原始团队 JSON 严格解析正整数团队 ID。
     *
     * @param team 原始团队 JSON
     * @return 团队 ID
     */
    private Long parseTeamId(JSONObject team) {
        if (!team.containsKey(CONFIG_KEY_TEAM_ID)) {
            return null;
        }
        Object rawTeamId = team.get(CONFIG_KEY_TEAM_ID);
        if (rawTeamId == null) {
            return null;
        }
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
