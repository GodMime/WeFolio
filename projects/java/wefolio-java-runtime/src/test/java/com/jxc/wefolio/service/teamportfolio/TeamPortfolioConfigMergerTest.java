package com.jxc.wefolio.service.teamportfolio;

import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.dict.TeamPortfolioComponentTypeDict;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 新旧编辑器配置合并测试 — 覆盖所有兼容合并场景。
 */
class TeamPortfolioConfigMergerTest {

    /** 旧团队编辑器漏传或默认开启组件标题时保留已关闭状态，跨菜单移动也不丢失。 */
    @Test
    void previousVideoEditorPreservesComponentTitleSwitchAcrossMenus() {
        var existing = new TeamPortfolioConfigDto();
        existing.setEditorSchemaRevision(10);
        existing.setComponents(List.of(displayComponent("video", TeamPortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode(),
                JSONObject.of("title", "团队影像", "showComponentTitle", false, "showTitle", true))));
        for (boolean includeDefault : List.of(false, true)) {
            var incoming = new TeamPortfolioConfigDto();
            incoming.setEditorSchemaRevision(9);
            incoming.setComponents(List.of());
            var values = JSONObject.of("title", "新版标题", "showTitle", false);
            if (includeDefault) { values.put("showComponentTitle", true); }
            var moved = displayComponent("video", TeamPortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode(), values);
            incoming.setBottomNav(bottomNav(menu("home", "首页", null), menu("detail", "详情", List.of(moved))));

            var merged = TeamPortfolioConfigMerger.merge(incoming, existing);

            assertThat(merged.getBottomNav().getItems().get(1).getComponents().getFirst().getConfig())
                    .containsEntry("showComponentTitle", false).containsEntry("title", "新版标题")
                    .containsEntry("showTitle", false);
            assertThat(values.get("showComponentTitle")).isEqualTo(includeDefault ? true : null);
            assertThat(existing.getComponents().getFirst().getConfig()).containsEntry("showTitle", true);
        }
    }

    /** 新团队编辑器可以显式开关组件标题，旧编辑器仍可删除或替换已认识的视频轮播。 */
    @Test
    void currentVideoEditorCanChangeComponentTitleSwitchAndLegacyDeletionStillWorks() {
        var existing = new TeamPortfolioConfigDto();
        existing.setEditorSchemaRevision(10);
        existing.setComponents(List.of(displayComponent("video", TeamPortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode(),
                JSONObject.of("title", "团队影像", "showComponentTitle", false))));
        var incoming = new TeamPortfolioConfigDto();
        incoming.setEditorSchemaRevision(10);
        for (boolean enabled : List.of(true, false)) {
            incoming.setComponents(List.of(displayComponent("video", TeamPortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode(),
                    JSONObject.of("title", "团队影像", "showComponentTitle", enabled))));
            assertThat(TeamPortfolioConfigMerger.merge(incoming, existing).getComponents().getFirst().getConfig())
                    .containsEntry("showComponentTitle", enabled).containsEntry("title", "团队影像");
        }
        incoming.setEditorSchemaRevision(9);
        incoming.setComponents(List.of());
        assertThat(TeamPortfolioConfigMerger.merge(incoming, existing).getComponents()).isEmpty();
        incoming.setComponents(List.of(displayComponent("video", TeamPortfolioComponentTypeDict.TEXT_SECTION.getCode(),
                JSONObject.of("content", "新文本"))));
        assertThat(TeamPortfolioConfigMerger.merge(incoming, existing).getComponents().getFirst().getConfig())
                .doesNotContainKey("showComponentTitle");
    }

