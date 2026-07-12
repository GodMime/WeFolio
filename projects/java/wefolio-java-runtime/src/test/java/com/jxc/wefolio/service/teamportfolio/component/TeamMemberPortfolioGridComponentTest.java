package com.jxc.wefolio.service.teamportfolio.component;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jxc.wefolio.dict.JoinStatusDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAccessService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import com.jxc.wefolio.service.teamportfolio.component.memberportfoliogrid.TeamMemberPortfolioGridComponentConfig;
import com.jxc.wefolio.service.teamportfolio.component.memberportfoliogrid.TeamMemberPortfolioGridComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.memberportfoliogrid.TeamMemberPortfolioGridComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.memberportfoliogrid.TeamMemberPortfolioGridComponentService;
import com.jxc.wefolio.service.teamportfolio.component.memberportfoliogrid.TeamMemberPortfolioGridComponentValidator;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 双列成员作品集组件的独立契约测试。
 */
@ExtendWith(MockitoExtension.class)
class TeamMemberPortfolioGridComponentTest {

    /** 组件生产源码目录。 */
    private static final Path GRID_SOURCE = Path.of("service/teamportfolio/component/memberportfoliogrid");

    /** 团队 ID。 */
    private static final long TEAM_ID = 11L;

    /** 团队作品集 ID。 */
    private static final long TEAM_PORTFOLIO_ID = 21L;

    /** 标准个人作品集 Schema 版本。 */
    private static final String STANDARD_PERSONAL_SCHEMA_VERSION = "standard-personal-v1";

    /** 组件实例键。 */
    private static final String COMPONENT_KEY = "member-grid-1";

    /** 组件路径。 */
    private static final String COMPONENT_PATH = "components[3]";

    /** 组件不得依赖的个人作品集完整类型名。 */
    private static final Set<String> FORBIDDEN_PERSONAL_DEPENDENCY_TYPES = Set.of(
            "com.jxc.wefolio.dto.PortfolioConfigDto",
            "com.jxc.wefolio.dto.PortfolioRenderDto",
            "com.jxc.wefolio.service.MinePortfolioService",
            "com.jxc.wefolio.service.VisitorPortfolioService",
            "com.jxc.wefolio.service.PortfolioConfigValidator",
            "com.jxc.wefolio.service.PortfolioRenderService",
            "com.jxc.wefolio.service.PortfolioVisitService",
            "com.jxc.wefolio.service.ContactLeadService");

    /** 组件内禁止出现的通用字段标识或 JSON 键。 */
    private static final Pattern FORBIDDEN_FIELD_PATTERN = Pattern.compile(
            "(?i)\\b(?:tags|groups|labels|configScope)\\b");

    /** 成员关系 Mapper 模拟。 */
    @Mock
    private TeamMemberEntityMapper teamMemberEntityMapper;

    /** 用户 Mapper 模拟。 */
    @Mock
    private UserEntityMapper userEntityMapper;

    /** 作品集 Mapper 模拟。 */
    @Mock
    private PortfolioEntityMapper portfolioEntityMapper;

    /** 团队作品集访问控制服务模拟。 */
    @Mock
    private TeamPortfolioAccessService teamPortfolioAccessService;

