package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.message.PortfolioMessage;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import com.jxc.wefolio.service.teamportfolio.TeamTextBackgroundSupport;
import com.jxc.wefolio.service.teamportfolio.component.textsection.TeamTextSectionComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.textsection.TeamTextSectionComponentValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 普通文字颜色在个人及团队保存、序列化和展示之间的契约测试。 */
class PortfolioTextColorPipelineTest {

    /** 仅替代数据库查询边界，文字规则与渲染使用真实实现。 */
    private final WorkEntityMapper workMapper = mock(WorkEntityMapper.class);

    /** 对象存储边界模拟。 */
    private final CosService cosService = mock(CosService.class);

    /** 个人配置保存规范化入口。 */
    private final PortfolioConfigValidator personalValidator = new PortfolioConfigValidator(workMapper);

    /** 个人完整展示入口。 */
    private final PortfolioRenderService personalRenderer = new PortfolioRenderService(
            workMapper, mock(PortfolioEntityMapper.class), cosService);

    /** 团队背景规则使用真实实现，避免遮蔽颜色与背景配置的合并行为。 */
    private final TeamTextBackgroundSupport teamBackground = new TeamTextBackgroundSupport(
            mock(TeamMemberEntityMapper.class), mock(UserEntityMapper.class), workMapper, cosService);

    /** 团队普通文字保存入口。 */
    private final TeamTextSectionComponentValidator teamValidator = new TeamTextSectionComponentValidator(teamBackground);

    /** 团队普通文字展示入口。 */
    private final TeamTextSectionComponentRenderer teamRenderer = new TeamTextSectionComponentRenderer(teamBackground);