    /** 旧团队编辑器修改高度与移动分割线不覆盖未知颜色，新版可以主动重设颜色。 */
    @Test
    void legacyDividerEditorPreservesHexColorAndHeightChangesAcrossMenus() {
        var existing = new TeamPortfolioConfigDto();
        existing.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        existing.setComponents(List.of(displayComponent("divider", TeamPortfolioComponentTypeDict.DIVIDER.getCode(),
                JSONObject.of("color", "#12ABCD", "heightPx", 16))));
        for (Integer revision : new Integer[]{null, 2, 6, TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT}) {
            var incoming = new TeamPortfolioConfigDto();
            incoming.setEditorSchemaRevision(revision);
            var edited = displayComponent("divider", TeamPortfolioComponentTypeDict.DIVIDER.getCode(),
                    JSONObject.of("color", "GRAY", "heightPx", 32));
            incoming.setComponents(revision == null ? List.of(edited) : List.of());
            if (revision != null) {
                incoming.setBottomNav(bottomNav(menu("home", "首页", null), menu("detail", "详情", List.of(edited))));
            }
            var merged = TeamPortfolioConfigMerger.merge(incoming, existing);
            String expected = revision != null && revision == TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT ? "GRAY" : "#12ABCD";
            assertThat(TeamPortfolioComponentTraversal.listComponentLocations(merged).getFirst().component().getConfig())
                    .containsEntry("color", expected).containsEntry("heightPx", 32);
            assertThat(edited.getConfig()).containsEntry("color", "GRAY");
        }
    }

    /** 团队旧枚举可照常修改，分割线可删除，同键更换类型不复制颜色。 */
    @Test
    void legacyDividerEditorStillChangesEnumsDeletesAndReplacesComponents() {
        var existing = new TeamPortfolioConfigDto();
        existing.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        existing.setComponents(List.of(displayComponent("divider", TeamPortfolioComponentTypeDict.DIVIDER.getCode(),
                JSONObject.of("color", "TRANSPARENT", "heightPx", 16))));
        var incoming = new TeamPortfolioConfigDto();
        incoming.setEditorSchemaRevision(6);
        incoming.setComponents(List.of(displayComponent("divider", TeamPortfolioComponentTypeDict.DIVIDER.getCode(),
                JSONObject.of("color", "BLACK", "heightPx", 24))));
        assertThat(TeamPortfolioConfigMerger.merge(incoming, existing).getComponents().getFirst().getConfig())
                .containsEntry("color", "BLACK").containsEntry("heightPx", 24);
        existing.getComponents().getFirst().setConfig(JSONObject.of("color", "#F5F6F8", "heightPx", 16));
        incoming.setComponents(List.of());
        assertThat(TeamPortfolioConfigMerger.merge(incoming, existing).getComponents()).isEmpty();
        incoming.setComponents(List.of(displayComponent("divider", TeamPortfolioComponentTypeDict.TEXT_SECTION.getCode(),
                JSONObject.of("content", "替换后的正文"))));
        assertThat(TeamPortfolioConfigMerger.merge(incoming, existing).getComponents().getFirst().getConfig())
                .doesNotContainKey("color");
    }

    /** 旧请求只有已知类型时仍保护新字段，跨菜单移动与音频缺省组合不丢数据。 */
    @Test void protectsNewFieldsBeforeTypeEarlyReturnAcrossMenus() {
        for (Integer revision : new Integer[]{2, 4}) {
            TeamPortfolioConfigDto existing = new TeamPortfolioConfigDto(); existing.setEditorSchemaRevision(5);
            var saved = displayComponent("single", TeamPortfolioComponentTypeDict.SINGLE_WORK.getCode(),
                    JSONObject.of("openMode", "DETAIL_PAGE", "detailOptions", JSONObject.of("showTitle", false, "showDescription", true), "showDescription", true));
            existing.setComponents(List.of(saved));
            TeamPortfolioConfigDto incoming = new TeamPortfolioConfigDto(); incoming.setEditorSchemaRevision(revision);
            var moved = displayComponent("single", TeamPortfolioComponentTypeDict.SINGLE_WORK.getCode(), JSONObject.of("showDescription", false));
            incoming.setComponents(List.of());
            incoming.setBottomNav(bottomNav(menu("home", "首页", null), menu("detail", "详情", List.of(moved))));
            TeamPortfolioConfigDto merged = TeamPortfolioConfigMerger.merge(incoming, existing);
            assertThat(merged.getBottomNav().getItems().get(1).getComponents()).hasSize(1);
            var result = TeamPortfolioComponentTraversal.listComponentLocations(merged).getFirst().component();
            assertThat(result.getConfig()).containsEntry("openMode", "DETAIL_PAGE").containsEntry("showDescription", false);
            assertThat((java.util.Map<?, ?>) result.getConfig().get("detailOptions")).isEqualTo(JSONObject.of("showTitle", false, "showDescription", true));
            assertThat(moved.getConfig()).doesNotContainKey("openMode");
        }
    }

