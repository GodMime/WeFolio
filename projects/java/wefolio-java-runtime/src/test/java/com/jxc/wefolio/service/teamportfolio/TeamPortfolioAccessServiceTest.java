package com.jxc.wefolio.service.teamportfolio;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jxc.wefolio.config.TeamPortfolioProperties;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 团队作品集访问控制服务测试。 */
class TeamPortfolioAccessServiceTest {

    private static final long PORTFOLIO_ID = 101L;
    private static final long TEAM_ID = 201L;
    private static final long USER_ID = 301L;
    private static final String STANDARD_TEAM_SCHEMA_VERSION = "standard-team-v1";

    @BeforeAll
    static void initializeTeamMemberTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), TeamMemberEntity.class);
    }

    @Test
    void disabledFeatureRejectsEveryPublicMethodWithoutMapperCalls() {
        TestContext context = context(false);

        assertFeatureDisabled(() -> context.service.requireVisiblePortfolio(PORTFOLIO_ID, USER_ID));
        assertFeatureDisabled(() -> context.service.requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID));
        assertFeatureDisabled(() -> context.service.requireTeamRole(TEAM_ID, USER_ID,
                Set.of(TeamRoleDict.OWNER.getCode())));

        verifyNoInteractions(context.portfolioMapper, context.teamMapper, context.memberMapper);
    }

    @Test
    void visiblePortfolioRejectsMissingPortfolio() {
        TestContext context = context(true);

        assertPortfolioNotFound(() -> context.service.requireVisiblePortfolio(PORTFOLIO_ID, USER_ID));

        verify(context.portfolioMapper).selectById(PORTFOLIO_ID);
        verifyNoInteractions(context.teamMapper, context.memberMapper);
    }

    @ParameterizedTest
    @MethodSource("invalidPortfolioIdentityCases")
    void visiblePortfolioRejectsEveryInvalidPortfolioIdentity(PortfolioEntity portfolio) {
        TestContext context = context(true);
        when(context.portfolioMapper.selectById(PORTFOLIO_ID)).thenReturn(portfolio);

        assertPortfolioNotFound(() -> context.service.requireVisiblePortfolio(PORTFOLIO_ID, USER_ID));

        verifyNoInteractions(context.teamMapper, context.memberMapper);
    }

    @Test
    void visiblePortfolioRejectsInactiveTeam() {
        TestContext context = context(true);
        when(context.portfolioMapper.selectById(PORTFOLIO_ID)).thenReturn(activeTeamPortfolio());
        when(context.teamMapper.selectById(TEAM_ID)).thenReturn(team(TeamStatusDict.DISSOLVED.getCode()));

        assertPortfolioNotFound(() -> context.service.requireVisiblePortfolio(PORTFOLIO_ID, USER_ID));

        verifyNoInteractions(context.memberMapper);
    }

    @ParameterizedTest
    @MethodSource("unjoinedMemberships")
    void visiblePortfolioHidesPortfolioForMissingPendingAndRemovedMembership(TeamMemberEntity membership) {
        TestContext context = visibleContext(membership);

        assertPortfolioNotFound(() -> context.service.requireVisiblePortfolio(PORTFOLIO_ID, USER_ID));
    }

    @ParameterizedTest
    @MethodSource("joinedRoleCases")
    void visiblePortfolioBuildsRoleMatrix(String role, boolean canMaintain) {
        TestContext context = visibleContext(member(role, JoinStatusDict.JOINED.getCode()));

        TeamPortfolioAccessService.TeamPortfolioAccess access =
                context.service.requireVisiblePortfolio(PORTFOLIO_ID, USER_ID);

        assertThat(access.portfolio()).isEqualTo(activeTeamPortfolio());
        assertThat(access.team()).isEqualTo(team(TeamStatusDict.ACTIVE.getCode()));
        assertThat(access.membership().getRole()).isEqualTo(role);
        assertThat(access.canMaintain()).isEqualTo(canMaintain);
        assertThat(access.canShare()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("maintainableRoles")
    void maintainablePortfolioAllowsOwnerAndManager(String role) {
        TestContext context = visibleContext(member(role, JoinStatusDict.JOINED.getCode()));

        TeamPortfolioAccessService.TeamPortfolioAccess access =
                context.service.requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID);

        assertThat(access.canMaintain()).isTrue();
    }

    @Test
    void maintainablePortfolioRejectsMemberAfterVisibilityValidation() {
        TestContext context = visibleContext(member(TeamRoleDict.MEMBER.getCode(), JoinStatusDict.JOINED.getCode()));

        assertThatThrownBy(() -> context.service.requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage(TeamPortfolioMessage.NO_MAINTAIN_PERMISSION);
    }

    @Test
    void teamRoleAllowsJoinedMemberWithRequiredRoleAndReturnsNoPortfolio() {
        TestContext context = teamRoleContext(member(TeamRoleDict.MANAGER.getCode(), JoinStatusDict.JOINED.getCode()));

        TeamPortfolioAccessService.TeamPortfolioAccess access = context.service.requireTeamRole(
                TEAM_ID, USER_ID, Set.of(TeamRoleDict.OWNER.getCode(), TeamRoleDict.MANAGER.getCode()));

        assertThat(access.portfolio()).isNull();
        assertThat(access.team()).isEqualTo(team(TeamStatusDict.ACTIVE.getCode()));
        assertThat(access.membership().getRole()).isEqualTo(TeamRoleDict.MANAGER.getCode());
        assertThat(access.canMaintain()).isTrue();
        assertThat(access.canShare()).isTrue();
    }

    @Test
    void teamRoleRejectsInactiveTeamMissingMembershipAndDeniedRole() {
        TestContext inactiveTeam = context(true);
        when(inactiveTeam.teamMapper.selectById(TEAM_ID)).thenReturn(team(TeamStatusDict.DISSOLVED.getCode()));
        assertNoAccess(() -> inactiveTeam.service.requireTeamRole(TEAM_ID, USER_ID,
                Set.of(TeamRoleDict.OWNER.getCode())));

        TestContext missingMembership = teamRoleContext(null);
        assertNoAccess(() -> missingMembership.service.requireTeamRole(TEAM_ID, USER_ID,
                Set.of(TeamRoleDict.OWNER.getCode())));

        TestContext deniedRole = teamRoleContext(member(TeamRoleDict.MEMBER.getCode(), JoinStatusDict.JOINED.getCode()));
        assertNoAccess(() -> deniedRole.service.requireTeamRole(TEAM_ID, USER_ID,
                Set.of(TeamRoleDict.OWNER.getCode())));
    }

    @Test
    void teamRoleRejectsPendingOrRemovedMembershipAndInvalidRequiredRoles() {
        for (TeamMemberEntity membership : List.of(
                member(TeamRoleDict.MEMBER.getCode(), JoinStatusDict.PENDING_CONFIRMATION.getCode()),
                member(TeamRoleDict.MEMBER.getCode(), JoinStatusDict.REMOVED.getCode()))) {
            TestContext context = teamRoleContext(membership);
            assertNoAccess(() -> context.service.requireTeamRole(TEAM_ID, USER_ID,
                    Set.of(TeamRoleDict.MEMBER.getCode())));
        }

        TestContext invalidRoles = teamRoleContext(member(TeamRoleDict.OWNER.getCode(), JoinStatusDict.JOINED.getCode()));
        assertNoAccess(() -> invalidRoles.service.requireTeamRole(TEAM_ID, USER_ID, Set.of()));
        assertNoAccess(() -> invalidRoles.service.requireTeamRole(TEAM_ID, USER_ID, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void membershipQueryConstrainsTeamUserAndJoinedStatus() {
        TestContext context = visibleContext(member(TeamRoleDict.OWNER.getCode(), JoinStatusDict.JOINED.getCode()));

        context.service.requireVisiblePortfolio(PORTFOLIO_ID, USER_ID);

        ArgumentCaptor<Wrapper<TeamMemberEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(context.memberMapper).selectOne(captor.capture());
        LambdaQueryWrapper<TeamMemberEntity> wrapper = (LambdaQueryWrapper<TeamMemberEntity>) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("team_id", "user_id", "join_status");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(TEAM_ID, USER_ID, JoinStatusDict.JOINED.getCode());
    }

    private static Stream<PortfolioEntity> invalidPortfolioIdentityCases() {
        PortfolioEntity wrongOwner = activeTeamPortfolio();
        wrongOwner.setOwnerType(PortfolioOwnerTypeDict.USER.getCode());
        PortfolioEntity wrongTemplate = activeTeamPortfolio();
        wrongTemplate.setTemplateType(PortfolioTemplateTypeDict.ADVANCED.getCode());
        PortfolioEntity inactive = activeTeamPortfolio();
        inactive.setStatus(PortfolioStatusDict.DISABLED.getCode());
        PortfolioEntity wrongSchema = activeTeamPortfolio();
        wrongSchema.setSchemaVersion("standard-user-v1");
        return Stream.of(wrongOwner, wrongTemplate, inactive, wrongSchema);
    }

    private static Stream<TeamMemberEntity> unjoinedMemberships() {
        return Stream.of(null,
                member(TeamRoleDict.MEMBER.getCode(), JoinStatusDict.PENDING_CONFIRMATION.getCode()),
                member(TeamRoleDict.MEMBER.getCode(), JoinStatusDict.REMOVED.getCode()));
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> joinedRoleCases() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(TeamRoleDict.OWNER.getCode(), true),
                org.junit.jupiter.params.provider.Arguments.of(TeamRoleDict.MANAGER.getCode(), true),
                org.junit.jupiter.params.provider.Arguments.of(TeamRoleDict.MEMBER.getCode(), false)
        );
    }

    private static Stream<String> maintainableRoles() {
        return Stream.of(TeamRoleDict.OWNER.getCode(), TeamRoleDict.MANAGER.getCode());
    }

    private static TestContext visibleContext(TeamMemberEntity membership) {
        TestContext context = context(true);
        when(context.portfolioMapper.selectById(PORTFOLIO_ID)).thenReturn(activeTeamPortfolio());
        when(context.teamMapper.selectById(TEAM_ID)).thenReturn(team(TeamStatusDict.ACTIVE.getCode()));
        when(context.memberMapper.selectOne(any())).thenReturn(membership);
        return context;
    }

    private static TestContext teamRoleContext(TeamMemberEntity membership) {
        TestContext context = context(true);
        when(context.teamMapper.selectById(TEAM_ID)).thenReturn(team(TeamStatusDict.ACTIVE.getCode()));
        when(context.memberMapper.selectOne(any())).thenReturn(membership);
        return context;
    }

    private static TestContext context(boolean enabled) {
        TeamPortfolioProperties properties = new TeamPortfolioProperties();
        properties.setEnabled(enabled);
        PortfolioEntityMapper portfolioMapper = mock(PortfolioEntityMapper.class);
        TeamEntityMapper teamMapper = mock(TeamEntityMapper.class);
        TeamMemberEntityMapper memberMapper = mock(TeamMemberEntityMapper.class);
        return new TestContext(new TeamPortfolioAccessService(properties, portfolioMapper, teamMapper, memberMapper),
                portfolioMapper, teamMapper, memberMapper);
    }

    private static PortfolioEntity activeTeamPortfolio() {
        PortfolioEntity portfolio = new PortfolioEntity();
        portfolio.setId(PORTFOLIO_ID);
        portfolio.setOwnerType(PortfolioOwnerTypeDict.TEAM.getCode());
        portfolio.setOwnerId(TEAM_ID);
        portfolio.setTemplateType(PortfolioTemplateTypeDict.STANDARD.getCode());
        portfolio.setStatus(PortfolioStatusDict.ACTIVE.getCode());
        portfolio.setSchemaVersion(STANDARD_TEAM_SCHEMA_VERSION);
        return portfolio;
    }

    private static TeamEntity team(String status) {
        TeamEntity team = new TeamEntity();
        team.setId(TEAM_ID);
        team.setStatus(status);
        return team;
    }

    private static TeamMemberEntity member(String role, String joinStatus) {
        TeamMemberEntity membership = new TeamMemberEntity();
        membership.setTeamId(TEAM_ID);
        membership.setUserId(USER_ID);
        membership.setRole(role);
        membership.setJoinStatus(joinStatus);
        return membership;
    }

    private static void assertFeatureDisabled(ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .hasMessage(TeamPortfolioMessage.FEATURE_DISABLED);
    }

    private static void assertPortfolioNotFound(ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .hasMessage(TeamPortfolioMessage.PORTFOLIO_NOT_FOUND);
    }

    private static void assertNoAccess(ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .hasMessage(TeamPortfolioMessage.NO_ACCESS);
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }

    private record TestContext(
            TeamPortfolioAccessService service,
            PortfolioEntityMapper portfolioMapper,
            TeamEntityMapper teamMapper,
            TeamMemberEntityMapper memberMapper
    ) {
    }
}
