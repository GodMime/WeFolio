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
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.dict.TeamMemberChangeStatusDict;
import com.jxc.wefolio.dict.TeamRoleDict;
import com.jxc.wefolio.dict.TeamStatusDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.dto.MineTeamDetailResponse;
import com.jxc.wefolio.dto.MineTeamMemberChangeCreateRequest;
import com.jxc.wefolio.dto.MineTeamMemberChangeDetailRequest;
import com.jxc.wefolio.dto.MineTeamMemberChangeDetailResponse;
import com.jxc.wefolio.dto.MineTeamMemberRemoveRequest;
import com.jxc.wefolio.dto.MineTeamOwnerTransferRequest;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.entity.SystemMessageEntity;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.entity.TeamMemberChangeRequestEntity;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.PortfolioReferenceEntityMapper;
import com.jxc.wefolio.mapper.SystemMessageEntityMapper;
import com.jxc.wefolio.mapper.TeamEntityMapper;
import com.jxc.wefolio.mapper.TeamMemberChangeRequestEntityMapper;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 团队成员信息变更特性测试 — 覆盖拥有者发起变更、成员确认、转让和移除。
 */
@ExtendWith(MockitoExtension.class)
class MineTeamMemberChangeFeatureTest {

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

    /** 成员变更请求 Mapper 模拟 */
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

