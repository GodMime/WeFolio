package com.jxc.wefolio.service.teamportfolio.component.teamprofile;

import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.TeamEntityMapper;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAssetService;
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

    /** 团队名称必填提示。 */
    private static final String TEAM_NAME_REQUIRED_MESSAGE = "请填写团队名称";

    /** 团队名称超长提示。 */
    private static final String TEAM_NAME_TOO_LONG_MESSAGE = "团队名称不能超过100字";

    /** 团队简介超长提示。 */
    private static final String TEAM_INTRO_TOO_LONG_MESSAGE = "团队简介不能超过1000字";

    /** 团队名称最大长度。 */
    private static final int TEAM_NAME_MAX_LENGTH = 100;

    /** 团队简介最大长度。 */
    private static final int TEAM_INTRO_MAX_LENGTH = 1000;

    /** 团队数据访问器。 */
    private final TeamEntityMapper teamEntityMapper;

    /** 团队作品集素材服务。 */
    private final TeamPortfolioAssetService teamPortfolioAssetService;

    /**
     * 校验团队资料组件并保留作品集私有快照。
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
        TeamProfileComponentConfig.TeamSnapshot snapshot = hasPortfolioSnapshot(configuredTeam)
                ? configuredTeam : snapshotFromTeam(team);
        snapshot.setTeamId(team.getId());
        normalizeAndValidateSnapshot(snapshot);
        validateAvatar(snapshot.getAvatarUrl(), team, context);
        TeamProfileComponentConfig normalizedConfig = new TeamProfileComponentConfig();
        normalizedConfig.setTeam(snapshot);
        normalizedConfig.setVisibleFields(TeamProfileComponentConfig.normalizeVisibleFields(
                componentConfig.getVisibleFields()));
        return normalizedConfig.toJsonObject();
    }

    /**
     * 判断客户端是否提交了作品集私有展示快照。
     *
     * @param snapshot 客户端快照
     * @return 是否包含展示字段
     */
    private boolean hasPortfolioSnapshot(TeamProfileComponentConfig.TeamSnapshot snapshot) {
        return snapshot != null && snapshot.getTeamId() != null
                && (snapshot.getAvatarUrl() != null
                || snapshot.getTeamName() != null
                || snapshot.getIntro() != null);
    }

    /**
     * 从当前团队记录构建历史空配置的兼容快照。
     *
     * @param team 当前团队记录
     * @return 完整团队资料快照
     */
    private TeamProfileComponentConfig.TeamSnapshot snapshotFromTeam(TeamEntity team) {
        TeamProfileComponentConfig.TeamSnapshot snapshot = new TeamProfileComponentConfig.TeamSnapshot();
        snapshot.setTeamId(team.getId());
        snapshot.setAvatarUrl(team.getAvatarUrl());
        snapshot.setTeamName(team.getName());
        snapshot.setIntro(team.getIntro());
        return snapshot;
    }

    /**
     * 规范化并校验作品集内的团队展示字段。
     *
     * @param snapshot 团队资料快照
     */
    private void normalizeAndValidateSnapshot(TeamProfileComponentConfig.TeamSnapshot snapshot) {
        String avatarUrl = normalizeNullableText(snapshot.getAvatarUrl());
        String teamName = normalizeText(snapshot.getTeamName());
        String intro = normalizeNullableText(snapshot.getIntro());
        if (teamName.isEmpty()) {
            throw new BusinessException(TEAM_NAME_REQUIRED_MESSAGE);
        }
        if (teamName.length() > TEAM_NAME_MAX_LENGTH) {
            throw new BusinessException(TEAM_NAME_TOO_LONG_MESSAGE);
        }
        if (intro != null && intro.length() > TEAM_INTRO_MAX_LENGTH) {
            throw new BusinessException(TEAM_INTRO_TOO_LONG_MESSAGE);
        }
        snapshot.setAvatarUrl(avatarUrl);
        snapshot.setTeamName(teamName);
        snapshot.setIntro(intro);
    }

    /**
     * 将可空文本规范化为去除首尾空白的字符串。
     *
     * @param value 原始文本
     * @return 规范化文本
     */
    private String normalizeText(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * 规范化可空展示文本并保留显式空值。
     *
     * @param value 原始文本
     * @return 去除首尾空白的文本或空值
     */
    private String normalizeNullableText(String value) {
        return value == null ? null : value.strip();
    }

    /**
     * 校验团队资料头像只能复用当前团队头像或使用当前作品集素材。
     *
     * @param avatarUrl 规范化后的头像地址
     * @param team 当前团队记录
     * @param context 组件上下文
     */
    private void validateAvatar(
            String avatarUrl,
            TeamEntity team,
            TeamPortfolioComponentContext context
    ) {
        if (avatarUrl == null || avatarUrl.isEmpty()
                || avatarUrl.equals(normalizeText(team.getAvatarUrl()))) {
            return;
        }
        teamPortfolioAssetService.validateUploadedImageUrl(
                context.teamId(), context.portfolioId(), avatarUrl);
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
