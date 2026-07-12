package com.jxc.wefolio.service.teamportfolio;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.config.TeamPortfolioProperties;
import com.jxc.wefolio.constant.TeamPortfolioConstants;
import com.jxc.wefolio.dict.JoinStatusDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.TeamRoleDict;
import com.jxc.wefolio.dict.TeamStatusDict;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.TeamEntityMapper;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.message.TeamPortfolioMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 团队作品集的统一访问控制服务。
 */
@Service
@RequiredArgsConstructor
public class TeamPortfolioAccessService {

    /** 成员关系查询只取当前的一条记录。 */
    private static final String QUERY_LIMIT_ONE = "LIMIT 1";

    /** 可维护团队作品集的角色集合。 */
    private static final Set<String> MAINTAINABLE_ROLES = Set.of(
            TeamRoleDict.OWNER.getCode(), TeamRoleDict.MANAGER.getCode());

    /** 团队作品集功能配置。 */
    private final TeamPortfolioProperties teamPortfolioProperties;

    /** 作品集数据访问器。 */
    private final PortfolioEntityMapper portfolioEntityMapper;

    /** 团队数据访问器。 */
    private final TeamEntityMapper teamEntityMapper;

    /** 团队成员数据访问器。 */
    private final TeamMemberEntityMapper teamMemberEntityMapper;

    /**
     * 校验当前用户是否可查看指定团队作品集。
     *
     * @param portfolioId 作品集 ID
     * @param userId 当前用户 ID
     * @return 已校验的团队作品集访问上下文
     */
    public TeamPortfolioAccess requireVisiblePortfolio(long portfolioId, long userId) {
        requireFeatureEnabled();
        return requireVisiblePortfolioInternal(portfolioId, userId);
    }

    /**
     * 校验当前用户是否可维护指定团队作品集。
     *
     * @param portfolioId 作品集 ID
     * @param userId 当前用户 ID
     * @return 已校验的团队作品集访问上下文
     */
    public TeamPortfolioAccess requireMaintainablePortfolio(long portfolioId, long userId) {
        requireFeatureEnabled();
        TeamPortfolioAccess access = requireVisiblePortfolioInternal(portfolioId, userId);
        if (!access.canMaintain()) {
            throw new BusinessException(TeamPortfolioMessage.NO_MAINTAIN_PERMISSION);
        }
        return access;
    }

    /**
     * 校验当前用户在指定团队中是否具备目标角色。
     *
     * @param teamId 团队 ID
     * @param userId 当前用户 ID
     * @param roles 允许访问的角色集合
     * @return 已校验的团队访问上下文
     */
    public TeamPortfolioAccess requireTeamRole(long teamId, long userId, Set<String> roles) {
        requireFeatureEnabled();
        TeamEntity team = requireActiveTeam(teamId, TeamPortfolioMessage.NO_ACCESS);
        TeamMemberEntity membership = requireJoinedMembership(teamId, userId, TeamPortfolioMessage.NO_ACCESS);
        if (roles == null || roles.isEmpty() || !roles.contains(membership.getRole())) {
            throw new BusinessException(TeamPortfolioMessage.NO_ACCESS);
        }
        return access(null, team, membership);
    }

    /**
     * 校验作品集、团队与成员关系的可见性。
     *
     * @param portfolioId 作品集 ID
     * @param userId 当前用户 ID
     * @return 已校验的团队作品集访问上下文
     */
    private TeamPortfolioAccess requireVisiblePortfolioInternal(long portfolioId, long userId) {
        PortfolioEntity portfolio = portfolioEntityMapper.selectById(portfolioId);
        if (!isActiveStandardTeamPortfolio(portfolio)) {
            throw new BusinessException(TeamPortfolioMessage.PORTFOLIO_NOT_FOUND);
        }
        TeamEntity team = requireActiveTeam(portfolio.getOwnerId(), TeamPortfolioMessage.PORTFOLIO_NOT_FOUND);
        TeamMemberEntity membership = requireJoinedMembership(team.getId(), userId,
                TeamPortfolioMessage.PORTFOLIO_NOT_FOUND);
        return access(portfolio, team, membership);
    }

    /**
     * 校验团队作品集功能是否已启用。
     */
    private void requireFeatureEnabled() {
        if (!teamPortfolioProperties.isEnabled()) {
            throw new BusinessException(TeamPortfolioMessage.FEATURE_DISABLED);
        }
    }

    /**
     * 判断作品集是否是当前可访问的标准团队作品集。
     *
     * @param portfolio 作品集实体
     * @return 是否满足身份约束
     */
    private boolean isActiveStandardTeamPortfolio(PortfolioEntity portfolio) {
        return portfolio != null
                && PortfolioOwnerTypeDict.TEAM.getCode().equals(portfolio.getOwnerType())
                && PortfolioTemplateTypeDict.STANDARD.getCode().equals(portfolio.getTemplateType())
                && PortfolioStatusDict.ACTIVE.getCode().equals(portfolio.getStatus())
                && TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1.equals(portfolio.getSchemaVersion());
    }

    /**
     * 查询并校验有效团队。
     *
     * @param teamId 团队 ID
     * @param message 校验失败提示文案
     * @return 有效团队
     */
    private TeamEntity requireActiveTeam(Long teamId, String message) {
        TeamEntity team = teamId == null ? null : teamEntityMapper.selectById(teamId);
        if (team == null || !TeamStatusDict.ACTIVE.getCode().equals(team.getStatus())) {
            throw new BusinessException(message);
        }
        return team;
    }

    /**
     * 查询并校验当前已加入的团队成员关系。
     *
     * @param teamId 团队 ID
     * @param userId 当前用户 ID
     * @param message 校验失败提示文案
     * @return 已加入的成员关系
     */
    private TeamMemberEntity requireJoinedMembership(long teamId, long userId, String message) {
        TeamMemberEntity membership = teamMemberEntityMapper.selectOne(
                Wrappers.lambdaQuery(TeamMemberEntity.class)
                        .eq(TeamMemberEntity::getTeamId, teamId)
                        .eq(TeamMemberEntity::getUserId, userId)
                        .eq(TeamMemberEntity::getJoinStatus, JoinStatusDict.JOINED.getCode())
                        .last(QUERY_LIMIT_ONE)
        );
        if (membership == null || !JoinStatusDict.JOINED.getCode().equals(membership.getJoinStatus())) {
            throw new BusinessException(message);
        }
        return membership;
    }

    /**
     * 组装访问上下文和角色能力。
     *
     * @param portfolio 作品集实体，团队角色校验时为 null
     * @param team 团队实体
     * @param membership 已加入的成员关系
     * @return 团队作品集访问上下文
     */
    private TeamPortfolioAccess access(
            PortfolioEntity portfolio,
            TeamEntity team,
            TeamMemberEntity membership
    ) {
        boolean canMaintain = MAINTAINABLE_ROLES.contains(membership.getRole());
        return new TeamPortfolioAccess(portfolio, team, membership, canMaintain, true);
    }

    /**
     * 团队作品集访问校验后的实体上下文及能力结果。
     *
     * @param portfolio 已校验的作品集，团队角色校验时为 null
     * @param team 已校验的有效团队
     * @param membership 已校验的已加入成员关系
     * @param canMaintain 是否可维护团队作品集
     * @param canShare 是否可分享团队作品集
     */
    public record TeamPortfolioAccess(
            PortfolioEntity portfolio,
            TeamEntity team,
            TeamMemberEntity membership,
            boolean canMaintain,
            boolean canShare
    ) {
    }
}