    /** 初始化 LambdaQueryWrapper 所需的实体表信息。 */
    @BeforeAll
    static void initializeTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TeamMemberEntity.class);
        TableInfoHelper.initTableInfo(assistant, PortfolioEntity.class);
    }

    /** 配置模型只能包含有序的成员和个人作品集 ID。 */
    @Test
    void configShouldOwnOnlyItemsAndIds() {
        assertFields(TeamMemberPortfolioGridComponentConfig.class, "items");
        assertFields(TeamMemberPortfolioGridComponentConfig.Item.class, "memberUserId", "portfolioId");
    }

    /** 校验、渲染和引用提取器必须注册为可注入的 Spring 组件。 */
    @Test
    void validatorRendererAndExtractorShouldBeSpringComponents() {
        assertThat(TeamMemberPortfolioGridComponentValidator.class.isAnnotationPresent(Component.class)).isTrue();
        assertThat(TeamMemberPortfolioGridComponentRenderer.class.isAnnotationPresent(Component.class)).isTrue();
        assertThat(TeamMemberPortfolioGridComponentReferenceExtractor.class.isAnnotationPresent(Component.class)).isTrue();
    }

    /** 校验器应在查询前拒绝畸形、缺失、小数和溢出标识。 */
    @Test
    void validatorShouldRejectMalformedAndLossyIdsBeforeQueries() {
        List<JSONObject> invalidConfigs = List.of(
                new JSONObject(), JSON.parseObject("{\"items\":{}}"), JSON.parseObject("{\"items\":[1]}"),
                itemsConfig(item(null, 101L)), itemsConfig(item("1", 101L)), itemsConfig(item(1L, "101")),
                itemsConfig(item(new BigDecimal("1.5"), 101L)), itemsConfig(item(1L, new BigDecimal("101.5"))),
                itemsConfig(item(new BigInteger("9223372036854775808"), 101L)),
                itemsConfig(item(1L, new BigDecimal("9223372036854775808"))), itemsConfig(item(0L, 101L)),
                itemsConfig(item(1L, -1L)));

        for (JSONObject config : invalidConfigs) {
            assertInvalid(() -> validator().normalizeAndValidate(config, context()), "双列作品集配置不正确");
        }
        verifyNoInteractions(teamMemberEntityMapper, userEntityMapper, portfolioEntityMapper);
    }

    /** 校验器应接受无损 BigDecimal 整数并以完整谓词执行三次批量查询。 */
    @Test
    void validatorShouldAcceptIntegralBigDecimalAndBatchWithExactPredicates() {
        stubValidPair(1L, 101L);

        JSONObject normalized = validator().normalizeAndValidate(
                itemsConfig(item(new BigDecimal("1.0"), new BigDecimal("101.00"))), context());

        assertThat(normalized.toJSONString()).isEqualTo("{\"items\":[{\"memberUserId\":1,\"portfolioId\":101}]}");
        assertMembershipQuery(captureMembershipListQuery(), true, TEAM_ID, 1L);
        verify(userEntityMapper).selectBatchIds(Set.of(1L));
        assertPortfolioIdsQuery(capturePortfolioListQuery(), 101L);
    }

    /** 校验器应保留顺序、拒绝空配置和重复作品集，且成员失败严格短路。 */
    @Test
    void validatorShouldPreserveOrderRejectEmptyDuplicateAndShortCircuitMembershipFailure() {
        assertInvalid(() -> validator().normalizeAndValidate(itemsConfig(), context()), "双列作品集至少选择一个作品集");
        assertInvalid(() -> validator().normalizeAndValidate(itemsConfig(item(1L, 101L), item(2L, 101L)), context()),
                "双列作品集不能重复选择同一作品集");

        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of());
        assertInvalid(() -> validator().normalizeAndValidate(itemsConfig(item(1L, 101L)), context()), "成员作品集不存在或未发布");
        verify(userEntityMapper, never()).selectBatchIds(any());
        verify(portfolioEntityMapper, never()).selectList(any());

        reset(teamMemberEntityMapper, userEntityMapper, portfolioEntityMapper);
        stubValidPairs(List.of(pair(1L, 101L), pair(2L, 102L)));
        JSONObject normalized = validator().normalizeAndValidate(itemsConfig(item(2L, 102L), item(1L, 101L)), context());
        assertThat(normalized.toJSONString()).isEqualTo(
                "{\"items\":[{\"memberUserId\":2,\"portfolioId\":102},{\"memberUserId\":1,\"portfolioId\":101}]}");
    }

    /** 校验器必须拒绝成员、用户与作品集任一重校验谓词不满足及坏发布 JSON。 */
    @Test
    void validatorShouldRequireMembershipActiveUserAndPublishedPersonalPortfolio() {
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(member(1L, 1L)));
        when(userEntityMapper.selectBatchIds(Set.of(1L))).thenReturn(List.of(disabledUser(1L)));
        assertInvalid(() -> validator().normalizeAndValidate(itemsConfig(item(1L, 101L)), context()), "成员作品集不存在或未发布");
        verify(portfolioEntityMapper, never()).selectList(any());

        reset(teamMemberEntityMapper, userEntityMapper, portfolioEntityMapper);
        stubValidPair(1L, 101L);
        PortfolioEntity invalid = personalPortfolio(101L, 1L);
        invalid.setOwnerType(PortfolioOwnerTypeDict.TEAM.getCode());
        when(portfolioEntityMapper.selectList(any())).thenReturn(List.of(invalid));
        assertInvalid(() -> validator().normalizeAndValidate(itemsConfig(item(1L, 101L)), context()), "成员作品集不存在或未发布");

        PortfolioEntity missingOwnerId = personalPortfolio(101L, 1L);
        missingOwnerId.setOwnerId(null);
        for (PortfolioEntity invalidPortfolio : List.of(
                missingOwnerId,
                personalPortfolio(101L, 2L),
                portfolioWith(101L, 1L, PortfolioTemplateTypeDict.ADVANCED.getCode(), PortfolioStatusDict.ACTIVE.getCode(),
                        STANDARD_PERSONAL_SCHEMA_VERSION, PortfolioPublicationStatusDict.PUBLISHED.getCode(), publishedJson("标题", "封面")),
                portfolioWith(101L, 1L, PortfolioTemplateTypeDict.STANDARD.getCode(), PortfolioStatusDict.DISABLED.getCode(),
                        STANDARD_PERSONAL_SCHEMA_VERSION, PortfolioPublicationStatusDict.PUBLISHED.getCode(), publishedJson("标题", "封面")),
                portfolioWith(101L, 1L, PortfolioTemplateTypeDict.STANDARD.getCode(), PortfolioStatusDict.ACTIVE.getCode(),
                        "other-schema", PortfolioPublicationStatusDict.PUBLISHED.getCode(), publishedJson("标题", "封面")),
                portfolioWith(101L, 1L, PortfolioTemplateTypeDict.STANDARD.getCode(), PortfolioStatusDict.ACTIVE.getCode(),
                        STANDARD_PERSONAL_SCHEMA_VERSION, PortfolioPublicationStatusDict.DRAFT_ONLY.getCode(), publishedJson("标题", "封面")),
                portfolioWith(101L, 1L, PortfolioTemplateTypeDict.STANDARD.getCode(), PortfolioStatusDict.ACTIVE.getCode(),
                        STANDARD_PERSONAL_SCHEMA_VERSION, PortfolioPublicationStatusDict.PUBLISHED.getCode(), "{"),
                portfolioWith(101L, 1L, PortfolioTemplateTypeDict.STANDARD.getCode(), PortfolioStatusDict.ACTIVE.getCode(),
                        STANDARD_PERSONAL_SCHEMA_VERSION, PortfolioPublicationStatusDict.PUBLISHED.getCode(), "{\"share\":{\"title\":\" \"}}"))) {
            reset(teamMemberEntityMapper, userEntityMapper, portfolioEntityMapper);
            when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(member(1L, 1L)));
            when(userEntityMapper.selectBatchIds(Set.of(1L))).thenReturn(List.of(user(1L)));
            when(portfolioEntityMapper.selectList(any())).thenReturn(List.of(invalidPortfolio));
            assertInvalid(() -> validator().normalizeAndValidate(itemsConfig(item(1L, 101L)), context()), "成员作品集不存在或未发布");
        }
    }

    /** 渲染器必须在查询前独立重校验配置，在查询后重校验所有关联数据。 */
    @Test
    void rendererShouldRevalidateBeforeQueriesAndKeepAllDataPredicates() {
        for (JSONObject invalid : List.of(new JSONObject(), itemsConfig(), itemsConfig(item(1L, 101L), item(2L, 101L)),
                itemsConfig(item(new BigDecimal("1.1"), 101L)))) {
            assertThatThrownBy(() -> renderer().render(invalid, context())).isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(teamMemberEntityMapper, userEntityMapper, portfolioEntityMapper);

        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of());
        assertInvalid(() -> renderer().render(itemsConfig(item(1L, 101L)), context()), "成员作品集不存在或未发布");
        verifyNoInteractions(userEntityMapper, portfolioEntityMapper);

        reset(teamMemberEntityMapper, userEntityMapper, portfolioEntityMapper);
        TeamMemberEntity deniedMembership = member(1L, 1L);
        deniedMembership.setAllowPortfolio(0);
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(deniedMembership));
        assertInvalid(() -> renderer().render(itemsConfig(item(1L, 101L)), context()), "成员作品集不存在或未发布");
        verifyNoInteractions(userEntityMapper, portfolioEntityMapper);

        reset(teamMemberEntityMapper, userEntityMapper, portfolioEntityMapper);
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(member(1L, 1L)));
        when(userEntityMapper.selectBatchIds(Set.of(1L))).thenReturn(List.of(disabledUser(1L)));
        assertInvalid(() -> renderer().render(itemsConfig(item(1L, 101L)), context()), "成员作品集不存在或未发布");
        verify(portfolioEntityMapper, never()).selectList(any());

        PortfolioEntity missingOwnerId = personalPortfolio(101L, 1L);
        missingOwnerId.setOwnerId(null);
        PortfolioEntity invalidOwnerType = personalPortfolio(101L, 1L);
        invalidOwnerType.setOwnerType(PortfolioOwnerTypeDict.TEAM.getCode());
        PortfolioEntity invalidShareCode = personalPortfolio(101L, 1L);
        invalidShareCode.setShareCode(" ");
        for (PortfolioEntity invalidPortfolio : List.of(
                missingOwnerId,
                personalPortfolio(101L, 2L),
                invalidOwnerType,
                portfolioWith(101L, 1L, PortfolioTemplateTypeDict.ADVANCED.getCode(), PortfolioStatusDict.ACTIVE.getCode(),
                        STANDARD_PERSONAL_SCHEMA_VERSION, PortfolioPublicationStatusDict.PUBLISHED.getCode(), publishedJson("标题", "封面")),
                portfolioWith(101L, 1L, PortfolioTemplateTypeDict.STANDARD.getCode(), PortfolioStatusDict.DISABLED.getCode(),
                        STANDARD_PERSONAL_SCHEMA_VERSION, PortfolioPublicationStatusDict.PUBLISHED.getCode(), publishedJson("标题", "封面")),
                portfolioWith(101L, 1L, PortfolioTemplateTypeDict.STANDARD.getCode(), PortfolioStatusDict.ACTIVE.getCode(),
                        "other-schema", PortfolioPublicationStatusDict.PUBLISHED.getCode(), publishedJson("标题", "封面")),
                portfolioWith(101L, 1L, PortfolioTemplateTypeDict.STANDARD.getCode(), PortfolioStatusDict.ACTIVE.getCode(),
                        STANDARD_PERSONAL_SCHEMA_VERSION, PortfolioPublicationStatusDict.DRAFT_ONLY.getCode(), publishedJson("标题", "封面")),
                invalidShareCode,
                portfolioWith(101L, 1L, PortfolioTemplateTypeDict.STANDARD.getCode(), PortfolioStatusDict.ACTIVE.getCode(),
                        STANDARD_PERSONAL_SCHEMA_VERSION, PortfolioPublicationStatusDict.PUBLISHED.getCode(), "{"),
                portfolioWith(101L, 1L, PortfolioTemplateTypeDict.STANDARD.getCode(), PortfolioStatusDict.ACTIVE.getCode(),
                        STANDARD_PERSONAL_SCHEMA_VERSION, PortfolioPublicationStatusDict.PUBLISHED.getCode(), "{\"share\":{\"title\":\" \"}}"))) {
            reset(teamMemberEntityMapper, userEntityMapper, portfolioEntityMapper);
            when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(member(1L, 1L)));
            when(userEntityMapper.selectBatchIds(Set.of(1L))).thenReturn(List.of(user(1L)));
            when(portfolioEntityMapper.selectList(any())).thenReturn(List.of(invalidPortfolio));
            assertInvalid(() -> renderer().render(itemsConfig(item(1L, 101L)), context()), "成员作品集不存在或未发布");
        }
    }

    /** 渲染器应批量读取并返回有序、脱离输入且字段精确的条目。 */
    @Test
    void rendererShouldRenderOrderedDetachedItemsWithExactShape() {
        TeamMemberEntity secondMember = member(2L, 2L);
        TeamMemberEntity firstMember = member(1L, 1L);
        UserEntity secondUser = user(2L);
        secondUser.setNickname("乙");
        secondUser.setAvatarUrl("avatar-2");
        UserEntity firstUser = user(1L);
        firstUser.setNickname("甲");
        firstUser.setAvatarUrl("avatar-1");
        PortfolioEntity secondPortfolio = personalPortfolio(102L, 2L);
        secondPortfolio.setPublishedConfigJson(publishedJson("乙作品集", "cover-2"));
        PortfolioEntity firstPortfolio = personalPortfolio(101L, 1L);
        firstPortfolio.setPublishedConfigJson(publishedJson("甲作品集", "cover-1"));
        firstPortfolio.setPublishedRevision(8);
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(firstMember, secondMember));
        when(userEntityMapper.selectBatchIds(Set.of(1L, 2L))).thenReturn(List.of(firstUser, secondUser));
        when(portfolioEntityMapper.selectList(any())).thenReturn(List.of(firstPortfolio, secondPortfolio));

        JSONObject config = itemsConfig(item(2L, 102L), item(1L, 101L));
        JSONObject rendered = renderer().render(config, context());
        config.getJSONArray("items").getJSONObject(0).put("portfolioId", 999L);

        assertThat(rendered.getJSONArray("items")).hasSize(2);
        JSONObject first = rendered.getJSONArray("items").getJSONObject(0);
        assertThat(first.keySet()).containsExactly("memberUserId", "memberDisplayName", "memberAvatarUrl", "portfolioId",
                "title", "coverUrl", "shareCode", "publishedRevision");
        assertThat(first).containsEntry("memberUserId", 2L).containsEntry("memberDisplayName", "乙")
                .containsEntry("memberAvatarUrl", "avatar-2").containsEntry("portfolioId", 102L)
                .containsEntry("title", "乙作品集").containsEntry("coverUrl", "cover-2")
                .containsEntry("shareCode", "share-102").containsEntry("publishedRevision", 1);
        assertThat(rendered.getJSONArray("items").getJSONObject(1)).containsEntry("portfolioId", 101L)
                .containsEntry("title", "甲作品集").containsEntry("publishedRevision", 8);
        verify(teamMemberEntityMapper).selectList(any());
        verify(userEntityMapper).selectBatchIds(Set.of(1L, 2L));
        verify(portfolioEntityMapper).selectList(any());
    }

    /** 提取器应继承渲染器的查询前校验，并精确保存完整渲染条目快照。 */
    @Test
    void extractorShouldUseExactRenderedSnapshotsAndLeaveScopeUnset() {
        stubValidPair(1L, 101L);
        TeamMemberPortfolioGridComponentRenderer renderer = renderer();
        JSONObject rendered = renderer.render(itemsConfig(item(1L, 101L)), context());
        List<PortfolioReferenceEntity> references = new TeamMemberPortfolioGridComponentReferenceExtractor(renderer)
                .extract(COMPONENT_KEY, COMPONENT_PATH, itemsConfig(item(1L, 101L)), context());

        assertThat(references).hasSize(1);
        PortfolioReferenceEntity reference = references.getFirst();
        assertThat(reference.getPortfolioId()).isEqualTo(TEAM_PORTFOLIO_ID);
        assertThat(reference.getReferenceType()).isEqualTo("MEMBER_PORTFOLIO");
        assertThat(reference.getReferenceId()).isEqualTo(101L);
        assertThat(reference.getComponentKey()).isEqualTo(COMPONENT_KEY);
        assertThat(reference.getComponentPath()).isEqualTo("components[3].items[0]");
        assertThat(reference.getSortOrder()).isZero();
        assertThat(reference.getIsValid()).isEqualTo(1);
        assertThat(reference.getConfigScope()).isNull();
        assertThat(reference.getSnapshotJson()).isEqualTo(JSON.toJSONString(
                rendered.getJSONArray("items").getJSONObject(0), JSONWriter.Feature.WriteNulls));

        reset(teamMemberEntityMapper, userEntityMapper, portfolioEntityMapper);
        assertThatThrownBy(() -> new TeamMemberPortfolioGridComponentReferenceExtractor(renderer())
                .extract(COMPONENT_KEY, COMPONENT_PATH, itemsConfig(), context())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(teamMemberEntityMapper, userEntityMapper, portfolioEntityMapper);
    }

    /** 成员来源接口应先通过访问控制，再按成员关系顺序批量筛选活跃账号。 */
    @Test
    void serviceShouldListActiveMembersInMembershipOrderWithTwoQueries() {
        when(teamPortfolioAccessService.requireMaintainablePortfolio(TEAM_PORTFOLIO_ID, 99L)).thenReturn(access());
        TeamMemberEntity first = member(3L, 3L);
        first.setProfession("策划");
        TeamMemberEntity second = member(4L, 4L);
        second.setProfession("摄影师");
        UserEntity active = user(4L);
        active.setNickname("乙");
        active.setAvatarUrl("avatar-4");
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(first, second));
        when(userEntityMapper.selectBatchIds(Set.of(3L, 4L))).thenReturn(List.of(active, disabledUser(3L)));

        assertThat(service().listMembers(TEAM_PORTFOLIO_ID, 99L)).containsExactly(
                new TeamMemberPortfolioGridComponentService.MemberOption(4L, "乙", "avatar-4", "摄影师"));
        verify(teamPortfolioAccessService).requireMaintainablePortfolio(TEAM_PORTFOLIO_ID, 99L);
        assertMembershipQuery(captureMembershipListQuery(), false, TEAM_ID);
        verify(userEntityMapper).selectBatchIds(Set.of(3L, 4L));
        verify(portfolioEntityMapper, never()).selectList(any());
    }

    /** 个人作品集来源应先核验访问、成员和活跃账号，再按发布时间和 ID 逆序一次查询。 */
    @Test
    void serviceShouldListPublishedPortfoliosAfterMembershipAndUserChecks() {
        when(teamPortfolioAccessService.requireMaintainablePortfolio(TEAM_PORTFOLIO_ID, 99L)).thenReturn(access());
        when(teamMemberEntityMapper.selectOne(any())).thenReturn(member(1L, 1L));
        when(userEntityMapper.selectById(1L)).thenReturn(user(1L));
        PortfolioEntity latest = personalPortfolio(102L, 1L);
        latest.setPublishedAt(LocalDateTime.now());
        latest.setPublishedConfigJson(publishedJson("最新", "cover-2"));
        PortfolioEntity malformed = personalPortfolio(101L, 1L);
        malformed.setPublishedConfigJson("{");
        when(portfolioEntityMapper.selectList(any())).thenReturn(List.of(latest, malformed));

        assertThat(service().listPortfolios(TEAM_PORTFOLIO_ID, 1L, 99L)).containsExactly(
                new TeamMemberPortfolioGridComponentService.PortfolioOption(102L, "最新", "cover-2", "share-102", 1));
        verify(teamPortfolioAccessService).requireMaintainablePortfolio(TEAM_PORTFOLIO_ID, 99L);
        LambdaQueryWrapper<TeamMemberEntity> membershipQuery = captureMembershipOneQuery();
        assertThat(membershipQuery.getSqlSegment()).contains("team_id", "user_id", "join_status", "allow_portfolio", "LIMIT 1");
        assertThat(membershipQuery.getParamNameValuePairs().values()).contains(TEAM_ID, 1L, JoinStatusDict.JOINED.getCode(), 1);
        verify(userEntityMapper).selectById(1L);
        assertSourcePortfolioQuery(capturePortfolioListQuery(), 1L);
    }

    /** 访问控制、成员关系和用户可用性失败都应使来源服务立即短路。 */
    @Test
    void serviceShouldShortCircuitAccessMembershipAndInactiveUserFailures() {
        when(teamPortfolioAccessService.requireMaintainablePortfolio(TEAM_PORTFOLIO_ID, 99L))
                .thenThrow(new BusinessException("无维护权限"));
        assertInvalid(() -> service().listMembers(TEAM_PORTFOLIO_ID, 99L), "无维护权限");
        assertInvalid(() -> service().listPortfolios(TEAM_PORTFOLIO_ID, 1L, 99L), "无维护权限");
        verifyNoInteractions(teamMemberEntityMapper, userEntityMapper, portfolioEntityMapper);

        reset(teamPortfolioAccessService, teamMemberEntityMapper, userEntityMapper, portfolioEntityMapper);
        when(teamPortfolioAccessService.requireMaintainablePortfolio(TEAM_PORTFOLIO_ID, 99L)).thenReturn(access());
        when(teamMemberEntityMapper.selectOne(any())).thenReturn(null);
        assertInvalid(() -> service().listPortfolios(TEAM_PORTFOLIO_ID, 1L, 99L), "团队成员不存在或不可用");
        verifyNoInteractions(userEntityMapper, portfolioEntityMapper);

        reset(teamPortfolioAccessService, teamMemberEntityMapper, userEntityMapper, portfolioEntityMapper);
        when(teamPortfolioAccessService.requireMaintainablePortfolio(TEAM_PORTFOLIO_ID, 99L)).thenReturn(access());
        when(teamMemberEntityMapper.selectOne(any())).thenReturn(member(1L, 1L));
        when(userEntityMapper.selectById(1L)).thenReturn(disabledUser(1L));
        assertInvalid(() -> service().listPortfolios(TEAM_PORTFOLIO_ID, 1L, 99L), "团队成员不存在或不可用");
        verify(portfolioEntityMapper, never()).selectList(any());
    }

    /** 所有 Mapper 的空返回应保持空集合或业务失败语义，不能发生空指针。 */
    @Test
    void componentShouldHandleNullMapperResultsSafely() {
        when(teamMemberEntityMapper.selectList(any())).thenReturn(null);
        assertInvalid(() -> validator().normalizeAndValidate(itemsConfig(item(1L, 101L)), context()), "成员作品集不存在或未发布");
        assertInvalid(() -> renderer().render(itemsConfig(item(1L, 101L)), context()), "成员作品集不存在或未发布");
        verify(userEntityMapper, never()).selectBatchIds(any());

        reset(teamMemberEntityMapper, userEntityMapper, portfolioEntityMapper);
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(member(1L, 1L)));
        when(userEntityMapper.selectBatchIds(Set.of(1L))).thenReturn(null);
        assertInvalid(() -> validator().normalizeAndValidate(itemsConfig(item(1L, 101L)), context()), "成员作品集不存在或未发布");
        assertInvalid(() -> renderer().render(itemsConfig(item(1L, 101L)), context()), "成员作品集不存在或未发布");
        verify(portfolioEntityMapper, never()).selectList(any());

        reset(teamMemberEntityMapper, userEntityMapper, portfolioEntityMapper);
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(member(1L, 1L)));
        when(userEntityMapper.selectBatchIds(Set.of(1L))).thenReturn(List.of(user(1L)));
        when(portfolioEntityMapper.selectList(any())).thenReturn(null);
        assertInvalid(() -> validator().normalizeAndValidate(itemsConfig(item(1L, 101L)), context()), "成员作品集不存在或未发布");
        assertInvalid(() -> renderer().render(itemsConfig(item(1L, 101L)), context()), "成员作品集不存在或未发布");

        reset(teamPortfolioAccessService, teamMemberEntityMapper, userEntityMapper, portfolioEntityMapper);
        when(teamPortfolioAccessService.requireMaintainablePortfolio(TEAM_PORTFOLIO_ID, 99L)).thenReturn(access());
        when(teamMemberEntityMapper.selectList(any())).thenReturn(null);
        assertThat(service().listMembers(TEAM_PORTFOLIO_ID, 99L)).isEmpty();
        verify(userEntityMapper, never()).selectBatchIds(any());

        reset(teamPortfolioAccessService, teamMemberEntityMapper, userEntityMapper, portfolioEntityMapper);
        when(teamPortfolioAccessService.requireMaintainablePortfolio(TEAM_PORTFOLIO_ID, 99L)).thenReturn(access());
        when(teamMemberEntityMapper.selectOne(any())).thenReturn(member(1L, 1L));
        when(userEntityMapper.selectById(1L)).thenReturn(null);
        assertInvalid(() -> service().listPortfolios(TEAM_PORTFOLIO_ID, 1L, 99L), "团队成员不存在或不可用");
        verify(portfolioEntityMapper, never()).selectList(any());

        reset(teamPortfolioAccessService, teamMemberEntityMapper, userEntityMapper, portfolioEntityMapper);
        when(teamPortfolioAccessService.requireMaintainablePortfolio(TEAM_PORTFOLIO_ID, 99L)).thenReturn(access());
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(member(1L, 1L)));
        when(userEntityMapper.selectBatchIds(Set.of(1L))).thenReturn(null);
        assertThat(service().listMembers(TEAM_PORTFOLIO_ID, 99L)).isEmpty();

        reset(teamPortfolioAccessService, teamMemberEntityMapper, userEntityMapper, portfolioEntityMapper);
        when(teamPortfolioAccessService.requireMaintainablePortfolio(TEAM_PORTFOLIO_ID, 99L)).thenReturn(access());
        when(teamMemberEntityMapper.selectOne(any())).thenReturn(null);
        assertInvalid(() -> service().listPortfolios(TEAM_PORTFOLIO_ID, 1L, 99L), "团队成员不存在或不可用");
        verifyNoInteractions(userEntityMapper, portfolioEntityMapper);

        reset(teamPortfolioAccessService, teamMemberEntityMapper, userEntityMapper, portfolioEntityMapper);
        when(teamPortfolioAccessService.requireMaintainablePortfolio(TEAM_PORTFOLIO_ID, 99L)).thenReturn(access());
        when(teamMemberEntityMapper.selectOne(any())).thenReturn(member(1L, 1L));
        when(userEntityMapper.selectById(1L)).thenReturn(user(1L));
        when(portfolioEntityMapper.selectList(any())).thenReturn(null);
        assertThat(service().listPortfolios(TEAM_PORTFOLIO_ID, 1L, 99L)).isEmpty();
    }

    /** 五个生产源码文件必须完整、使用配置 Bean 并保持禁止依赖扫描可用。 */
    @Test
    void gridProductionSourcesShouldBeCompleteIndependentAndScanRuleShouldWork() throws Exception {
        Path sourceRoot = Path.of("src/main/java/com/jxc/wefolio");
        List<Path> sources;
        try (var files = Files.list(sourceRoot.resolve(GRID_SOURCE))) {
            sources = files.filter(path -> path.getFileName().toString().endsWith(".java")).toList();
        }
        assertThat(sources).containsExactlyInAnyOrder(
                sourceRoot.resolve(GRID_SOURCE).resolve("TeamMemberPortfolioGridComponentConfig.java"),
                sourceRoot.resolve(GRID_SOURCE).resolve("TeamMemberPortfolioGridComponentValidator.java"),
                sourceRoot.resolve(GRID_SOURCE).resolve("TeamMemberPortfolioGridComponentRenderer.java"),
                sourceRoot.resolve(GRID_SOURCE).resolve("TeamMemberPortfolioGridComponentReferenceExtractor.java"),
                sourceRoot.resolve(GRID_SOURCE).resolve("TeamMemberPortfolioGridComponentService.java"));
        for (Path source : sources) {
            String content = Files.readString(source);
            assertThat(hasForbiddenDependency(content)).isFalse();
            assertThat(hasNonGridComponentDependency(content)).isFalse();
            assertThat(hasForbiddenField(content)).isFalse();
        }
        String validatorSource = Files.readString(sourceRoot.resolve(GRID_SOURCE)
                .resolve("TeamMemberPortfolioGridComponentValidator.java"));
        assertThat(validatorSource).contains("toJavaObject(TeamMemberPortfolioGridComponentConfig.class)", "selectBatchIds", "selectList");
        FORBIDDEN_PERSONAL_DEPENDENCY_TYPES.forEach(forbiddenType -> assertThat(
                hasForbiddenDependency("import " + forbiddenType + ";"))
                .as("禁止类型 %s 必须命中依赖扫描", forbiddenType)
                .isTrue());
        assertThat(hasNonGridComponentDependency(
                "import com.jxc.wefolio.service.teamportfolio.component.carousel.TeamCarouselComponentRenderer;"))
                .isTrue();
        assertThat(hasForbiddenField("private List<String> tags;")).isTrue();
        assertThat(hasForbiddenField("json.put(\"groups\", value);")).isTrue();
        assertThat(hasForbiddenField("json.put(\"labels\", value);")).isTrue();
        assertThat(hasForbiddenField("json.put(\"configScope\", value);")).isTrue();
    }

    /** 创建校验器。 */
    private TeamMemberPortfolioGridComponentValidator validator() {
        return new TeamMemberPortfolioGridComponentValidator(teamMemberEntityMapper, userEntityMapper, portfolioEntityMapper);
    }

    /** 创建渲染器。 */
    private TeamMemberPortfolioGridComponentRenderer renderer() {
        return new TeamMemberPortfolioGridComponentRenderer(teamMemberEntityMapper, userEntityMapper, portfolioEntityMapper);
    }

    /** 创建来源服务。 */
    private TeamMemberPortfolioGridComponentService service() {
        return new TeamMemberPortfolioGridComponentService(
                teamPortfolioAccessService, teamMemberEntityMapper, userEntityMapper, portfolioEntityMapper);
    }

    /** 返回固定组件上下文。 */
    private TeamPortfolioComponentContext context() {
        return new TeamPortfolioComponentContext(TEAM_ID, TEAM_PORTFOLIO_ID, 3);
    }

    /** 构造配置。 */
    private JSONObject itemsConfig(JSONObject... items) {
        JSONObject config = new JSONObject();
        config.put("items", new JSONArray(List.of(items)));
        return config;
    }

    /** 构造配置条目。 */
    private JSONObject item(Object memberUserId, Object portfolioId) {
        JSONObject item = new JSONObject();
        item.put("memberUserId", memberUserId);
        item.put("portfolioId", portfolioId);
        return item;
    }

    /** 构造有效的成员和作品集对。 */
    private long[] pair(long memberUserId, long portfolioId) {
        return new long[]{memberUserId, portfolioId};
    }

    /** 配置一组有效关联的批量 Mapper 返回。 */
    private void stubValidPairs(List<long[]> pairs) {
        List<TeamMemberEntity> members = pairs.stream().map(pair -> member(pair[0], pair[0])).toList();
        List<UserEntity> users = pairs.stream().map(pair -> user(pair[0])).toList();
        List<PortfolioEntity> portfolios = pairs.stream().map(pair -> personalPortfolio(pair[1], pair[0])).toList();
        when(teamMemberEntityMapper.selectList(any())).thenReturn(members);
        when(userEntityMapper.selectBatchIds(any())).thenReturn(users);
        when(portfolioEntityMapper.selectList(any())).thenReturn(portfolios);
    }

    /** 配置一组有效关联。 */
    private void stubValidPair(long memberUserId, long portfolioId) {
        stubValidPairs(List.of(pair(memberUserId, portfolioId)));
    }

    /** 构造有效成员关系。 */
    private TeamMemberEntity member(long id, long userId) {
        TeamMemberEntity entity = new TeamMemberEntity();
        entity.setId(id);
        entity.setTeamId(TEAM_ID);
        entity.setUserId(userId);
        entity.setProfession("摄影师");
        entity.setJoinStatus(JoinStatusDict.JOINED.getCode());
        entity.setAllowPortfolio(1);
        return entity;
    }

    /** 构造正常用户。 */
    private UserEntity user(long id) {
        UserEntity entity = new UserEntity();
        entity.setId(id);
        entity.setNickname("成员" + id);
        entity.setStatus(UserStatusDict.ACTIVE.getCode());
        return entity;
    }

    /** 构造禁用用户。 */
    private UserEntity disabledUser(long id) {
        UserEntity entity = user(id);
        entity.setStatus(UserStatusDict.DISABLED.getCode());
        return entity;
    }

    /** 构造有效个人作品集。 */
    private PortfolioEntity personalPortfolio(long id, long ownerId) {
        return portfolioWith(id, ownerId, PortfolioTemplateTypeDict.STANDARD.getCode(), PortfolioStatusDict.ACTIVE.getCode(),
                STANDARD_PERSONAL_SCHEMA_VERSION, PortfolioPublicationStatusDict.PUBLISHED.getCode(),
                publishedJson("作品集" + id, "cover-" + id));
    }

    /** 按指定状态构造个人作品集。 */
    private PortfolioEntity portfolioWith(long id, long ownerId, String templateType, String status, String schemaVersion,
                                          String publicationStatus, String publishedConfigJson) {
        PortfolioEntity entity = new PortfolioEntity();
        entity.setId(id);
        entity.setOwnerType(PortfolioOwnerTypeDict.USER.getCode());
        entity.setOwnerId(ownerId);
        entity.setTemplateType(templateType);
        entity.setStatus(status);
        entity.setSchemaVersion(schemaVersion);
        entity.setPublicationStatus(publicationStatus);
        entity.setPublishedConfigJson(publishedConfigJson);
        entity.setShareCode("share-" + id);
        entity.setPublishedRevision(1);
        return entity;
    }

    /** 构造发布配置。 */
    private String publishedJson(String title, String coverUrl) {
        return JSON.toJSONString(JSON.parseObject("{\"share\":{\"title\":\"" + title + "\",\"coverUrl\":\"" + coverUrl + "\"}}"));
    }

    /** 构造访问通过结果。 */
    private TeamPortfolioAccessService.TeamPortfolioAccess access() {
        TeamEntity team = new TeamEntity();
        team.setId(TEAM_ID);
        return new TeamPortfolioAccessService.TeamPortfolioAccess(null, team, null, true, true);
    }

    /** 断言业务异常。 */
    private void assertInvalid(Runnable action, String message) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class).hasMessage(message);
    }

    /** 捕获成员批量查询。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaQueryWrapper<TeamMemberEntity> captureMembershipListQuery() {
        ArgumentCaptor<LambdaQueryWrapper<TeamMemberEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(teamMemberEntityMapper).selectList(captor.capture());
        return captor.getValue();
    }

    /** 捕获单条成员查询。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaQueryWrapper<TeamMemberEntity> captureMembershipOneQuery() {
        ArgumentCaptor<LambdaQueryWrapper<TeamMemberEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(teamMemberEntityMapper).selectOne(captor.capture());
        return captor.getValue();
    }

    /** 捕获作品集批量查询。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaQueryWrapper<PortfolioEntity> capturePortfolioListQuery() {
        ArgumentCaptor<LambdaQueryWrapper<PortfolioEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(portfolioEntityMapper).selectList(captor.capture());
        return captor.getValue();
    }

    /** 断言成员查询的谓词与顺序。 */
    private void assertMembershipQuery(LambdaQueryWrapper<TeamMemberEntity> query, boolean containsUserIds, Object... values) {
        assertThat(query.getSqlSegment()).contains("team_id", "join_status", "allow_portfolio");
        if (containsUserIds) {
            assertThat(query.getSqlSegment()).contains("user_id", "IN");
        } else {
            assertThat(query.getSqlSegment()).contains("ORDER BY", "id", "ASC");
        }
        assertThat(query.getParamNameValuePairs().values()).contains(values);
        assertThat(query.getParamNameValuePairs().values()).contains(JoinStatusDict.JOINED.getCode(), 1);
    }

    /** 断言作品集 ID 批量查询。 */
    private void assertPortfolioIdsQuery(LambdaQueryWrapper<PortfolioEntity> query, Object... values) {
        assertThat(query.getSqlSegment()).contains("id", "IN");
        assertThat(query.getParamNameValuePairs().values()).contains(values);
    }

    /** 断言成员个人作品集来源查询的全部过滤与排序契约。 */
    private void assertSourcePortfolioQuery(LambdaQueryWrapper<PortfolioEntity> query, long memberUserId) {
        String sql = query.getSqlSegment();
        assertThat(sql).contains("owner_id", "owner_type", "template_type", "status", "schema_version",
                "publication_status");
        String compactSql = sql.replaceAll("\\s+", "");
        assertThat(compactSql).contains("published_config_jsonISNOTNULL", "TRIM(published_config_json)<>",
                "share_codeISNOTNULL", "TRIM(share_code)<>", "ORDERBYpublished_atDESC,idDESC");
        assertThat(query.getParamNameValuePairs().values()).containsExactlyInAnyOrder(memberUserId,
                PortfolioOwnerTypeDict.USER.getCode(), PortfolioTemplateTypeDict.STANDARD.getCode(),
                PortfolioStatusDict.ACTIVE.getCode(), STANDARD_PERSONAL_SCHEMA_VERSION,
                PortfolioPublicationStatusDict.PUBLISHED.getCode(), "", "");
    }

    /** 断言模型实例字段。 */
    private void assertFields(Class<?> type, String... expectedNames) {
        assertThat(Arrays.stream(type.getDeclaredFields()).filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)).containsExactlyInAnyOrder(expectedNames);
    }

    /** 判断源码是否包含禁止依赖。 */
    private boolean hasForbiddenDependency(String source) {
        return FORBIDDEN_PERSONAL_DEPENDENCY_TYPES.stream().anyMatch(source::contains);
    }

    /** 判断源码是否引用非当前组件包。 */
    private boolean hasNonGridComponentDependency(String source) {
        return Pattern.compile("com\\.jxc\\.wefolio\\.service\\.(?!teamportfolio\\.component\\.memberportfoliogrid(?:\\.|;))"
                        + "[A-Za-z0-9_.]*component(?:\\.|;)")
                .matcher(source).find();
    }

    /** 判断源码是否包含禁止的字段标识或 JSON 键。 */
    private boolean hasForbiddenField(String source) {
        return FORBIDDEN_FIELD_PATTERN.matcher(source).find();
    }
}