    /** 无版本旧端不认识导航，不能把请求菜单移动当成有效移动；仍在顶层的组件字段必须保护。 */
    @Test void nullRevisionKeepsLegacyNavigationRuleAndProtectsRemainingComponentFields() {
        var existing = new TeamPortfolioConfigDto(); existing.setEditorSchemaRevision(5);
        existing.setComponents(List.of(displayComponent("single", "SINGLE_WORK",
                JSONObject.of("openMode", "DETAIL_PAGE", "detailOptions", JSONObject.of("showTitle", false)))));
        var incoming = new TeamPortfolioConfigDto();
        var moved = displayComponent("single", "SINGLE_WORK", JSONObject.of("showDescription", false));
        incoming.setComponents(List.of());
        incoming.setBottomNav(bottomNav(menu("home", "首页", null), menu("detail", "详情", List.of(moved))));
        var ignoredMove = TeamPortfolioConfigMerger.merge(incoming, existing);
        assertThat(ignoredMove.getBottomNav()).isNull();
        assertThat(ignoredMove.getComponents()).isEmpty();
        incoming.setComponents(List.of(moved));
        var retained = TeamPortfolioConfigMerger.merge(incoming, existing);
        assertThat(retained.getBottomNav()).isNull();
        assertThat(retained.getComponents().getFirst().getConfig()).containsEntry("openMode", "DETAIL_PAGE")
                .containsEntry("showDescription", false).containsEntry("detailOptions", JSONObject.of("showTitle", false));
        assertThat(moved.getConfig()).doesNotContainKey("openMode");
    }

    /** 新客户端显式 false 可修改；旧客户端删除已知组件不恢复，同键不同类型不套字段。 */
    @Test void currentChangesDeletionAndTypeIdentityRemainAuthoritative() {
        TeamPortfolioConfigDto existing = new TeamPortfolioConfigDto(); existing.setEditorSchemaRevision(5);
        existing.setComponents(List.of(displayComponent("video", TeamPortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode(),
                JSONObject.of("displayStyle", "PORTRAIT_CARDS", "showDescription", true))));
        TeamPortfolioConfigDto incoming = new TeamPortfolioConfigDto(); incoming.setEditorSchemaRevision(5);
        incoming.setComponents(List.of(displayComponent("video", TeamPortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode(),
                JSONObject.of("displayStyle", "STACKED", "showDescription", false))));
        assertThat(TeamPortfolioConfigMerger.merge(incoming, existing).getComponents().getFirst().getConfig())
                .containsEntry("displayStyle", "STACKED").containsEntry("showDescription", false);
        incoming.setEditorSchemaRevision(4); incoming.setComponents(List.of());
        assertThat(TeamPortfolioConfigMerger.merge(incoming, existing).getComponents()).isEmpty();
        incoming.setComponents(List.of(displayComponent("video", TeamPortfolioComponentTypeDict.TEXT_SECTION.getCode(), JSONObject.of("content", "新文本"))));
        assertThat(TeamPortfolioConfigMerger.merge(incoming, existing).getComponents().getFirst().getConfig()).doesNotContainKeys("displayStyle", "showDescription");
    }

