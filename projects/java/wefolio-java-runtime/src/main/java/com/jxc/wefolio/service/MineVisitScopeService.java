package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.dict.JoinStatusDict;
import com.jxc.wefolio.dict.TeamRoleDict;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 我的访问记录范围服务 — 统一解析维护者可读取及可管理的已加入团队范围。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MineVisitScopeService {

    /** 团队范围不可用时的降级原因。 */
    public static final String TEAM_SCOPE_UNAVAILABLE = "TEAM_SCOPE_UNAVAILABLE";

    /** 可管理团队访问记录的成员角色。 */
    private static final Set<String> MANAGEABLE_ROLES = Set.of(
            TeamRoleDict.OWNER.getCode(), TeamRoleDict.MANAGER.getCode());

    /** 团队成员数据访问对象。 */
    private final TeamMemberEntityMapper teamMemberEntityMapper;

    /**
     * 解析可读取的范围；数据访问失败时降级为仅个人范围。
     *
     * @param userId 当前维护者用户 ID
     * @return 包含已加入团队的读取范围，或降级范围
     */
    public Scope resolveReadScope(Long userId) {
        try {
            return Scope.complete(selectJoinedTeamRoles(userId));
        } catch (DataAccessException exception) {
            log.warn("访问记录团队范围查询失败，降级为个人范围: userId={}", userId, exception);
            return Scope.degraded();
        }
    }

    /**
     * 强制解析完整团队范围；数据访问失败时向调用方传播异常。
     *
     * @param userId 当前维护者用户 ID
     * @return 包含已加入团队的完整读取范围
     */
    public Scope requireScope(Long userId) {
        return Scope.complete(selectJoinedTeamRoles(userId));
    }

    /**
     * 查询并归集用户已加入团队的角色；重复团队保留查询结果中的首个角色。
     *
     * @param userId 当前维护者用户 ID
     * @return 以团队 ID 为键的成员角色映射
     */
    private Map<Long, String> selectJoinedTeamRoles(Long userId) {
        List<TeamMemberEntity> memberships = teamMemberEntityMapper.selectList(
                Wrappers.lambdaQuery(TeamMemberEntity.class)
                        .eq(TeamMemberEntity::getUserId, userId)
                        .eq(TeamMemberEntity::getJoinStatus, JoinStatusDict.JOINED.getCode()));
        if (memberships == null || memberships.isEmpty()) {
            return Map.of();
        }
        return memberships.stream()
                .filter(Objects::nonNull)
                .filter(value -> value.getTeamId() != null)
                .filter(value -> JoinStatusDict.JOINED.getCode().equals(value.getJoinStatus()))
                .collect(Collectors.toMap(
                        TeamMemberEntity::getTeamId,
                        value -> value.getRole() == null ? "" : value.getRole(),
                        (first, ignored) -> first));
    }

    /**
     * 访问记录团队范围值对象。
     *
     * @param teamRoles 团队 ID 与成员角色的不可变映射
     * @param complete 是否成功获得完整团队范围
     * @param reason 降级原因；完整范围为空字符串
     */
    public record Scope(Map<Long, String> teamRoles, boolean complete, String reason) {

        /**
         * 复制角色映射，确保通过公开构造器创建的范围同样不可变。
         */
        public Scope {
            teamRoles = Map.copyOf(teamRoles);
        }

        /**
         * 创建完整团队范围。
         *
         * @param roles 团队 ID 与成员角色映射
         * @return 不可变的完整范围
         */
        public static Scope complete(Map<Long, String> roles) {
            return new Scope(Map.copyOf(roles), true, "");
        }

        /**
         * 创建仅个人范围的降级结果。
         *
         * @return 不可变的降级范围
         */
        public static Scope degraded() {
            return new Scope(Map.of(), false, TEAM_SCOPE_UNAVAILABLE);
        }

        /**
         * 按团队 ID 升序返回可读取团队列表。
         *
         * @return 已排序的团队 ID 列表
         */
        public List<Long> teamIds() {
            return teamRoles.keySet().stream().sorted().toList();
        }

        /**
         * 判断团队是否处于可读取范围。
         *
         * @param teamId 团队 ID
         * @return 已加入该团队时为 true
         */
        public boolean canReadTeam(Long teamId) {
            return teamId != null && teamRoles.containsKey(teamId);
        }

        /**
         * 判断当前成员角色是否可管理该团队。
         *
         * @param teamId 团队 ID
         * @return 拥有者或管理者时为 true
         */
        public boolean canManageTeam(Long teamId) {
            return teamId != null && MANAGEABLE_ROLES.contains(teamRoles.get(teamId));
        }
    }
}
