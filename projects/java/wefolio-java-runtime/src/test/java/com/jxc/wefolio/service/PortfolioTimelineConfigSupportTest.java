package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.dto.PortfolioClientCapabilities;
import com.jxc.wefolio.dto.MinePortfolioDraftSaveRequest;
import com.alibaba.fastjson2.JSONWriter;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.service.portfoliofont.PortfolioFontConfigSupport;
import com.jxc.wefolio.service.portfoliofont.PortfolioFontPlan;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioConfigValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 时间轴跨语言边界、真实规范化、导航保护与字体计划回归。 */
class PortfolioTimelineConfigSupportTest {
    /** 跨语言同一夹具证明码点及日期行为，而不只比较常量。 */
    @Test void followsCrossLanguageContract() throws Exception {
        var fixture = JSON.parseObject(Files.readString(Path.of("../../../tests/fixtures/portfolio-timeline-contract.json")));
        assertThat(fixture.getJSONObject("limits")).containsEntry("MAX_NODES", PortfolioTimelineConfigSupport.MAX_NODES)
                .containsEntry("MAX_CUSTOM_LABEL_CODE_POINTS", PortfolioTimelineConfigSupport.MAX_CUSTOM_LABEL_CODE_POINTS)
                .containsEntry("MAX_TOTAL_TEXT_CODE_POINTS", PortfolioTimelineConfigSupport.MAX_TOTAL_TEXT_CODE_POINTS);
        for (var item : fixture.getJSONArray("cases")) {
            var test = (JSONObject) item;
            if (test.getBooleanValue("valid")) {
                var normalized = PortfolioTimelineConfigSupport.normalize(test.getJSONObject("config"));
                assertThat(PortfolioTimelineConfigSupport.normalize(normalized)).as(test.getString("name")).isEqualTo(normalized);
            } else {
                assertThatThrownBy(() -> PortfolioTimelineConfigSupport.normalize(test.getJSONObject("config")))
                        .as(test.getString("name")).isInstanceOf(BusinessException.class);
            }
        }
    }

    /** 自定义标记与三种日期混排，原文、顺序和现有字段在 JSON 往返后均保持。 */
    @Test void preservesMixedNodeLabelsAcrossNormalizationAndSerialization() throws Exception {
        String customLabel = " 筹备期 - v1.0  ";
        var source = JSONObject.of("nodes", List.of(
                JSONObject.of("nodeKey", "year", "datePrecision", PortfolioTimelineConfigSupport.YEAR, "date", "2018", "title", "起步"),
                JSONObject.of("nodeKey", "custom", "datePrecision", PortfolioTimelineConfigSupport.CUSTOM, "date", customLabel, "title", "准备"),
                JSONObject.of("nodeKey", "month", "datePrecision", PortfolioTimelineConfigSupport.MONTH, "date", "2021-05", "title", "合作"),
                JSONObject.of("nodeKey", "day", "datePrecision", PortfolioTimelineConfigSupport.DAY, "date", "2024-05-20", "title", "纪念")));
        String original = JSON.toJSONString(source);
        var normalized = PortfolioTimelineConfigSupport.normalize(source);
        var serialized = JSON.parseObject(JSON.toJSONString(normalized));
        assertThat(serialized.getJSONArray("nodes").toJavaList(JSONObject.class))
                .extracting(node -> node.getString("date")).containsExactly("2018", customLabel, "2021-05", "2024-05-20");
        assertThat(serialized.getJSONArray("nodes").getJSONObject(1)).doesNotContainKey("customLabel");
        assertThat(PortfolioTimelineConfigSupport.normalize(serialized)).isEqualTo(normalized);
        PortfolioTimelineConfigSupport.validate(new ObjectMapper().readTree(serialized.toJSONString()));
        assertThat(JSON.toJSONString(source)).isEqualTo(original);
        assertThat(PortfolioTimelineConfigSupport.displayDate(PortfolioTimelineConfigSupport.CUSTOM, customLabel)).isEqualTo(customLabel);
        assertThat(PortfolioTimelineConfigSupport.displayDate(PortfolioTimelineConfigSupport.DAY, "2024-05-20")).isEqualTo("2024.05.20");
        assertThat(PortfolioTimelineConfigSupport.displayDate("2021-05")).isEqualTo("2021.05");
    }