    /** 旧编辑器无法识别的联系与网格组件按类型原样保护。 */
    @Test void protectsNewComponentTypes() {
        TeamPortfolioConfigDto existing = new TeamPortfolioConfigDto(); existing.setEditorSchemaRevision(5);
        existing.setComponents(List.of(displayComponent("contact", TeamPortfolioComponentTypeDict.CONTACT_INFO.getCode(), JSONObject.of("contactPhone", "123")),
                displayComponent("grid", TeamPortfolioComponentTypeDict.TEXT_GRID.getCode(), JSONObject.of("rows", 2))));
        TeamPortfolioConfigDto incoming = new TeamPortfolioConfigDto(); incoming.setEditorSchemaRevision(4); incoming.setComponents(List.of());
        assertThat(TeamPortfolioConfigMerger.merge(incoming, existing).getComponents()).extracting(TeamPortfolioConfigDto.ComponentEnvelope::getComponentKey).containsExactly("contact", "grid");
    }


    /** 已认识网格的上一版编辑器跨菜单移动时，开关可修改而新边框字段受保护。 */
    @Test void previousGridEditorPreservesBorderOptionsAcrossMenus() {
        var existing = new TeamPortfolioConfigDto(); existing.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        var saved = displayComponent("grid", TeamPortfolioComponentTypeDict.TEXT_GRID.getCode(),
                JSONObject.of("cellBorder", true, "cellBorderWidthRpx", 8, "cellBorderColor", "#112233"));
        existing.setComponents(List.of(saved));
        for (boolean explicitUnsupportedOptions : List.of(false, true)) {
            var incoming = new TeamPortfolioConfigDto(); incoming.setEditorSchemaRevision(5); incoming.setComponents(List.of());
            var values = JSONObject.of("cellBorder", false);
            if (explicitUnsupportedOptions) { values.put("cellBorderWidthRpx", 1); values.put("cellBorderColor", "AUTO"); }
            var moved = displayComponent("grid", TeamPortfolioComponentTypeDict.TEXT_GRID.getCode(), values);
            incoming.setBottomNav(bottomNav(menu("home", "首页", null), menu("detail", "详情", List.of(moved))));
            var merged = TeamPortfolioConfigMerger.merge(incoming, existing);
            assertThat(merged.getComponents()).isEmpty();
            assertThat(merged.getBottomNav().getItems().get(1).getComponents()).hasSize(1);
            assertThat(TeamPortfolioComponentTraversal.listComponentLocations(merged).getFirst().component().getConfig())
                    .containsEntry("cellBorder", false).containsEntry("cellBorderWidthRpx", 8)
                    .containsEntry("cellBorderColor", "#112233");
            assertThat(moved.getConfig()).doesNotContainEntry("cellBorderWidthRpx", 8);
        }
    }

    /** 当前编辑器可重设宽度颜色，旧编辑器仍可删除其认识的网格。 */
    @Test void currentGridEditorCanChangeBorderAndPreviousEditorCanDeleteGrid() {
        var existing = new TeamPortfolioConfigDto(); existing.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        existing.setComponents(List.of(displayComponent("grid", TeamPortfolioComponentTypeDict.TEXT_GRID.getCode(),
                JSONObject.of("cellBorderWidthRpx", 8, "cellBorderColor", "#112233"))));
        var incoming = new TeamPortfolioConfigDto(); incoming.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        incoming.setComponents(List.of(displayComponent("grid", TeamPortfolioComponentTypeDict.TEXT_GRID.getCode(),
                JSONObject.of("cellBorderWidthRpx", 2, "cellBorderColor", "#FFFFFF"))));
        assertThat(TeamPortfolioConfigMerger.merge(incoming, existing).getComponents().getFirst().getConfig())
                .containsEntry("cellBorderWidthRpx", 2).containsEntry("cellBorderColor", "#FFFFFF");
        incoming.setEditorSchemaRevision(5); incoming.setComponents(List.of());
        assertThat(TeamPortfolioConfigMerger.merge(incoming, existing).getComponents()).isEmpty();
    }

