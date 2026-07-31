package com.jxc.wefolio.service.teamportfolio.component.singlework;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.dict.JoinStatusDict;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.message.TeamPortfolioMessage;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/**
 * 团队单个作品组件配置校验器。
 */
@Component
@RequiredArgsConstructor
public class TeamSingleWorkComponentValidator {

    /** 允许作品授权标识。 */
    private static final int ALLOW_WORKS = 1;

    /** 团队成员 Mapper。 */
    private final TeamMemberEntityMapper teamMemberEntityMapper;

    /** 用户 Mapper。 */
    private final UserEntityMapper userEntityMapper;

    /** 作品 Mapper。 */
    private final WorkEntityMapper workEntityMapper;

    /**
     * 规范化并校验单个作品配置。
     *
     * @param config 原始配置
     * @param context 组件上下文
     * @return 严格白名单配置
     */
    public JSONObject normalizeAndValidate(JSONObject config, TeamPortfolioComponentContext context) {
        TeamSingleWorkComponentConfig normalized = parseConfig(config);
        validateResources(normalized, context);
        return normalized.toJsonObject();
    }

    /**
     * 严格解析配置字段。
     */
    private TeamSingleWorkComponentConfig parseConfig(JSONObject config) {
        if (config == null
                || !config.containsKey("memberUserId")
                || !config.containsKey("workId")) {
            throw new BusinessException(TeamPortfolioMessage.SINGLE_WORK_CONFIG_INVALID);
        }
        TeamSingleWorkComponentConfig normalized = new TeamSingleWorkComponentConfig();
        normalized.setMemberUserId(parsePositiveLong(config.get("memberUserId")));
        normalized.setWorkId(parsePositiveLong(config.get("workId")));
        normalized.setShowTitle(parseBoolean(config.get("showTitle"), true));
        normalized.setShowDescription(parseBoolean(config.get("showDescription"), false));
        return normalized;
    }

    /**
     * 校验成员授权、账号状态和作品状态。
     */
    private void validateResources(
            TeamSingleWorkComponentConfig config,
            TeamPortfolioComponentContext context
    ) {
        List<TeamMemberEntity> memberships = teamMemberEntityMapper.selectList(
                Wrappers.lambdaQuery(TeamMemberEntity.class)
                        .eq(TeamMemberEntity::getTeamId, context.teamId())
                        .eq(TeamMemberEntity::getUserId, config.getMemberUserId())
                        .eq(TeamMemberEntity::getJoinStatus, JoinStatusDict.JOINED.getCode())
                        .eq(TeamMemberEntity::getAllowWorks, ALLOW_WORKS)
        );
        if (memberships == null || memberships.isEmpty()) {
            throw new BusinessException(TeamPortfolioMessage.SINGLE_WORK_UNAVAILABLE);
        }
        List<UserEntity> users = userEntityMapper.selectBatchIds(List.of(config.getMemberUserId()));
        boolean activeUser = users != null && users.stream().anyMatch(user ->
                user != null
                        && config.getMemberUserId().equals(user.getId())
                        && UserStatusDict.ACTIVE.getCode().equals(user.getStatus()));
        if (!activeUser) {
            throw new BusinessException(TeamPortfolioMessage.SINGLE_WORK_UNAVAILABLE);
        }
        List<WorkEntity> works = workEntityMapper.selectList(
                Wrappers.lambdaQuery(WorkEntity.class).eq(WorkEntity::getId, config.getWorkId())
        );
        WorkEntity work = works == null ? null : works.stream()
                .filter(item -> item != null && config.getWorkId().equals(item.getId()))
                .findFirst()
                .orElse(null);
        if (!isUsableWork(work, config.getMemberUserId())) {
            throw new BusinessException(TeamPortfolioMessage.SINGLE_WORK_UNAVAILABLE);
        }
    }

    /**
     * 判断作品是否符合单个作品展示要求。
     */
    private boolean isUsableWork(WorkEntity work, Long memberUserId) {
        return work != null
                && memberUserId.equals(work.getUserId())
                && WorkStatusDict.ACTIVE.getCode().equals(work.getStatus())
                && WorkAuditStatusDict.PASSED.getCode().equals(work.getAuditStatus())
                && (MediaTypeDict.IMAGE.getCode().equals(work.getMediaType())
                || MediaTypeDict.VIDEO.getCode().equals(work.getMediaType())
                || MediaTypeDict.ANIMATION.getCode().equals(work.getMediaType()));
    }

    /**
     * 严格解析正整数。
     */
    private Long parsePositiveLong(Object rawValue) {
        long value;
        try {
            if (rawValue instanceof Byte number) {
                value = number;
            } else if (rawValue instanceof Short number) {
                value = number;
            } else if (rawValue instanceof Integer number) {
                value = number;
            } else if (rawValue instanceof Long number) {
                value = number;
            } else if (rawValue instanceof BigDecimal number) {
                value = number.longValueExact();
            } else if (rawValue instanceof BigInteger number) {
                value = number.longValueExact();
            } else {
                throw new BusinessException(TeamPortfolioMessage.SINGLE_WORK_CONFIG_INVALID);
            }
        } catch (ArithmeticException exception) {
            throw new BusinessException(TeamPortfolioMessage.SINGLE_WORK_CONFIG_INVALID);
        }
        if (value <= 0) {
            throw new BusinessException(TeamPortfolioMessage.SINGLE_WORK_CONFIG_INVALID);
        }
        return value;
    }

    /**
     * 显式布尔值原样保留，缺省或非法值使用默认值。
     */
    private Boolean parseBoolean(Object value, boolean defaultValue) {
        return value instanceof Boolean booleanValue ? booleanValue : defaultValue;
    }
}
