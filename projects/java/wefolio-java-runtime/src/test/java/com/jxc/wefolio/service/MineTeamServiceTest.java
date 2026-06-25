package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.JoinStatusDict;
import com.jxc.wefolio.dict.TeamRoleDict;
import com.jxc.wefolio.dict.TeamStatusDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dto.MineTeamCreateRequest;
import com.jxc.wefolio.dto.MineTeamDetailResponse;
import com.jxc.wefolio.dto.MineTeamListResponse;
import com.jxc.wefolio.dto.MineTeamUpdateRequest;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.TeamEntityMapper;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 团队服务测试 — 覆盖我的团队列表、创建、详情和资料维护。
 */
@ExtendWith(MockitoExtension.class)
class MineTeamServiceTest {

    /** 团队 Mapper 模拟 */
    @Mock
    private TeamEntityMapper teamEntityMapper;

    /** 团队成员 Mapper 模拟 */
    @Mock
    private TeamMemberEntityMapper teamMemberEntityMapper;

    /** 用户 Mapper 模拟 */
    @Mock
    private UserEntityMapper userEntityMapper;

    /** COS 服务模拟 */
    @Mock
    private CosService cosService;

    /** 团队注册事务服务模拟 */
    @Mock
    private TeamRegistrationService teamRegistrationService;