    /** 无版本编辑器仍按未知组件保护整格配置，边框字段不会被缺省或移动请求抹掉。 */
    @Test void nullRevisionRetainsGridAndBorderAtOriginalLocation() {
        var existing = new TeamPortfolioConfigDto(); existing.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        existing.setComponents(List.of(displayComponent("grid", TeamPortfolioComponentTypeDict.TEXT_GRID.getCode(),
                JSONObject.of("cellBorder", true, "cellBorderWidthRpx", 8, "cellBorderColor", "#112233"))));
        var incoming = new TeamPortfolioConfigDto(); incoming.setComponents(List.of());
        incoming.setBottomNav(bottomNav(menu("home", "首页", null), menu("detail", "详情", List.of(
                displayComponent("grid", TeamPortfolioComponentTypeDict.TEXT_GRID.getCode(), JSONObject.of("cellBorder", false))))));
        var merged = TeamPortfolioConfigMerger.merge(incoming, existing);
        assertThat(TeamPortfolioComponentTraversal.listComponentLocations(merged)).hasSize(1);
        assertThat(merged.getComponents().getFirst().getConfig()).containsEntry("cellBorder", true)
                .containsEntry("cellBorderWidthRpx", 8).containsEntry("cellBorderColor", "#112233");
    }

    /** 当前版显式空值在根组件和菜单组件均交给后续校验，旧字段仍保留原序列化空值语义。 */
    @Test void preservesOnlyNewGridBorderNullsAcrossRootAndMenuCopies() {
        var values = new JSONObject();
        values.put("cellBorderWidthRpx", null); values.put("cellBorderColor", null); values.put("cellPaddingRpx", null);
        var incoming = new TeamPortfolioConfigDto();
        incoming.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        incoming.setComponents(List.of(displayComponent("root_grid", TeamPortfolioComponentTypeDict.TEXT_GRID.getCode(), values)));
        incoming.setBottomNav(bottomNav(menu("home", "首页", null), menu("detail", "详情", List.of(
                displayComponent("menu_grid", TeamPortfolioComponentTypeDict.TEXT_GRID.getCode(), values)))));
        var existing = new TeamPortfolioConfigDto();
        existing.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        for (TeamPortfolioConfigDto previous : new TeamPortfolioConfigDto[]{null, existing}) {
            var merged = TeamPortfolioConfigMerger.merge(incoming, previous);
            assertThat(TeamPortfolioComponentTraversal.listComponentLocations(merged)).hasSize(2).allSatisfy(location ->
                    assertThat(location.component().getConfig()).containsEntry("cellBorderWidthRpx", null)
                            .containsEntry("cellBorderColor", null).doesNotContainKey("cellPaddingRpx"));
        }
    }

    /** 创建测试信封。 */
    private TeamPortfolioConfigDto.ComponentEnvelope displayComponent(String key, String type, JSONObject values) {
        var component = new TeamPortfolioConfigDto.ComponentEnvelope(); component.setComponentKey(key); component.setComponentType(type);
        component.setEnabled(true); component.setSortOrder(1000); component.setConfig(values); return component;
    }

    /**
     * 新编辑器首次创建，无已有草稿，以请求字段为准。
     */
    @Test
    void mergeShouldUseIncomingFieldsWhenNoExistingDraft() {
        TeamPortfolioConfigDto incoming = configWithRevision(
                2, "#151515", bottomNav(
                        menu("nav_home", "主页", null),
                        menu("nav_works", "作品", List.of())));
        incoming.setComponents(List.of(component("home")));

        TeamPortfolioConfigDto merged = TeamPortfolioConfigMerger.merge(incoming, null);

        assertThat(merged.getEditorSchemaRevision()).isEqualTo(2);
        assertThat(merged.getStyle().getBackgroundColor()).isEqualTo("#151515");
        assertThat(merged.getBottomNav().getItems()).hasSize(2);
        assertThat(merged.getComponents()).extracting(
                TeamPortfolioConfigDto.ComponentEnvelope::getComponentKey).containsExactly("home");
    }