    /** 个人与团队保存发布均允许自定义；不声明时间轴能力的旧请求仍保留已保存组件。 */
    @Test void savesPublishesAndProtectsCustomNodesForBothOwners() {
        String customLabel = " 至今 - v1.0 ";
        for (boolean team : List.of(false, true)) {
            var component = timeline("custom-journey");
            var customNode = component.getJSONObject("config").getJSONArray("nodes").getJSONObject(0);
            customNode.put("datePrecision", PortfolioTimelineConfigSupport.CUSTOM);
            customNode.put("date", customLabel);
            var incoming = page(team);
            incoming.put("components", List.of(component));
            var saved = normalize(team, incoming, null, true);
            assertThat(saved.getJSONArray("components").getJSONObject(0).getJSONObject("config")
                    .getJSONArray("nodes").getJSONObject(0).getString("date")).isEqualTo(customLabel);
            if (team) {
                assertThat(teamValidator().validateForPublish(saved.to(TeamPortfolioConfigDto.class), context()).getComponents()).hasSize(1);
            } else {
                new PortfolioConfigValidator(null).validateForPublish(7L, saved.to(PortfolioConfigDto.class));
            }
            var oldClientSaved = normalize(team, page(team), saved, false);
            var protectedTimeline = oldClientSaved.getJSONArray("components").toJavaList(JSONObject.class).stream()
                    .filter(item -> "custom-journey".equals(item.getString("componentKey"))).findFirst().orElseThrow();
            assertThat(protectedTimeline.getJSONObject("config").getJSONArray("nodes").getJSONObject(0))
                    .containsEntry("datePrecision", PortfolioTimelineConfigSupport.CUSTOM).containsEntry("date", customLabel);
        }
    }

    /** 自定义标记复用日期文字角色，按原文收集字形，不能把横杠改成日期分隔点。 */
    @Test void collectsCustomLabelGlyphsWithoutDateFormatting() {
        String customLabel = " 筹备 - 未来 ";
        for (boolean team : List.of(false, true)) {
            var config = page(team);
            var component = timeline("custom-fonts");
            var timeline = component.getJSONObject("config");
            timeline.put("textStyles", JSONObject.of("date", JSONObject.of("fontId", "ALLURA")));
            var customNode = timeline.getJSONArray("nodes").getJSONObject(0);
            customNode.put("datePrecision", PortfolioTimelineConfigSupport.CUSTOM);
            customNode.put("date", customLabel);
            component.put("config", PortfolioTimelineConfigSupport.normalize(timeline));
            config.put("components", List.of(component));
            config.put("fonts", JSONObject.of("ALLURA", JSONObject.of("fontVersion", "v1")));
            var normalized = component.getJSONObject("config");
            assertThat(PortfolioTimelineConfigSupport.fontDemandNodes(normalized)).hasSize(1)
                    .first().satisfies(demand -> assertThat(demand).containsEntry("content", customLabel));
            var plan = team ? PortfolioFontPlan.from(config.to(TeamPortfolioConfigDto.class)) : PortfolioFontPlan.from(config.to(PortfolioConfigDto.class));
            assertThat(plan.groups().stream().flatMap(group -> group.codepoints().stream()).toList())
                    .contains((int) '-', (int) '筹', (int) '未').doesNotContain((int) '.');
        }
    }

