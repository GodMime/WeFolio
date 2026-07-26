package com.jxc.wefolio.service.teamportfolio;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.constant.TeamPortfolioConstants;
import com.jxc.wefolio.dict.TeamPortfolioComponentTypeDict;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioRenderDto;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 团队作品集顶层渲染分发测试。
 */
@ExtendWith(MockitoExtension.class)
class TeamPortfolioRenderServiceTest {

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
                case SCHEDULE_QUERY -> when(scheduleRenderer.render(any(JSONObject.class), eq(context))).thenReturn(rendered);
                case CONTACT_FORM -> when(contactRenderer.render(any(JSONObject.class), eq(context))).thenReturn(rendered);
                case QR_CONTACT -> when(qrRenderer.render(any(JSONObject.class), eq(context))).thenReturn(rendered);
            }
        }
    }

    private TeamPortfolioRenderService service() {
        return new TeamPortfolioRenderService(teamProfileRenderer, carouselRenderer, singleWorkRenderer, dividerRenderer, gridRenderer,
                listRenderer, textRenderer, scheduleRenderer, contactRenderer, qrRenderer);
    }

    private void verifyNoRendererInteractions() {
        verifyNoInteractions(teamProfileRenderer, carouselRenderer, singleWorkRenderer, dividerRenderer, gridRenderer, listRenderer,
                textRenderer, scheduleRenderer, contactRenderer, qrRenderer);
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
}