    /**
     * 新编辑器再次保存，已有新字段草稿，以请求为准覆盖。
     */
    @Test
    void mergeShouldUseIncomingWhenBothHaveCurrentRevision() {
        TeamPortfolioConfigDto incoming = configWithRevision(
                2, "#F5F6F8", bottomNav(
                        menu("nav_home", "主页", null),
                        menu("nav_works", "作品", List.of(component("new")))));
        TeamPortfolioConfigDto existing = configWithRevision(
                2, "#151515", bottomNav(
                        menu("nav_home", "主页", null),
                        menu("nav_works", "作品", List.of(component("old")))));

        TeamPortfolioConfigDto merged = TeamPortfolioConfigMerger.merge(incoming, existing);

        assertThat(merged.getEditorSchemaRevision()).isEqualTo(2);
        assertThat(merged.getStyle().getBackgroundColor()).isEqualTo("#F5F6F8");
        assertThat(merged.getBottomNav().getItems().get(1).getComponents()).extracting(
                TeamPortfolioConfigDto.ComponentEnvelope::getComponentKey).containsExactly("new");
    }

    /**
     * 旧编辑器保存时 revision 为 null，必须保留已有草稿的 style 和 bottomNav。
     */
    @Test
    void mergeShouldPreserveExistingStyleAndNavWhenIncomingIsLegacy() {
        TeamPortfolioConfigDto incoming = new TeamPortfolioConfigDto();
        incoming.setComponents(List.of(component("updated-home")));
        TeamPortfolioConfigDto existing = configWithRevision(
                2, "#151515", bottomNav(
                        menu("nav_home", "主页", null),
                        menu("nav_works", "作品", List.of(component("secondary")))));

        TeamPortfolioConfigDto merged = TeamPortfolioConfigMerger.merge(incoming, existing);

        assertThat(merged.getEditorSchemaRevision()).isEqualTo(2);
        assertThat(merged.getStyle().getBackgroundColor()).isEqualTo("#151515");
        assertThat(merged.getBottomNav().getItems()).hasSize(2);
        // 顶层组件应为旧编辑器的更新值
        assertThat(merged.getComponents()).extracting(
                TeamPortfolioConfigDto.ComponentEnvelope::getComponentKey)
                .containsExactly("updated-home");
        // 次级菜单组件保持不动
        assertThat(merged.getBottomNav().getItems().get(1).getComponents()).extracting(
                TeamPortfolioConfigDto.ComponentEnvelope::getComponentKey)
                .containsExactly("secondary");
    }

    /**
     * 旧编辑器显式发送 revision 1，语义与 null 相同，已有 revision 2 草稿不应降级。
     */
    @Test
    void mergeShouldNotDowngradeExistingRevisionWhenIncomingIsVersionOne() {
        TeamPortfolioConfigDto incoming = new TeamPortfolioConfigDto();
        incoming.setEditorSchemaRevision(1);
        incoming.setComponents(List.of(component("home")));
        TeamPortfolioConfigDto existing = configWithRevision(
                2, "#151515", bottomNav(
                        menu("nav_home", "主页", null),
                        menu("nav_works", "作品", List.of())));

        TeamPortfolioConfigDto merged = TeamPortfolioConfigMerger.merge(incoming, existing);

        assertThat(merged.getEditorSchemaRevision()).isEqualTo(2);
        assertThat(merged.getStyle().getBackgroundColor()).isEqualTo("#151515");
        assertThat(merged.getBottomNav().getItems()).hasSize(2);
    }