    /** 原始 HTTP 能力禁止隐式数值转换，历史缺省不产生新指纹字段。 */
    @Test void parsesTimelineCapabilityStrictly() throws Exception {
        for (String value : List.of("null", "\"1\"", "true", "1.0", "2", "-1")) {
            String raw = "{\"portfolioTimeline\":" + value + "}";
            assertThatThrownBy(() -> JSON.parseObject(raw, PortfolioClientCapabilities.class)).as(raw).isInstanceOf(Exception.class);
            assertThatThrownBy(() -> new ObjectMapper().readValue(raw, PortfolioClientCapabilities.class)).as(raw).isInstanceOf(Exception.class);
        }
        var missing = JSON.parseObject("{\"portfolioRemoteFont\":1}", PortfolioClientCapabilities.class);
        assertThat(missing.hasTimelineIntent()).isFalse();
        assertThat(JSON.toJSONString(missing)).isEqualTo("{\"portfolioRemoteFont\":1}");
        for (int flag : List.of(0, 1)) {
            var caps = JSON.parseObject("{\"portfolioTimeline\":" + flag + "}", PortfolioClientCapabilities.class);
            assertThat(caps.hasTimelineIntent()).isTrue();
            assertThat(caps.supportsTimeline()).isEqualTo(flag == 1);
        }
        assertThatThrownBy(() -> PortfolioEditingCapabilities.standard(14, capable(), false)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> PortfolioEditingCapabilities.advanced(10, 2, true)).isInstanceOf(BusinessException.class);
    }

    /** 新增能力字段不能污染旧字体请求的 null 保留指纹。 */
    @Test void keepsLegacyFontFingerprintWithoutTimelineDeclaration() {
        var request = new MinePortfolioDraftSaveRequest();
        request.setConfig(page(false).to(PortfolioConfigDto.class));
        var caps = new PortfolioClientCapabilities(); caps.setPortfolioRemoteFont(1); request.setClientCapabilities(caps);
        var historical = JSON.parseObject(JSON.toJSONString(request, JSONWriter.Feature.WriteMapNullValue, JSONWriter.Feature.MapSortField));
        historical.getJSONObject("clientCapabilities").remove("portfolioTimeline");
        String expected = "portfolio-remote-font-v1:false:" + JSON.toJSONString(historical, JSONWriter.Feature.WriteMapNullValue, JSONWriter.Feature.MapSortField);
        assertThat(PortfolioFontConfigSupport.fingerprint(request)).isEqualTo(expected).doesNotContain("portfolioTimeline");
        caps.setPortfolioTimeline(0); String explicitZero = PortfolioFontConfigSupport.fingerprint(request);
        caps.setPortfolioTimeline(1);
        assertThat(PortfolioFontConfigSupport.fingerprint(request)).isNotEqualTo(explicitZero).contains("portfolioTimeline");
    }

    /** 两套真实校验器均按能力保护旧端关闭导航，明确声明的新端可删除。 */
    @Test void protectsSecondaryTimelineWhenOldClientDisablesNavigation() {
        for (boolean team : List.of(false, true)) {
            var saved = page(team);
            saved.put("bottomNav", JSONObject.of("enabled", true, "items", List.of(
                    JSONObject.of("key", "nav_home", "title", "首页"),
                    JSONObject.of("key", "nav_story", "title", "历程", "components", List.of(timeline("journey"), contact("deleted"))))));
            var incoming = page(team);
            incoming.put("bottomNav", JSONObject.of("enabled", false));
            var result = normalize(team, incoming, saved, false);
            assertThat(result.getJSONObject("bottomNav").getBooleanValue("enabled")).isTrue();
            assertThat(result.getJSONObject("bottomNav").getJSONArray("items")).hasSize(2);
            var children = result.getJSONObject("bottomNav").getJSONArray("items").getJSONObject(1).getJSONArray("components");
            assertThat(children).hasSize(1);
            assertThat(children.getJSONObject(0).getString("componentKey")).isEqualTo("journey");
            var capableResult = normalize(team, incoming, saved, true);
            assertThat(capableResult.getJSONObject("bottomNav").getBooleanValue("enabled")).isFalse();
            assertThat(saved.getJSONObject("bottomNav").getJSONArray("items").getJSONObject(1).getJSONArray("components")).hasSize(2);
        }
    }

