package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.JoinStatusDict;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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

    @Test
    void createTeamWithOwnerCreatesActiveTeamAndOwnerMembership() {
        doAnswer(invocation -> {
            TeamEntity team = invocation.getArgument(0);
            team.setId(100L);
            return 1;
        }).when(teamEntityMapper).insert(any(TeamEntity.class));
        when(teamMemberEntityMapper.insert(any(TeamMemberEntity.class))).thenReturn(1);
        TeamRegistrationService service = new TeamRegistrationService(teamEntityMapper, teamMemberEntityMapper);

        TeamRegistrationService.TeamCreationResult result = service.createTeamWithOwner(
                "TM2048ABCD", 7L, "星曜司仪团", "高端婚礼主持团队", "https://cos.example.com/team.png");

        ArgumentCaptor<TeamEntity> teamCaptor = ArgumentCaptor.forClass(TeamEntity.class);
        ArgumentCaptor<TeamMemberEntity> memberCaptor = ArgumentCaptor.forClass(TeamMemberEntity.class);
        verify(teamEntityMapper).insert(teamCaptor.capture());
        verify(teamMemberEntityMapper).insert(memberCaptor.capture());
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
    }

    @Test
    void createTeamWithOwnerRejectsMissingGeneratedTeamId() {
        when(teamEntityMapper.insert(any(TeamEntity.class))).thenReturn(1);
        TeamRegistrationService service = new TeamRegistrationService(teamEntityMapper, teamMemberEntityMapper);

        assertThatThrownBy(() -> service.createTeamWithOwner("TM2048ABCD", 7L, "星曜司仪团", "", ""))
                .isInstanceOf(BusinessException.class)
                .hasMessage("团队创建失败，请重试");
    }
}