    /** 合法团队组件上下文。 */
    private final TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 3);

    /** 缺省或空颜色兼容旧客户端，保存与直接展示都补充自动颜色语义。 */
    @Test
    void absentAndNullColorDefaultToAutoForSaveAndHistoricalRender() {
        for (boolean explicitNull : List.of(false, true)) {
            PortfolioConfigDto personal = personalConfig();
            JSONObject team = teamConfig();
            if (explicitNull) {
                personal.getComponents().getFirst().getConfig().put("color", null);
                team.put("color", null);
            }
            assertThat(renderPersonal(personal)).containsEntry("color", "AUTO");
            assertThat(personalValidator.normalize(7L, personal).getComponents().getFirst().getConfig())
                    .containsEntry("color", "AUTO");
            assertThat(teamRenderer.render(team, context)).containsEntry("color", "AUTO");
            assertThat(teamValidator.normalizeAndValidate(team, context)).containsEntry("color", "AUTO");
        }
    }

    /** 自动、预设与自定义颜色均能通过持久化 JSON 往返，十六进制统一大写。 */
    @Test
    void validColorsSurvivePersonalAndTeamSaveJsonAndRender() {
        for (Map.Entry<String, String> color : Map.of(
                "AUTO", "AUTO", "#FFFFFF", "#FFFFFF", "#212529", "#212529",
                "#a1b2c3", "#A1B2C3", "#AbCdEf", "#ABCDEF").entrySet()) {
            PortfolioConfigDto personal = personalConfig();
            personal.getComponents().getFirst().getConfig().put("color", color.getKey());
            PortfolioConfigDto saved = personalValidator.normalize(7L, personal);
            PortfolioConfigDto loaded = JSON.parseObject(JSON.toJSONString(saved), PortfolioConfigDto.class);
            assertThat(loaded.getComponents().getFirst().getConfig()).containsEntry("color", color.getValue());
            assertThat(renderPersonal(loaded)).containsEntry("color", color.getValue()).containsEntry("content", "文字介绍");

            JSONObject team = teamConfig();
            team.put("color", color.getKey());
            JSONObject loadedTeam = JSON.parseObject(teamValidator.normalizeAndValidate(team, context).toJSONString());
            assertThat(loadedTeam).containsEntry("color", color.getValue());
            assertThat(teamRenderer.render(loadedTeam, context)).containsEntry("color", color.getValue())
                    .containsEntry("content", "文字介绍");
        }
    }

    /** 非法显式颜色不能通过保存，避免任意 CSS 或类型被序列化到展示端。 */
    @Test
    void invalidColorTypesAndCssAreRejectedForBothSavePaths() {
        for (Object invalid : List.of("", "auto", "#fff", "red", "#12345678", " #123456",
                "#123456;background:url(x)", 123456, true, List.of("#FFFFFF"), Map.of("value", "AUTO"))) {
            PortfolioConfigDto personal = personalConfig();
            personal.getComponents().getFirst().getConfig().put("color", invalid);
            JSONObject team = teamConfig();
            team.put("color", invalid);
            assertThatThrownBy(() -> personalValidator.normalize(7L, personal))
                    .isInstanceOf(BusinessException.class).hasMessage("文字颜色仅支持自动或六位十六进制颜色");
            assertThatThrownBy(() -> teamValidator.normalizeAndValidate(team, context))
                    .isInstanceOf(BusinessException.class).hasMessage("文字颜色仅支持自动或六位十六进制颜色");
        }
    }

    /** 显式颜色不随主题和失效背景改变，展示历史非法颜色时安全回退自动。 */
    @Test
    void renderPreservesExplicitColorAcrossThemesAndUnavailableBackgrounds() {
        for (String theme : List.of("#FFFFFF", "#212529")) {
            for (boolean backgroundEnabled : List.of(false, true)) {
                PortfolioConfigDto personal = personalConfig();
                PortfolioConfigDto.Style style = new PortfolioConfigDto.Style();
                style.setBackgroundColor(theme);
                personal.setStyle(style);
                personal.getComponents().getFirst().getConfig().put("backgroundEnabled", backgroundEnabled);
                personal.getComponents().getFirst().getConfig().put("color", "#a1b2c3");
                assertThat(renderPersonal(personal)).containsEntry("color", "#A1B2C3");
                JSONObject team = teamConfig();
                team.put("backgroundEnabled", backgroundEnabled);
                team.put("color", "#a1b2c3");
                assertThat(teamRenderer.render(team, context)).containsEntry("color", "#A1B2C3");
            }
        }
        PortfolioConfigDto personal = personalConfig();
        personal.getComponents().getFirst().getConfig().put("color", "red;display:none");
        assertThat(renderPersonal(personal)).containsEntry("color", "AUTO").containsEntry("content", "文字介绍");
        JSONObject team = teamConfig();
        team.put("color", "red;display:none");
        assertThat(teamRenderer.render(team, context)).containsEntry("color", "AUTO").containsEntry("content", "文字介绍");
    }

    /** 新颜色规则不能抢先覆盖原有内容及字号校验错误。 */
    @Test
    void existingValidationErrorsKeepPriorityOverInvalidColor() {
        PortfolioConfigDto personal = personalConfig();
        personal.getComponents().getFirst().getConfig().put("color", false);
        personal.getComponents().getFirst().getConfig().put("content", "");
        JSONObject team = teamConfig();
        team.put("color", false);
        team.put("content", "");
        assertThatThrownBy(() -> personalValidator.normalize(7L, personal))
                .hasMessage(PortfolioMessage.TEXT_SECTION_CONTENT_REQUIRED_MESSAGE);
        assertThatThrownBy(() -> teamValidator.normalizeAndValidate(team, context))
                .hasMessage(PortfolioMessage.TEXT_SECTION_CONTENT_REQUIRED_MESSAGE);
        personal.getComponents().getFirst().getConfig().put("content", "文字介绍");
        personal.getComponents().getFirst().getConfig().put("fontSizeRpx", 1);
        team.put("content", "文字介绍");
        team.put("fontSizeRpx", 1);
        assertThatThrownBy(() -> personalValidator.normalize(7L, personal))
                .hasMessage(PortfolioMessage.TEXT_SECTION_FONT_SIZE_INVALID_MESSAGE);
        assertThatThrownBy(() -> teamValidator.normalizeAndValidate(team, context))
                .hasMessage(PortfolioMessage.TEXT_SECTION_FONT_SIZE_INVALID_MESSAGE);
    }

    /** 构造旧版客户端不包含颜色字段的个人作品集。 */
    private PortfolioConfigDto personalConfig() {
        return JSON.parseObject("""
                {"schemaVersion":"standard-personal-v1","components":[{"componentKey":"c_text",
                 "componentType":"TEXT_SECTION","enabled":true,"sortOrder":1000,
                 "config":{"content":"文字介绍"}}]}
                """, PortfolioConfigDto.class);
    }

    /** 构造旧版团队普通文字配置。 */
    private JSONObject teamConfig() {
        return JSON.parseObject("""
                {"content":"文字介绍","alignment":"LEFT"}
                """);
    }

    /** 获取个人接口实际序列化后的文字展示对象，覆盖 DTO 字段遗漏。 */
    private JSONObject renderPersonal(PortfolioConfigDto config) {
        PortfolioEntity portfolio = new PortfolioEntity();
        portfolio.setOwnerId(7L);
        portfolio.setId(90L);
        return JSON.parseObject(JSON.toJSONString(personalRenderer.render(portfolio, config, true, false, null, null)))
                .getJSONArray("components").getJSONObject(0).getJSONObject("textSection");
    }
}