    /** 首页被删并提升下一页时，受保护内容仍属于原菜单，不串到新首页。 */
    @Test void restoresProtectedHomeByMenuIdentityAfterPromotion() {
        for (boolean team : List.of(false, true)) {
            var saved = page(team);
            saved.put("components", List.of(timeline("journey"), contact("deleted")));
            saved.put("bottomNav", JSONObject.of("enabled", true, "items", List.of(
                    JSONObject.of("key", "nav_home", "title", "首页"),
                    JSONObject.of("key", "nav_other", "title", "其它", "components", List.of(contact("other-contact"))))));
            var incoming = page(team);
            incoming.put("components", List.of(contact("other-contact")));
            incoming.put("bottomNav", JSONObject.of("enabled", true, "items", List.of(
                    JSONObject.of("key", "nav_other", "title", "其它"))));
            var result = normalize(team, incoming, saved, false);
            assertThat(result.getJSONObject("bottomNav").getJSONArray("items").getJSONObject(0).getString("key")).isEqualTo("nav_home");
            assertThat(result.getJSONArray("components")).hasSize(1);
            assertThat(result.getJSONArray("components").getJSONObject(0).getString("componentKey")).isEqualTo("journey");
            assertThat(result.getJSONObject("bottomNav").getJSONArray("items").getJSONObject(1).getJSONArray("components").getJSONObject(0).getString("componentKey")).isEqualTo("other-contact");
        }
    }

    /** 新增时间轴必须同时满足能力与 revision；发布重验使用持久配置模式。 */
    @Test void rejectsUndeclaredTimelineAndRevalidatesSavedPublication() {
        for (boolean team : List.of(false, true)) {
            var incoming = page(team); incoming.put("components", List.of(timeline("journey")));
            assertThatThrownBy(() -> normalize(team, incoming, null, false)).isInstanceOf(BusinessException.class);
            var saved = normalize(team, incoming, null, true);
            if (team) {
                assertThat(teamValidator().validateForPublish(saved.to(TeamPortfolioConfigDto.class), context()).getComponents()).hasSize(1);
            } else {
                new PortfolioConfigValidator(null).validateForPublish(7L, saved.to(PortfolioConfigDto.class));
            }
        }
    }

    /** 统一远程字体原始引用保留，实际字形只收集继承后的可见片段。 */
    @Test void separatesRawFontReferencesFromInheritedGlyphDemands() {
        var config = page(false);
        var component = timeline("journey");
        var timeline = component.getJSONObject("config");
        timeline.put("title", "标题");
        timeline.put("textStyles", JSONObject.of("heading", JSONObject.of("fontId", "ALLURA"),
                "date", JSONObject.of("fontId", "ALLURA"), "title", JSONObject.of("fontId", "ALLURA"),
                "description", JSONObject.of("fontId", "ALLURA")));
        timeline.put("nodes", List.of(JSONObject.of("nodeKey", "event", "datePrecision", "DAY", "date", "2024-05-20",
                "title", "不入字形", "description", "继承字形😀", "textStyles", JSONObject.of(
                        "title", JSONObject.of("fontFamily", "SYSTEM"), "description", JSONObject.of("color", "#112233")))));
        component.put("config", PortfolioTimelineConfigSupport.normalize(timeline));
        config.put("components", List.of(component));
        config.put("fonts", JSONObject.of("ALLURA", JSONObject.of("fontVersion", "v1")));
        var dto = config.to(PortfolioConfigDto.class);
        var plan = PortfolioFontPlan.from(dto);
        var all = plan.groups().stream().flatMap(group -> group.codepoints().stream()).toList();
        assertThat(all).contains((int) '.', 128512).doesNotContain((int) '-', (int) '不');
        assertThat(PortfolioFontConfigSupport.nodes(dto)).hasSize(6);
        assertThat(PortfolioFontConfigSupport.demandNodes(dto)).hasSize(3);
        var same = config.to(PortfolioConfigDto.class);
        PortfolioFontConfigSupport.merge(same, same, dto, true);
        assertThat(PortfolioFontPlan.from(same)).isEqualTo(plan);
        var colorOnly = JSON.parseObject(JSON.toJSONString(config));
        colorOnly.getJSONArray("components").getJSONObject(0).getJSONObject("config").getJSONObject("textStyles").getJSONObject("date").put("color", "#FFFFFF");
        assertThat(PortfolioFontPlan.from(colorOnly.to(PortfolioConfigDto.class)).hash()).isEqualTo(plan.hash());
        colorOnly.getJSONArray("components").getJSONObject(0).getJSONObject("config").put("variant", "DATE_COLUMN");
        assertThat(PortfolioFontPlan.from(colorOnly.to(PortfolioConfigDto.class)).hash()).isNotEqualTo(plan.hash());
        var reset = JSON.parseObject(JSON.toJSONString(config));
        reset.getJSONArray("components").getJSONObject(0).getJSONObject("config").getJSONObject("textStyles").put("description", new JSONObject());
        var resetDto = reset.to(PortfolioConfigDto.class);
        PortfolioFontConfigSupport.merge(resetDto, resetDto, dto, true);
        assertThat(PortfolioFontPlan.from(resetDto).groups().stream().flatMap(group -> group.codepoints().stream()).toList()).doesNotContain(128512);
        assertThat(reset.getJSONArray("components").getJSONObject(0).getJSONObject("config").getJSONObject("textStyles").getJSONObject("description")).isEmpty();
    }

