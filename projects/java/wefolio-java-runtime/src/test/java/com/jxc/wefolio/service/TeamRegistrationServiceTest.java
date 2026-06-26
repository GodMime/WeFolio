package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.JoinStatusDict;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import com.jxc.wefolio.dict.TeamRoleDict;
import com.jxc.wefolio.dict.TeamStatusDict;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.TeamEntityMapper;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 团队注册服务测试 — 覆盖团队主表和拥有者成员关系的事务写入。
 */
@ExtendWith(MockitoExtension.class)
class TeamRegistrationServiceTest {

    /** 团队 Mapper 模拟 */
    @Mock
    private TeamEntityMapper teamEntityMapper;

    /** 团队成员 Mapper 模拟 */
    @Mock
    private TeamMemberEntityMapper teamMemberEntityMapper;

    /** 积分服务模拟 */
    @Mock
    private PointService pointService;

    @Test
    void createTeamWithOwnerCreatesActiveTeamAndOwnerMembership() {
        doAnswer(invocation -> {
            TeamEntity team = invocation.getArgument(0);
            team.setId(100L);
            return 1;
        }).when(teamEntityMapper).insert(any(TeamEntity.class));
        when(teamMemberEntityMapper.insert(any(TeamMemberEntity.class))).thenReturn(1);
        TeamRegistrationService service = new TeamRegistrationService(teamEntityMapper, teamMemberEntityMapper, pointService);

        TeamRegistrationService.TeamCreationResult result = service.createTeamWithOwner(
                "TM2048ABCD", 7L, "星曜司仪团", "高端婚礼主持团队", "https://cos.example.com/team.png");

        ArgumentCaptor<TeamEntity> teamCaptor = ArgumentCaptor.forClass(TeamEntity.class);
        ArgumentCaptor<TeamMemberEntity> memberCaptor = ArgumentCaptor.forClass(TeamMemberEntity.class);
        verify(teamEntityMapper).insert(teamCaptor.capture());
        verify(teamMemberEntityMapper).insert(memberCaptor.capture());
        InOrder inOrder = inOrder(pointService, teamEntityMapper, teamMemberEntityMapper);
        inOrder.verify(pointService).assertCanConsume(7L, PointSceneCodeDict.CREATE_TEAM.getCode(), 1);
        inOrder.verify(teamEntityMapper).insert(any(TeamEntity.class));
        inOrder.verify(teamMemberEntityMapper).insert(any(TeamMemberEntity.class));
        TeamEntity team = teamCaptor.getValue();
        TeamMemberEntity owner = memberCaptor.getValue();
        assertThat(team.getUniqueCode()).isEqualTo("TM2048ABCD");
        assertThat(team.getName()).isEqualTo("星曜司仪团");
        assertThat(team.getIntro()).isEqualTo("高端婚礼主持团队");
        assertThat(team.getAvatarUrl()).isEqualTo("https://cos.example.com/team.png");
        assertThat(team.getOwnerUserId()).isEqualTo(7L);
        assertThat(team.getStatus()).isEqualTo(TeamStatusDict.ACTIVE.getCode());
        assertThat(owner.getTeamId()).isEqualTo(100L);
        assertThat(owner.getUserId()).isEqualTo(7L);
        assertThat(owner.getRole()).isEqualTo(TeamRoleDict.OWNER.getCode());
        assertThat(owner.getJoinStatus()).isEqualTo(JoinStatusDict.JOINED.getCode());
        assertThat(result.getTeam()).isSameAs(team);
        assertThat(result.getOwnerMembership()).isSameAs(owner);
        verify(pointService).consume(
                7L,
                PointSceneCodeDict.CREATE_TEAM.getCode(),
                "TEAM",
                "100",
                1,
                "CREATE_TEAM:100",
                "新建团队扣除积分"
        );
    }

    @Test
    void createTeamWithOwnerRejectsInsufficientPointsBeforeInsert() {
        doThrow(new BusinessException("积分余额不足，请充值后再试"))
                .when(pointService).assertCanConsume(7L, PointSceneCodeDict.CREATE_TEAM.getCode(), 1);
        TeamRegistrationService service = new TeamRegistrationService(teamEntityMapper, teamMemberEntityMapper, pointService);

        assertThatThrownBy(() -> service.createTeamWithOwner(
                "TM2048ABCD", 7L, "星曜司仪团", "高端婚礼主持团队", ""))
                .isInstanceOf(BusinessException.class)
                .hasMessage("积分余额不足，请充值后再试");
        verify(teamEntityMapper, never()).insert(any(TeamEntity.class));
        verify(teamMemberEntityMapper, never()).insert(any(TeamMemberEntity.class));
        verify(pointService, never()).consume(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void createTeamWithOwnerRejectsMissingGeneratedTeamId() {
        when(teamEntityMapper.insert(any(TeamEntity.class))).thenReturn(1);
        TeamRegistrationService service = new TeamRegistrationService(teamEntityMapper, teamMemberEntityMapper, pointService);

        assertThatThrownBy(() -> service.createTeamWithOwner("TM2048ABCD", 7L, "星曜司仪团", "", ""))
                .isInstanceOf(BusinessException.class)
                .hasMessage("团队创建失败，请重试");
    }
}
