package com.jxc.wefolio.service.teamportfolio;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jxc.wefolio.constant.TeamPortfolioConstants;
import com.jxc.wefolio.dict.PortfolioConfigScopeDict;
import com.jxc.wefolio.dict.TeamPortfolioComponentTypeDict;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.entity.BaseEntity;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioReferenceEntityMapper;
import com.jxc.wefolio.service.teamportfolio.component.carousel.TeamCarouselComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.contactform.TeamContactFormComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.divider.TeamDividerComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.memberportfoliogrid.TeamMemberPortfolioGridComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.memberportfoliolist.TeamMemberPortfolioListComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.qrcontact.TeamQrContactComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.schedulequery.TeamScheduleQueryComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.singlework.TeamSingleWorkComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.teamprofile.TeamProfileComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.textsection.TeamTextSectionComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.structuredtextsection.TeamStructuredTextSectionComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.TeamTextBackgroundSupport;
import com.jxc.wefolio.service.teamportfolio.component.videocarousel.TeamVideoCarouselComponentReferenceExtractor;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 团队作品集引用重建测试。
 */
@ExtendWith(MockitoExtension.class)
class TeamPortfolioReferenceServiceTest {
    /** 结构化文字组件策略模拟。 */
    @Mock private TeamStructuredTextSectionComponentReferenceExtractor structuredTextExtractor;

    @Mock private PortfolioReferenceEntityMapper referenceMapper;
    @Mock private TeamProfileComponentReferenceExtractor teamProfileExtractor;
    @Mock private TeamCarouselComponentReferenceExtractor carouselExtractor;
    @Mock private TeamSingleWorkComponentReferenceExtractor singleWorkExtractor;
    @Mock private TeamDividerComponentReferenceExtractor dividerExtractor;
    @Mock private TeamMemberPortfolioGridComponentReferenceExtractor gridExtractor;
    @Mock private TeamMemberPortfolioListComponentReferenceExtractor listExtractor;
    @Mock private TeamTextSectionComponentReferenceExtractor textExtractor;
    @Mock private TeamScheduleQueryComponentReferenceExtractor scheduleExtractor;
    @Mock private TeamContactFormComponentReferenceExtractor contactExtractor;
    @Mock private TeamQrContactComponentReferenceExtractor qrExtractor;
    @Mock private TeamVideoCarouselComponentReferenceExtractor videoCarouselExtractor;

