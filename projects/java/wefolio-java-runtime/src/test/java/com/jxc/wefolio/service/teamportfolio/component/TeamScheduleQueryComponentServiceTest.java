package com.jxc.wefolio.service.teamportfolio.component;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jxc.wefolio.common.auth.VisitorContext;
import com.jxc.wefolio.constant.TeamPortfolioConstants;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioScheduleQueryRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioScheduleQueryResponse;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.entity.BaseEntity;
import com.jxc.wefolio.entity.ScheduleEntity;
import com.jxc.wefolio.entity.SlotDefinitionEntity;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.entity.TeamScheduleQueryRecordEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.ScheduleEntityMapper;
import com.jxc.wefolio.mapper.SlotDefinitionEntityMapper;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.mapper.TeamScheduleQueryRecordEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import com.jxc.wefolio.service.teamportfolio.component.schedulequery.TeamScheduleQueryComponentConfig;
import com.jxc.wefolio.service.teamportfolio.component.schedulequery.TeamScheduleQueryComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.schedulequery.TeamScheduleQueryComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.schedulequery.TeamScheduleQueryComponentService;
import com.jxc.wefolio.service.teamportfolio.component.schedulequery.TeamScheduleQueryComponentValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 团队档期查询组件的独立契约测试。
 */
@ExtendWith(MockitoExtension.class)
class TeamScheduleQueryComponentServiceTest {

    /** 组件生产源码目录。 */
    private static final Path COMPONENT_SOURCE = Path.of("service/teamportfolio/component/schedulequery");

    /** 团队 ID。 */
    private static final long TEAM_ID = 11L;

    /** 作品集 ID。 */
    private static final long PORTFOLIO_ID = 21L;

    /** 组件键。 */
    private static final String COMPONENT_KEY = "team-schedule-1";

    /** 组件路径。 */
    private static final String COMPONENT_PATH = "components[4]";

    /** 默认查询日期。 */
    private static final LocalDate QUERY_DATE = LocalDate.of(2026, 8, 1);

