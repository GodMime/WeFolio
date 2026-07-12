package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.jxc.wefolio.common.UniqueCodeGenerator;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.JoinStatusDict;
import com.jxc.wefolio.dict.MessageActionTypeDict;
import com.jxc.wefolio.dict.MessageCategoryDict;
import com.jxc.wefolio.dict.MessageReadStatusDict;
import com.jxc.wefolio.dict.MessageTypeDict;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import com.jxc.wefolio.dict.TeamRoleDict;
import com.jxc.wefolio.dict.TeamStatusDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dto.MineTeamInvitationResponse;
import com.jxc.wefolio.dto.MineTeamMemberCandidateResponse;
import com.jxc.wefolio.dto.MineTeamMemberInviteRequest;
import com.jxc.wefolio.dto.MineTeamCreateRequest;
import com.jxc.wefolio.dto.MineTeamDetailResponse;
import com.jxc.wefolio.dto.MineTeamListResponse;
import com.jxc.wefolio.dto.MineTeamUpdateRequest;
import com.jxc.wefolio.entity.SystemMessageEntity;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.SystemMessageEntityMapper;
import com.jxc.wefolio.mapper.TeamEntityMapper;
import com.jxc.wefolio.mapper.TeamMemberChangeRequestEntityMapper;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.PortfolioReferenceEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioReferenceGuardService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
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

    /** 系统消息 Mapper 模拟 */
    @Mock
    private SystemMessageEntityMapper systemMessageEntityMapper;

    /** 成员信息变更请求 Mapper 模拟 */
    @Mock
    private TeamMemberChangeRequestEntityMapper teamMemberChangeRequestEntityMapper;

    /** 成员信息变更事务辅助服务模拟 */
    @Mock
    private TeamMemberChangeRequestTransactionService teamMemberChangeRequestTransactionService;

    /** 作品集 Mapper 模拟 */
    @Mock
    private PortfolioEntityMapper portfolioEntityMapper;

    /** 作品集引用 Mapper 模拟 */
    @Mock
    private PortfolioReferenceEntityMapper portfolioReferenceEntityMapper;

    /** 作品 Mapper 模拟 */
    @Mock
    private WorkEntityMapper workEntityMapper;

    /** COS 服务模拟 */
    @Mock
    private CosService cosService;

    /** 团队注册事务服务模拟 */
    @Mock
    private TeamRegistrationService teamRegistrationService;

    /** 积分服务模拟 */
    @Mock
    private PointService pointService;

    /** 唯一码生成器模拟 */
    @Mock
    private UniqueCodeGenerator uniqueCodeGenerator;

    /** 团队作品集引用保护服务模拟 */
    @Mock
    private TeamPortfolioReferenceGuardService teamPortfolioReferenceGuardService;

    @BeforeEach
    void setUp() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
    }

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    /**
     * 邀请成员需要事务包裹成员关系和站内消息两次写入。
     *
     * @throws NoSuchMethodException 方法不存在时抛出
     */
    @Test
    void inviteMemberShouldRollbackMemberAndMessageWritesTogether() throws NoSuchMethodException {
        Method method = MineTeamService.class.getMethod("inviteMember", Long.class, MineTeamMemberInviteRequest.class);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.rollbackFor()).contains(Exception.class);
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
    void createTeamPreChecksPointsInitializesCosAndCreatesOwnerMember() {
        MineTeamCreateRequest request = new MineTeamCreateRequest();
        request.setName(" 星曜司仪团 ");
        request.setIntro(" 高端婚礼主持团队 ");
        when(uniqueCodeGenerator.generate(eq(UniqueCodeGenerator.TEAM_PREFIX), any())).thenReturn("TMTEST0001");
        when(teamRegistrationService.createTeamWithOwner(
                eq("TMTEST0001"), eq(7L), eq("星曜司仪团"), eq("高端婚礼主持团队"), eq("")))
                .thenAnswer(invocation -> {
                    TeamRegistrationService.TeamCreationResult result =
                            new TeamRegistrationService.TeamCreationResult();
                    result.setTeam(team(100L, "TMTEST0001", "星曜司仪团", ""));
                    result.getTeam().setIntro("高端婚礼主持团队");
                    result.setOwnerMembership(member(
                            21L, 100L, 7L, TeamRoleDict.OWNER, JoinStatusDict.JOINED));
                    return result;
                });

        MineTeamService service = service();

        MineTeamDetailResponse response = service.createTeam(request);

        InOrder inOrder = inOrder(pointService, cosService, teamRegistrationService);
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        inOrder.verify(pointService).assertCanConsume(7L, PointSceneCodeDict.CREATE_TEAM.getCode(), 1);
        inOrder.verify(cosService).initTeamStorage(codeCaptor.capture());
        inOrder.verify(teamRegistrationService).createTeamWithOwner(
                eq(codeCaptor.getValue()), eq(7L), eq("星曜司仪团"), eq("高端婚礼主持团队"), eq(""));
        assertThat(codeCaptor.getValue()).startsWith("TM").hasSize(10);
        assertThat(response.getTeam().getTeamId()).isEqualTo(100L);
        assertThat(response.getTeam().isCanMaintain()).isTrue();
    }

    @Test
    void createTeamRejectsInsufficientPointsBeforeInitializingCos() {
        MineTeamCreateRequest request = new MineTeamCreateRequest();
        request.setName("星曜司仪团");
        doThrow(new BusinessException("积分余额不足，请充值后再试"))
                .when(pointService).assertCanConsume(7L, PointSceneCodeDict.CREATE_TEAM.getCode(), 1);

        MineTeamService service = service();

        assertThatThrownBy(() -> service.createTeam(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("积分余额不足，请充值后再试");
        verify(cosService, never()).initTeamStorage(anyString());
        verify(teamRegistrationService, never()).createTeamWithOwner(
                anyString(), eq(7L), anyString(), anyString(), anyString());
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
        when(teamEntityMapper.update(any(TeamEntity.class), any(Wrapper.class))).thenReturn(1);

        MineTeamService service = service();

        MineTeamDetailResponse response = service.updateTeam(100L, request);

        ArgumentCaptor<TeamEntity> entityCaptor = ArgumentCaptor.forClass(TeamEntity.class);
        ArgumentCaptor<Wrapper<TeamEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(teamEntityMapper).update(entityCaptor.capture(), captor.capture());
        TeamEntity updateEntity = entityCaptor.getValue();
        assertThat(updateEntity.getName()).isEqualTo("星曜司仪团升级版");
        assertThat(updateEntity.getIntro()).isEqualTo("双城服务团队");
        assertThat(updateEntity.getAvatarUrl()).isEqualTo("https://cos.example.com/new.png");
        assertThat(updateEntity.getUpdatedAt()).isNull();
        assertThat(updateEntity.getVersion()).isEqualTo(3);
        assertThat(captor.getValue().getSqlSegment()).contains("id");
        assertThat(response.getTeam().getName()).isEqualTo("星曜司仪团升级版");
        assertThat(response.getTeam().getIntro()).isEqualTo("双城服务团队");
        assertThat(response.getTeam().getAvatarUrl()).isEqualTo("https://cos.example.com/new.png");
    }

    @Test
    void updateTeamReportsOptimisticLockConflict() {
        TeamEntity team = team(100L, "TM2048", "星曜司仪团", "");
        MineTeamUpdateRequest request = new MineTeamUpdateRequest();
        request.setName("星曜司仪团升级版");
        when(teamEntityMapper.selectById(100L)).thenReturn(team);
        when(teamMemberEntityMapper.selectOne(any()))
                .thenReturn(member(22L, 100L, 7L, TeamRoleDict.MANAGER, JoinStatusDict.JOINED));
        when(teamEntityMapper.update(any(TeamEntity.class), any(Wrapper.class))).thenReturn(0);

        MineTeamService service = service();

        assertThatThrownBy(() -> service.updateTeam(100L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("团队资料已被其他管理员更新，请刷新后重试");
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
        when(teamEntityMapper.update(any(TeamEntity.class), any(Wrapper.class))).thenReturn(1);
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

    /**
     * 团队维护详情查询已加入和待确认成员，避免维护页的待确认筛选无数据。
     */
    @Test
    void getTeamDetailQueriesJoinedAndPendingMembersForMaintenanceView() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/jxc/wefolio/service/MineTeamService.java"));
        int methodIndex = source.indexOf("private MineTeamDetailResponse buildDetailResponseWithMembers(");
        int userMapIndex = source.indexOf("Map<Long, UserEntity> userMap", methodIndex);

        String queryBlock = source.substring(methodIndex, userMapIndex);

        assertThat(queryBlock)
                .contains(".eq(TeamMemberEntity::getTeamId, team.getId())")
                .contains(".in(TeamMemberEntity::getJoinStatus")
                .contains("JoinStatusDict.JOINED.getCode()")
                .contains("JoinStatusDict.PENDING_CONFIRMATION.getCode()");
    }

    /** 团队成员引用保护不得继续解析个人作品集 DTO 或团队 JSON。 */
    @Test
    void memberReferenceProtectionShouldDelegateWithoutSchemaParsingDependencies() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/jxc/wefolio/service/MineTeamService.java"));

        assertThat(source)
                .contains("teamPortfolioReferenceGuardService.assertMemberCanLeave(")
                .doesNotContain("assertMemberContentNotReferenced")
                .doesNotContain("PortfolioConfigDto")
                .doesNotContain("JSON.parseObject")
                .doesNotContain("getSnapshotJson()")
                .doesNotContain("getComponentPath()");
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
        when(teamEntityMapper.update(any(TeamEntity.class), any(Wrapper.class))).thenReturn(1);

        MineTeamService service = service();

        MineTeamDetailResponse response = service.updateTeam(100L, request);

        assertThat(response.getTeam().getIntro()).isEmpty();
        assertThat(response.getTeam().getAvatarUrl()).isEmpty();
    }

    /**
     * 候选人查询返回启用用户，并标记当前是否可邀请。
     */
    @Test
    void memberCandidateReturnsActiveUserAndExistingPermissionState() {
        TeamEntity team = team(100L, "TM2048", "星曜司仪团", "");
        TeamMemberEntity owner = member(21L, 100L, 7L, TeamRoleDict.OWNER, JoinStatusDict.JOINED);
        UserEntity candidate = user(8L, "WF1186", "乔伊", "化妆师", "https://cos.example.com/u8.png");
        when(teamEntityMapper.selectById(100L)).thenReturn(team);
        when(teamMemberEntityMapper.selectOne(any()))
                .thenReturn(owner)
                .thenReturn(null);
        when(userEntityMapper.selectOne(any())).thenReturn(candidate);

        MineTeamService service = service();

        MineTeamMemberCandidateResponse response = service.getMemberCandidate(100L, " WF1186 ");

        assertThat(response.getUserId()).isEqualTo(8L);
        assertThat(response.getUniqueCode()).isEqualTo("WF1186");
        assertThat(response.getDisplayName()).isEqualTo("乔伊 · 化妆师");
        assertThat(response.isCanInvite()).isTrue();
        assertThat(response.getReason()).isEqualTo("可添加");
    }

    /**
     * 邀请新成员时创建待确认成员关系，默认仅开放个人作品集和头像资料。
     */
    @Test
    void inviteMemberCreatesPendingMemberWithDefaultReferencePermissionsAndMessage() {
        TeamEntity team = team(100L, "TM2048", "星曜司仪团", "");
        TeamMemberEntity owner = member(21L, 100L, 7L, TeamRoleDict.OWNER, JoinStatusDict.JOINED);
        UserEntity invitee = user(8L, "WF1186", "乔伊", "化妆师", "https://cos.example.com/u8.png");
        MineTeamMemberInviteRequest request = new MineTeamMemberInviteRequest();
        request.setUniqueCode(" WF1186 ");
        request.setRole(TeamRoleDict.MEMBER.getCode());
        request.setProfession(" 化妆师 ");
        when(teamEntityMapper.selectById(100L)).thenReturn(team);
        when(teamMemberEntityMapper.selectOne(any()))
                .thenReturn(owner)
                .thenReturn(null);
        when(userEntityMapper.selectOne(any())).thenReturn(invitee);
        when(teamMemberEntityMapper.insert(any(TeamMemberEntity.class))).thenAnswer(invocation -> {
            TeamMemberEntity member = invocation.getArgument(0);
            member.setId(31L);
            return 1;
        });
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(owner));
        when(userEntityMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(
                user(7L, "WF8392", "林安", "婚礼司仪", "https://cos.example.com/u7.png")
        ));

        MineTeamService service = service();

        MineTeamDetailResponse response = service.inviteMember(100L, request);

        ArgumentCaptor<TeamMemberEntity> memberCaptor = ArgumentCaptor.forClass(TeamMemberEntity.class);
        verify(teamMemberEntityMapper).insert(memberCaptor.capture());
        TeamMemberEntity inserted = memberCaptor.getValue();
        assertThat(inserted.getTeamId()).isEqualTo(100L);
        assertThat(inserted.getUserId()).isEqualTo(8L);
        assertThat(inserted.getRole()).isEqualTo(TeamRoleDict.MEMBER.getCode());
        assertThat(inserted.getProfession()).isEqualTo("化妆师");
        assertThat(inserted.getJoinStatus()).isEqualTo(JoinStatusDict.PENDING_CONFIRMATION.getCode());
        assertThat(inserted.getAllowPortfolio()).isEqualTo(1);
        assertThat(inserted.getAllowProfile()).isEqualTo(1);
        assertThat(inserted.getAllowWorks()).isZero();
        assertThat(inserted.getInvitedBy()).isEqualTo(7L);
        ArgumentCaptor<SystemMessageEntity> messageCaptor = ArgumentCaptor.forClass(SystemMessageEntity.class);
        verify(systemMessageEntityMapper).insert(messageCaptor.capture());
        SystemMessageEntity message = messageCaptor.getValue();
        assertThat(message.getUserId()).isEqualTo(8L);
        assertThat(message.getMessageType()).isEqualTo(MessageTypeDict.TEAM_INVITATION.getCode());
        assertThat(message.getCategory()).isEqualTo(MessageCategoryDict.TEAM.getCode());
        assertThat(message.getReadStatus()).isEqualTo(MessageReadStatusDict.UNREAD.getCode());
        assertThat(message.getActionType()).isEqualTo(MessageActionTypeDict.TEAM_INVITATION.getCode());
        assertThat(message.getActionUrl()).isEqualTo("/pages/team-invitations/team-invitations?memberId=31");
        assertThat(message.getIdempotencyKey()).isEqualTo("team_invitation:31");
        assertThat(response.getTeam().getMemberCount()).isEqualTo(1);
        assertThat(response.getMembers()).hasSize(1);
    }

    /**
     * 并发邀请导致成员唯一键冲突时返回业务错误，而不是透出数据库异常。
     */
    @Test
    void inviteMemberConvertsDuplicatePendingInsertToFriendlyError() {
        TeamEntity team = team(100L, "TM2048", "星曜司仪团", "");
        TeamMemberEntity owner = member(21L, 100L, 7L, TeamRoleDict.OWNER, JoinStatusDict.JOINED);
        UserEntity invitee = user(8L, "WF1186", "乔伊", "化妆师", "https://cos.example.com/u8.png");
        MineTeamMemberInviteRequest request = new MineTeamMemberInviteRequest();
        request.setUniqueCode("WF1186");
        request.setRole(TeamRoleDict.MEMBER.getCode());
        when(teamEntityMapper.selectById(100L)).thenReturn(team);
        when(teamMemberEntityMapper.selectOne(any()))
                .thenReturn(owner)
                .thenReturn(null);
        when(userEntityMapper.selectOne(any())).thenReturn(invitee);
        when(teamMemberEntityMapper.insert(any(TeamMemberEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_team_member"));

        MineTeamService service = service();

        assertThatThrownBy(() -> service.inviteMember(100L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("已有待确认邀请，请刷新后查看");
        verify(systemMessageEntityMapper, never()).insert(any(SystemMessageEntity.class));
    }

    /**
     * 邀请成员时团队身份超长应返回团队身份文案。
     */
    @Test
    void inviteMemberRejectsTooLongTeamIdentityWithTeamIdentityMessage() {
        TeamEntity team = team(100L, "TM2048", "星曜司仪团", "");
        TeamMemberEntity owner = member(21L, 100L, 7L, TeamRoleDict.OWNER, JoinStatusDict.JOINED);
        UserEntity invitee = user(8L, "WF1186", "乔伊", "化妆师", "https://cos.example.com/u8.png");
        MineTeamMemberInviteRequest request = new MineTeamMemberInviteRequest();
        request.setUniqueCode("WF1186");
        request.setRole(TeamRoleDict.MEMBER.getCode());
        request.setProfession("团".repeat(51));
        when(teamEntityMapper.selectById(100L)).thenReturn(team);
        when(teamMemberEntityMapper.selectOne(any())).thenReturn(owner);
        when(userEntityMapper.selectOne(any())).thenReturn(invitee);

        MineTeamService service = service();

        assertThatThrownBy(() -> service.inviteMember(100L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("团队身份不能超过 50 个字");
        verify(teamMemberEntityMapper, never()).insert(any(TeamMemberEntity.class));
        verify(systemMessageEntityMapper, never()).insert(any(SystemMessageEntity.class));
    }

    /**
     * 成员关系 ID 未回填时不创建无法打开的邀请消息。
     */
    @Test
    void inviteMemberFailsWhenPendingMembershipIdIsMissing() {
        TeamEntity team = team(100L, "TM2048", "星曜司仪团", "");
        TeamMemberEntity owner = member(21L, 100L, 7L, TeamRoleDict.OWNER, JoinStatusDict.JOINED);
        UserEntity invitee = user(8L, "WF1186", "乔伊", "化妆师", "https://cos.example.com/u8.png");
        MineTeamMemberInviteRequest request = new MineTeamMemberInviteRequest();
        request.setUniqueCode("WF1186");
        request.setRole(TeamRoleDict.MEMBER.getCode());
        when(teamEntityMapper.selectById(100L)).thenReturn(team);
        when(teamMemberEntityMapper.selectOne(any()))
                .thenReturn(owner)
                .thenReturn(null);
        when(userEntityMapper.selectOne(any())).thenReturn(invitee);
        when(teamMemberEntityMapper.insert(any(TeamMemberEntity.class))).thenReturn(1);

        MineTeamService service = service();

        assertThatThrownBy(() -> service.inviteMember(100L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("团队邀请保存失败，请重试");
        verify(systemMessageEntityMapper, never()).insert(any(SystemMessageEntity.class));
    }

    /**
     * 管理员不能添加成员，只有团队拥有者可维护成员。
     */
    @Test
    void inviteMemberRejectsManagerBecauseOnlyOwnerCanManageMembers() {
        TeamEntity team = team(100L, "TM2048", "星曜司仪团", "");
        MineTeamMemberInviteRequest request = new MineTeamMemberInviteRequest();
        request.setUniqueCode("WF1186");
        request.setRole(TeamRoleDict.MEMBER.getCode());
        when(teamEntityMapper.selectById(100L)).thenReturn(team);
        when(teamMemberEntityMapper.selectOne(any()))
                .thenReturn(member(22L, 100L, 7L, TeamRoleDict.MANAGER, JoinStatusDict.JOINED));

        MineTeamService service = service();

        assertThatThrownBy(() -> service.inviteMember(100L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("无团队成员维护权限");
        verify(teamMemberEntityMapper, never()).insert(any(TeamMemberEntity.class));
        verify(systemMessageEntityMapper, never()).insert(any(SystemMessageEntity.class));
    }

    /**
     * 重新邀请已移除成员时按提交权限恢复为待确认，且更新条件锁定原状态。
     */
    @Test
    void inviteMemberRestoresRemovedMemberAsPendingWithSubmittedPermissions() {
        TeamEntity team = team(100L, "TM2048", "星曜司仪团", "");
        TeamMemberEntity owner = member(21L, 100L, 7L, TeamRoleDict.OWNER, JoinStatusDict.JOINED);
        TeamMemberEntity removed = member(31L, 100L, 8L, TeamRoleDict.MEMBER, JoinStatusDict.REMOVED);
        UserEntity invitee = user(8L, "WF1186", "乔伊", "化妆师", "https://cos.example.com/u8.png");
        MineTeamMemberInviteRequest request = new MineTeamMemberInviteRequest();
        request.setUniqueCode("WF1186");
        request.setRole(TeamRoleDict.MANAGER.getCode());
        request.setProfession("");
        request.setAllowPortfolio(false);
        request.setAllowProfile(true);
        request.setAllowWorks(true);
        when(teamEntityMapper.selectById(100L)).thenReturn(team);
        when(teamMemberEntityMapper.selectOne(any()))
                .thenReturn(owner)
                .thenReturn(removed);
        when(userEntityMapper.selectOne(any())).thenReturn(invitee);
        when(teamMemberEntityMapper.update(any(TeamMemberEntity.class), any(Wrapper.class))).thenReturn(1);
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(owner, removed));
        when(userEntityMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(
                user(7L, "WF8392", "林安", "婚礼司仪", "https://cos.example.com/u7.png"),
                invitee
        ));

        MineTeamService service = service();

        service.inviteMember(100L, request);

        ArgumentCaptor<Wrapper<TeamMemberEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(teamMemberEntityMapper).update(any(TeamMemberEntity.class), captor.capture());
        String sqlSet = ((UpdateWrapper<TeamMemberEntity>) captor.getValue()).getSqlSet();
        String sqlSegment = captor.getValue().getSqlSegment();
        assertThat(sqlSet).contains("join_status", "role", "allow_portfolio", "allow_profile", "allow_works");
        assertThat(sqlSegment).contains("id", "join_status");
        assertThat(removed.getJoinStatus()).isEqualTo(JoinStatusDict.PENDING_CONFIRMATION.getCode());
        assertThat(removed.getRole()).isEqualTo(TeamRoleDict.MANAGER.getCode());
        assertThat(removed.getAllowPortfolio()).isZero();
        assertThat(removed.getAllowProfile()).isEqualTo(1);
        assertThat(removed.getAllowWorks()).isEqualTo(1);
    }

    /**
     * 打开团队邀请详情时自动将对应邀请消息标记为已读。
     */
    @Test
    void getInvitationMarksRelatedInvitationMessageRead() {
        TeamEntity team = team(100L, "TM2048", "星曜司仪团", "");
        TeamMemberEntity pending = pendingMember(31L, 100L, 7L);
        when(teamMemberEntityMapper.selectById(31L)).thenReturn(pending);
        when(teamEntityMapper.selectById(100L)).thenReturn(team);
        when(userEntityMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(
                user(7L, "WF8392", "林安", "婚礼司仪", "https://cos.example.com/u7.png")
        ));

        MineTeamService service = service();

        MineTeamInvitationResponse response = service.getInvitation(31L);

        assertThat(response.getMemberId()).isEqualTo(31L);
        ArgumentCaptor<Wrapper<SystemMessageEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(systemMessageEntityMapper).update(any(SystemMessageEntity.class), captor.capture());
        UpdateWrapper<SystemMessageEntity> updateWrapper = (UpdateWrapper<SystemMessageEntity>) captor.getValue();
        String sqlSet = updateWrapper.getSqlSet();
        String sqlSegment = updateWrapper.getSqlSegment();
        assertThat(sqlSet).contains("read_status", "read_at");
        assertThat(sqlSegment).contains("user_id", "idempotency_key", "read_status");
        assertThat(updateWrapper.getParamNameValuePairs().values())
                .contains(7L, "team_invitation:31",
                        MessageReadStatusDict.UNREAD.getCode(), MessageReadStatusDict.READ.getCode());
    }

    /**
     * 接受邀请时仅当前用户的待确认成员关系可变更为已加入。
     */
    @Test
    void acceptInvitationChangesCurrentUsersPendingMembershipToJoined() {
        TeamEntity team = team(100L, "TM2048", "星曜司仪团", "");
        TeamMemberEntity pending = pendingMember(31L, 100L, 7L);
        pending.setInvitedBy(8L);
        when(teamMemberEntityMapper.selectById(31L)).thenReturn(pending);
        when(teamEntityMapper.selectById(100L)).thenReturn(team);
        when(teamMemberEntityMapper.update(any(TeamMemberEntity.class), any(Wrapper.class))).thenReturn(1);
        when(userEntityMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(
                user(7L, "WF8392", "林安", "婚礼司仪", "https://cos.example.com/u7.png"),
                user(8L, "WF8888", "乔伊", "化妆师", "https://cos.example.com/u8.png")
        ));

        MineTeamService service = service();

        MineTeamInvitationResponse response = service.acceptInvitation(31L);

        assertThat(response.getJoinStatus()).isEqualTo(JoinStatusDict.JOINED.getCode());
        assertThat(response.isCanRespond()).isFalse();
        ArgumentCaptor<Wrapper<TeamMemberEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(teamMemberEntityMapper).update(any(TeamMemberEntity.class), captor.capture());
        assertThat(((UpdateWrapper<TeamMemberEntity>) captor.getValue()).getSqlSet())
                .contains("join_status", "responded_at", "joined_at");
        assertInvitationMessageActionCleared();
    }

    /**
     * 拒绝邀请时仅当前用户的待确认成员关系可变更为已拒绝。
     */
    @Test
    void rejectInvitationChangesCurrentUsersPendingMembershipToRejected() {
        TeamEntity team = team(100L, "TM2048", "星曜司仪团", "");
        TeamMemberEntity pending = pendingMember(31L, 100L, 7L);
        when(teamMemberEntityMapper.selectById(31L)).thenReturn(pending);
        when(teamEntityMapper.selectById(100L)).thenReturn(team);
        when(teamMemberEntityMapper.update(any(TeamMemberEntity.class), any(Wrapper.class))).thenReturn(1);
        when(userEntityMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(
                user(7L, "WF8392", "林安", "婚礼司仪", "https://cos.example.com/u7.png")
        ));

        MineTeamService service = service();

        MineTeamInvitationResponse response = service.rejectInvitation(31L);

        assertThat(response.getJoinStatus()).isEqualTo(JoinStatusDict.REJECTED.getCode());
        assertThat(response.isCanRespond()).isFalse();
        ArgumentCaptor<Wrapper<TeamMemberEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(teamMemberEntityMapper).update(any(TeamMemberEntity.class), captor.capture());
        assertThat(((UpdateWrapper<TeamMemberEntity>) captor.getValue()).getSqlSet())
                .contains("join_status", "responded_at");
        assertInvitationMessageActionCleared();
    }

    /**
     * 断言团队邀请消息动作已被清理。
     */
    private void assertInvitationMessageActionCleared() {
        ArgumentCaptor<Wrapper<SystemMessageEntity>> messageCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(systemMessageEntityMapper).update(any(SystemMessageEntity.class), messageCaptor.capture());
        UpdateWrapper<SystemMessageEntity> updateWrapper = (UpdateWrapper<SystemMessageEntity>) messageCaptor.getValue();
        String sqlSet = updateWrapper.getSqlSet();
        String sqlSegment = updateWrapper.getSqlSegment();
        assertThat(sqlSet).contains("action_type", "action_url");
        assertThat(sqlSegment).contains("user_id", "idempotency_key");
        assertThat(updateWrapper.getParamNameValuePairs().values())
                .contains(7L, "team_invitation:31", MessageActionTypeDict.NONE.getCode(), "");
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
        team.setVersion(3);
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
     * 构造待确认成员关系。
     *
     * @param id 成员关系 ID
     * @param teamId 团队 ID
     * @param userId 用户 ID
     * @return 待确认成员关系
     */
    private TeamMemberEntity pendingMember(Long id, Long teamId, Long userId) {
        TeamMemberEntity member = member(id, teamId, userId, TeamRoleDict.MEMBER, JoinStatusDict.PENDING_CONFIRMATION);
        member.setJoinedAt(null);
        member.setInvitedBy(7L);
        member.setInvitedAt(LocalDateTime.of(2026, 6, 20, 9, 30));
        member.setAllowPortfolio(1);
        member.setAllowProfile(1);
        member.setAllowWorks(0);
        return member;
    }

    /**
     * 构造用户实体。
     *
     * @param id 用户 ID
     * @param uniqueCode 个人唯一码
     * @param nickname 昵称
     * @param profession 个人职业信息
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
                systemMessageEntityMapper,
                teamMemberChangeRequestEntityMapper,
                teamMemberChangeRequestTransactionService,
                cosService,
                teamRegistrationService,
                pointService,
                uniqueCodeGenerator,
                teamPortfolioReferenceGuardService
        );
    }
}