    /**
     * 初始化 LambdaQueryWrapper 的实体表信息。
     */
    @BeforeAll
    static void initializeTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                PortfolioReferenceEntity.class);
    }

    /**
     * 草稿重建应按作品集和作用域逻辑删除旧记录，再写入所有组件当前引用。
     */
    @Test
    void rebuildShouldLogicallyReplaceOnlyDraftReferencesAndDispatchAllTypes() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 3);
        TeamPortfolioConfigDto config = configForEveryType(true);
        configureExtractors(context);

        service().rebuild(context.portfolioId(), PortfolioConfigScopeDict.DRAFT.getCode(), config, context);

        ArgumentCaptor<Wrapper<PortfolioReferenceEntity>> deleteCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(referenceMapper).delete(deleteCaptor.capture());
        assertDeleteScope(deleteCaptor.getValue(), context.portfolioId(), PortfolioConfigScopeDict.DRAFT.getCode());
        ArgumentCaptor<PortfolioReferenceEntity> insertCaptor = ArgumentCaptor.forClass(PortfolioReferenceEntity.class);
        verify(referenceMapper, times(12)).insert(insertCaptor.capture());
        assertThat(insertCaptor.getAllValues()).allSatisfy(reference -> {
            assertThat(reference.getPortfolioId()).isEqualTo(context.portfolioId());
            assertThat(reference.getConfigScope()).isEqualTo(PortfolioConfigScopeDict.DRAFT.getCode());
            assertThat(reference.getIsValid()).isEqualTo(1);
        });
        verify(teamProfileExtractor).extract(eq("team_profile"), eq("components[0]"), any(JSONObject.class), eq(context));
        verify(carouselExtractor).extract(eq("carousel"), eq("components[1]"), any(JSONObject.class), eq(context));
        verify(singleWorkExtractor).extract(eq("single_work"), eq("components[2]"), any(JSONObject.class), eq(context));
        verify(dividerExtractor).extract(eq("divider"), eq("components[3]"), any(JSONObject.class), eq(context));
        verify(gridExtractor).extract(eq("member_portfolio_grid"), eq("components[4]"), any(JSONObject.class), eq(context));
        verify(listExtractor).extract(eq("member_portfolio_list"), eq("components[5]"), any(JSONObject.class), eq(context));
        verify(textExtractor).extract(eq("text_section"), eq("components[6]"), any(JSONObject.class), eq(context));
        verify(scheduleExtractor).extract(eq("schedule_query"), eq("components[7]"), any(JSONObject.class), eq(context));
        verify(contactExtractor).extract(eq("contact_form"), eq("components[8]"), any(JSONObject.class), eq(context));
        verify(qrExtractor).extract(eq("qr_contact"), eq("components[9]"), any(JSONObject.class), eq(context));
        verify(videoCarouselExtractor).extract(
                eq("video_carousel"), eq("components[10]"), any(JSONObject.class), eq(context));
    }

    /**
     * 草稿和正式引用作用域独立重建，非法作用域必须拒绝而不能误删数据。
     */
    @Test
    void rebuildShouldKeepDraftAndPublishedScopesIndependentAndRejectOtherScopes() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 3);
        TeamPortfolioConfigDto disabledConfig = configForEveryType(false);

        service().rebuild(context.portfolioId(), PortfolioConfigScopeDict.DRAFT.getCode(), disabledConfig, context);
        service().rebuild(context.portfolioId(), PortfolioConfigScopeDict.PUBLISHED.getCode(), disabledConfig, context);

        ArgumentCaptor<Wrapper<PortfolioReferenceEntity>> deleteCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(referenceMapper, times(2)).delete(deleteCaptor.capture());
        assertDeleteScope(deleteCaptor.getAllValues().get(0), context.portfolioId(),
                PortfolioConfigScopeDict.DRAFT.getCode());
        assertDeleteScope(deleteCaptor.getAllValues().get(1), context.portfolioId(),
                PortfolioConfigScopeDict.PUBLISHED.getCode());
        verify(referenceMapper, times(0)).insert(any(PortfolioReferenceEntity.class));
        assertThatThrownBy(() -> service().rebuild(context.portfolioId(), "ARCHIVED", disabledConfig, context))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 引用分发对相同和空排序值必须保留输入先后顺序。
     */
    @Test
    void rebuildShouldPreserveInputOrderForEqualAndNullSortOrders() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 3);
        when(dividerExtractor.extract(anyString(), anyString(), any(JSONObject.class), eq(context)))
                .thenReturn(List.of());
        TeamPortfolioConfigDto config = config(List.of(
                component("equal-z", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true),
                component("equal-a", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true),
                component("null-z", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), null, true),
                component("null-a", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), null, true)));

        service().rebuild(context.portfolioId(), PortfolioConfigScopeDict.DRAFT.getCode(), config, context);

        InOrder order = inOrder(dividerExtractor);
        order.verify(dividerExtractor).extract(eq("equal-z"), eq("components[0]"), any(JSONObject.class), eq(context));
        order.verify(dividerExtractor).extract(eq("equal-a"), eq("components[1]"), any(JSONObject.class), eq(context));
        order.verify(dividerExtractor).extract(eq("null-z"), eq("components[2]"), any(JSONObject.class), eq(context));
        order.verify(dividerExtractor).extract(eq("null-a"), eq("components[3]"), any(JSONObject.class), eq(context));
    }

    /**
     * 次级菜单组件必须参与引用重建，并携带其在原始配置中的真实路径。
     */
    @Test
    void rebuildShouldExtractSecondaryMenuReferencesWithOriginalPaths() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 3);
        when(singleWorkExtractor.extract(anyString(), anyString(), any(JSONObject.class), eq(context)))
                .thenReturn(List.of(reference(TeamPortfolioComponentTypeDict.SINGLE_WORK.getCode())));
        TeamPortfolioConfigDto config = config(List.of(
                component("home", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, false)));
        TeamPortfolioConfigDto.BottomNavItem home = menu("nav_home", "首页", null);
        TeamPortfolioConfigDto.BottomNavItem works = menu("nav_works", "作品", List.of(
                component("secondary-disabled", TeamPortfolioComponentTypeDict.SINGLE_WORK.getCode(), 1000, false),
                component("secondary-work", TeamPortfolioComponentTypeDict.SINGLE_WORK.getCode(), 2000, true)));
        TeamPortfolioConfigDto.BottomNav bottomNav = new TeamPortfolioConfigDto.BottomNav();
        bottomNav.setEnabled(true);
        bottomNav.setItems(List.of(home, works));
        config.setBottomNav(bottomNav);

        service().rebuild(context.portfolioId(), PortfolioConfigScopeDict.DRAFT.getCode(), config, context);

        verify(singleWorkExtractor).extract(
                eq("secondary-work"),
                eq("bottomNav.items[1].components[1]"),
                any(JSONObject.class),
                eq(context));
        verify(referenceMapper).insert(any(PortfolioReferenceEntity.class));
        verifyNoInteractions(dividerExtractor);
    }

    /**
     * 重建方法必须声明异常回滚，保证删除和批量插入原子执行。
     */
    @Test
    void rebuildShouldRollbackForEveryException() throws NoSuchMethodException {
        Method method = TeamPortfolioReferenceService.class.getMethod(
                "rebuild", long.class, String.class, TeamPortfolioConfigDto.class,
                TeamPortfolioComponentContext.class);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.rollbackFor()).contains(Exception.class);
    }

    /**
     * 引用实体必须通过 deleted 字段执行 ID 回填式逻辑删除。
     */
    @Test
    void portfolioReferenceShouldUseIdBackedLogicalDeletion() throws NoSuchFieldException {
        Field deleted = BaseEntity.class.getDeclaredField("deleted");

        TableLogic tableLogic = deleted.getAnnotation(TableLogic.class);

        assertThat(tableLogic).isNotNull();
        assertThat(tableLogic.value()).isEqualTo("0");
        assertThat(tableLogic.delval()).isEqualTo("id");
    }

    /**
     * 空配置必须在任何提取或数据库调用之前拒绝。
     */
    @Test
    void rebuildShouldRejectNullConfigBeforeExtractorOrMapperInvocation() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 3);

        assertThatThrownBy(() -> service().rebuild(context.portfolioId(),
                PortfolioConfigScopeDict.DRAFT.getCode(), null, context))
                .isInstanceOf(BusinessException.class);
        verifyNoReferenceCollaboratorInteractions();
    }

    /**
     * 错误 schema 必须在任何提取或数据库调用之前拒绝。
     */
    @Test
    void rebuildShouldRejectWrongSchemaBeforeExtractorOrMapperInvocation() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 3);
        TeamPortfolioConfigDto wrongSchema = config(List.of());
        wrongSchema.setSchemaVersion("standard-personal-v1");

        assertThatThrownBy(() -> service().rebuild(context.portfolioId(),
                PortfolioConfigScopeDict.DRAFT.getCode(), wrongSchema, context))
                .isInstanceOf(BusinessException.class);
        verifyNoReferenceCollaboratorInteractions();
    }

    /**
     * 非法上下文必须在任何提取或数据库调用之前拒绝。
     */
    @Test
    void rebuildShouldRejectInvalidContextBeforeExtractorOrMapperInvocation() {
        TeamPortfolioConfigDto config = config(List.of());
        List<TeamPortfolioComponentContext> invalidContexts = Arrays.asList(
                null,
                new TeamPortfolioComponentContext(0L, 22L, 0),
                new TeamPortfolioComponentContext(11L, 0L, 0),
                new TeamPortfolioComponentContext(11L, 22L, -1));

        for (TeamPortfolioComponentContext invalidContext : invalidContexts) {
            long portfolioId = invalidContext == null ? 22L : invalidContext.portfolioId();
            assertThatThrownBy(() -> service().rebuild(portfolioId,
                    PortfolioConfigScopeDict.DRAFT.getCode(), config, invalidContext))
                    .isInstanceOf(BusinessException.class);
        }
        verifyNoReferenceCollaboratorInteractions();
    }

    /**
     * 组件提取异常不得被吞掉，也不能提前删除原引用。
     */
    @Test
    void rebuildShouldPropagateExtractorExceptionBeforeMapperInvocation() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 3);
        RuntimeException failure = new IllegalStateException("extract failed");
        when(dividerExtractor.extract(anyString(), anyString(), any(JSONObject.class), eq(context)))
                .thenThrow(failure);
        TeamPortfolioConfigDto config = config(List.of(
                component("divider", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true)));

        assertThatThrownBy(() -> service().rebuild(context.portfolioId(),
                PortfolioConfigScopeDict.DRAFT.getCode(), config, context)).isSameAs(failure);
        verifyNoInteractions(referenceMapper);
    }

    private void configureExtractors(TeamPortfolioComponentContext context) {
        for (TeamPortfolioComponentTypeDict type : TeamPortfolioComponentTypeDict.values()) {
            List<PortfolioReferenceEntity> references = List.of(reference(type.getCode()));
            switch (type) {
                case TEAM_PROFILE -> when(teamProfileExtractor.extract(anyString(), anyString(), any(JSONObject.class), eq(context))).thenReturn(references);
                case CAROUSEL -> when(carouselExtractor.extract(anyString(), anyString(), any(JSONObject.class), eq(context))).thenReturn(references);
                case SINGLE_WORK -> when(singleWorkExtractor.extract(anyString(), anyString(), any(JSONObject.class), eq(context))).thenReturn(references);
                case DIVIDER -> when(dividerExtractor.extract(anyString(), anyString(), any(JSONObject.class), eq(context))).thenReturn(references);
                case MEMBER_PORTFOLIO_GRID -> when(gridExtractor.extract(anyString(), anyString(), any(JSONObject.class), eq(context))).thenReturn(references);
                case MEMBER_PORTFOLIO_LIST -> when(listExtractor.extract(anyString(), anyString(), any(JSONObject.class), eq(context))).thenReturn(references);
                case TEXT_SECTION -> when(textExtractor.extract(anyString(), anyString(), any(JSONObject.class), eq(context))).thenReturn(references);
                case STRUCTURED_TEXT_SECTION -> when(structuredTextExtractor.extract(anyString(), anyString(), any(JSONObject.class), eq(context))).thenReturn(references);
                case SCHEDULE_QUERY -> when(scheduleExtractor.extract(anyString(), anyString(), any(JSONObject.class), eq(context))).thenReturn(references);
                case CONTACT_FORM -> when(contactExtractor.extract(anyString(), anyString(), any(JSONObject.class), eq(context))).thenReturn(references);
                case QR_CONTACT -> when(qrExtractor.extract(anyString(), anyString(), any(JSONObject.class), eq(context))).thenReturn(references);
                case VIDEO_CAROUSEL -> when(videoCarouselExtractor.extract(
                        anyString(), anyString(), any(JSONObject.class), eq(context))).thenReturn(references);
            }
        }
    }

    private PortfolioReferenceEntity reference(String type) {
        PortfolioReferenceEntity reference = new PortfolioReferenceEntity();
        reference.setReferenceType(type);
        reference.setReferenceId(99L);
        reference.setIsValid(1);
        return reference;
    }

    private void assertDeleteScope(Wrapper<PortfolioReferenceEntity> wrapper, long portfolioId, String scope) {
        AbstractWrapper<?, ?, ?> query = (AbstractWrapper<?, ?, ?>) wrapper;
        assertThat(query.getSqlSegment()).contains("portfolio_id", "config_scope");
        assertThat(query.getParamNameValuePairs().values()).containsExactlyInAnyOrder(portfolioId, scope);
    }

    private void verifyNoReferenceCollaboratorInteractions() {
        verifyNoInteractions(referenceMapper, teamProfileExtractor, carouselExtractor, singleWorkExtractor, dividerExtractor, gridExtractor,
                listExtractor, textExtractor, scheduleExtractor, contactExtractor, qrExtractor, videoCarouselExtractor, structuredTextExtractor);
    }

    private TeamPortfolioReferenceService service() {
        return new TeamPortfolioReferenceService(referenceMapper, teamProfileExtractor, carouselExtractor, singleWorkExtractor, dividerExtractor,
                gridExtractor, listExtractor, textExtractor, scheduleExtractor, contactExtractor, qrExtractor,
                videoCarouselExtractor, structuredTextExtractor);
    }

    private TeamPortfolioConfigDto configForEveryType(boolean enabled) {
        List<TeamPortfolioConfigDto.ComponentEnvelope> components = new ArrayList<>();
        for (TeamPortfolioComponentTypeDict type : TeamPortfolioComponentTypeDict.values()) {
            TeamPortfolioConfigDto.ComponentEnvelope component = new TeamPortfolioConfigDto.ComponentEnvelope();
            component.setComponentKey(type.getCode().toLowerCase());
            component.setComponentType(type.getCode());
            component.setSortOrder(components.size() * 1000);
            component.setEnabled(enabled);
            component.setConfig(new JSONObject());
            components.add(component);
        }
        return config(components);
    }

    private TeamPortfolioConfigDto config(List<TeamPortfolioConfigDto.ComponentEnvelope> components) {
        TeamPortfolioConfigDto config = new TeamPortfolioConfigDto();
        config.setSchemaVersion(TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1);
        config.setComponents(components);
        return config;
    }

    private TeamPortfolioConfigDto.ComponentEnvelope component(
            String key,
            String type,
            Integer sortOrder,
            boolean enabled
    ) {
        TeamPortfolioConfigDto.ComponentEnvelope component = new TeamPortfolioConfigDto.ComponentEnvelope();
        component.setComponentKey(key);
        component.setComponentType(type);
        component.setSortOrder(sortOrder);
        component.setEnabled(enabled);
        component.setConfig(new JSONObject());
        return component;
    }

    private TeamPortfolioConfigDto.BottomNavItem menu(
            String key,
            String title,
            List<TeamPortfolioConfigDto.ComponentEnvelope> components
    ) {
        TeamPortfolioConfigDto.BottomNavItem item = new TeamPortfolioConfigDto.BottomNavItem();
        item.setKey(key);
        item.setTitle(title);
        item.setComponents(components);
        return item;
    }
}