    /** 统一事件发生时间。 */
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 7, 11, 9, 30);

    /** 固定测试时钟。 */
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-11T01:30:00Z"), ZoneOffset.ofHours(8));

    /** 固定测试当天。 */
    private static final LocalDate FIXED_TODAY = LocalDate.of(2026, 7, 11);

    /** 个人 DTO 全限定名禁止规则。 */
    private static final Pattern PERSONAL_DTO_REFERENCE = Pattern.compile(
            "com\\.jxc\\.wefolio\\.dto\\.(?!teamportfolio(?:\\.|\\b))(?:\\*|[A-Za-z0-9_.$]+)");

    /** 顶层个人 Service 全限定名禁止规则。 */
    private static final Pattern TOP_LEVEL_PERSONAL_SERVICE_REFERENCE = Pattern.compile(
            "com\\.jxc\\.wefolio\\.service\\.(?!teamportfolio(?:\\.|\\b))(?:\\*|[A-Z][A-Za-z0-9_$]*)");

    /** 其他团队组件包全限定名禁止规则。 */
    private static final Pattern OTHER_TEAM_COMPONENT_REFERENCE = Pattern.compile(
            "com\\.jxc\\.wefolio\\.service\\.teamportfolio\\.component\\."
                    + "(?!schedulequery(?:\\.|\\b))(?:\\*|[A-Za-z0-9_.$]+)");

    /** 直接实例化渲染器禁止规则。 */
    private static final Pattern DIRECT_RENDERER_CONSTRUCTION = Pattern.compile(
            "new\\s+(?:com\\.jxc\\.wefolio\\.service\\.teamportfolio\\.component\\.schedulequery\\.)?"
                    + "TeamScheduleQueryComponentRenderer\\s*\\(");

    /** 团队成员 Mapper。 */
    @Mock
    private TeamMemberEntityMapper teamMemberEntityMapper;

    /** 用户 Mapper。 */
    @Mock
    private UserEntityMapper userEntityMapper;

    /** 档位定义 Mapper。 */
    @Mock
    private SlotDefinitionEntityMapper slotDefinitionEntityMapper;

    /** 档期 Mapper。 */
    @Mock
    private ScheduleEntityMapper scheduleEntityMapper;

    /** 团队查档记录 Mapper。 */
    @Mock
    private TeamScheduleQueryRecordEntityMapper teamScheduleQueryRecordEntityMapper;

    /**
     * 初始化 LambdaQueryWrapper 所需的实体表信息。
     */
    @BeforeAll
    static void initializeTableInfo() {
        com.baomidou.mybatisplus.core.MybatisConfiguration configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, TeamMemberEntity.class);
    }

    /**
     * 配置、请求和响应 DTO 只能公开约定字段，且组件入口必须是 Spring Bean。
     */
    @Test
    void componentTypesShouldExposeOnlyContractFieldsAndSpringRoles() {
        assertFields(TeamScheduleQueryComponentConfig.class, "title", "description", "displayMode", "queryRange");
        assertFields(TeamScheduleQueryComponentConfig.QueryRange.class,
                "type", "futureDays", "startDate", "endDate");
        assertFields(TeamPortfolioScheduleQueryRequest.class, "componentKey", "queriedDate", "idempotencyKey");
        assertFields(TeamPortfolioScheduleQueryResponse.class,
                "portfolioType", "queriedDate", "status", "statusText", "available", "members");
        assertFields(TeamPortfolioScheduleQueryResponse.Member.class,
                "memberUserId", "displayName", "avatarUrl", "status", "statusText", "totalSlotCount",
                "availableSlotCount", "emptySlotDefinition");
        assertThat(TeamScheduleQueryComponentValidator.class.getAnnotation(Component.class)).isNotNull();
        assertThat(TeamScheduleQueryComponentRenderer.class.getAnnotation(Component.class)).isNotNull();
        assertThat(TeamScheduleQueryComponentReferenceExtractor.class.getAnnotation(Component.class)).isNotNull();
        assertThat(TeamScheduleQueryComponentService.class.getAnnotation(Service.class)).isNotNull();
        assertThat(Arrays.stream(TeamScheduleQueryComponentService.PublishedQueryRecordContext.class
                        .getRecordComponents()).map(component -> component.getName()))
                .containsExactly("visitRecordId", "sourceType", "snapshotRecordable", "occurredAt");
        Transactional transactional = Arrays.stream(TeamScheduleQueryComponentService.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("queryPublished"))
                .findFirst().orElseThrow().getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.rollbackFor()).contains(Exception.class);
    }

    /**
     * 成员实体必须继承 BaseEntity 的逻辑删除字段契约，不在 wrapper 片段中伪造 deleted 条件。
     */
    @Test
    void teamMemberShouldRetainGlobalLogicalDeleteContract() throws NoSuchFieldException {
        assertThat(TeamMemberEntity.class.getSuperclass()).isEqualTo(BaseEntity.class);
        Field deletedField = BaseEntity.class.getDeclaredField("deleted");
        TableLogic tableLogic = deletedField.getAnnotation(TableLogic.class);
        assertThat(tableLogic).isNotNull();
        assertThat(tableLogic.value()).isEqualTo("0");
        assertThat(tableLogic.delval()).isEqualTo("id");
    }

    /**
     * 校验器应规范化默认值、无关范围字段和显式空值。
     */
    @Test
    void validatorAndRendererShouldMatchPersonalNormalizationSemantics() {
        TeamScheduleQueryComponentValidator validator = new TeamScheduleQueryComponentValidator();
        TeamScheduleQueryComponentRenderer renderer = new TeamScheduleQueryComponentRenderer();

        JSONObject unlimited = validator.normalizeAndValidate(null, context());
        JSONObject futureDays = validator.normalizeAndValidate(JSON.parseObject("""
                {"title":"  档期  ","description":"   ","displayMode":" INLINE_CALENDAR ",
                 "queryRange":{"type":" FUTURE_DAYS ","futureDays":" 7 ",
                 "startDate":"2026-01-01","endDate":"2026-01-02"}}
                """), context());
        JSONObject dateRange = validator.normalizeAndValidate(JSON.parseObject("""
                {"queryRange":{"type":"DATE_RANGE","startDate":"2026-08-01","endDate":"2026-08-03","futureDays":9}}
                """), context());
        JSONObject nonObjectRange = validator.normalizeAndValidate(JSON.parseObject("""
                {"title":null,"description":"  说明  ","displayMode":"  ","queryRange":["DATE_RANGE"]}
                """), context());
        JSONObject numberRange = new JSONObject();
        JSONObject numberQueryRange = new JSONObject();
        numberQueryRange.put("type", "FUTURE_DAYS");
        numberQueryRange.put("futureDays", 3L);
        numberRange.put("queryRange", numberQueryRange);

        assertThat(unlimited).isEqualTo(JSON.parseObject("""
                {"title":"","description":"","displayMode":"MODAL_CALENDAR",
                 "queryRange":{"type":"UNLIMITED","futureDays":null,"startDate":null,"endDate":null}}
                """));
        assertThat(futureDays).isEqualTo(JSON.parseObject("""
                {"title":"档期","description":"","displayMode":"INLINE_CALENDAR",
                 "queryRange":{"type":"FUTURE_DAYS","futureDays":7,"startDate":null,"endDate":null}}
                """));
        assertThat(dateRange).isEqualTo(JSON.parseObject("""
                {"title":"","description":"","displayMode":"MODAL_CALENDAR",
                 "queryRange":{"type":"DATE_RANGE","futureDays":null,"startDate":"2026-08-01","endDate":"2026-08-03"}}
                """));
        assertThat(nonObjectRange).isEqualTo(JSON.parseObject("""
                {"title":"","description":"说明","displayMode":"MODAL_CALENDAR",
                 "queryRange":{"type":"UNLIMITED","futureDays":null,"startDate":null,"endDate":null}}
                """));
        assertThat(validator.normalizeAndValidate(numberRange, context()).getJSONObject("queryRange"))
                .containsEntry("futureDays", 3);
        assertThat(renderer.render(JSON.parseObject("""
                {"title":"  标题  ","description":null,"displayMode":" ","queryRange":"invalid"}
                """), context())).isEqualTo(JSON.parseObject("""
                {"title":"标题","description":"","displayMode":"MODAL_CALENDAR",
                 "queryRange":{"type":"UNLIMITED","futureDays":null,"startDate":null,"endDate":null}}
                """));
        assertThat(renderer.render(JSON.parseObject("""
                {"queryRange":{"type":" FUTURE_DAYS ","futureDays":" 5 "}}
                """), context()).getJSONObject("queryRange")).containsEntry("futureDays", 5);
        assertThat(validator.normalizeAndValidate(JSON.parseObject("""
                {"queryRange":{"type":"   ","futureDays":9}}
                """), context()).getJSONObject("queryRange")).isEqualTo(JSON.parseObject("""
                {"type":"UNLIMITED","futureDays":null,"startDate":null,"endDate":null}
                """));
    }

    /**
     * 配置校验器和渲染器必须拒绝畸形范围，渲染结果还必须与输入脱离。
     */
    @Test
    void validatorAndRendererShouldRejectInvalidRangesAndReturnDetachedSnapshot() {
        TeamScheduleQueryComponentValidator validator = new TeamScheduleQueryComponentValidator();
        TeamScheduleQueryComponentRenderer renderer = new TeamScheduleQueryComponentRenderer();
        List<JSONObject> invalidConfigs = List.of(
                JSON.parseObject("{\"displayMode\":\"DRAWER\"}"),
                JSON.parseObject("{\"queryRange\":{\"type\":\"FUTURE_DAYS\",\"futureDays\":0}}"),
                JSON.parseObject("{\"queryRange\":{\"type\":\"FUTURE_DAYS\",\"futureDays\":\"7x\"}}"),
                JSON.parseObject("{\"queryRange\":{\"type\":\"DATE_RANGE\",\"startDate\":\"2026-02-30\",\"endDate\":\"2026-03-01\"}}"),
                JSON.parseObject("{\"queryRange\":{\"type\":\"DATE_RANGE\",\"startDate\":\"2026-08-02\",\"endDate\":\"2026-08-01\"}}")
        );

        for (JSONObject invalidConfig : invalidConfigs) {
            assertThatThrownBy(() -> validator.normalizeAndValidate(invalidConfig, context()))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> renderer.render(invalidConfig, context()))
                    .isInstanceOf(BusinessException.class);
        }

        JSONObject normalized = validator.normalizeAndValidate(JSON.parseObject("""
                {"title":"档期","description":"说明","queryRange":{"type":"UNLIMITED"}}
                """), context());
        JSONObject rendered = renderer.render(normalized, context());
        normalized.put("title", "被修改");
        normalized.getJSONObject("queryRange").put("type", "DATE_RANGE");

        assertThat(rendered).isEqualTo(JSON.parseObject("""
                {"title":"档期","description":"说明","displayMode":"MODAL_CALENDAR",
                 "queryRange":{"type":"UNLIMITED","futureDays":null,"startDate":null,"endDate":null}}
                """));
    }

    /**
     * 引用提取器应基于重新渲染的快照生成唯一的团队档期组件引用。
     */
    @Test
    void extractorShouldCreateExactScheduleReferenceFromRenderedSnapshot() {
        JSONObject input = JSON.parseObject("""
                {"displayMode":"INLINE_CALENDAR","queryRange":{"type":"DATE_RANGE",
                 "startDate":"2026-08-01","endDate":"2026-08-03"}}
                """);
        List<PortfolioReferenceEntity> references = new TeamScheduleQueryComponentReferenceExtractor(
                new TeamScheduleQueryComponentRenderer())
                .extract(COMPONENT_KEY, COMPONENT_PATH, input, context());

        assertThat(references).hasSize(1);
        PortfolioReferenceEntity reference = references.getFirst();
        assertThat(reference.getPortfolioId()).isEqualTo(PORTFOLIO_ID);
        assertThat(reference.getReferenceType()).isEqualTo("SCHEDULE_COMPONENT");
        assertThat(reference.getReferenceId()).isEqualTo(TEAM_ID);
        assertThat(reference.getComponentKey()).isEqualTo(COMPONENT_KEY);
        assertThat(reference.getComponentPath()).isEqualTo(COMPONENT_PATH);
        assertThat(reference.getSortOrder()).isZero();
        assertThat(reference.getIsValid()).isEqualTo(1);
        assertThat(reference.getConfigScope()).isNull();
        assertThat(JSON.parseObject(reference.getSnapshotJson())).isEqualTo(JSON.parseObject("""
                {"displayMode":"INLINE_CALENDAR","queryRange":{"type":"DATE_RANGE",
                 "futureDays":null,"startDate":"2026-08-01","endDate":"2026-08-03"}}
                """));
    }

    /**
     * 引用提取器必须在调用渲染器前拒绝空组件标识、空路径和非法上下文。
     */
    @Test
    void extractorShouldRejectInvalidIdentityBeforeRendering() {
        TeamScheduleQueryComponentRenderer renderer = mock(TeamScheduleQueryComponentRenderer.class);
        TeamScheduleQueryComponentReferenceExtractor extractor =
                new TeamScheduleQueryComponentReferenceExtractor(renderer);

        assertThatThrownBy(() -> extractor.extract(" ", COMPONENT_PATH, new JSONObject(), context()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> extractor.extract(COMPONENT_KEY, " ", new JSONObject(), context()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> extractor.extract(COMPONENT_KEY, COMPONENT_PATH, new JSONObject(), null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> extractor.extract(COMPONENT_KEY, COMPONENT_PATH, new JSONObject(),
                new TeamPortfolioComponentContext(0L, PORTFOLIO_ID, 1)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> extractor.extract(COMPONENT_KEY, COMPONENT_PATH, new JSONObject(),
                new TeamPortfolioComponentContext(TEAM_ID, 0L, 1)))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(renderer);
    }

    /**
     * 成员必须按成员关系 ID 稳定排序，并且两个批量 Mapper 各执行一次。
     */
    @Test
    void previewShouldAggregateMembersInStableOrderWithExactlyTwoBatchQueries() {
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(
                membership(2L, 2L, "JOINED"), membership(1L, 1L, "JOINED"),
                membership(3L, 3L, "PENDING_CONFIRMATION"), membership(4L, null, "JOINED")));
        when(userEntityMapper.selectBatchIds(Set.of(1L, 2L))).thenReturn(List.of(
                user(2L, "乙", "avatar-2", "ACTIVE"), user(1L, "甲", "avatar-1", "ACTIVE"),
                user(99L, "错成员", "avatar-99", "ACTIVE")));
        when(slotDefinitionEntityMapper.selectActiveByUserIds(Set.of(1L, 2L))).thenReturn(Arrays.asList(
                slot(102L, 1L), slot(201L, 2L), slot(101L, 1L), slot(999L, 99L), null));
        when(scheduleEntityMapper.selectByUserIdsAndDate(Set.of(1L, 2L), QUERY_DATE)).thenReturn(Arrays.asList(
                schedule(1L, 101L, QUERY_DATE, "BOOKED"), schedule(1L, 102L, QUERY_DATE, "UNKNOWN"),
                schedule(99L, 999L, QUERY_DATE, "BOOKED"), null));

        TeamPortfolioScheduleQueryResponse response = service().queryPreview(
                context(), unlimitedConfig(), request(QUERY_DATE));

        assertThat(response.getPortfolioType()).isEqualTo("TEAM");
        assertThat(response.getQueriedDate()).isEqualTo(QUERY_DATE);
        assertThat(response.getStatus()).isEqualTo("TEAM_PARTIAL_AVAILABLE");
        assertThat(response.getStatusText()).isEqualTo("部分成员可约");
        assertThat(response.isAvailable()).isTrue();
        assertThat(response.getMembers()).hasSize(2);
        assertMember(response.getMembers().get(0), 1L, "甲", "avatar-1", "FULL", "已满", 2, 0, false);
        assertMember(response.getMembers().get(1), 2L, "乙", "avatar-2", "AVAILABLE", "空闲", 1, 1, false);
        ArgumentCaptor<Collection<Long>> slotUserIds = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Collection<Long>> scheduleUserIds = ArgumentCaptor.forClass(Collection.class);
        verify(slotDefinitionEntityMapper, times(1)).selectActiveByUserIds(slotUserIds.capture());
        verify(scheduleEntityMapper, times(1)).selectByUserIdsAndDate(scheduleUserIds.capture(), eq(QUERY_DATE));
        assertThat(slotUserIds.getValue()).containsExactly(1L, 2L);
        assertThat(scheduleUserIds.getValue()).containsExactly(1L, 2L);
        LambdaQueryWrapper<TeamMemberEntity> memberQuery = captureTeamMemberQuery();
        assertThat(memberQuery.getSqlSegment()).contains("team_id", "join_status", "ORDER BY", "id", "ASC");
        assertThat(memberQuery.getParamNameValuePairs().values())
                .containsExactlyInAnyOrder(TEAM_ID, "JOINED");
        assertThat(memberQuery.getSqlSegment()).doesNotContain("deleted");
        verify(teamScheduleQueryRecordEntityMapper, never()).insert(
                org.mockito.ArgumentMatchers.<TeamScheduleQueryRecordEntity>any());
    }

    /**
     * 组件服务应从顶层配置定位自己的 enabled 实例，并独立生成预览选项和执行查询。
     */
    @Test
    void previewEntryPointsShouldOwnEnabledScheduleComponentLookup() {
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of());
        TeamScheduleQueryComponentService service = service();
        TeamPortfolioConfigDto config = portfolioConfig(
                component(COMPONENT_KEY, "SCHEDULE_QUERY", true, unlimitedConfig()));

        JSONObject options = service.previewOptions(context(), config, COMPONENT_KEY);
        TeamPortfolioScheduleQueryResponse response = service.queryPreview(
                context(), config, request(QUERY_DATE));

        assertThat(options).isEqualTo(unlimitedConfig());
        assertThat(response.getPortfolioType()).isEqualTo("TEAM");
        assertThat(response.getQueriedDate()).isEqualTo(QUERY_DATE);
        verify(teamMemberEntityMapper, times(1)).selectList(any());
    }

    /**
     * 组件服务必须拒绝缺失、停用、错类型和空配置实例，且不触发成员或档期查询。
     */
    @Test
    void previewEntryPointsShouldRejectInvalidEnvelopeBeforeDataQueries() {
        TeamScheduleQueryComponentService service = service();
        List<TeamPortfolioConfigDto> invalidConfigs = List.of(
                portfolioConfig(),
                portfolioConfig(component(COMPONENT_KEY, "SCHEDULE_QUERY", false, unlimitedConfig())),
                portfolioConfig(component(COMPONENT_KEY, "CAROUSEL", true, unlimitedConfig())),
                portfolioConfig(component(COMPONENT_KEY, "SCHEDULE_QUERY", true, null)));

        for (TeamPortfolioConfigDto invalid : invalidConfigs) {
            assertThatThrownBy(() -> service.previewOptions(context(), invalid, COMPONENT_KEY))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.queryPreview(context(), invalid, request(QUERY_DATE)))
                    .isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> service.previewOptions(context(), null, COMPONENT_KEY))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.previewOptions(context(), portfolioConfig(), " "))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(teamMemberEntityMapper, userEntityMapper, slotDefinitionEntityMapper,
                scheduleEntityMapper, teamScheduleQueryRecordEntityMapper);
    }

    /**
     * 已发布入口必须接收顶层配置并由 schedule 包定位 enabled SCHEDULE_QUERY 实例。
     */
    @Test
    void publishedEntryPointOwnsConfigAwareEnabledComponentLookup() {
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of());
        TeamPortfolioConfigDto config = portfolioConfig(
                component(COMPONENT_KEY, "SCHEDULE_QUERY", true, unlimitedConfig()));

        TeamPortfolioScheduleQueryResponse response = service().queryPublished(
                portfolio(), config, request(QUERY_DATE),
                new VisitorContext(91L, "visitor-key", "token"), publishedContext(false));

        assertThat(response.getPortfolioType()).isEqualTo("TEAM");
        assertThat(response.getQueriedDate()).isEqualTo(QUERY_DATE);
        verify(teamMemberEntityMapper).selectList(any());
        verify(teamScheduleQueryRecordEntityMapper, never()).insert(any(TeamScheduleQueryRecordEntity.class));
    }

    /**
     * 已发布配置缺失、停用或错类型实例时必须在成员和快照查询前拒绝。
     */
    @Test
    void publishedConfigAwareEntryRejectsInvalidEnvelopeBeforeEveryDataQuery() {
        List<TeamPortfolioConfigDto> invalidConfigs = List.of(
                portfolioConfig(),
                portfolioConfig(component(COMPONENT_KEY, "SCHEDULE_QUERY", false, unlimitedConfig())),
                portfolioConfig(component(COMPONENT_KEY, "CONTACT_FORM", true, unlimitedConfig())),
                portfolioConfig(component(COMPONENT_KEY, "SCHEDULE_QUERY", true, null)));

        for (TeamPortfolioConfigDto config : invalidConfigs) {
            assertThatThrownBy(() -> service().queryPublished(
                    portfolio(), config, request(QUERY_DATE),
                    new VisitorContext(91L, "visitor-key", "token"), publishedContext(false)))
                    .isInstanceOf(BusinessException.class);
        }

        verifyNoInteractions(teamMemberEntityMapper, userEntityMapper, slotDefinitionEntityMapper,
                scheduleEntityMapper, teamScheduleQueryRecordEntityMapper);
    }

    /**
     * 没有有效成员时不得查询档位或档期，已发布查询仍应保存完整空团队快照。
     */
    @Test
    void publishedQueryShouldShortCircuitEmptyMembersAndPersistFullResult() {
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(
                membership(2L, 2L, "PENDING_CONFIRMATION"), membership(1L, 1L, "JOINED")));
        when(userEntityMapper.selectBatchIds(Set.of(1L))).thenReturn(List.of(user(1L, "已停用", "avatar", "DISABLED")));
        when(teamScheduleQueryRecordEntityMapper.insert(any(TeamScheduleQueryRecordEntity.class))).thenReturn(1);

        TeamPortfolioScheduleQueryResponse response = service().queryPublished(
                portfolio(), unlimitedConfig(), request(QUERY_DATE), new VisitorContext(91L, "visitor-key", "token"),
                publishedContext(true));

        assertThat(response.getStatus()).isEqualTo("TEAM_FULL");
        assertThat(response.getStatusText()).isEqualTo("已满");
        assertThat(response.isAvailable()).isFalse();
        assertThat(response.getMembers()).isEmpty();
        verify(slotDefinitionEntityMapper, never()).selectActiveByUserIds(any());
        verify(scheduleEntityMapper, never()).selectByUserIdsAndDate(any(), any());
        ArgumentCaptor<TeamScheduleQueryRecordEntity> recordCaptor =
                ArgumentCaptor.forClass(TeamScheduleQueryRecordEntity.class);
        verify(teamScheduleQueryRecordEntityMapper, times(1)).insert(recordCaptor.capture());
        TeamScheduleQueryRecordEntity record = recordCaptor.getValue();
        assertThat(record.getPortfolioId()).isEqualTo(PORTFOLIO_ID);
        assertThat(record.getPortfolioRevision()).isEqualTo(7);
        assertThat(record.getPortfolioTitleSnapshot()).isEqualTo("团队作品集");
        assertThat(record.getTeamId()).isEqualTo(TEAM_ID);
        assertThat(record.getVisitRecordId()).isEqualTo(81L);
        assertThat(record.getVisitorId()).isEqualTo(91L);
        assertThat(record.getVisitorKey()).isEqualTo("visitor-key");
        assertThat(record.getSourceType()).isEqualTo("QR_CODE");
        assertThat(record.getDisplayMode()).isEqualTo("MODAL_CALENDAR");
        assertThat(record.getQueriedDate()).isEqualTo(QUERY_DATE);
        assertThat(record.getResultStatus()).isEqualTo("TEAM_FULL");
        assertThat(record.getResultStatusText()).isEqualTo("已满");
        assertThat(record.getAvailable()).isZero();
        assertThat(record.getResultMessage()).isEqualTo("已满");
        assertThat(JSON.parseArray(record.getTeamResultJson())).isEmpty();
        assertThat(record.getAvailableMemberCount()).isZero();
        assertThat(record.getPartialAvailableMemberCount()).isZero();
        assertThat(record.getFullMemberCount()).isZero();
        assertThat(record.getQueriedAt()).isEqualTo(OCCURRED_AT);
    }

    /**
     * 重复事件门控为不可记录时仍返回聚合结果，但连续调用都不得写入快照。
     */
    @Test
    void publishedQueryShouldSkipSnapshotForDuplicateEventResult() {
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of());
        TeamScheduleQueryComponentService.PublishedQueryRecordContext skipped =
                TeamScheduleQueryComponentService.PublishedQueryRecordContext.skipped();

        assertThat(skipped.visitRecordId()).isNull();
        assertThat(skipped.sourceType()).isNull();
        assertThat(skipped.snapshotRecordable()).isFalse();
        assertThat(skipped.occurredAt()).isNull();

        TeamPortfolioScheduleQueryResponse first = service().queryPublished(
                portfolio(), unlimitedConfig(), request(QUERY_DATE), new VisitorContext(null, "visitor-key", "token"),
                skipped);
        TeamPortfolioScheduleQueryResponse duplicate = service().queryPublished(
                portfolio(), unlimitedConfig(), request(QUERY_DATE), new VisitorContext(null, "visitor-key", "token"),
                skipped);

        assertThat(first.getStatus()).isEqualTo("TEAM_FULL");
        assertThat(duplicate).isEqualTo(first);
        verify(teamScheduleQueryRecordEntityMapper, never()).insert(any(TeamScheduleQueryRecordEntity.class));
    }

    /**
     * 已发布入口必须在任何聚合或写入前拒绝非法作品集、访客、事件上下文和幂等键。
     */
    @Test
    void publishedQueryShouldRejectInvalidContextBeforeDatabaseAccess() {
        List<Consumer<PortfolioEntity>> invalidPortfolioMutations = List.of(
                value -> value.setId(0L),
                value -> value.setOwnerId(0L),
                value -> value.setOwnerType("USER"),
                value -> value.setTemplateType("ADVANCED"),
                value -> value.setStatus("DISABLED"),
                value -> value.setSchemaVersion("standard-personal-v1"),
                value -> value.setPublicationStatus("DRAFT_ONLY"),
                value -> value.setPublishedRevision(0),
                value -> value.setPublishedConfigJson("not-json"),
                value -> value.setPublishedConfigJson("{}"),
                value -> value.setPublishedConfigJson("{\"share\":{\"title\":\"  \"}}"),
                value -> value.setPublishedConfigJson(publishedConfig("字".repeat(101)))
        );

        assertPublishedRejected(null, request(QUERY_DATE), new VisitorContext(91L, "visitor-key", "token"),
                publishedContext(true));
        for (Consumer<PortfolioEntity> mutation : invalidPortfolioMutations) {
            PortfolioEntity invalid = portfolio();
            mutation.accept(invalid);
            assertPublishedRejected(invalid, request(QUERY_DATE), new VisitorContext(91L, "visitor-key", "token"),
                    publishedContext(true));
        }
        assertPublishedRejected(portfolio(), request(QUERY_DATE), null, publishedContext(true));
        assertPublishedRejected(portfolio(), request(QUERY_DATE), new VisitorContext(91L, " ", "token"),
                publishedContext(true));
        assertPublishedRejected(portfolio(), request(QUERY_DATE), new VisitorContext(91L, "v".repeat(65), "token"),
                publishedContext(true));
        assertPublishedRejected(portfolio(), request(QUERY_DATE), new VisitorContext(0L, "visitor-key", "token"),
                publishedContext(true));
        assertPublishedRejected(portfolio(), request(QUERY_DATE), new VisitorContext(91L, "visitor-key", "token"), null);
        assertPublishedRejected(portfolio(), request(QUERY_DATE), new VisitorContext(91L, "visitor-key", "token"),
                new TeamScheduleQueryComponentService.PublishedQueryRecordContext(0L, "QR_CODE", true, OCCURRED_AT));
        assertPublishedRejected(portfolio(), request(QUERY_DATE), new VisitorContext(91L, "visitor-key", "token"),
                new TeamScheduleQueryComponentService.PublishedQueryRecordContext(81L, "QR_CODE", true, null));
        TeamPortfolioScheduleQueryRequest blankKey = request(QUERY_DATE);
        blankKey.setIdempotencyKey(" ");
        assertPublishedRejected(portfolio(), blankKey, new VisitorContext(91L, "visitor-key", "token"),
                publishedContext(true));
        TeamPortfolioScheduleQueryRequest longKey = request(QUERY_DATE);
        longKey.setIdempotencyKey("i".repeat(65));
        assertPublishedRejected(portfolio(), longKey, new VisitorContext(91L, "visitor-key", "token"),
                publishedContext(true));

        verifyNoInteractions(teamMemberEntityMapper, userEntityMapper, slotDefinitionEntityMapper,
                scheduleEntityMapper, teamScheduleQueryRecordEntityMapper);
    }

    /**
     * 标题 100 个 Unicode 字符和空 visitorId 合法，标题快照必须使用去空白后的发布值。
     */
    @Test
    void publishedQueryShouldAcceptTitleBoundaryAndNullableVisitorId() {
        String boundaryTitle = "界".repeat(100);
        PortfolioEntity portfolio = portfolio();
        portfolio.setPublishedConfigJson(publishedConfig("  " + boundaryTitle + "  "));
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of());
        when(teamScheduleQueryRecordEntityMapper.insert(any(TeamScheduleQueryRecordEntity.class))).thenReturn(1);

        service().queryPublished(portfolio, unlimitedConfig(), request(QUERY_DATE),
                new VisitorContext(null, "visitor-key", "token"), publishedContext(true));

        ArgumentCaptor<TeamScheduleQueryRecordEntity> recordCaptor =
                ArgumentCaptor.forClass(TeamScheduleQueryRecordEntity.class);
        verify(teamScheduleQueryRecordEntityMapper).insert(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getPortfolioTitleSnapshot()).isEqualTo(boundaryTitle);
        assertThat(recordCaptor.getValue().getVisitorId()).isNull();
    }

    /**
     * 快照 insert 未影响恰好一行或抛出异常时，已发布查询必须失败。
     */
    @Test
    void publishedQueryShouldRequireSingleInsertedSnapshotRowAndPropagateInsertFailure() {
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of());
        when(teamScheduleQueryRecordEntityMapper.insert(any(TeamScheduleQueryRecordEntity.class))).thenReturn(0);

        assertThatThrownBy(() -> service().queryPublished(portfolio(), unlimitedConfig(), request(QUERY_DATE),
                new VisitorContext(91L, "visitor-key", "token"), publishedContext(true)))
                .isInstanceOf(BusinessException.class);

        when(teamScheduleQueryRecordEntityMapper.insert(any(TeamScheduleQueryRecordEntity.class)))
                .thenThrow(new IllegalStateException("insert failed"));
        assertThatThrownBy(() -> service().queryPublished(portfolio(), unlimitedConfig(), request(QUERY_DATE),
                new VisitorContext(91L, "visitor-key", "token"), publishedContext(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("insert failed");
        verify(teamScheduleQueryRecordEntityMapper, times(2)).insert(any(TeamScheduleQueryRecordEntity.class));
    }

    /**
     * 非空成员的持久化快照必须字段完整，并分别统计空闲、部分空闲和已满成员。
     */
    @Test
    void publishedQueryShouldPersistCompleteMemberSnapshotAndThreeCounts() {
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(
                membership(1L, 1L, "JOINED"), membership(2L, 2L, "JOINED"), membership(3L, 3L, "JOINED")));
        when(userEntityMapper.selectBatchIds(Set.of(1L, 2L, 3L))).thenReturn(List.of(
                user(1L, "甲", "a1", "ACTIVE"), user(2L, "乙", "a2", "ACTIVE"),
                user(3L, null, null, "ACTIVE")));
        when(slotDefinitionEntityMapper.selectActiveByUserIds(Set.of(1L, 2L, 3L))).thenReturn(List.of(
                slot(101L, 1L), slot(201L, 2L), slot(202L, 2L)));
        when(scheduleEntityMapper.selectByUserIdsAndDate(Set.of(1L, 2L, 3L), QUERY_DATE)).thenReturn(List.of(
                schedule(2L, 201L, QUERY_DATE, "TENTATIVE")));
        when(teamScheduleQueryRecordEntityMapper.insert(any(TeamScheduleQueryRecordEntity.class))).thenReturn(1);

        TeamPortfolioScheduleQueryResponse response = service().queryPublished(
                portfolio(), unlimitedConfig(), request(QUERY_DATE), new VisitorContext(91L, "visitor-key", "token"),
                publishedContext(true));

        assertThat(response.getStatus()).isEqualTo("TEAM_PARTIAL_AVAILABLE");
        ArgumentCaptor<TeamScheduleQueryRecordEntity> captor =
                ArgumentCaptor.forClass(TeamScheduleQueryRecordEntity.class);
        verify(teamScheduleQueryRecordEntityMapper).insert(captor.capture());
        TeamScheduleQueryRecordEntity record = captor.getValue();
        assertThat(record.getAvailableMemberCount()).isEqualTo(1);
        assertThat(record.getPartialAvailableMemberCount()).isEqualTo(1);
        assertThat(record.getFullMemberCount()).isEqualTo(1);
        assertThat(record.getTeamResultJson())
                .contains("\"displayName\":null", "\"avatarUrl\":null");
        JSONArray members = JSON.parseArray(record.getTeamResultJson());
        assertThat(members).hasSize(3);
        for (int index = 0; index < members.size(); index++) {
            assertThat(members.getJSONObject(index).keySet()).containsExactlyInAnyOrder(
                    "memberUserId", "displayName", "avatarUrl", "status", "statusText",
                    "totalSlotCount", "availableSlotCount", "emptySlotDefinition");
        }
        assertThat(members.getJSONObject(2).containsKey("displayName")).isTrue();
        assertThat(members.getJSONObject(2).get("displayName")).isNull();
        assertThat(members.getJSONObject(2).containsKey("avatarUrl")).isTrue();
        assertThat(members.getJSONObject(2).get("avatarUrl")).isNull();
    }

    /**
     * 有效档位全空闲、部分空闲和无档位的成员必须汇总为准确的团队状态。
     */
    @Test
    void previewShouldCalculateAvailablePartialAndEmptySlotDefinitionStatuses() {
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(
                membership(1L, 1L, "JOINED"), membership(2L, 2L, "JOINED"), membership(3L, 3L, "JOINED")));
        when(userEntityMapper.selectBatchIds(Set.of(1L, 2L, 3L))).thenReturn(List.of(
                user(1L, "甲", "a1", "ACTIVE"), user(2L, "乙", "a2", "ACTIVE"), user(3L, "丙", "a3", "ACTIVE")));
        when(slotDefinitionEntityMapper.selectActiveByUserIds(Set.of(1L, 2L, 3L))).thenReturn(List.of(
                slot(101L, 1L), slot(201L, 2L), slot(202L, 2L)));
        when(scheduleEntityMapper.selectByUserIdsAndDate(Set.of(1L, 2L, 3L), QUERY_DATE)).thenReturn(List.of(
                schedule(2L, 201L, QUERY_DATE, "TENTATIVE")));

        TeamPortfolioScheduleQueryResponse response = service().queryPreview(
                context(), unlimitedConfig(), request(QUERY_DATE));

        assertThat(response.getStatus()).isEqualTo("TEAM_PARTIAL_AVAILABLE");
        assertMember(response.getMembers().get(0), 1L, "甲", "a1", "AVAILABLE", "空闲", 1, 1, false);
        assertMember(response.getMembers().get(1), 2L, "乙", "a2", "PARTIAL_AVAILABLE", "部分档期空闲", 2, 1, false);
        assertMember(response.getMembers().get(2), 3L, "丙", "a3", "FULL", "已满", 0, 0, true);
    }

    /**
     * 团队全空闲和全满均应采用精确团队状态；重复和错归属 Mapper 行不得污染聚合结果。
     */
    @Test
    void previewShouldCalculateTerminalTeamStatusesAndIgnoreDuplicateOrMismatchedMapperRows() {
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(membership(1L, 1L, "JOINED")));
        when(userEntityMapper.selectBatchIds(Set.of(1L))).thenReturn(List.of(user(1L, "甲", "a1", "ACTIVE")));
        when(slotDefinitionEntityMapper.selectActiveByUserIds(Set.of(1L))).thenReturn(List.of(slot(101L, 1L), slot(101L, 1L)));
        when(scheduleEntityMapper.selectByUserIdsAndDate(Set.of(1L), QUERY_DATE)).thenReturn(List.of(
                schedule(99L, 101L, QUERY_DATE, "BOOKED"), schedule(1L, 101L, QUERY_DATE, "BOOKED"),
                schedule(1L, 101L, QUERY_DATE, "REST")));

        TeamPortfolioScheduleQueryResponse full = service().queryPreview(context(), unlimitedConfig(), request(QUERY_DATE));
        assertThat(full.getStatus()).isEqualTo("TEAM_FULL");
        assertThat(full.isAvailable()).isFalse();
        assertMember(full.getMembers().getFirst(), 1L, "甲", "a1", "FULL", "已满", 1, 0, false);

        when(scheduleEntityMapper.selectByUserIdsAndDate(Set.of(1L), QUERY_DATE)).thenReturn(List.of());
        TeamPortfolioScheduleQueryResponse available = service().queryPreview(context(), unlimitedConfig(), request(QUERY_DATE));
        assertThat(available.getStatus()).isEqualTo("TEAM_AVAILABLE");
        assertThat(available.isAvailable()).isTrue();
    }

    /**
     * 两个批量 Mapper 都返回 null 时仍应稳定产生无档位已满成员。
     */
    @Test
    void previewShouldHandleNullBatchMapperResults() {
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of(membership(1L, 1L, "JOINED")));
        when(userEntityMapper.selectBatchIds(Set.of(1L))).thenReturn(List.of(user(1L, "甲", "a1", "ACTIVE")));
        when(slotDefinitionEntityMapper.selectActiveByUserIds(Set.of(1L))).thenReturn(null);
        when(scheduleEntityMapper.selectByUserIdsAndDate(Set.of(1L), QUERY_DATE)).thenReturn(null);

        TeamPortfolioScheduleQueryResponse response = service().queryPreview(
                context(), unlimitedConfig(), request(QUERY_DATE));

        assertThat(response.getStatus()).isEqualTo("TEAM_FULL");
        assertMember(response.getMembers().getFirst(), 1L, "甲", "a1", "FULL", "已满", 0, 0, true);
        verify(slotDefinitionEntityMapper, times(1)).selectActiveByUserIds(Set.of(1L));
        verify(scheduleEntityMapper, times(1)).selectByUserIdsAndDate(Set.of(1L), QUERY_DATE);
    }

    /**
     * FUTURE_DAYS 与 DATE_RANGE 的首尾日期都必须按固定 Clock 作为闭区间接受。
     */
    @Test
    void previewShouldAcceptInclusiveRangeBoundariesWithInjectedClock() {
        when(teamMemberEntityMapper.selectList(any())).thenReturn(List.of());
        TeamScheduleQueryComponentValidator validator = new TeamScheduleQueryComponentValidator();
        JSONObject futureDays = validator.normalizeAndValidate(JSON.parseObject("""
                {"queryRange":{"type":"FUTURE_DAYS","futureDays":2}}
                """), context());
        JSONObject dateRange = validator.normalizeAndValidate(JSON.parseObject("""
                {"queryRange":{"type":"DATE_RANGE","startDate":"2026-08-01","endDate":"2026-08-03"}}
                """), context());
        TeamScheduleQueryComponentService service = service(FIXED_CLOCK);

        assertThatCode(() -> service.queryPreview(context(), futureDays, request(FIXED_TODAY)))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.queryPreview(context(), futureDays, request(FIXED_TODAY.plusDays(2))))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.queryPreview(context(), dateRange, request(LocalDate.of(2026, 8, 1))))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.queryPreview(context(), dateRange, request(LocalDate.of(2026, 8, 3))))
                .doesNotThrowAnyException();
        verify(teamMemberEntityMapper, times(4)).selectList(any());
        verify(slotDefinitionEntityMapper, never()).selectActiveByUserIds(any());
        verify(scheduleEntityMapper, never()).selectByUserIdsAndDate(any(), any());
    }

    /**
     * 运行时必须再次拒绝缺失日期和超出 FUTURE_DAYS、DATE_RANGE 的查询，且不得调用批量 Mapper。
     */
    @Test
    void previewShouldValidateRequestAndRangeBeforeAnyBatchQuery() {
        TeamScheduleQueryComponentService service = service(FIXED_CLOCK);
        TeamScheduleQueryComponentValidator validator = new TeamScheduleQueryComponentValidator();
        JSONObject futureDays = validator.normalizeAndValidate(JSON.parseObject("""
                {"queryRange":{"type":"FUTURE_DAYS","futureDays":1}}
                """), context());
        JSONObject dateRange = validator.normalizeAndValidate(JSON.parseObject("""
                {"queryRange":{"type":"DATE_RANGE","startDate":"2026-08-01","endDate":"2026-08-03"}}
                """), context());

        assertThatThrownBy(() -> service.queryPreview(context(), unlimitedConfig(), null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.queryPreview(context(), unlimitedConfig(), request(null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.queryPreview(context(), futureDays, request(FIXED_TODAY.minusDays(1))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.queryPreview(context(), futureDays, request(FIXED_TODAY.plusDays(2))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.queryPreview(context(), dateRange, request(LocalDate.of(2026, 7, 31))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.queryPreview(context(), dateRange, request(LocalDate.of(2026, 8, 4))))
                .isInstanceOf(BusinessException.class);
        verify(slotDefinitionEntityMapper, never()).selectActiveByUserIds(any());
        verify(scheduleEntityMapper, never()).selectByUserIdsAndDate(any(), any());
    }

    /**
     * 五个生产文件必须完整、独立，且不得通过普通或 static import 引入个人或其他团队组件。
     */
    @Test
    void productionSourcesShouldRemainCompleteAndStrictlyIsolated() throws Exception {
        Path sourceRoot = Path.of("src/main/java/com/jxc/wefolio");
        List<Path> expected = List.of(
                sourceRoot.resolve(COMPONENT_SOURCE).resolve("TeamScheduleQueryComponentConfig.java"),
                sourceRoot.resolve(COMPONENT_SOURCE).resolve("TeamScheduleQueryComponentValidator.java"),
                sourceRoot.resolve(COMPONENT_SOURCE).resolve("TeamScheduleQueryComponentRenderer.java"),
                sourceRoot.resolve(COMPONENT_SOURCE).resolve("TeamScheduleQueryComponentReferenceExtractor.java"),
                sourceRoot.resolve(COMPONENT_SOURCE).resolve("TeamScheduleQueryComponentService.java")
        );
        try (var files = Files.list(sourceRoot.resolve(COMPONENT_SOURCE))) {
            assertThat(files.filter(path -> path.getFileName().toString().endsWith(".java")).toList())
                    .containsExactlyInAnyOrderElementsOf(expected);
        }
        for (Path source : expected) {
            assertIndependentProductionSource(Files.readString(source));
        }
        assertThat(Files.readString(expected.get(1))).contains("TeamScheduleQueryComponentConfig");
        assertThat(Files.readString(expected.get(2))).contains("TeamScheduleQueryComponentConfig");
        String serviceSource = Files.readString(expected.get(4));
        assertThat(serviceSource)
                .contains("PortfolioTypeDict.TEAM", "SlotDefinitionStatusDict.ACTIVE")
                .doesNotContain("new TeamScheduleQueryComponentRenderer(", "java.util.Collection;",
                        "partialCount", "record ActiveMember(TeamMemberEntity membership",
                        "LocalDate.now()", "LocalDateTime.now()");
        assertThat(Files.readString(expected.get(3)))
                .doesNotContain("new TeamScheduleQueryComponentRenderer(");
    }

    /**
     * 隔离扫描必须真实拒绝普通 import、static import、全限定名、跨组件引用和直接 new 渲染器。
     */
    @Test
    void isolationScannerShouldRejectCounterexamplesAndAllowTeamBoundaries() {
        List<String> counterexamples = List.of(
                "import com.jxc.wefolio.dto.PortfolioConfigDto;",
                "import com.jxc.wefolio.dto.*;",
                "import static com.jxc.wefolio.service.PortfolioRenderService.render;",
                "import com.jxc.wefolio.service.*;",
                "class Bad { com.jxc.wefolio.service.VisitorPortfolioService value; }",
                "import com.jxc.wefolio.service.teamportfolio.component.carousel.TeamCarouselComponentRenderer;",
                "import com.jxc.wefolio.service.teamportfolio.component.*;",
                "class Bad { com.jxc.wefolio.service.teamportfolio.component.contactform.TeamContactFormComponentService value; }",
                "class Bad { Object value = new TeamScheduleQueryComponentRenderer(); }",
                "class Bad { Object value = new com.jxc.wefolio.service.teamportfolio.component.schedulequery."
                        + "TeamScheduleQueryComponentRenderer(); }"
        );
        for (String counterexample : counterexamples) {
            assertThatThrownBy(() -> assertIndependentProductionSource(counterexample))
                    .isInstanceOf(AssertionError.class);
        }
        assertThatCode(() -> assertIndependentProductionSource("""
                import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioScheduleQueryRequest;
                import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
                class Allowed { com.jxc.wefolio.service.teamportfolio.component.schedulequery.TeamScheduleQueryComponentConfig value; }
                """)).doesNotThrowAnyException();
    }

    /**
     * 创建待测服务。
     *
     * @return 团队档期查询组件服务
     */
    private TeamScheduleQueryComponentService service() {
        return new TeamScheduleQueryComponentService(teamMemberEntityMapper, userEntityMapper, slotDefinitionEntityMapper,
                scheduleEntityMapper, teamScheduleQueryRecordEntityMapper, new TeamScheduleQueryComponentRenderer());
    }

    /**
     * 通过包可见测试构造器创建使用固定时钟的服务。
     */
    private TeamScheduleQueryComponentService service(Clock clock) {
        try {
            var constructor = TeamScheduleQueryComponentService.class.getDeclaredConstructor(
                    TeamMemberEntityMapper.class, UserEntityMapper.class, SlotDefinitionEntityMapper.class,
                    ScheduleEntityMapper.class, TeamScheduleQueryRecordEntityMapper.class,
                    TeamScheduleQueryComponentRenderer.class, Clock.class);
            constructor.setAccessible(true);
            return constructor.newInstance(teamMemberEntityMapper, userEntityMapper, slotDefinitionEntityMapper,
                    scheduleEntityMapper, teamScheduleQueryRecordEntityMapper,
                    new TeamScheduleQueryComponentRenderer(), clock);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 创建包含指定组件的顶层团队作品集配置。 */
    private TeamPortfolioConfigDto portfolioConfig(TeamPortfolioConfigDto.ComponentEnvelope... components) {
        TeamPortfolioConfigDto config = new TeamPortfolioConfigDto();
        config.setSchemaVersion(TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1);
        config.setComponents(List.of(components));
        return config;
    }

    /** 创建预览入口使用的组件信封。 */
    private TeamPortfolioConfigDto.ComponentEnvelope component(
            String componentKey,
            String componentType,
            boolean enabled,
            JSONObject config
    ) {
        TeamPortfolioConfigDto.ComponentEnvelope envelope = new TeamPortfolioConfigDto.ComponentEnvelope();
        envelope.setComponentKey(componentKey);
        envelope.setComponentType(componentType);
        envelope.setEnabled(enabled);
        envelope.setConfig(config);
        return envelope;
    }

    /**
     * 创建组件上下文。
     *
     * @return 团队组件上下文
     */
    private TeamPortfolioComponentContext context() {
        return new TeamPortfolioComponentContext(TEAM_ID, PORTFOLIO_ID, 7);
    }

    /**
     * 创建无限制规范化配置。
     *
     * @return 无限制组件配置
     */
    private JSONObject unlimitedConfig() {
        return new TeamScheduleQueryComponentValidator().normalizeAndValidate(new JSONObject(), context());
    }

    /**
     * 创建查询请求。
     *
     * @param date 查询日期
     * @return 查询请求
     */
    private TeamPortfolioScheduleQueryRequest request(LocalDate date) {
        TeamPortfolioScheduleQueryRequest request = new TeamPortfolioScheduleQueryRequest();
        request.setComponentKey(COMPONENT_KEY);
        request.setQueriedDate(date);
        request.setIdempotencyKey("idem-1");
        return request;
    }

    /**
     * 创建已发布团队作品集。
     *
     * @return 团队作品集实体
     */
    private PortfolioEntity portfolio() {
        PortfolioEntity portfolio = new PortfolioEntity();
        portfolio.setId(PORTFOLIO_ID);
        portfolio.setOwnerType("TEAM");
        portfolio.setOwnerId(TEAM_ID);
        portfolio.setTemplateType("STANDARD");
        portfolio.setStatus("ACTIVE");
        portfolio.setSchemaVersion("standard-team-v1");
        portfolio.setPublicationStatus("PUBLISHED");
        portfolio.setPublishedRevision(7);
        portfolio.setPublishedConfigJson(publishedConfig("团队作品集"));
        return portfolio;
    }

    /** 创建发布查询事件上下文。 */
    private TeamScheduleQueryComponentService.PublishedQueryRecordContext publishedContext(boolean snapshotRecordable) {
        return snapshotRecordable
                ? TeamScheduleQueryComponentService.PublishedQueryRecordContext.recordable(
                81L, "QR_CODE", OCCURRED_AT)
                : TeamScheduleQueryComponentService.PublishedQueryRecordContext.skipped();
    }

    /** 创建仅包含分享标题的发布配置。 */
    private String publishedConfig(String title) {
        JSONObject share = new JSONObject();
        share.put("title", title);
        JSONObject root = new JSONObject();
        root.put("share", share);
        return root.toJSONString();
    }

    /** 断言已发布入口拒绝请求。 */
    private void assertPublishedRejected(
            PortfolioEntity portfolio,
            TeamPortfolioScheduleQueryRequest request,
            VisitorContext visitor,
            TeamScheduleQueryComponentService.PublishedQueryRecordContext recordContext
    ) {
        assertThatThrownBy(() -> service().queryPublished(
                portfolio, unlimitedConfig(), request, visitor, recordContext))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 创建团队成员关系。
     *
     * @param id 成员关系 ID
     * @param userId 成员用户 ID
     * @param joinStatus 加入状态
     * @return 成员关系
     */
    private TeamMemberEntity membership(long id, Long userId, String joinStatus) {
        TeamMemberEntity membership = new TeamMemberEntity();
        membership.setId(id);
        membership.setTeamId(TEAM_ID);
        membership.setUserId(userId);
        membership.setJoinStatus(joinStatus);
        return membership;
    }

    /**
     * 创建用户。
     *
     * @param id 用户 ID
     * @param nickname 昵称
     * @param avatarUrl 头像
     * @param status 账号状态
     * @return 用户实体
     */
    private UserEntity user(long id, String nickname, String avatarUrl, String status) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setNickname(nickname);
        user.setAvatarUrl(avatarUrl);
        user.setStatus(status);
        return user;
    }

    /**
     * 创建生效档位定义。
     *
     * @param id 档位 ID
     * @param userId 用户 ID
     * @return 档位定义
     */
    private SlotDefinitionEntity slot(long id, long userId) {
        SlotDefinitionEntity slot = new SlotDefinitionEntity();
        slot.setId(id);
        slot.setUserId(userId);
        slot.setStatus("ACTIVE");
        return slot;
    }

    /**
     * 创建档期记录。
     *
     * @param userId 用户 ID
     * @param slotDefinitionId 档位 ID
     * @param date 日期
     * @param status 档期状态
     * @return 档期记录
     */
    private ScheduleEntity schedule(long userId, long slotDefinitionId, LocalDate date, String status) {
        ScheduleEntity schedule = new ScheduleEntity();
        schedule.setUserId(userId);
        schedule.setSlotDefinitionId(slotDefinitionId);
        schedule.setScheduleDate(date);
        schedule.setStatus(status);
        return schedule;
    }

    /**
     * 断言成员查询结果。
     */
    private void assertMember(
            TeamPortfolioScheduleQueryResponse.Member member,
            long userId,
            String displayName,
            String avatarUrl,
            String status,
            String statusText,
            int totalSlotCount,
            int availableSlotCount,
            boolean emptySlotDefinition
    ) {
        assertThat(member.getMemberUserId()).isEqualTo(userId);
        assertThat(member.getDisplayName()).isEqualTo(displayName);
        assertThat(member.getAvatarUrl()).isEqualTo(avatarUrl);
        assertThat(member.getStatus()).isEqualTo(status);
        assertThat(member.getStatusText()).isEqualTo(statusText);
        assertThat(member.getTotalSlotCount()).isEqualTo(totalSlotCount);
        assertThat(member.getAvailableSlotCount()).isEqualTo(availableSlotCount);
        assertThat(member.isEmptySlotDefinition()).isEqualTo(emptySlotDefinition);
    }

    /**
     * 断言非静态字段集合。
     *
     * @param type 类型
     * @param expectedNames 字段名称
     */
    private void assertFields(Class<?> type, String... expectedNames) {
        assertThat(Arrays.stream(type.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName))
                .containsExactlyInAnyOrder(expectedNames);
    }

    /** 捕获成员批量查询 wrapper。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaQueryWrapper<TeamMemberEntity> captureTeamMemberQuery() {
        ArgumentCaptor<LambdaQueryWrapper<TeamMemberEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(teamMemberEntityMapper).selectList(captor.capture());
        return captor.getValue();
    }

    /** 按包级默认拒绝规则断言生产源码独立。 */
    private void assertIndependentProductionSource(String source) {
        assertThat(PERSONAL_DTO_REFERENCE.matcher(source).find()).isFalse();
        assertThat(TOP_LEVEL_PERSONAL_SERVICE_REFERENCE.matcher(source).find()).isFalse();
        assertThat(OTHER_TEAM_COMPONENT_REFERENCE.matcher(source).find()).isFalse();
        assertThat(DIRECT_RENDERER_CONSTRUCTION.matcher(source).find()).isFalse();
    }
}
