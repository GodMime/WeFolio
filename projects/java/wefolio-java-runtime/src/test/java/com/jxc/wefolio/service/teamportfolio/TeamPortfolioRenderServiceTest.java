package com.jxc.wefolio.service.teamportfolio;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.constant.TeamPortfolioConstants;
import com.jxc.wefolio.dict.TeamPortfolioComponentTypeDict;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioRenderDto;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.service.teamportfolio.component.carousel.TeamCarouselComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.contactform.TeamContactFormComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.divider.TeamDividerComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.memberportfoliogrid.TeamMemberPortfolioGridComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.memberportfoliolist.TeamMemberPortfolioListComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.qrcontact.TeamQrContactComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.schedulequery.TeamScheduleQueryComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.singlework.TeamSingleWorkComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.teamprofile.TeamProfileComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.textsection.TeamTextSectionComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.structuredtextsection.TeamStructuredTextSectionComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.TeamTextBackgroundSupport;
import com.jxc.wefolio.service.teamportfolio.component.videocarousel.TeamVideoCarouselComponentRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 团队作品集顶层渲染分发测试。
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class TeamPortfolioRenderServiceTest {
    /** 结构化文字组件策略模拟。 */
    @Mock private TeamStructuredTextSectionComponentRenderer structuredTextRenderer;
    /** 背景资源支持模拟。 */
    @Mock private TeamTextBackgroundSupport textBackgroundSupport;

    @Mock private TeamProfileComponentRenderer teamProfileRenderer;
    @Mock private TeamCarouselComponentRenderer carouselRenderer;
    @Mock private TeamSingleWorkComponentRenderer singleWorkRenderer;
    @Mock private TeamDividerComponentRenderer dividerRenderer;
    @Mock private TeamMemberPortfolioGridComponentRenderer gridRenderer;
    @Mock private TeamMemberPortfolioListComponentRenderer listRenderer;
    @Mock private TeamTextSectionComponentRenderer textRenderer;
    @Mock private TeamScheduleQueryComponentRenderer scheduleRenderer;
    @Mock private TeamContactFormComponentRenderer contactRenderer;
    @Mock private TeamQrContactComponentRenderer qrRenderer;
    @Mock private TeamVideoCarouselComponentRenderer videoCarouselRenderer;

    /**
     * 渲染仅分发已启用的团队组件，并保持其稳定排序。
     */
    @Test
    void renderShouldDispatchAllSupportedTypesAndSortEnabledComponents() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 3);
        configureRenderers(context);
        List<TeamPortfolioConfigDto.ComponentEnvelope> components = new ArrayList<>();
        int sortOrder = 9000;
        for (TeamPortfolioComponentTypeDict type : TeamPortfolioComponentTypeDict.values()) {
            components.add(component(type.getCode().toLowerCase(), type.getCode(), sortOrder, true));
            sortOrder -= 1000;
        }
        components.add(component("disabled", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 0, false));

        TeamPortfolioRenderDto render = service().render(JSON.toJSONString(config(components)), context);

        assertThat(render.getPortfolioId()).isEqualTo(context.portfolioId());
        assertThat(render.getTeamId()).isEqualTo(context.teamId());
        assertThat(render.getTitle()).isEqualTo("团队作品集");
        assertThat(render.getComponents()).extracting(TeamPortfolioRenderDto.Component::getComponentType)
                .containsExactly(
                        TeamPortfolioComponentTypeDict.STRUCTURED_TEXT_SECTION.getCode(),
                        TeamPortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode(),
                        TeamPortfolioComponentTypeDict.QR_CONTACT.getCode(),
                        TeamPortfolioComponentTypeDict.CONTACT_FORM.getCode(),
                        TeamPortfolioComponentTypeDict.SCHEDULE_QUERY.getCode(),
                        TeamPortfolioComponentTypeDict.TEXT_SECTION.getCode(),
                        TeamPortfolioComponentTypeDict.MEMBER_PORTFOLIO_LIST.getCode(),
                        TeamPortfolioComponentTypeDict.MEMBER_PORTFOLIO_GRID.getCode(),
                        TeamPortfolioComponentTypeDict.DIVIDER.getCode(),
                        TeamPortfolioComponentTypeDict.SINGLE_WORK.getCode(),
                        TeamPortfolioComponentTypeDict.CAROUSEL.getCode(),
                        TeamPortfolioComponentTypeDict.TEAM_PROFILE.getCode());
        assertThat(render.getComponents()).allSatisfy(component ->
                assertThat(component.getData().getString("renderer")).isEqualTo(component.getComponentType()));
        verify(teamProfileRenderer).render(any(JSONObject.class), eq(context));
        verify(carouselRenderer).render(any(JSONObject.class), eq(context));
        verify(singleWorkRenderer).render(any(JSONObject.class), eq(context));
        verify(dividerRenderer).render(any(JSONObject.class), eq(context));
        verify(gridRenderer).render(any(JSONObject.class), eq(context));
        verify(listRenderer).render(any(JSONObject.class), eq(context));
        verify(textRenderer).render(any(JSONObject.class), eq(context));
        verify(scheduleRenderer).render(any(JSONObject.class), eq(context));
        verify(contactRenderer).render(any(JSONObject.class), eq(context));
        verify(qrRenderer).render(any(JSONObject.class), eq(context));
        verify(videoCarouselRenderer).render(any(JSONObject.class), eq(context));
    }

    /**
     * 视频轮播全部条目失效时，顶层渲染应省略该组件且不影响其他组件。
     */
    @Test
    void renderShouldOmitVideoCarouselWhenRendererReturnsNull() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 3);
        when(videoCarouselRenderer.render(any(JSONObject.class), eq(context))).thenReturn(null);
        JSONObject dividerData = new JSONObject();
        dividerData.put("heightPx", 16);
        when(dividerRenderer.render(any(JSONObject.class), eq(context))).thenReturn(dividerData);
        TeamPortfolioConfigDto config = config(List.of(
                component("video", TeamPortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode(), 1000, true),
                component("divider", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 2000, true)));

        TeamPortfolioRenderDto render = service().render(JSON.toJSONString(config), context);

        assertThat(render.getComponents()).extracting(TeamPortfolioRenderDto.Component::getComponentKey)
                .containsExactly("divider");
    }

    /**
     * 相同排序值和多个空排序值必须保留输入先后顺序。
     */
    @Test
    void renderShouldPreserveInputOrderForEqualAndNullSortOrders() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 3);
        when(dividerRenderer.render(any(JSONObject.class), eq(context))).thenReturn(new JSONObject());
        TeamPortfolioConfigDto config = config(List.of(
                component("equal-z", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true),
                component("equal-a", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true),
                component("null-z", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), null, true),
                component("null-a", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), null, true)));

        TeamPortfolioRenderDto render = service().render(JSON.toJSONString(config), context);

        assertThat(render.getComponents()).extracting(TeamPortfolioRenderDto.Component::getComponentKey)
                .containsExactly("equal-z", "equal-a", "null-z", "null-a");
    }

    /**
     * 已归一化配置中出现未知类型时仍必须拒绝，不能静默漏渲染。
     */
    @Test
    void renderShouldRejectUnknownComponentType() {
        assertThatThrownBy(() -> service().render(JSON.toJSONString(config(List.of(
                component("unknown", "WORK_GRID", 1000, true)))), new TeamPortfolioComponentContext(11L, 22L, 0)))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 非法上下文必须稳定抛出业务异常，且不能调用任何组件渲染器。
     */
    @Test
    void renderShouldRejectInvalidContextBeforeRendererInvocation() {
        String normalizedJson = JSON.toJSONString(config(List.of(
                component("divider", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true))));
        List<TeamPortfolioComponentContext> invalidContexts = Arrays.asList(
                null,
                new TeamPortfolioComponentContext(0L, 22L, 0),
                new TeamPortfolioComponentContext(11L, 0L, 0),
                new TeamPortfolioComponentContext(11L, 22L, -1));

        for (TeamPortfolioComponentContext invalidContext : invalidContexts) {
            assertThatThrownBy(() -> service().render(normalizedJson, invalidContext))
                    .isInstanceOf(BusinessException.class);
        }
        verifyNoRendererInteractions();
    }

    /**
     * 空配置和错误 schema 必须在调用组件渲染器前拒绝。
     */
    @Test
    void renderShouldRejectInvalidConfigAndSchemaBeforeRendererInvocation() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 0);
        TeamPortfolioConfigDto wrongSchema = config(List.of(
                component("divider", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true)));
        wrongSchema.setSchemaVersion("standard-personal-v1");

        assertThatThrownBy(() -> service().render(null, context)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service().render(JSON.toJSONString(wrongSchema), context))
                .isInstanceOf(BusinessException.class);
        verifyNoRendererInteractions();
    }

    /**
     * 渲染只消费规范化持久化配置，非法菜单容器和空组件信封必须直接拒绝。
     */
    @Test
    void renderShouldRejectMalformedMenuContainerAndComponentEnvelope() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 0);
        TeamPortfolioConfigDto nullEnvelope = config(new ArrayList<>(Arrays.asList(
                component("divider", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true),
                null)));
        TeamPortfolioConfigDto malformedNavigation = config(List.of(
                component("divider", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true)));
        TeamPortfolioConfigDto.BottomNav bottomNav = new TeamPortfolioConfigDto.BottomNav();
        bottomNav.setEnabled(true);
        bottomNav.setItems(List.of(
                menu("nav_home", "主页", null),
                menu("nav_more", "更多", null)));
        malformedNavigation.setBottomNav(bottomNav);

        assertThatThrownBy(() -> service().render(JSON.toJSONString(nullEnvelope), context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("团队作品集配置格式不正确");
        assertThatThrownBy(() -> service().render(JSON.toJSONString(malformedNavigation), context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("团队作品集配置格式不正确");
        verifyNoRendererInteractions();
    }

    /**
     * 空菜单项必须在拒绝渲染前记录可定位的警告日志，不能静默生成缺项菜单。
     */
    @Test
    void renderShouldWarnBeforeRejectingNullNavigationItem(CapturedOutput output) {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 0);
        TeamPortfolioConfigDto malformedNavigation = config(List.of(
                component("divider", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true)));
        TeamPortfolioConfigDto.BottomNav bottomNav = new TeamPortfolioConfigDto.BottomNav();
        bottomNav.setEnabled(true);
        bottomNav.setItems(new ArrayList<>(Arrays.asList(
                menu("nav_home", "主页", null),
                null)));
        malformedNavigation.setBottomNav(bottomNav);

        assertThatThrownBy(() -> service().render(JSON.toJSONString(malformedNavigation), context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("团队作品集配置格式不正确");
        assertThat(output)
                .contains("团队作品集规范化配置中的底部导航菜单项无效")
                .contains("teamId=11")
                .contains("portfolioId=22")
                .contains("menuIndex=1");
        verifyNoRendererInteractions();
    }

    /**
     * 旧配置必须渲染为默认白色浅色主题，并明确关闭底部导航。
     */
    @Test
    void renderShouldDefaultLegacyConfigToLightThemeAndDisabledNavigation() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 3);
        when(dividerRenderer.render(any(JSONObject.class), eq(context))).thenReturn(new JSONObject());

        TeamPortfolioRenderDto render = service().render(JSON.toJSONString(config(List.of(
                component("component-home", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true)
        ))), context);

        assertThat(render.getStyle().getBackgroundColor()).isEqualTo("#FFFFFF");
        assertThat(render.getStyle().getThemeMode()).isEqualTo("light");
        assertThat(render.getBottomNav().isEnabled()).isFalse();
        assertThat(render.getBottomNav().getItems()).isEmpty();
    }

    /**
     * 第一菜单和次级菜单必须调用同一个组件分发入口，并保持第一菜单顶层单一数据源。
     */
    @Test
    void renderShouldBuildDarkNavigationAndDispatchSecondaryComponents() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 4);
        when(dividerRenderer.render(any(JSONObject.class), eq(context)))
                .thenReturn(JSONObject.of("renderer", "home"));
        when(contactRenderer.render(any(JSONObject.class), eq(context)))
                .thenReturn(JSONObject.of("renderer", "secondary"));
        TeamPortfolioConfigDto config = config(List.of(
                component("component-home", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true)));
        TeamPortfolioConfigDto.Style style = new TeamPortfolioConfigDto.Style();
        style.setBackgroundColor("#151515");
        config.setStyle(style);
        TeamPortfolioConfigDto.BottomNavItem first = menu("nav_home", "主页", null);
        TeamPortfolioConfigDto.BottomNavItem second = menu("nav_contact", "联系", List.of(
                component("component-contact", TeamPortfolioComponentTypeDict.CONTACT_FORM.getCode(), 1000, true)));
        TeamPortfolioConfigDto.BottomNav bottomNav = new TeamPortfolioConfigDto.BottomNav();
        bottomNav.setEnabled(true);
        bottomNav.setItems(List.of(first, second));
        config.setBottomNav(bottomNav);

        TeamPortfolioRenderDto render = service().render(JSON.toJSONString(config), context);

        assertThat(render.getStyle().getBackgroundColor()).isEqualTo("#151515");
        assertThat(render.getStyle().getThemeMode()).isEqualTo("dark");
        assertThat(render.getComponents()).extracting(TeamPortfolioRenderDto.Component::getComponentKey)
                .containsExactly("component-home");
        assertThat(render.getBottomNav().isEnabled()).isTrue();
        assertThat(render.getBottomNav().getItems()).hasSize(2);
        assertThat(render.getBottomNav().getItems().getFirst().getComponents()).isNull();
        assertThat(render.getBottomNav().getItems().get(1).getComponents())
                .singleElement()
                .satisfies(component -> {
                    assertThat(component.getComponentKey()).isEqualTo("component-contact");
                    assertThat(component.getData().getString("renderer")).isEqualTo("secondary");
                });
        verify(dividerRenderer).render(any(JSONObject.class), eq(context));
        verify(contactRenderer).render(any(JSONObject.class), eq(context));
    }

    /**
     * 顶层入口须一次汇总主页与次级菜单背景，并把同一授权缓存传给两种文字策略。
     */
    @Test
    void renderShouldBatchMainAndSecondaryTextBackgroundsAndSkipDisabledComponents() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 5);
        TeamPortfolioConfigDto.ComponentEnvelope plain = component(
                "home-text", TeamPortfolioComponentTypeDict.TEXT_SECTION.getCode(), 1000, true);
        plain.setConfig(JSONObject.of("backgroundEnabled", true, "backgroundWorkId", 71L));
        TeamPortfolioConfigDto.ComponentEnvelope structured = component(
                "more-text", TeamPortfolioComponentTypeDict.STRUCTURED_TEXT_SECTION.getCode(), 1000, true);
        structured.setConfig(JSONObject.of("backgroundEnabled", true, "backgroundWorkId", 72L));
        TeamPortfolioConfigDto.ComponentEnvelope disabled = component(
                "disabled-text", TeamPortfolioComponentTypeDict.TEXT_SECTION.getCode(), 2000, false);
        disabled.setConfig(JSONObject.of("backgroundEnabled", true, "backgroundWorkId", 73L));
        TeamPortfolioConfigDto config = config(List.of(plain));
        TeamPortfolioConfigDto.BottomNav bottomNav = new TeamPortfolioConfigDto.BottomNav();
        bottomNav.setEnabled(true);
        bottomNav.setItems(List.of(menu("nav_home", "主页", null),
                menu("nav_more", "介绍", List.of(structured, disabled))));
        config.setBottomNav(bottomNav);
        Map<Long, WorkEntity> authorized = Map.of(71L, new WorkEntity(), 72L, new WorkEntity());
        List<TeamPortfolioComponentContext> dispatchedContexts = new ArrayList<>();
        when(textBackgroundSupport.load(anyList(), eq(context))).thenAnswer(invocation -> {
            List<JSONObject> backgrounds = invocation.getArgument(0);
            assertThat(backgrounds).extracting(item -> item.getLong("backgroundWorkId"))
                    .containsExactly(71L, 72L);
            return authorized;
        });
        when(textRenderer.render(any(JSONObject.class), any(TeamPortfolioComponentContext.class)))
                .thenAnswer(invocation -> {
                    dispatchedContexts.add(invocation.getArgument(1));
                    return JSONObject.of("content", "主页文字");
                });
        when(structuredTextRenderer.render(any(JSONObject.class), any(TeamPortfolioComponentContext.class)))
                .thenAnswer(invocation -> {
                    dispatchedContexts.add(invocation.getArgument(1));
                    return JSONObject.of("blocks", List.of(JSONObject.of("content", "次级菜单文字")));
                });

        TeamPortfolioRenderDto rendered = service().render(JSON.toJSONString(config), context);

        verify(textBackgroundSupport).load(anyList(), eq(context));
        assertThat(dispatchedContexts).hasSize(2);
        assertThat(dispatchedContexts.get(1)).isSameAs(dispatchedContexts.getFirst());
        assertThat(dispatchedContexts.getFirst().textBackgroundWorks()).isSameAs(authorized);
        assertThat(dispatchedContexts.getFirst().teamId()).isEqualTo(11L);
        assertThat(dispatchedContexts.getFirst().portfolioId()).isEqualTo(22L);
        assertThat(dispatchedContexts.getFirst().revision()).isEqualTo(5);
        assertThat(context.textBackgroundWorks()).isNull();
        assertThat(rendered.getComponents()).extracting(TeamPortfolioRenderDto.Component::getComponentKey)
                .containsExactly("home-text");
        assertThat(rendered.getBottomNav().getItems().get(1).getComponents())
                .extracting(TeamPortfolioRenderDto.Component::getComponentKey).containsExactly("more-text");
    }

    /**
     * 十类组件在第一菜单和次级菜单中必须保持完全相同的渲染数据契约。
     */
    @Test
    void renderShouldKeepEveryComponentContractIdenticalAcrossMenus() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 5);
        configureRenderers(context);
        List<TeamPortfolioConfigDto.ComponentEnvelope> firstComponents = new ArrayList<>();
        List<TeamPortfolioConfigDto.ComponentEnvelope> secondaryComponents = new ArrayList<>();
        int index = 0;
        for (TeamPortfolioComponentTypeDict type : TeamPortfolioComponentTypeDict.values()) {
            firstComponents.add(component(
                    "first-" + type.getCode().toLowerCase(),
                    type.getCode(),
                    (++index) * 1000,
                    true));
            secondaryComponents.add(component(
                    "secondary-" + type.getCode().toLowerCase(),
                    type.getCode(),
                    index * 1000,
                    true));
        }
        TeamPortfolioConfigDto config = config(firstComponents);
        TeamPortfolioConfigDto.BottomNav bottomNav = new TeamPortfolioConfigDto.BottomNav();
        bottomNav.setEnabled(true);
        bottomNav.setItems(List.of(
                menu("nav_home", "主页", null),
                menu("nav_more", "更多", secondaryComponents)));
        config.setBottomNav(bottomNav);

        TeamPortfolioRenderDto render = service().render(JSON.toJSONString(config), context);
        List<TeamPortfolioRenderDto.Component> secondary =
                render.getBottomNav().getItems().get(1).getComponents();

        assertThat(render.getComponents()).hasSize(TeamPortfolioComponentTypeDict.values().length);
        assertThat(secondary).hasSameSizeAs(render.getComponents());
        for (TeamPortfolioRenderDto.Component first : render.getComponents()) {
            TeamPortfolioRenderDto.Component matching = secondary.stream()
                    .filter(component -> component.getComponentType().equals(first.getComponentType()))
                    .findFirst()
                    .orElseThrow();
            assertThat(matching.getData()).isEqualTo(first.getData());
        }
    }

    private void configureRenderers(TeamPortfolioComponentContext context) {
        for (TeamPortfolioComponentTypeDict type : TeamPortfolioComponentTypeDict.values()) {
            JSONObject rendered = new JSONObject();
            rendered.put("renderer", type.getCode());
            switch (type) {
                case TEAM_PROFILE -> when(teamProfileRenderer.render(any(JSONObject.class), eq(context))).thenReturn(rendered);
                case CAROUSEL -> when(carouselRenderer.render(any(JSONObject.class), eq(context))).thenReturn(rendered);
                case SINGLE_WORK -> when(singleWorkRenderer.render(any(JSONObject.class), eq(context))).thenReturn(rendered);
                case DIVIDER -> when(dividerRenderer.render(any(JSONObject.class), eq(context))).thenReturn(rendered);
                case MEMBER_PORTFOLIO_GRID -> when(gridRenderer.render(any(JSONObject.class), eq(context))).thenReturn(rendered);
                case MEMBER_PORTFOLIO_LIST -> when(listRenderer.render(any(JSONObject.class), eq(context))).thenReturn(rendered);
                case TEXT_SECTION -> when(textRenderer.render(any(JSONObject.class), eq(context))).thenReturn(rendered);
                case STRUCTURED_TEXT_SECTION -> when(structuredTextRenderer.render(any(JSONObject.class), eq(context))).thenReturn(rendered);
                case SCHEDULE_QUERY -> when(scheduleRenderer.render(any(JSONObject.class), eq(context))).thenReturn(rendered);
                case CONTACT_FORM -> when(contactRenderer.render(any(JSONObject.class), eq(context))).thenReturn(rendered);
                case QR_CONTACT -> when(qrRenderer.render(any(JSONObject.class), eq(context))).thenReturn(rendered);
                case VIDEO_CAROUSEL -> when(videoCarouselRenderer.render(any(JSONObject.class), eq(context))).thenReturn(rendered);
            }
        }
    }

    private TeamPortfolioRenderService service() {
        return new TeamPortfolioRenderService(teamProfileRenderer, carouselRenderer, singleWorkRenderer, dividerRenderer, gridRenderer,
                listRenderer, textRenderer, scheduleRenderer, contactRenderer, qrRenderer, videoCarouselRenderer, structuredTextRenderer, textBackgroundSupport);
    }

    private void verifyNoRendererInteractions() {
        verifyNoInteractions(teamProfileRenderer, carouselRenderer, singleWorkRenderer, dividerRenderer, gridRenderer, listRenderer,
                textRenderer, scheduleRenderer, contactRenderer, qrRenderer, videoCarouselRenderer, structuredTextRenderer, textBackgroundSupport);
    }

    private TeamPortfolioConfigDto config(List<TeamPortfolioConfigDto.ComponentEnvelope> components) {
        TeamPortfolioConfigDto config = new TeamPortfolioConfigDto();
        config.setSchemaVersion(TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1);
        TeamPortfolioConfigDto.Share share = new TeamPortfolioConfigDto.Share();
        share.setTitle("团队作品集");
        config.setShare(share);
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
