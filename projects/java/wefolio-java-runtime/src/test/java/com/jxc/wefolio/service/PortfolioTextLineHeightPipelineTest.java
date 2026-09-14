package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.constant.TeamPortfolioConstants;
import com.jxc.wefolio.dict.PortfolioComponentTypeDict;
import com.jxc.wefolio.dict.PortfolioTextBlockTypeDict;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.message.PortfolioTextMessage;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioConfigValidator;
import com.jxc.wefolio.service.teamportfolio.TeamTextBackgroundSupport;
import com.jxc.wefolio.service.teamportfolio.component.structuredtextsection.TeamStructuredTextSectionComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.structuredtextsection.TeamStructuredTextSectionComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.textsection.TeamTextSectionComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.textsection.TeamTextSectionComponentValidator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 行高在个人与团队保存、旧请求合并、JSON 持久化和渲染之间的完整契约测试。 */
class PortfolioTextLineHeightPipelineTest {
    /** 三种行高所属组件。 */
    private static final List<PortfolioComponentTypeDict> TYPES = List.of(
            PortfolioComponentTypeDict.TEXT_SECTION, PortfolioComponentTypeDict.STRUCTURED_TEXT_SECTION,
            PortfolioComponentTypeDict.TEXT_GRID);
    /** 本次新增的可选字段。 */
    private static final String LINE_HEIGHT = "lineHeight";
    /** 文字及网格段落集合字段。 */
    private static final String BLOCKS = "blocks";
    /** 存储查询边界模拟。 */
    private final WorkEntityMapper workMapper = mock(WorkEntityMapper.class);
    /** 背景资源边界模拟，行高不依赖资源查询。 */
    private final TeamTextBackgroundSupport background = mock(TeamTextBackgroundSupport.class);
    /** 个人实际保存入口。 */
    private final PortfolioConfigValidator personalValidator = new PortfolioConfigValidator(workMapper);
    /** 个人实际渲染入口。 */
    private final PortfolioRenderService personalRenderer = new PortfolioRenderService(workMapper,
            mock(PortfolioEntityMapper.class), mock(CosService.class), mock(PortfolioBackgroundAudioService.class));
    /** 团队实际保存及组件分发入口，未涉及的组件策略不参与本组测试。 */
    private final TeamPortfolioConfigValidator teamValidator = new TeamPortfolioConfigValidator(
            null, null, null, null, null, null, new TeamTextSectionComponentValidator(background),
            null, null, null, null, new TeamStructuredTextSectionComponentValidator(background));
    /** 合法团队组件上下文。 */
    private final TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 3);

    /** 缺省始终不注入字段，保持三类组件及个人团队原有的各自行高。 */
    @Test
    void omittedLineHeightStaysAbsentAfterSaveAndRender() {
        for (boolean team : List.of(false, true)) {
            for (PortfolioComponentTypeDict type : TYPES) {
                JSONObject normalized = normalize(team, type, source(type), null);
                assertThat(node(type, normalized)).doesNotContainKey(LINE_HEIGHT);
                assertThat(node(type, render(team, type, normalized))).doesNotContainKey(LINE_HEIGHT);
            }
        }
    }

    /** 下限、上限、相邻步进与整数倍数均以 JSON 数值穿过保存和展示，不影响字号默认。 */
    @Test
    void legalLineHeightSurvivesSaveJsonAndRender() {
        for (boolean team : List.of(false, true)) {
            for (PortfolioComponentTypeDict type : TYPES) {
                for (Number value : List.<Number>of(0.5, 0.6, 1, 1.7, 2.9, 3, new BigDecimal("1.70"))) {
                    JSONObject config = source(type);
                    node(type, config).put(LINE_HEIGHT, value);
                    JSONObject normalized = normalize(team, type, config, null);
                    BigDecimal expected = new BigDecimal(value.toString());
                    assertThat(node(type, normalized).getBigDecimal(LINE_HEIGHT)).isEqualByComparingTo(expected);
                    JSONObject persisted = JSON.parseObject(normalized.toJSONString());
                    assertThat(node(type, render(team, type, persisted)).getBigDecimal(LINE_HEIGHT))
                            .isEqualByComparingTo(expected);
                    assertThat(node(type, normalized).get(LINE_HEIGHT)).isInstanceOf(Number.class);
                    if (type == PortfolioComponentTypeDict.TEXT_GRID) {
                        assertThat(node(type, normalized).getJSONArray("runs").getJSONObject(0))
                                .doesNotContainKey(LINE_HEIGHT).containsEntry("fontSizeRpx", 28);
                    }
                }
            }
        }
    }

    /** 缺失与显式空值不同，团队 JSON 深拷贝不能吞掉非法值或触发旧值回填。 */
    @Test
    void rejectsInvalidLineHeightThroughPersonalAndTeamSave() {
        for (boolean team : List.of(false, true)) {
            for (PortfolioComponentTypeDict type : TYPES) {
                JSONObject existing = source(type);
                node(type, existing).put(LINE_HEIGHT, 1.8);
                for (Object value : new Object[]{null, 0.4, 3.1, 1.55, "1.5", "", true,
                        Map.of("value", 1.5), Double.NaN, Double.POSITIVE_INFINITY}) {
                    JSONObject config = source(type);
                    node(type, config).put(LINE_HEIGHT, value);
                    assertThatThrownBy(() -> normalize(team, type, config, null))
                            .as("team=%s, type=%s, value=%s", team, type, value)
                            .isInstanceOf(BusinessException.class).hasMessage(PortfolioTextMessage.LINE_HEIGHT_INVALID);
                    assertThatThrownBy(() -> normalize(team, type, config, existing))
                            .as("已有草稿，team=%s, type=%s, value=%s", team, type, value)
                            .isInstanceOf(BusinessException.class).hasMessage(PortfolioTextMessage.LINE_HEIGHT_INVALID);
                }
            }
        }
    }

    /** 已认识组件的旧请求省略行高时保留草稿值，显式新数值始终覆盖且不修改请求。 */
    @Test
    void legacyOmissionPreservesExistingAndExplicitValueOverrides() {
        for (boolean team : List.of(false, true)) {
            for (PortfolioComponentTypeDict type : TYPES) {
                JSONObject existing = source(type);
                node(type, existing).put(LINE_HEIGHT, 1.8);
                JSONObject incoming = source(type);
                assertThat(node(type, normalize(team, type, incoming, existing)).getBigDecimal(LINE_HEIGHT))
                        .isEqualByComparingTo("1.8");
                assertThat(node(type, incoming)).doesNotContainKey(LINE_HEIGHT);
                node(type, incoming).put(LINE_HEIGHT, 0.5);
                assertThat(node(type, normalize(team, type, incoming, existing)).getBigDecimal(LINE_HEIGHT))
                        .isEqualByComparingTo("0.5");
                assertThat(node(type, existing)).containsEntry(LINE_HEIGHT, 1.8);
            }
        }
    }

    /** 结构化区块重排按键匹配，换类型不传播行高，删除不复活，留白不继承文字行高。 */
    @Test
    void structuredBlocksMatchKeysAndTypesWithoutRevivingDeletedBlocks() {
        for (boolean team : List.of(false, true)) {
            JSONObject existing = JSONObject.of(BLOCKS, List.of(
                    block("keep", PortfolioTextBlockTypeDict.PARAGRAPH, 1.8),
                    block("change", PortfolioTextBlockTypeDict.TITLE, 1.9),
                    block("delete", PortfolioTextBlockTypeDict.PARAGRAPH, 2.0)));
            JSONObject incoming = JSONObject.of(BLOCKS, List.of(
                    block("change", PortfolioTextBlockTypeDict.PARAGRAPH, null),
                    block("keep", PortfolioTextBlockTypeDict.PARAGRAPH, null),
                    block("space", PortfolioTextBlockTypeDict.SPACER, null)));
            JSONObject saved = normalize(team, PortfolioComponentTypeDict.STRUCTURED_TEXT_SECTION, incoming, existing);
            assertThat(saved.getJSONArray(BLOCKS)).hasSize(3);
            assertThat(saved.getJSONArray(BLOCKS).getJSONObject(0)).doesNotContainKey(LINE_HEIGHT);
            assertThat(saved.getJSONArray(BLOCKS).getJSONObject(1).getBigDecimal(LINE_HEIGHT)).isEqualByComparingTo("1.8");
            assertThat(saved.getJSONArray(BLOCKS).getJSONObject(2)).doesNotContainKey(LINE_HEIGHT);
        }
    }

    /** 留白不允许任何显式行高，团队拷贝也必须保留空值交给校验器拒绝。 */
    @Test
    void spacerRejectsExplicitLineHeightIncludingNull() {
        for (boolean team : List.of(false, true)) {
            for (Object value : new Object[]{null, 1.5, 99, "1.5"}) {
                JSONObject spacer = block("space", PortfolioTextBlockTypeDict.SPACER, null);
                spacer.put(LINE_HEIGHT, value);
                JSONObject config = JSONObject.of(BLOCKS, List.of(spacer,
                        block("body", PortfolioTextBlockTypeDict.PARAGRAPH, null)));
                assertThatThrownBy(() -> normalize(team, PortfolioComponentTypeDict.STRUCTURED_TEXT_SECTION, config, null))
                        .as("team=%s, value=%s", team, value).isInstanceOf(BusinessException.class)
                        .hasMessage(PortfolioTextMessage.LINE_HEIGHT_INVALID);
            }
        }
    }

    /** 网格段落跨格移动仍按稳定键继承，新增键不继承，删除段落不恢复。 */
    @Test
    void gridBlocksFollowStableKeysAcrossCells() {
        for (boolean team : List.of(false, true)) {
            JSONObject existing = source(PortfolioComponentTypeDict.TEXT_GRID);
            JSONObject incoming = source(PortfolioComponentTypeDict.TEXT_GRID);
            node(PortfolioComponentTypeDict.TEXT_GRID, existing).put(LINE_HEIGHT, 2.2);
            var cells = incoming.getJSONArray("cells");
            Object firstBlocks = cells.getJSONObject(0).get(BLOCKS);
            cells.getJSONObject(0).put(BLOCKS, cells.getJSONObject(1).get(BLOCKS));
            cells.getJSONObject(1).put(BLOCKS, firstBlocks);
            JSONObject saved = normalize(team, PortfolioComponentTypeDict.TEXT_GRID, incoming, existing);
            assertThat(node(PortfolioComponentTypeDict.TEXT_GRID, saved)).doesNotContainKey(LINE_HEIGHT);
            assertThat(saved.getJSONArray("cells").getJSONObject(1).getJSONArray(BLOCKS).getJSONObject(0)
                    .getBigDecimal(LINE_HEIGHT)).isEqualByComparingTo("2.2");
            node(PortfolioComponentTypeDict.TEXT_GRID, incoming).put("blockKey", "new_block");
            cells.getJSONObject(1).getJSONArray(BLOCKS).getJSONObject(0).put("blockKey", "replacement_block");
            JSONObject replaced = normalize(team, PortfolioComponentTypeDict.TEXT_GRID, incoming, existing);
            assertThat(replaced.getJSONArray("cells").getJSONObject(1).getJSONArray(BLOCKS)).hasSize(1);
            assertThat(replaced.getJSONArray("cells").getJSONObject(1).getJSONArray(BLOCKS).getJSONObject(0))
                    .doesNotContainKey(LINE_HEIGHT);
        }
    }

    /** 同组件键改为另一种文字组件时，旧组件行高不能传播到新类型。 */
    @Test
    void componentTypeChangeDoesNotCopyLineHeight() {
        JSONObject previous = source(PortfolioComponentTypeDict.TEXT_SECTION);
        previous.put(LINE_HEIGHT, 2.4);
        JSONObject incoming = source(PortfolioComponentTypeDict.STRUCTURED_TEXT_SECTION);
        PortfolioConfigDto personal = personalValidator.normalizeForDraft(7L,
                personalConfig(PortfolioComponentTypeDict.STRUCTURED_TEXT_SECTION, incoming),
                personalConfig(PortfolioComponentTypeDict.TEXT_SECTION, previous));
        assertThat(node(PortfolioComponentTypeDict.STRUCTURED_TEXT_SECTION,
                json(personal.getComponents().getFirst().getConfig()))).doesNotContainKey(LINE_HEIGHT);
        TeamPortfolioConfigDto team = teamValidator.normalizeForDraft(
                teamConfig(PortfolioComponentTypeDict.STRUCTURED_TEXT_SECTION, incoming),
                teamConfig(PortfolioComponentTypeDict.TEXT_SECTION, previous), context);
        assertThat(node(PortfolioComponentTypeDict.STRUCTURED_TEXT_SECTION,
                team.getComponents().getFirst().getConfig())).doesNotContainKey(LINE_HEIGHT);
    }

    /** 按所属层级构造旧配置，所有嵌套对象均可独立修改。 */
    private JSONObject source(PortfolioComponentTypeDict type) {
        return switch (type) {
            case TEXT_SECTION -> JSONObject.of("content", "正文");
            case STRUCTURED_TEXT_SECTION -> json(Map.of(BLOCKS,
                    List.of(block("body", PortfolioTextBlockTypeDict.PARAGRAPH, null))));
            case TEXT_GRID -> json(PortfolioTextGridConfigNormalizer.normalize(Map.of()));
            default -> throw new IllegalArgumentException(type.getCode());
        };
    }

    /** 构造独立结构化区块，空入参表示省略行高。 */
    private JSONObject block(String key, PortfolioTextBlockTypeDict type, Number height) {
        JSONObject value = JSONObject.of("blockKey", key, "type", type.getCode(), "content", "文字");
        if (height != null) { value.put(LINE_HEIGHT, height); }
        return value;
    }

    /** 读取配置或展示对象中的行高所属节点。 */
    private JSONObject node(PortfolioComponentTypeDict type, JSONObject config) {
        return switch (type) {
            case TEXT_SECTION -> config;
            case STRUCTURED_TEXT_SECTION -> config.getJSONArray(BLOCKS).getJSONObject(0);
            case TEXT_GRID -> config.getJSONArray("cells").getJSONObject(0).getJSONArray(BLOCKS).getJSONObject(0);
            default -> throw new IllegalArgumentException(type.getCode());
        };
    }

    /** 通过真实保存入口验证兼容合并发生在字段规范化之前。 */
    private JSONObject normalize(boolean team, PortfolioComponentTypeDict type, JSONObject source, JSONObject existing) {
        if (team) {
            return teamValidator.normalizeForDraft(teamConfig(type, source),
                    existing == null ? null : teamConfig(type, existing), context).getComponents().getFirst().getConfig();
        }
        return json(personalValidator.normalizeForDraft(7L, personalConfig(type, source),
                existing == null ? null : personalConfig(type, existing)).getComponents().getFirst().getConfig());
    }

    /** 使用组件已上线时的旧能力版本，确保字段省略保护不依赖版本升级。 */
    private PortfolioConfigDto personalConfig(PortfolioComponentTypeDict type, JSONObject config) {
        PortfolioConfigDto value = new PortfolioConfigDto();
        value.setSchemaVersion(PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1);
        value.setEditorSchemaRevision(6);
        PortfolioConfigDto.Component component = new PortfolioConfigDto.Component();
        component.setComponentKey("text"); component.setComponentType(type.getCode());
        component.setEnabled(true); component.setSortOrder(1000); component.setConfig(config);
        value.setComponents(List.of(component));
        return value;
    }

    /** 构造已认识三类文字组件的团队旧版请求。 */
    private TeamPortfolioConfigDto teamConfig(PortfolioComponentTypeDict type, JSONObject config) {
        TeamPortfolioConfigDto value = new TeamPortfolioConfigDto();
        value.setSchemaVersion(TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1);
        value.setEditorSchemaRevision(5);
        TeamPortfolioConfigDto.Share share = new TeamPortfolioConfigDto.Share();
        share.setTitle("团队作品集"); value.setShare(share);
        TeamPortfolioConfigDto.ComponentEnvelope component = new TeamPortfolioConfigDto.ComponentEnvelope();
        component.setComponentKey("text"); component.setComponentType(type.getCode());
        component.setEnabled(true); component.setSortOrder(1000); component.setConfig(config);
        value.setComponents(List.of(component));
        return value;
    }

    /** 展示使用实际个人入口和团队组件策略；网格展示共用同一纯规则。 */
    private JSONObject render(boolean team, PortfolioComponentTypeDict type, JSONObject config) {
        if (team) {
            return switch (type) {
                case TEXT_SECTION -> new TeamTextSectionComponentRenderer(background).render(config, context);
                case STRUCTURED_TEXT_SECTION -> new TeamStructuredTextSectionComponentRenderer(background).render(config, context);
                case TEXT_GRID -> json(PortfolioTextGridConfigNormalizer.normalize(config));
                default -> throw new IllegalArgumentException(type.getCode());
            };
        }
        PortfolioEntity portfolio = new PortfolioEntity(); portfolio.setOwnerId(7L); portfolio.setId(90L);
        JSONObject component = json(personalRenderer.render(portfolio, personalConfig(type, config), true, false, null, null))
                .getJSONArray("components").getJSONObject(0);
        return switch (type) {
            case TEXT_SECTION -> component.getJSONObject("textSection");
            case STRUCTURED_TEXT_SECTION -> component.getJSONObject("structuredTextSection");
            case TEXT_GRID -> component.getJSONObject("textGrid");
            default -> throw new IllegalArgumentException(type.getCode());
        };
    }

    /** 模拟实际持久化及展示的 JSON 往返，避免只验证内存对象。 */
    private JSONObject json(Object source) { return JSON.parseObject(JSON.toJSONString(source)); }
}