    /** 真实归属校验器的统一测试入口。 */
    private JSONObject normalize(boolean team, JSONObject input, JSONObject saved, boolean timeline) {
        var capabilities = PortfolioEditingCapabilities.standard(team ? 11 : 15, timeline ? capable() : null, team);
        Object normalized = team
                ? teamValidator().normalizeForDraft(input.to(TeamPortfolioConfigDto.class), saved == null ? null : saved.to(TeamPortfolioConfigDto.class), context(), capabilities)
                : new PortfolioConfigValidator(null).normalizeForDraft(7L, input.to(PortfolioConfigDto.class), saved == null ? null : saved.to(PortfolioConfigDto.class), capabilities);
        return JSON.parseObject(JSON.toJSONString(normalized));
    }
    /** 测试只有纯规则组件，不访问团队远端依赖。 */
    private TeamPortfolioConfigValidator teamValidator() { return new TeamPortfolioConfigValidator(null, null, null, null, null, null, null, null, null, null, null, null); }
    /** 合法团队维护上下文。 */
    private TeamPortfolioComponentContext context() { return new TeamPortfolioComponentContext(11L, 22L, 1); }
    /** 显式新版标准能力。 */
    private PortfolioClientCapabilities capable() { var caps = new PortfolioClientCapabilities(); caps.setPortfolioTimeline(1); return caps; }
    /** 普通根配置保持原有 schema 与字段。 */
    private JSONObject page(boolean team) { return JSONObject.of("schemaVersion", team ? "standard-team-v1" : "standard-personal-v1", "editorSchemaRevision", team ? 11 : 15,
            "share", JSONObject.of("title", "作品集"), "components", List.of(contact("home-contact"))); }
    /** 不依赖外部资源的已知组件。 */
    private JSONObject contact(String key) { return JSONObject.of("componentKey", key, "componentType", "CONTACT_INFO", "sortOrder", 1000, "enabled", true, "config", JSONObject.of("contactWechat", "映期")); }
    /** 最小可保存时间轴。 */
    private JSONObject timeline(String key) { return JSONObject.of("componentKey", key, "componentType", "TIMELINE", "sortOrder", 2000, "enabled", true,
            "config", JSONObject.of("nodes", List.of(JSONObject.of("nodeKey", "event", "datePrecision", "YEAR", "date", "2018", "title", "开始")))); }
}