    @BeforeEach
    void setUp() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
    }

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    @Test
    void mutatingMemberChangeOperationsShouldRunInTransaction() throws NoSuchMethodException {
        assertTransactional("createMemberChangeRequest", MineTeamMemberChangeCreateRequest.class);
        assertTransactional("acceptMemberChangeRequest", MineTeamMemberChangeDetailRequest.class);
        assertTransactional("rejectMemberChangeRequest", MineTeamMemberChangeDetailRequest.class);
        assertTransactional("transferOwner", MineTeamOwnerTransferRequest.class);
        assertTransactional("removeMember", MineTeamMemberRemoveRequest.class);
    }

    @Test
    void conflictInvalidationShouldRunInRequiresNewTransaction() throws NoSuchMethodException {
        Method method = TeamMemberChangeRequestTransactionService.class.getMethod(
                "invalidateMemberChangeAndClearAction", Long.class, Long.class);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(transactional.rollbackFor()).contains(Exception.class);
    }

    @Test
    void createMemberChangeRequestStoresSnapshotSendsMessageAndMarksMemberPending() {
        TeamEntity team = team();
        TeamMemberEntity owner = member(21L, 7L, TeamRoleDict.OWNER);
        TeamMemberEntity target = member(31L, 8L, TeamRoleDict.MEMBER);
        target.setProfession("摄影师");
        target.setAllowPortfolio(1);
        target.setAllowProfile(0);
        target.setAllowWorks(1);
        target.setVersion(5);
        MineTeamMemberChangeCreateRequest request = new MineTeamMemberChangeCreateRequest();
        request.setTeamId(100L);
        request.setMemberId(31L);
        request.setRole(TeamRoleDict.MANAGER.getCode());
        request.setProfession(" 导演 ");
        request.setAllowPortfolio(false);
        request.setAllowProfile(true);
        request.setAllowWorks(true);
        TeamMemberChangeRequestEntity pending = changeRequest(41L, target);
        pending.setRoleAfter(TeamRoleDict.MANAGER.getCode());
        pending.setProfessionAfter("导演");
        pending.setAllowPortfolioAfter(0);
        pending.setAllowProfileAfter(1);
        pending.setAllowWorksAfter(1);
        when(teamEntityMapper.selectById(100L)).thenReturn(team);
        when(teamMemberEntityMapper.selectOne(any())).thenReturn(owner);
        when(teamMemberEntityMapper.selectById(31L)).thenReturn(target);
        when(teamMemberChangeRequestEntityMapper.selectCount(any())).thenReturn(0L);
        when(teamMemberChangeRequestEntityMapper.insert(any(TeamMemberChangeRequestEntity.class))).thenAnswer(invocation -> {
            TeamMemberChangeRequestEntity entity = invocation.getArgument(0);
            entity.setId(41L);
            return 1;
        });
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(owner, target));
        when(userEntityMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(
                user(7L, "WF0007", "林安", "主持人"),
                user(8L, "WF0008", "乔伊", "摄影师")
        ));
        when(teamMemberChangeRequestEntityMapper.selectList(any())).thenReturn(List.of(pending));

        MineTeamDetailResponse response = service().createMemberChangeRequest(request);

        ArgumentCaptor<TeamMemberChangeRequestEntity> requestCaptor =
                ArgumentCaptor.forClass(TeamMemberChangeRequestEntity.class);
        verify(teamMemberChangeRequestEntityMapper).insert(requestCaptor.capture());
        TeamMemberChangeRequestEntity inserted = requestCaptor.getValue();
        assertThat(inserted.getTeamId()).isEqualTo(100L);
        assertThat(inserted.getMemberId()).isEqualTo(31L);
        assertThat(inserted.getTargetUserId()).isEqualTo(8L);
        assertThat(inserted.getRequestedByUserId()).isEqualTo(7L);
        assertThat(inserted.getMemberVersionBefore()).isEqualTo(5);
        assertThat(inserted.getRoleBefore()).isEqualTo(TeamRoleDict.MEMBER.getCode());
        assertThat(inserted.getRoleAfter()).isEqualTo(TeamRoleDict.MANAGER.getCode());
        assertThat(inserted.getProfessionBefore()).isEqualTo("摄影师");
        assertThat(inserted.getProfessionAfter()).isEqualTo("导演");
        assertThat(inserted.getAllowPortfolioBefore()).isEqualTo(1);
        assertThat(inserted.getAllowPortfolioAfter()).isZero();
        assertThat(inserted.getStatus()).isEqualTo(TeamMemberChangeStatusDict.PENDING_CONFIRMATION.getCode());
        assertThat(inserted.getRequestedAt()).isNotNull();
        ArgumentCaptor<SystemMessageEntity> messageCaptor = ArgumentCaptor.forClass(SystemMessageEntity.class);
        verify(systemMessageEntityMapper).insert(messageCaptor.capture());
        SystemMessageEntity message = messageCaptor.getValue();
        assertThat(message.getUserId()).isEqualTo(8L);
        assertThat(message.getMessageType()).isEqualTo(MessageTypeDict.TEAM_ROLE_CHANGED.getCode());
        assertThat(message.getCategory()).isEqualTo(MessageCategoryDict.TEAM.getCode());
        assertThat(message.getReadStatus()).isEqualTo(MessageReadStatusDict.UNREAD.getCode());
        assertThat(message.getActionType()).isEqualTo(MessageActionTypeDict.TEAM_MEMBER_CHANGE.getCode());
        assertThat(message.getActionUrl()).isEqualTo("/pages/team-member-change/team-member-change?changeRequestId=41");
        assertThat(message.getIdempotencyKey()).isEqualTo("team_member_change:41");
        MineTeamDetailResponse.MemberItem targetItem = response.getMembers().stream()
                .filter(item -> Long.valueOf(31L).equals(item.getMemberId()))
                .findFirst()
                .orElseThrow();
        assertThat(targetItem.isPendingChange()).isTrue();
        assertThat(targetItem.getPendingChangeId()).isEqualTo(41L);
        assertThat(targetItem.getPendingChangeText()).isEqualTo("信息变更待同意");
    }

    /**
     * 发起成员信息变更时团队身份超长应返回团队身份文案。
     */
    @Test
    void createMemberChangeRequestRejectsTooLongTeamIdentityWithTeamIdentityMessage() {
        TeamEntity team = team();
        TeamMemberEntity owner = member(21L, 7L, TeamRoleDict.OWNER);
        TeamMemberEntity target = member(31L, 8L, TeamRoleDict.MEMBER);
        MineTeamMemberChangeCreateRequest request = new MineTeamMemberChangeCreateRequest();
        request.setTeamId(100L);
        request.setMemberId(31L);
        request.setRole(TeamRoleDict.MEMBER.getCode());
        request.setProfession("团".repeat(51));
        when(teamEntityMapper.selectById(100L)).thenReturn(team);
        when(teamMemberEntityMapper.selectOne(any())).thenReturn(owner);
        when(teamMemberEntityMapper.selectById(31L)).thenReturn(target);

        assertThatThrownBy(() -> service().createMemberChangeRequest(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("团队身份不能超过 50 个字");
        verify(teamMemberChangeRequestEntityMapper, never()).insert(any(TeamMemberChangeRequestEntity.class));
        verify(systemMessageEntityMapper, never()).insert(any(SystemMessageEntity.class));
    }

    @Test
    void getMemberChangeRequestDetailMarksMessageReadAndReturnsBeforeAfterValues() {
        AuthContextHolder.set(new AuthContext(8L, "wf-dev-user-8"));
        TeamMemberEntity target = member(31L, 8L, TeamRoleDict.MEMBER);
        TeamMemberChangeRequestEntity changeRequest = changeRequest(41L, target);
        MineTeamMemberChangeDetailRequest request = new MineTeamMemberChangeDetailRequest();
        request.setChangeRequestId(41L);
        when(teamMemberChangeRequestEntityMapper.selectById(41L)).thenReturn(changeRequest);
        when(teamEntityMapper.selectById(100L)).thenReturn(team());
        when(userEntityMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(
                user(7L, "WF0007", "林安", "主持人"),
                user(8L, "WF0008", "乔伊", "摄影师")
        ));

        MineTeamMemberChangeDetailResponse response = service().getMemberChangeRequestDetail(request);

        assertThat(response.getChangeRequestId()).isEqualTo(41L);
        assertThat(response.getTeamName()).isEqualTo("星曜司仪团");
        assertThat(response.getRequesterName()).isEqualTo("林安 · 主持人");
        assertThat(response.getStatus()).isEqualTo(TeamMemberChangeStatusDict.PENDING_CONFIRMATION.getCode());
        assertThat(response.isCanRespond()).isTrue();
        assertThat(response.getRoleBeforeText()).isEqualTo("普通成员");
        assertThat(response.getRoleAfterText()).isEqualTo("管理者");
        assertThat(response.getProfessionAfter()).isEqualTo("导演");
        assertThat(response.getPermissionAfterText()).isEqualTo("主页资料、作品素材");
        ArgumentCaptor<Wrapper<SystemMessageEntity>> messageCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(systemMessageEntityMapper).update(any(SystemMessageEntity.class), messageCaptor.capture());
        UpdateWrapper<SystemMessageEntity> updateWrapper = (UpdateWrapper<SystemMessageEntity>) messageCaptor.getValue();
        assertThat(updateWrapper.getSqlSet()).contains("read_status", "read_at");
        assertThat(updateWrapper.getSqlSegment()).contains("user_id", "idempotency_key", "read_status");
        assertThat(updateWrapper.getParamNameValuePairs().values())
                .contains(8L, "team_member_change:41",
                        MessageReadStatusDict.UNREAD.getCode(), MessageReadStatusDict.READ.getCode());
    }

    @Test
    void acceptMemberChangeRequestAppliesSnapshotWhenMemberVersionMatches() {
        AuthContextHolder.set(new AuthContext(8L, "wf-dev-user-8"));
        TeamMemberEntity target = member(31L, 8L, TeamRoleDict.MEMBER);
        target.setVersion(5);
        TeamMemberChangeRequestEntity changeRequest = changeRequest(41L, target);
        MineTeamMemberChangeDetailRequest request = new MineTeamMemberChangeDetailRequest();
        request.setChangeRequestId(41L);
        when(teamMemberChangeRequestEntityMapper.selectById(41L)).thenReturn(changeRequest);
        when(teamEntityMapper.selectById(100L)).thenReturn(team());
        when(teamMemberEntityMapper.selectById(31L)).thenReturn(target);
        when(teamMemberEntityMapper.update(any(TeamMemberEntity.class), any(Wrapper.class))).thenReturn(1);
        when(teamMemberChangeRequestEntityMapper.update(any(TeamMemberChangeRequestEntity.class), any(Wrapper.class))).thenReturn(1);
        when(userEntityMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(
                user(7L, "WF0007", "林安", "主持人"),
                user(8L, "WF0008", "乔伊", "摄影师")
        ));

        MineTeamMemberChangeDetailResponse response = service().acceptMemberChangeRequest(request);

        assertThat(response.getStatus()).isEqualTo(TeamMemberChangeStatusDict.ACCEPTED.getCode());
        assertThat(response.isCanRespond()).isFalse();
        ArgumentCaptor<Wrapper<TeamMemberEntity>> memberCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(teamMemberEntityMapper).update(any(TeamMemberEntity.class), memberCaptor.capture());
        UpdateWrapper<TeamMemberEntity> memberUpdate = (UpdateWrapper<TeamMemberEntity>) memberCaptor.getValue();
        assertThat(memberUpdate.getSqlSet())
                .contains("role", "profession", "allow_portfolio", "allow_profile", "allow_works", "version");
        assertThat(memberUpdate.getSqlSegment()).contains("role", "version");
        assertThat(memberUpdate.getParamNameValuePairs().values())
                .contains(TeamRoleDict.MEMBER.getCode(), TeamRoleDict.MANAGER.getCode(), 5);
        ArgumentCaptor<Wrapper<SystemMessageEntity>> messageCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(systemMessageEntityMapper).update(any(SystemMessageEntity.class), messageCaptor.capture());
        UpdateWrapper<SystemMessageEntity> updateWrapper = (UpdateWrapper<SystemMessageEntity>) messageCaptor.getValue();
        assertThat(updateWrapper.getSqlSet())
                .contains("action_type", "action_url");
        assertThat(updateWrapper.getSqlSegment()).contains("user_id", "idempotency_key");
        assertThat(updateWrapper.getParamNameValuePairs().values()).contains(MessageActionTypeDict.NONE.getCode(), "");
    }

    @Test
    void acceptMemberChangeRequestInvalidatesWithRequiresNewServiceWhenMemberVersionChanged() {
        AuthContextHolder.set(new AuthContext(8L, "wf-dev-user-8"));
        TeamMemberEntity target = member(31L, 8L, TeamRoleDict.MEMBER);
        target.setVersion(6);
        TeamMemberChangeRequestEntity changeRequest = changeRequest(41L, target);
        MineTeamMemberChangeDetailRequest request = new MineTeamMemberChangeDetailRequest();
        request.setChangeRequestId(41L);
        when(teamMemberChangeRequestEntityMapper.selectById(41L)).thenReturn(changeRequest);
        when(teamEntityMapper.selectById(100L)).thenReturn(team());
        when(teamMemberEntityMapper.selectById(31L)).thenReturn(target);

        assertThatThrownBy(() -> service().acceptMemberChangeRequest(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("成员信息已变化，请联系团队拥有者重新发起。");

        verify(teamMemberChangeRequestTransactionService).invalidateMemberChangeAndClearAction(41L, 8L);
        verify(teamMemberEntityMapper, never()).update(any(TeamMemberEntity.class), any(Wrapper.class));
    }

    @Test
    void transferOwnerPromotesTargetDemotesOriginalOwnerUpdatesTeamAndSendsNotice() {
        TeamEntity team = team();
        TeamMemberEntity owner = member(21L, 7L, TeamRoleDict.OWNER);
        TeamMemberEntity target = member(31L, 8L, TeamRoleDict.MANAGER);
        TeamMemberChangeRequestEntity pendingChange = changeRequest(41L, target);
        MineTeamOwnerTransferRequest request = new MineTeamOwnerTransferRequest();
        request.setTeamId(100L);
        request.setMemberId(31L);
        when(teamEntityMapper.selectById(100L)).thenReturn(team);
        when(teamMemberEntityMapper.selectOne(any())).thenReturn(owner);
        when(teamMemberEntityMapper.selectById(31L)).thenReturn(target);
        when(teamMemberEntityMapper.update(any(TeamMemberEntity.class), any(Wrapper.class))).thenReturn(1);
        when(teamEntityMapper.update(any(TeamEntity.class), any(Wrapper.class))).thenReturn(1);
        when(teamMemberChangeRequestEntityMapper.update(any(TeamMemberChangeRequestEntity.class), any(Wrapper.class))).thenReturn(1);
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(owner, target));
        when(userEntityMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(
                user(7L, "WF0007", "林安", "主持人"),
                user(8L, "WF0008", "乔伊", "摄影师")
        ));
        when(teamMemberChangeRequestEntityMapper.selectList(any()))
                .thenReturn(List.of(pendingChange))
                .thenReturn(List.of());

        MineTeamDetailResponse response = service().transferOwner(request);

        assertThat(response.getTeam().getRole()).isEqualTo(TeamRoleDict.MANAGER.getCode());
        assertThat(owner.getRole()).isEqualTo(TeamRoleDict.MANAGER.getCode());
        assertThat(target.getRole()).isEqualTo(TeamRoleDict.OWNER.getCode());
        assertThat(team.getOwnerUserId()).isEqualTo(8L);
        ArgumentCaptor<Wrapper<TeamMemberEntity>> memberUpdateCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(teamMemberEntityMapper, times(2)).update(any(TeamMemberEntity.class), memberUpdateCaptor.capture());
        assertThat(memberUpdateCaptor.getAllValues()).allSatisfy(wrapper -> {
            UpdateWrapper<TeamMemberEntity> updateWrapper = (UpdateWrapper<TeamMemberEntity>) wrapper;
            assertThat(updateWrapper.getSqlSet()).contains("role", "version");
            assertThat(updateWrapper.getSqlSegment()).contains("role", "join_status", "version");
            assertThat(updateWrapper.getParamNameValuePairs().values()).contains(5);
        });
        ArgumentCaptor<Wrapper<TeamEntity>> teamUpdateCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(teamEntityMapper).update(any(TeamEntity.class), teamUpdateCaptor.capture());
        UpdateWrapper<TeamEntity> teamUpdate = (UpdateWrapper<TeamEntity>) teamUpdateCaptor.getValue();
        assertThat(teamUpdate.getSqlSet()).contains("owner_user_id", "version");
        assertThat(teamUpdate.getSqlSegment()).contains("id", "version");
        assertThat(teamUpdate.getParamNameValuePairs().values()).contains(100L, 3, 8L);
        ArgumentCaptor<SystemMessageEntity> messageCaptor = ArgumentCaptor.forClass(SystemMessageEntity.class);
        verify(systemMessageEntityMapper).insert(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getUserId()).isEqualTo(8L);
        assertThat(messageCaptor.getValue().getContent()).isEqualTo("你已被指定为「星曜司仪团」的拥有者。");
        assertThat(messageCaptor.getValue().getIdempotencyKey()).isEqualTo("team_owner_transfer:100:3:31");
        ArgumentCaptor<Wrapper<SystemMessageEntity>> messageUpdateCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(systemMessageEntityMapper).update(any(SystemMessageEntity.class), messageUpdateCaptor.capture());
        UpdateWrapper<SystemMessageEntity> messageUpdate =
                (UpdateWrapper<SystemMessageEntity>) messageUpdateCaptor.getValue();
        assertThat(messageUpdate.getSqlSegment()).contains("idempotency_key");
        assertThat(messageUpdate.getParamNameValuePairs().values())
                .contains(MessageActionTypeDict.NONE.getCode(), "");
    }

    @Test
    void transferOwnerUpdatesExistingNoticeWhenPlainNoticeKeyCollides() {
        TeamEntity team = team();
        TeamMemberEntity owner = member(21L, 7L, TeamRoleDict.OWNER);
        TeamMemberEntity target = member(31L, 8L, TeamRoleDict.MANAGER);
        MineTeamOwnerTransferRequest request = new MineTeamOwnerTransferRequest();
        request.setTeamId(100L);
        request.setMemberId(31L);
        when(teamEntityMapper.selectById(100L)).thenReturn(team);
        when(teamMemberEntityMapper.selectOne(any())).thenReturn(owner);
        when(teamMemberEntityMapper.selectById(31L)).thenReturn(target);
        when(teamMemberEntityMapper.update(any(TeamMemberEntity.class), any(Wrapper.class))).thenReturn(1);
        when(teamEntityMapper.update(any(TeamEntity.class), any(Wrapper.class))).thenReturn(1);
        when(teamMemberChangeRequestEntityMapper.selectList(any())).thenReturn(List.of()).thenReturn(List.of());
        when(systemMessageEntityMapper.insert(any(SystemMessageEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(owner, target));
        when(userEntityMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(
                user(7L, "WF0007", "林安", "主持人"),
                user(8L, "WF0008", "乔伊", "摄影师")
        ));

        MineTeamDetailResponse response = service().transferOwner(request);

        assertThat(response.getTeam().getRole()).isEqualTo(TeamRoleDict.MANAGER.getCode());
        ArgumentCaptor<Wrapper<SystemMessageEntity>> messageUpdateCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(systemMessageEntityMapper).update(any(SystemMessageEntity.class), messageUpdateCaptor.capture());
        UpdateWrapper<SystemMessageEntity> updateWrapper = (UpdateWrapper<SystemMessageEntity>) messageUpdateCaptor.getValue();
        assertThat(updateWrapper.getSqlSet())
                .contains("read_status", "read_at", "title", "content", "action_type", "action_url");
        assertThat(updateWrapper.getSqlSegment()).contains("idempotency_key");
        assertThat(updateWrapper.getParamNameValuePairs().values())
                .contains(MessageReadStatusDict.UNREAD.getCode(), MessageActionTypeDict.NONE.getCode(), "");
    }

    @Test
    void removeMemberRejectsWhenMemberContentIsReferencedByTeamPortfolio() {
        TeamMemberEntity owner = member(21L, 7L, TeamRoleDict.OWNER);
        TeamMemberEntity target = member(31L, 8L, TeamRoleDict.MEMBER);
        MineTeamMemberRemoveRequest request = new MineTeamMemberRemoveRequest();
        request.setTeamId(100L);
        request.setMemberId(31L);
        when(teamEntityMapper.selectById(100L)).thenReturn(team());
        when(teamMemberEntityMapper.selectOne(any())).thenReturn(owner);
        when(teamMemberEntityMapper.selectById(31L)).thenReturn(target);
        when(workEntityMapper.selectList(any())).thenReturn(List.of(work(501L)));
        when(portfolioEntityMapper.selectList(any()))
                .thenReturn(List.of(portfolio(601L, PortfolioOwnerTypeDict.USER, 8L, "乔伊个人作品集")))
                .thenReturn(List.of(
                        portfolio(701L, PortfolioOwnerTypeDict.TEAM, 100L, "婚礼案例集"),
                        portfolio(702L, PortfolioOwnerTypeDict.TEAM, 100L, "主持作品集"),
                        portfolio(703L, PortfolioOwnerTypeDict.TEAM, 100L, "年度精选")
                ));
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of(
                reference(701L, ReferenceTypeDict.WORK, 501L),
                reference(702L, ReferenceTypeDict.MEMBER_PORTFOLIO, 601L),
                reference(703L, ReferenceTypeDict.USER_PROFILE, 8L)
        ));

        assertThatThrownBy(() -> service().removeMember(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("无法移除，成员内容仍被《婚礼案例集》《主持作品集》等 3 个作品集使用，请先移除引用。");
        verify(teamMemberEntityMapper, never()).update(any(TeamMemberEntity.class), any(Wrapper.class));
        verify(systemMessageEntityMapper, never()).insert(any(SystemMessageEntity.class));
    }

    @Test
    void removeMemberMarksJoinedMemberRemovedAndSendsNoticeWhenNoTeamReferences() {
        TeamEntity team = team();
        TeamMemberEntity owner = member(21L, 7L, TeamRoleDict.OWNER);
        TeamMemberEntity target = member(31L, 8L, TeamRoleDict.MEMBER);
        MineTeamMemberRemoveRequest request = new MineTeamMemberRemoveRequest();
        request.setTeamId(100L);
        request.setMemberId(31L);
        when(teamEntityMapper.selectById(100L)).thenReturn(team);
        when(teamMemberEntityMapper.selectOne(any())).thenReturn(owner);
        when(teamMemberEntityMapper.selectById(31L)).thenReturn(target);
        when(workEntityMapper.selectList(any())).thenReturn(List.of());
        when(portfolioEntityMapper.selectList(any()))
                .thenReturn(List.of())
                .thenReturn(List.of(portfolio(701L, PortfolioOwnerTypeDict.TEAM, 100L, "婚礼案例集")));
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of());
        when(teamMemberEntityMapper.update(any(TeamMemberEntity.class), any(Wrapper.class))).thenReturn(1);
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(owner));
        when(userEntityMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(
                user(7L, "WF0007", "林安", "主持人")
        ));
        when(teamMemberChangeRequestEntityMapper.selectList(any())).thenReturn(List.of());

        MineTeamDetailResponse response = service().removeMember(request);

        assertThat(response.getMembers()).hasSize(1);
        assertThat(target.getJoinStatus()).isEqualTo(JoinStatusDict.REMOVED.getCode());
        ArgumentCaptor<Wrapper<TeamMemberEntity>> memberCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(teamMemberEntityMapper).update(any(TeamMemberEntity.class), memberCaptor.capture());
        assertThat(((UpdateWrapper<TeamMemberEntity>) memberCaptor.getValue()).getSqlSet())
                .contains("join_status", "removed_at", "removal_reason");
        ArgumentCaptor<SystemMessageEntity> messageCaptor = ArgumentCaptor.forClass(SystemMessageEntity.class);
        verify(systemMessageEntityMapper).insert(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getUserId()).isEqualTo(8L);
        assertThat(messageCaptor.getValue().getContent()).isEqualTo("你已被移出「星曜司仪团」。");
    }

    private void assertTransactional(String methodName, Class<?> parameterType) throws NoSuchMethodException {
        Method method = MineTeamService.class.getMethod(methodName, parameterType);
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.rollbackFor()).contains(Exception.class);
    }

    private MineTeamService service() {
        return new MineTeamService(
                teamEntityMapper,
                teamMemberEntityMapper,
                userEntityMapper,
                systemMessageEntityMapper,
                teamMemberChangeRequestEntityMapper,
                teamMemberChangeRequestTransactionService,
                portfolioEntityMapper,
                portfolioReferenceEntityMapper,
                workEntityMapper,
                cosService,
                teamRegistrationService,
                pointService,
                uniqueCodeGenerator
        );
    }

    private TeamEntity team() {
        TeamEntity team = new TeamEntity();
        team.setId(100L);
        team.setUniqueCode("TM2048");
        team.setName("星曜司仪团");
        team.setAvatarUrl("");
        team.setIntro("团队简介");
        team.setCity("");
        team.setOwnerUserId(7L);
        team.setStatus(TeamStatusDict.ACTIVE.getCode());
        team.setVersion(3);
        return team;
    }

    private TeamMemberEntity member(Long id, Long userId, TeamRoleDict role) {
        TeamMemberEntity member = new TeamMemberEntity();
        member.setId(id);
        member.setTeamId(100L);
        member.setUserId(userId);
        member.setRole(role.getCode());
        member.setProfession("");
        member.setJoinStatus(JoinStatusDict.JOINED.getCode());
        member.setAllowPortfolio(1);
        member.setAllowProfile(1);
        member.setAllowWorks(0);
        member.setJoinedAt(LocalDateTime.of(2026, 6, 1, 9, 30));
        member.setVersion(5);
        return member;
    }

    private TeamMemberChangeRequestEntity changeRequest(Long id, TeamMemberEntity target) {
        TeamMemberChangeRequestEntity request = new TeamMemberChangeRequestEntity();
        request.setId(id);
        request.setTeamId(target.getTeamId());
        request.setMemberId(target.getId());
        request.setTargetUserId(target.getUserId());
        request.setRequestedByUserId(7L);
        request.setMemberVersionBefore(5);
        request.setRoleBefore(TeamRoleDict.MEMBER.getCode());
        request.setRoleAfter(TeamRoleDict.MANAGER.getCode());
        request.setProfessionBefore("摄影师");
        request.setProfessionAfter("导演");
        request.setAllowPortfolioBefore(1);
        request.setAllowPortfolioAfter(0);
        request.setAllowProfileBefore(0);
        request.setAllowProfileAfter(1);
        request.setAllowWorksBefore(1);
        request.setAllowWorksAfter(1);
        request.setStatus(TeamMemberChangeStatusDict.PENDING_CONFIRMATION.getCode());
        request.setRequestedAt(LocalDateTime.of(2026, 6, 20, 9, 30));
        return request;
    }

    private UserEntity user(Long id, String uniqueCode, String nickname, String profession) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setUniqueCode(uniqueCode);
        user.setNickname(nickname);
        user.setProfession(profession);
        user.setStatus(UserStatusDict.ACTIVE.getCode());
        return user;
    }

    private WorkEntity work(Long id) {
        WorkEntity work = new WorkEntity();
        work.setId(id);
        work.setUserId(8L);
        work.setStatus(WorkStatusDict.ACTIVE.getCode());
        return work;
    }

    private PortfolioEntity portfolio(Long id, PortfolioOwnerTypeDict ownerType, Long ownerId, String title) {
        PortfolioEntity portfolio = new PortfolioEntity();
        portfolio.setId(id);
        portfolio.setOwnerType(ownerType.getCode());
        portfolio.setOwnerId(ownerId);
        portfolio.setDraftConfigJson("{\"share\":{\"title\":\"" + title + "\"},\"components\":[]}");
        portfolio.setPublishedConfigJson("{\"share\":{\"title\":\"" + title + "\"},\"components\":[]}");
        portfolio.setStatus(PortfolioStatusDict.ACTIVE.getCode());
        return portfolio;
    }

    private PortfolioReferenceEntity reference(Long portfolioId, ReferenceTypeDict type, Long referenceId) {
        PortfolioReferenceEntity reference = new PortfolioReferenceEntity();
        reference.setPortfolioId(portfolioId);
        reference.setReferenceType(type.getCode());
        reference.setReferenceId(referenceId);
        reference.setIsValid(1);
        return reference;
    }
}
