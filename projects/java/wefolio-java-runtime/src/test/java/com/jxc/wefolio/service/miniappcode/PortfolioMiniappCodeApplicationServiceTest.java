package com.jxc.wefolio.service.miniappcode;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jxc.wefolio.config.TeamPortfolioProperties;
import com.jxc.wefolio.constant.TeamPortfolioConstants;
import com.jxc.wefolio.dict.JoinStatusDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.TeamRoleDict;
import com.jxc.wefolio.dict.TeamStatusDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.dto.PortfolioMiniappCodeResponse;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.TeamEntityMapper;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAccessService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 小程序码应用服务测试：验证归属、真实团队权限与不可变发布快照。 */
class PortfolioMiniappCodeApplicationServiceTest {
    /** 作品集数据库边界。 */
    private final PortfolioEntityMapper portfolios = mock(PortfolioEntityMapper.class);
    /** 所属用户数据库边界。 */
    private final UserEntityMapper users = mock(UserEntityMapper.class);
    /** 所属团队数据库边界。 */
    private final TeamEntityMapper teams = mock(TeamEntityMapper.class);
    /** 团队成员数据库边界。 */
    private final TeamMemberEntityMapper members = mock(TeamMemberEntityMapper.class);
    /** 远端成图边界，模拟缓存命中与生成均返回同一结果。 */
    private final PortfolioMiniappCodeResourceService images = mock(PortfolioMiniappCodeResourceService.class);
    /** 被测应用服务。 */
    private PortfolioMiniappCodeApplicationService service;
    /** 当前有效作品集。 */
    private PortfolioEntity portfolio;
    /** 当前个人资料。 */
    private UserEntity user;
    /** 当前团队资料。 */
    private TeamEntity team;
    /** 当前团队成员。 */
    private TeamMemberEntity member;