    /**
     * 旧编辑器保存尚无新字段的旧草稿，不凭空启用导航。
     */
    @Test
    void mergeShouldNotAddStyleOrNavWhenExistingHasNoCurrentFields() {
        TeamPortfolioConfigDto incoming = new TeamPortfolioConfigDto();
        incoming.setComponents(List.of(component("home")));
        TeamPortfolioConfigDto existing = new TeamPortfolioConfigDto();
        existing.setComponents(List.of(component("old-home")));

        TeamPortfolioConfigDto merged = TeamPortfolioConfigMerger.merge(incoming, existing);

        assertThat(merged.getEditorSchemaRevision()).isNull();
        assertThat(merged.getStyle()).isNull();
        assertThat(merged.getBottomNav()).isNull();
        assertThat(merged.getComponents()).extracting(
                TeamPortfolioConfigDto.ComponentEnvelope::getComponentKey).containsExactly("home");
    }

    /**
     * 新编辑器保存时 revision 为 0，小于当前值，按旧编辑器处理。
     */
    @Test
    void mergeShouldTreatRevisionsBelowCurrentAsLegacy() {
        TeamPortfolioConfigDto incoming = new TeamPortfolioConfigDto();
        incoming.setEditorSchemaRevision(0);
        incoming.setComponents(List.of(component("legacy-home")));
        incoming.setStyle(style("#FFFFFF"));
        incoming.setBottomNav(bottomNav(menu("nav_home", "主页", null)));
        TeamPortfolioConfigDto existing = configWithRevision(
                2, "#151515", bottomNav(
                        menu("nav_home", "主页", null),
                        menu("nav_works", "作品", List.of())));

        TeamPortfolioConfigDto merged = TeamPortfolioConfigMerger.merge(incoming, existing);

        assertThat(merged.getEditorSchemaRevision()).isEqualTo(2);
        assertThat(merged.getStyle().getBackgroundColor()).isEqualTo("#151515");
        assertThat(merged.getBottomNav().getItems()).hasSize(2);
        assertThat(merged.getComponents()).extracting(
                TeamPortfolioConfigDto.ComponentEnvelope::getComponentKey)
                .containsExactly("legacy-home");
    }

    /**
     * 传入超过当前版本的 revision 不应由合并器拦截，由调用方校验。
     */
    @Test
    void mergeShouldPassThroughUnsupportedRevision() {
        TeamPortfolioConfigDto incoming = new TeamPortfolioConfigDto();
        incoming.setEditorSchemaRevision(99);
        incoming.setComponents(List.of(component("future")));
        incoming.setStyle(style("#FF0000"));
        incoming.setBottomNav(bottomNav(menu("nav_home", "主页", null), menu("nav_future", "未来", List.of())));

        TeamPortfolioConfigDto merged = TeamPortfolioConfigMerger.merge(incoming, null);

        assertThat(merged.getEditorSchemaRevision()).isEqualTo(99);
        assertThat(merged.getStyle().getBackgroundColor()).isEqualTo("#FF0000");
        assertThat(merged.getBottomNav().getItems()).hasSize(2);
    }

    @Test
    void mergeShouldProtectRevisionThreeVideoCarouselWithAnchorOrdering() {
        TeamPortfolioConfigDto existing = configWithRevision(
                3, "#151515", bottomNav(menu("nav_home", "主页", null)));
        existing.setComponents(List.of(
                component("A"),
                component("VC", TeamPortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode(),
                        com.alibaba.fastjson2.JSONObject.of("title", "数据库视频")),
                component("B"),
                component("C")));
        TeamPortfolioConfigDto incoming = configWithRevision(
                2, "#F5F6F8", bottomNav(menu("nav_home", "主页", null)));
        incoming.setComponents(List.of(component("A"), component("C"), component("B")));

        TeamPortfolioConfigDto merged = TeamPortfolioConfigMerger.merge(incoming, existing);

        assertThat(merged.getEditorSchemaRevision()).isEqualTo(3);
        assertThat(merged.getStyle().getBackgroundColor()).isEqualTo("#F5F6F8");
        assertThat(merged.getComponents())
                .extracting(TeamPortfolioConfigDto.ComponentEnvelope::getComponentKey)
                .containsExactly("A", "VC", "C", "B");
        assertThat(merged.getComponents())
                .extracting(TeamPortfolioConfigDto.ComponentEnvelope::getSortOrder)
                .containsExactly(1000, 2000, 3000, 4000);
    }

