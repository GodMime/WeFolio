package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 访问记录团队范围服务测试 — 覆盖已加入团队范围与数据访问失败边界。
 */
@ExtendWith(MockitoExtension.class)
class MineVisitScopeServiceTest {

    @Mock
    private TeamMemberEntityMapper teamMemberEntityMapper;

    /** 初始化 Lambda 查询所需的团队成员表元数据。 */
    @BeforeAll
    static void initializeTableMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(configuration, "mine-visit-scope-team-member"),
                TeamMemberEntity.class);
    }

    /** 已加入的拥有者、管理者和成员均应进入读取范围，且仅前两者拥有管理权限。 */
    @Test
    void resolveReadScopeShouldKeepEveryJoinedRole() {
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(
                membership(201L, "OWNER", "JOINED"),
                membership(202L, "MANAGER", "JOINED"),
                membership(203L, "MEMBER", "JOINED"),
                membership(204L, "OWNER", "REJECTED")));

        MineVisitScopeService.Scope scope = service().resolveReadScope(7L);

        assertThat(scope.complete()).isTrue();
        assertThat(scope.reason()).isEmpty();
        assertThat(scope.teamIds()).containsExactly(201L, 202L, 203L);
        assertThat(scope.canManageTeam(201L)).isTrue();
        assertThat(scope.canManageTeam(203L)).isFalse();

        ArgumentCaptor<LambdaQueryWrapper<TeamMemberEntity>> queryCaptor = ArgumentCaptor.captor();
        verify(teamMemberEntityMapper).selectList(queryCaptor.capture());
        LambdaQueryWrapper<TeamMemberEntity> query = queryCaptor.getValue();
        String sql = query.getSqlSegment().replaceAll("\\s+", " ");
        assertThat(sql).contains(
                "user_id = #{ew.paramNameValuePairs.MPGENVAL1}",
                "join_status = #{ew.paramNameValuePairs.MPGENVAL2}");
        assertThat(query.getParamNameValuePairs())
                .containsEntry("MPGENVAL1", 7L)
                .containsEntry("MPGENVAL2", "JOINED");
    }

    /** 读取范围在数据访问暂时不可用时应仅降级到个人范围。 */
    @Test
    void resolveReadScopeShouldDegradeOnlyDataAccessFailure() {
        when(teamMemberEntityMapper.selectList(any()))
                .thenThrow(new DataAccessResourceFailureException("temporary"));

        MineVisitScopeService.Scope scope = service().resolveReadScope(7L);

        assertThat(scope.complete()).isFalse();
        assertThat(scope.reason()).isEqualTo("TEAM_SCOPE_UNAVAILABLE");
        assertThat(scope.teamIds()).isEmpty();
    }

    /** 读取范围遇到非数据访问异常时必须原样传播，避免把程序错误伪装成降级。 */
    @Test
    void resolveReadScopeShouldPropagateNonDataAccessFailure() {
        IllegalStateException failure = new IllegalStateException("unexpected bug");
        when(teamMemberEntityMapper.selectList(any())).thenThrow(failure);

        assertThatThrownBy(() -> service().resolveReadScope(7L)).isSameAs(failure);
    }

    /** 强制范围查询在数据访问失败时必须传播原始异常并拒绝继续处理。 */
    @Test
    void requireScopeShouldFailClosed() {
        when(teamMemberEntityMapper.selectList(any()))
                .thenThrow(new DataAccessResourceFailureException("temporary"));

        assertThatThrownBy(() -> service().requireScope(7L))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    /** Scope 直接构造时应复制传入映射，避免后续外部修改扩大读取范围。 */
    @Test
    void scopeConstructorShouldDefensivelyCopyTeamRoles() {
        Map<Long, String> mutableRoles = new HashMap<>();
        mutableRoles.put(201L, "OWNER");

        MineVisitScopeService.Scope scope = new MineVisitScopeService.Scope(mutableRoles, true, "");
        mutableRoles.put(202L, "MEMBER");

        assertThat(scope.teamRoles()).containsExactlyEntriesOf(Map.of(201L, "OWNER"));
        assertThat(scope.teamIds()).containsExactly(201L);
    }

    /** 构造团队成员测试数据。 */
    private TeamMemberEntity membership(Long teamId, String role, String joinStatus) {
        TeamMemberEntity membership = new TeamMemberEntity();
        membership.setTeamId(teamId);
        membership.setUserId(7L);
        membership.setRole(role);
        membership.setJoinStatus(joinStatus);
        return membership;
    }

    /** 创建待测服务实例。 */
    private MineVisitScopeService service() {
        return new MineVisitScopeService(teamMemberEntityMapper);
    }
}