    /** 初始化真实团队权限服务使用的成员查询元数据。 */
    @BeforeAll
    static void initializeTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), TeamMemberEntity.class);
    }

    /** 每个用例建立有效个人作品集，团队用例再显式切换归属。 */
    @BeforeEach
    void setUp() {
        TeamPortfolioProperties properties = new TeamPortfolioProperties();
        properties.setEnabled(true);
        service = new PortfolioMiniappCodeApplicationService(portfolios, users,
                new TeamPortfolioAccessService(properties, portfolios, teams, members), images);
        portfolio = new PortfolioEntity();
        portfolio.setId(11L);
        portfolio.setOwnerId(7L);
        portfolio.setOwnerType(PortfolioOwnerTypeDict.USER.getCode());
        portfolio.setTemplateType(PortfolioTemplateTypeDict.STANDARD.getCode());
        portfolio.setStatus(PortfolioStatusDict.ACTIVE.getCode());
        portfolio.setSchemaVersion(PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1);
        portfolio.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        portfolio.setPublishedRevision(3);
        portfolio.setPublishedConfigJson("{\"share\":{\"title\":\"公开标题\",\"avatarUrl\":\"https://invalid/share-avatar.png\"}}");
        portfolio.setDraftConfigJson("{\"share\":{\"title\":\"秘密草稿\"}}");
        portfolio.setShareCode("PF1234567890");
        portfolio.setDeleted(0L);
        when(portfolios.selectById(11L)).thenReturn(portfolio);
        user = new UserEntity();
        user.setId(7L);
        user.setUniqueCode("WFUSER");
        user.setNickname("  个人姓名  ");
        user.setProfession("主持人");
        user.setCity("杭州");
        user.setAvatarUrl("https://cdn.example.invalid/personal.png");
        user.setStatus(UserStatusDict.ACTIVE.getCode());
        user.setDeleted(0L);
        when(users.selectById(7L)).thenReturn(user);
        when(images.generate(any(), eq(7L))).thenReturn(new PortfolioMiniappCodeResponse("https://cdn.example.invalid/code.png", "", "v1", PortfolioOwnerTypeDict.USER.getCode(), "name", "city", "title", 1080, 1440, 800, 120));
    }

    /** 成图只使用正式标题和所属用户个人资料头像，不使用分享配置头像或草稿。 */
    @Test
    void personalUsesPublishedTitleAndProfileAvatar() {
        assertThat(service.generatePersonal(11L, 7L).getCodeUrl()).endsWith("/code.png");
        PortfolioMiniappCodeSnapshot snapshot = capturedSnapshot();
        assertThat(snapshot.ownerType()).isEqualTo(PortfolioOwnerTypeDict.USER.getCode());
        assertThat(snapshot.ownerId()).isEqualTo(7L);
        assertThat(snapshot.uniqueCode()).isEqualTo("WFUSER");
        assertThat(snapshot.publishedRevision()).isEqualTo("3");
        assertThat(snapshot.shareTitle()).isEqualTo("公开标题");
        assertThat(snapshot.displayName()).isEqualTo("个人姓名");
        assertThat(snapshot.subtitle()).isEqualTo("主持人 · 杭州");
        assertThat(snapshot.avatarUrl()).isEqualTo("https://cdn.example.invalid/personal.png");
    }

    /** 无个人头像时传递空值，由成图层选择受控默认资源。 */
    @Test
    void personalWithoutAvatarDoesNotBorrowShareAvatar() {
        user.setAvatarUrl(null);
        user.setProfession(null);
        user.setCity(" ");
        service.generatePersonal(11L, 7L);
        assertThat(capturedSnapshot().avatarUrl()).isNull();
        assertThat(capturedSnapshot().subtitle()).isEmpty();
    }

    /** 越权请求不能命中成图缓存。 */
    @Test
    void personalOwnershipIsCheckedBeforeCacheLookup() {
        assertThatThrownBy(() -> service.generatePersonal(11L, 8L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(images);
    }

    /** 草稿、下架、删除和非标准对象都应在成图前拒绝。 */
    @ParameterizedTest
    @ValueSource(strings = {"draft", "offline", "deleted", "disabled", "advanced", "schema", "missing-config", "bad-config", "missing-revision"})
    void unavailablePersonalNeverGenerates(String kind) {
        invalidate(kind);
        assertThatThrownBy(() -> service.generatePersonal(11L, 7L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(images);
    }

    /** 无效身份和空路径参数必须由应用层拒绝。 */
    @Test
    void nullIdentityAndTargetAreRejected() {
        assertThatThrownBy(() -> service.generatePersonal(11L, null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.generatePersonal(null, 7L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.generateTeam(11L, null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.generateTeam(null, 7L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(images);
    }

    /** 命中缓存后发生发布态、归属或资料变化时，不返回过期图片。 */
    @ParameterizedTest
    @ValueSource(strings = {"offline", "deleted", "owner", "revision", "title", "avatar", "name"})
    void personalRevalidatesAfterImageResult(String change) {
        when(images.generate(any(), eq(7L))).thenAnswer(invocation -> {
            switch (change) {
                case "owner" -> portfolio.setOwnerId(8L);
                case "revision" -> portfolio.setPublishedRevision(4);
                case "title" -> portfolio.setPublishedConfigJson("{\"share\":{\"title\":\"新标题\"}}");
                case "avatar" -> user.setAvatarUrl("https://cdn.example.invalid/new.png");
                case "name" -> user.setNickname("新姓名");
                default -> invalidate(change);
            }
            return new PortfolioMiniappCodeResponse("https://cdn.example.invalid/stale.png", "", "v1", PortfolioOwnerTypeDict.USER.getCode(), "name", "city", "title", 1080, 1440, 800, 120);
        });
        assertThatThrownBy(() -> service.generatePersonal(11L, 7L)).isInstanceOf(BusinessException.class);
    }

    /** 草稿修改不影响发布快照，也不使当前码图失效。 */
    @Test
    void draftChangeDuringGenerationDoesNotInvalidatePublishedSnapshot() {
        when(images.generate(any(), eq(7L))).thenAnswer(invocation -> {
            portfolio.setDraftConfigJson("{\"share\":{\"title\":\"新的秘密草稿\"}}");
            portfolio.setVersion(99);
            return new PortfolioMiniappCodeResponse("https://cdn.example.invalid/code.png", "", "v1", PortfolioOwnerTypeDict.USER.getCode(), "name", "city", "title", 1080, 1440, 800, 120);
        });
        assertThat(service.generatePersonal(11L, 7L).getWidth()).isEqualTo(1080);
    }

    /** 普通已加入成员也能生成，且只使用团队名称、城市及头像。 */
    @Test
    void joinedOrdinaryTeamMemberUsesOnlyTeamProfile() {
        useTeam();
        service.generateTeam(11L, 7L);
        PortfolioMiniappCodeSnapshot snapshot = capturedSnapshot();
        assertThat(snapshot.ownerType()).isEqualTo(PortfolioOwnerTypeDict.TEAM.getCode());
        assertThat(snapshot.ownerId()).isEqualTo(21L);
        assertThat(snapshot.uniqueCode()).isEqualTo("TMTEAM");
        assertThat(snapshot.displayName()).isEqualTo("团队名称");
        assertThat(snapshot.subtitle()).isEqualTo("上海");
        assertThat(snapshot.avatarUrl()).isEqualTo("https://cdn.example.invalid/team.png");
        verifyNoInteractions(users);
    }

    /** 团队未配置头像时保持空值，绝不借用普通成员头像。 */
    @Test
    void teamMissingAvatarUsesNoMemberFallback() {
        useTeam();
        team.setAvatarUrl(null);
        team.setCity(null);
        service.generateTeam(11L, 7L);
        assertThat(capturedSnapshot().avatarUrl()).isNull();
        assertThat(capturedSnapshot().subtitle()).isEmpty();
        verifyNoInteractions(users);
    }

    /** 非成员及未正式加入成员不能通过缓存绕过权限。 */
    @ParameterizedTest
    @ValueSource(strings = {"absent", "pending", "removed"})
    void unjoinedMemberCannotGenerate(String state) {
        useTeam();
        if (state.equals("absent")) when(members.selectOne(any())).thenReturn(null);
        else member.setJoinStatus(state.equals("pending") ? JoinStatusDict.PENDING_CONFIRMATION.getCode() : JoinStatusDict.REMOVED.getCode());
        assertThatThrownBy(() -> service.generateTeam(11L, 7L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(images);
    }

    /** 返回前再次检查成员、团队有效性与当前发布快照，覆盖缓存命中路径。 */
    @ParameterizedTest
    @ValueSource(strings = {"removed", "missing-member", "dissolved", "offline", "deleted", "revision", "avatar", "team-deleted", "member-deleted"})
    void teamRevalidatesAfterImageResult(String change) {
        useTeam();
        when(images.generate(any(), eq(7L))).thenAnswer(invocation -> {
            switch (change) {
                case "removed" -> member.setJoinStatus(JoinStatusDict.REMOVED.getCode());
                case "missing-member" -> when(members.selectOne(any())).thenReturn(null);
                case "dissolved" -> team.setStatus(TeamStatusDict.DISSOLVED.getCode());
                case "revision" -> portfolio.setPublishedRevision(4);
                case "avatar" -> team.setAvatarUrl("https://cdn.example.invalid/new-team.png");
                case "team-deleted" -> team.setDeleted(21L);
                case "member-deleted" -> member.setDeleted(31L);
                default -> invalidate(change);
            }
            return new PortfolioMiniappCodeResponse("https://cdn.example.invalid/stale.png", "", "v1", PortfolioOwnerTypeDict.USER.getCode(), "name", "city", "title", 1080, 1440, 800, 120);
        });
        assertThatThrownBy(() -> service.generateTeam(11L, 7L)).isInstanceOf(BusinessException.class);
    }

    /** 下架团队作品集不能开始生成。 */
    @Test
    void unpublishedTeamDoesNotGenerate() {
        useTeam();
        portfolio.setPublicationStatus(PortfolioPublicationStatusDict.DRAFT_ONLY.getCode());
        assertThatThrownBy(() -> service.generateTeam(11L, 7L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(images);
    }

    /** 捕获跨越外部生成边界的公开快照。 */
    private PortfolioMiniappCodeSnapshot capturedSnapshot() {
        ArgumentCaptor<PortfolioMiniappCodeSnapshot> captor = ArgumentCaptor.forClass(PortfolioMiniappCodeSnapshot.class);
        verify(images).generate(captor.capture(), eq(7L));
        return captor.getValue();
    }

    /** 将个人样例切换到团队普通成员样例。 */
    private void useTeam() {
        portfolio.setOwnerType(PortfolioOwnerTypeDict.TEAM.getCode());
        portfolio.setOwnerId(21L);
        portfolio.setSchemaVersion(TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1);
        portfolio.setShareCode("TPF1234567890123456");
        team = new TeamEntity();
        team.setId(21L);
        team.setUniqueCode("TMTEAM");
        team.setName("团队名称");
        team.setCity("上海");
        team.setAvatarUrl("https://cdn.example.invalid/team.png");
        team.setStatus(TeamStatusDict.ACTIVE.getCode());
        team.setDeleted(0L);
        when(teams.selectById(21L)).thenReturn(team);
        member = new TeamMemberEntity();
        member.setId(31L);
        member.setTeamId(21L);
        member.setUserId(7L);
        member.setRole(TeamRoleDict.MEMBER.getCode());
        member.setJoinStatus(JoinStatusDict.JOINED.getCode());
        member.setDeleted(0L);
        when(members.selectOne(any())).thenReturn(member);
    }

    /** 模拟独立的作品集失效原因。 */
    private void invalidate(String kind) {
        switch (kind) {
            case "draft" -> portfolio.setPublicationStatus(PortfolioPublicationStatusDict.DRAFT_ONLY.getCode());
            case "offline" -> portfolio.setPublicationStatus(PortfolioPublicationStatusDict.OFFLINE.getCode());
            case "deleted" -> portfolio.setDeleted(11L);
            case "disabled" -> portfolio.setStatus(PortfolioStatusDict.DISABLED.getCode());
            case "advanced" -> portfolio.setTemplateType(PortfolioTemplateTypeDict.ADVANCED.getCode());
            case "schema" -> portfolio.setSchemaVersion("unsupported");
            case "missing-config" -> portfolio.setPublishedConfigJson(null);
            case "bad-config" -> portfolio.setPublishedConfigJson("not-json");
            case "missing-revision" -> portfolio.setPublishedRevision(null);
            default -> throw new IllegalArgumentException(kind);
        }
    }
}