    @BeforeEach
    void setUp() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
    }

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    @Test
    void listTeamsReturnsJoinedActiveTeamsWithRoleSummary() {
        TeamMemberEntity ownerMembership = member(21L, 100L, 7L, TeamRoleDict.OWNER, JoinStatusDict.JOINED);
        TeamMemberEntity managerMembership = member(22L, 101L, 7L, TeamRoleDict.MANAGER, JoinStatusDict.JOINED);
        TeamEntity ownerTeam = team(100L, "TM2048", "星曜司仪团", "https://cos.example.com/tm2048.png");
        TeamEntity managerTeam = team(101L, "TM7316", "星河主持团队", "");
        TeamMemberEntity teammate = member(23L, 100L, 8L, TeamRoleDict.MEMBER, JoinStatusDict.JOINED);
        when(teamMemberEntityMapper.selectList(any()))
                .thenReturn(List.of(ownerMembership, managerMembership))
                .thenReturn(List.of(ownerMembership, teammate, managerMembership));
        when(teamEntityMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(ownerTeam, managerTeam));

        MineTeamService service = service();

        MineTeamListResponse response = service.listTeams();

        assertThat(response.getSummary().getJoinedCount()).isEqualTo(2);
        assertThat(response.getSummary().getOwnerCount()).isEqualTo(1);
        assertThat(response.getSummary().getManageableCount()).isEqualTo(2);
        assertThat(response.getSummary().getSummaryText()).isEqualTo("已加入 2 个团队，其中 1 个为拥有者。");
        assertThat(response.getTeams()).hasSize(2);
        MineTeamListResponse.TeamItem first = response.getTeams().get(0);
        assertThat(first.getTeamId()).isEqualTo(100L);
        assertThat(first.getUniqueCode()).isEqualTo("TM2048");
        assertThat(first.getName()).isEqualTo("星曜司仪团");
        assertThat(first.getRoleText()).isEqualTo("拥有者");
        assertThat(first.isCanMaintain()).isTrue();
        assertThat(first.getMemberCount()).isEqualTo(2);
        assertThat(first.getMemberCountText()).isEqualTo("2 位成员");
    }

    @Test
    void createTeamGeneratesTeamCodeInitializesCosAndCreatesOwnerMember() {
        MineTeamCreateRequest request = new MineTeamCreateRequest();
        request.setName(" 星曜司仪团 ");
        request.setIntro(" 高端婚礼主持团队 ");
        when(teamEntityMapper.selectCount(any())).thenReturn(0L);
        when(teamRegistrationService.createTeamWithOwner(
                anyString(), eq(7L), eq("星曜司仪团"), eq("高端婚礼主持团队"), eq("")))
                .thenAnswer(invocation -> {
                    String uniqueCode = invocation.getArgument(0);
                    TeamRegistrationService.TeamCreationResult result =
                            new TeamRegistrationService.TeamCreationResult();
                    result.setTeam(team(100L, uniqueCode, "星曜司仪团", ""));
                    result.getTeam().setIntro("高端婚礼主持团队");
                    result.setOwnerMembership(member(
                            21L, 100L, 7L, TeamRoleDict.OWNER, JoinStatusDict.JOINED));
                    return result;
                });

        MineTeamService service = service();

        MineTeamDetailResponse response = service.createTeam(request);

        InOrder inOrder = inOrder(cosService, teamRegistrationService);
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        inOrder.verify(cosService).initTeamStorage(codeCaptor.capture());
        inOrder.verify(teamRegistrationService).createTeamWithOwner(
                eq(codeCaptor.getValue()), eq(7L), eq("星曜司仪团"), eq("高端婚礼主持团队"), eq(""));
        assertThat(codeCaptor.getValue()).startsWith("TM").hasSize(10);
        assertThat(response.getTeam().getTeamId()).isEqualTo(100L);
        assertThat(response.getTeam().isCanMaintain()).isTrue();
    }

    @Test
    void detailRejectsUsersOutsideTeam() {
        when(teamEntityMapper.selectById(100L)).thenReturn(team(100L, "TM2048", "星曜司仪团", ""));
        when(teamMemberEntityMapper.selectOne(any())).thenReturn(null);

        MineTeamService service = service();

        assertThatThrownBy(() -> service.getTeamDetail(100L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("团队不存在或无访问权限");
    }

    @Test
    void detailReturnsTeamAndMembersForJoinedMember() {
        TeamEntity team = team(100L, "TM2048", "星曜司仪团", "https://cos.example.com/tm2048.png");
        TeamMemberEntity owner = member(21L, 100L, 7L, TeamRoleDict.OWNER, JoinStatusDict.JOINED);
        TeamMemberEntity manager = member(22L, 100L, 8L, TeamRoleDict.MANAGER, JoinStatusDict.JOINED);
        when(teamEntityMapper.selectById(100L)).thenReturn(team);
        when(teamMemberEntityMapper.selectOne(any())).thenReturn(owner);
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(owner, manager));
        when(userEntityMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(
                user(7L, "WF8392", "林安", "婚礼司仪", "https://cos.example.com/u7.png"),
                user(8L, "MU1186", "乔伊", "化妆师", "https://cos.example.com/u8.png")
        ));

        MineTeamService service = service();

        MineTeamDetailResponse response = service.getTeamDetail(100L);

        assertThat(response.getTeam().getUniqueCode()).isEqualTo("TM2048");
        assertThat(response.getTeam().getRoleText()).isEqualTo("拥有者");
        assertThat(response.getMembers()).hasSize(2);
        assertThat(response.getMembers().get(1).getDisplayName()).isEqualTo("乔伊 · 化妆师");
        assertThat(response.getMembers().get(1).getRoleText()).isEqualTo("管理者");
        assertThat(response.getMembers().get(1).getJoinStatusText()).isEqualTo("已加入");
    }

    @Test
    void detailReturnsDisabledUserStatusForInactiveMember() {
        TeamEntity team = team(100L, "TM2048", "星曜司仪团", "https://cos.example.com/tm2048.png");
        TeamMemberEntity owner = member(21L, 100L, 7L, TeamRoleDict.OWNER, JoinStatusDict.JOINED);
        TeamMemberEntity disabledMember = member(22L, 100L, 8L, TeamRoleDict.MEMBER, JoinStatusDict.JOINED);
        UserEntity disabledUser = user(8L, "WF1186", "乔伊", "化妆师", "https://cos.example.com/u8.png");
        disabledUser.setStatus(UserStatusDict.DISABLED.getCode());
        when(teamEntityMapper.selectById(100L)).thenReturn(team);
        when(teamMemberEntityMapper.selectOne(any())).thenReturn(owner);
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(owner, disabledMember));
        when(userEntityMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(
                user(7L, "WF8392", "林安", "婚礼司仪", "https://cos.example.com/u7.png"),
                disabledUser
        ));

        MineTeamService service = service();

        MineTeamDetailResponse response = service.getTeamDetail(100L);
        MineTeamDetailResponse.MemberItem member = response.getMembers().get(1);

        assertThat(member.getDisplayName()).isEqualTo("乔伊 · 化妆师");
        assertThat(member.getUniqueCode()).isEqualTo("WF1186");
        assertThat(member.getUserStatus()).isEqualTo(UserStatusDict.DISABLED.getCode());
        assertThat(member.getUserStatusText()).isEqualTo("已停用");
        assertThat(member.getUserStatusTone()).isEqualTo("muted");
    }

    @Test
    void updateTeamRejectsOrdinaryMember() {
        MineTeamUpdateRequest request = new MineTeamUpdateRequest();
        request.setName("星曜司仪团");
        when(teamEntityMapper.selectById(100L)).thenReturn(team(100L, "TM2048", "星曜司仪团", ""));
        when(teamMemberEntityMapper.selectOne(any()))
                .thenReturn(member(23L, 100L, 7L, TeamRoleDict.MEMBER, JoinStatusDict.JOINED));

        MineTeamService service = service();

        assertThatThrownBy(() -> service.updateTeam(100L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("无团队维护权限");
    }

    @Test
    void updateTeamPersistsTrimmedFieldsForManager() {
        TeamEntity team = team(100L, "TM2048", "星曜司仪团", "");
        MineTeamUpdateRequest request = new MineTeamUpdateRequest();
        request.setName(" 星曜司仪团升级版 ");
        request.setIntro(" 双城服务团队 ");
        request.setAvatarUrl(" https://cos.example.com/new.png ");
        when(teamEntityMapper.selectById(100L)).thenReturn(team);
        when(teamMemberEntityMapper.selectOne(any()))
                .thenReturn(member(22L, 100L, 7L, TeamRoleDict.MANAGER, JoinStatusDict.JOINED));
        when(teamEntityMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        MineTeamService service = service();

        MineTeamDetailResponse response = service.updateTeam(100L, request);

        ArgumentCaptor<Wrapper<TeamEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(teamEntityMapper).update(isNull(), captor.capture());
        String sqlSet = ((UpdateWrapper<TeamEntity>) captor.getValue()).getSqlSet();
        assertThat(sqlSet).contains("name", "intro", "avatar_url", "updated_at");
        assertThat(response.getTeam().getName()).isEqualTo("星曜司仪团升级版");
        assertThat(response.getTeam().getIntro()).isEqualTo("双城服务团队");
        assertThat(response.getTeam().getAvatarUrl()).isEqualTo("https://cos.example.com/new.png");
    }

    @Test
    void updateTeamReturnsRealMemberCountAfterSave() {
        TeamEntity team = team(100L, "TM2048", "星曜司仪团", "");
        MineTeamUpdateRequest request = new MineTeamUpdateRequest();
        request.setName("星曜司仪团升级版");
        TeamMemberEntity owner = member(21L, 100L, 8L, TeamRoleDict.OWNER, JoinStatusDict.JOINED);
        TeamMemberEntity manager = member(22L, 100L, 7L, TeamRoleDict.MANAGER, JoinStatusDict.JOINED);
        TeamMemberEntity member = member(23L, 100L, 9L, TeamRoleDict.MEMBER, JoinStatusDict.JOINED);
        when(teamEntityMapper.selectById(100L)).thenReturn(team);
        when(teamMemberEntityMapper.selectOne(any())).thenReturn(manager);
        when(teamEntityMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(owner, manager, member));
        when(userEntityMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(
                user(7L, "WF8392", "林安", "婚礼司仪", "https://cos.example.com/u7.png"),
                user(8L, "MU1186", "乔伊", "化妆师", "https://cos.example.com/u8.png"),
                user(9L, "WF7712", "阿南", "摄影师", "https://cos.example.com/u9.png")
        ));

        MineTeamService service = service();

        MineTeamDetailResponse response = service.updateTeam(100L, request);

        assertThat(response.getTeam().getMemberCount()).isEqualTo(3);
        assertThat(response.getTeam().getMemberCountText()).isEqualTo("3 位成员");
        assertThat(response.getMembers()).hasSize(3);
    }

    @Test
    void updateTeamAllowsClearingOptionalIntroAndAvatar() {
        TeamEntity team = team(100L, "TM2048", "星曜司仪团", "https://cos.example.com/old.png");
        MineTeamUpdateRequest request = new MineTeamUpdateRequest();
        request.setName("星曜司仪团");
        request.setIntro(" ");
        request.setAvatarUrl(" ");
        when(teamEntityMapper.selectById(100L)).thenReturn(team);
        when(teamMemberEntityMapper.selectOne(any()))
                .thenReturn(member(21L, 100L, 7L, TeamRoleDict.OWNER, JoinStatusDict.JOINED));
        when(teamEntityMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        MineTeamService service = service();

        MineTeamDetailResponse response = service.updateTeam(100L, request);

        assertThat(response.getTeam().getIntro()).isEmpty();
        assertThat(response.getTeam().getAvatarUrl()).isEmpty();
    }

    /**
     * 构造团队实体。
     *
     * @param id 团队 ID
     * @param uniqueCode 团队唯一码
     * @param name 团队名称
     * @param avatarUrl 团队图标地址
     * @return 团队实体
     */
    private TeamEntity team(Long id, String uniqueCode, String name, String avatarUrl) {
        TeamEntity team = new TeamEntity();
        team.setId(id);
        team.setUniqueCode(uniqueCode);
        team.setName(name);
        team.setAvatarUrl(avatarUrl);
        team.setIntro("团队简介");
        team.setCity("");
        team.setOwnerUserId(7L);
        team.setStatus(TeamStatusDict.ACTIVE.getCode());
        team.setUpdatedAt(LocalDateTime.of(2026, 6, 18, 10, 0));
        return team;
    }

    /**
     * 构造成员关系实体。
     *
     * @param id 成员关系 ID
     * @param teamId 团队 ID
     * @param userId 用户 ID
     * @param role 角色
     * @param status 加入状态
     * @return 成员关系实体
     */
    private TeamMemberEntity member(Long id, Long teamId, Long userId, TeamRoleDict role, JoinStatusDict status) {
        TeamMemberEntity member = new TeamMemberEntity();
        member.setId(id);
        member.setTeamId(teamId);
        member.setUserId(userId);
        member.setRole(role.getCode());
        member.setJoinStatus(status.getCode());
        member.setProfession("");
        member.setAllowPortfolio(0);
        member.setAllowProfile(0);
        member.setAllowWorks(0);
        member.setJoinedAt(LocalDateTime.of(2026, 6, 1, 9, 30));
        return member;
    }

    /**
     * 构造用户实体。
     *
     * @param id 用户 ID
     * @param uniqueCode 个人唯一码
     * @param nickname 昵称
     * @param profession 职业身份
     * @param avatarUrl 头像地址
     * @return 用户实体
     */
    private UserEntity user(Long id, String uniqueCode, String nickname, String profession, String avatarUrl) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setUniqueCode(uniqueCode);
        user.setNickname(nickname);
        user.setProfession(profession);
        user.setAvatarUrl(avatarUrl);
        user.setStatus(UserStatusDict.ACTIVE.getCode());
        return user;
    }

    /**
     * 构造被测团队服务。
     *
     * @return 团队服务
     */
    private MineTeamService service() {
        return new MineTeamService(
                teamEntityMapper,
                teamMemberEntityMapper,
                userEntityMapper,
                cosService,
                teamRegistrationService
        );
    }
}