    @Test
    void mergeShouldProtectVideoCarouselInsideMatchingMenuAndRejectPayloadMutation() {
        TeamPortfolioConfigDto existing = configWithRevision(
                3, "#151515", bottomNav(
                        menu("nav_home", "主页", null),
                        menu("nav_works", "作品", List.of(
                                component("A"),
                                component("VC", TeamPortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode(),
                                        com.alibaba.fastjson2.JSONObject.of("title", "数据库视频")),
                                component("B")))));
        existing.setComponents(List.of(component("HOME")));
        TeamPortfolioConfigDto incoming = configWithRevision(
                2, "#F5F6F8", bottomNav(
                        menu("nav_home", "主页", null),
                        menu("nav_works", "作品", List.of(
                                component("B"),
                                component("VC", TeamPortfolioComponentTypeDict.TEXT_SECTION.getCode(),
                                        com.alibaba.fastjson2.JSONObject.of("content", "篡改")),
                                component("A")))));
        incoming.setComponents(List.of(component("HOME")));

        TeamPortfolioConfigDto merged = TeamPortfolioConfigMerger.merge(incoming, existing);

        assertThat(merged.getBottomNav().getItems().get(1).getComponents())
                .extracting(TeamPortfolioConfigDto.ComponentEnvelope::getComponentKey)
                .containsExactly("VC", "B", "A");
        assertThat(merged.getBottomNav().getItems().get(1).getComponents().getFirst().getComponentType())
                .isEqualTo(TeamPortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode());
        assertThat(merged.getBottomNav().getItems().get(1).getComponents().getFirst().getConfig())
                .containsEntry("title", "数据库视频");
    }

    // ==================== 辅助方法 ====================

    private TeamPortfolioConfigDto configWithRevision(int revision, String bgColor,
                                                       TeamPortfolioConfigDto.BottomNav bottomNav) {
        TeamPortfolioConfigDto config = new TeamPortfolioConfigDto();
        config.setEditorSchemaRevision(revision);
        config.setStyle(style(bgColor));
        config.setBottomNav(bottomNav);
        return config;
    }

    private TeamPortfolioConfigDto.Style style(String backgroundColor) {
        TeamPortfolioConfigDto.Style style = new TeamPortfolioConfigDto.Style();
        style.setBackgroundColor(backgroundColor);
        return style;
    }

    private TeamPortfolioConfigDto.BottomNav bottomNav(
            TeamPortfolioConfigDto.BottomNavItem... items) {
        TeamPortfolioConfigDto.BottomNav bottomNav = new TeamPortfolioConfigDto.BottomNav();
        bottomNav.setEnabled(true);
        bottomNav.setItems(
                java.util.Arrays.stream(items).toList());
        return bottomNav;
    }

    private TeamPortfolioConfigDto.BottomNavItem menu(String key, String title,
                                                       List<TeamPortfolioConfigDto.ComponentEnvelope> components) {
        TeamPortfolioConfigDto.BottomNavItem item = new TeamPortfolioConfigDto.BottomNavItem();
        item.setKey(key);
        item.setTitle(title);
        item.setComponents(components);
        return item;
    }

    private TeamPortfolioConfigDto.ComponentEnvelope component(String key) {
        return component(key, TeamPortfolioComponentTypeDict.DIVIDER.getCode(),
                new com.alibaba.fastjson2.JSONObject());
    }

    private TeamPortfolioConfigDto.ComponentEnvelope component(
            String key,
            String type,
            com.alibaba.fastjson2.JSONObject config
    ) {
        TeamPortfolioConfigDto.ComponentEnvelope component =
                new TeamPortfolioConfigDto.ComponentEnvelope();
        component.setComponentKey(key);
        component.setComponentType(type);
        component.setEnabled(true);
        component.setSortOrder(1000);
        component.setConfig(config);
        return component;
    }
}
