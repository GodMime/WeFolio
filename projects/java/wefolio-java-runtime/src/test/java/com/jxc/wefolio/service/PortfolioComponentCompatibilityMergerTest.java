package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.PortfolioComponentTypeDict;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 个人作品集低版本客户端组件兼容合并测试。 */
class PortfolioComponentCompatibilityMergerTest {

    /** 旧编辑器漏传或默认开启组件标题时保留已关闭状态，跨菜单移动也不丢失。 */
    @Test
    void previousVideoEditorPreservesComponentTitleSwitchAcrossMenus() {
        var existing = new PortfolioConfigDto();
        existing.setEditorSchemaRevision(14);
        existing.setComponents(List.of(displayComponent("video", PortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode(),
                JSONObject.of("title", "婚礼电影", "showComponentTitle", false, "showTitle", true))));
        for (boolean includeDefault : List.of(false, true)) {
            var incoming = new PortfolioConfigDto();
            incoming.setEditorSchemaRevision(13);
            incoming.setComponents(List.of());
            var values = JSONObject.of("title", "新版标题", "showTitle", false);
            if (includeDefault) { values.put("showComponentTitle", true); }
            var moved = displayComponent("video", PortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode(), values);
            incoming.setBottomNav(bottomNav(menu("home", "首页", null), menu("detail", "详情", List.of(moved))));

            var merged = PortfolioComponentCompatibilityMerger.merge(incoming, existing);

            assertThat(merged.getBottomNav().getItems().get(1).getComponents().getFirst().getConfig())
                    .containsEntry("showComponentTitle", false).containsEntry("title", "新版标题")
                    .containsEntry("showTitle", false);
            assertThat(values.get("showComponentTitle")).isEqualTo(includeDefault ? true : null);
            assertThat(existing.getComponents().getFirst().getConfig()).containsEntry("showTitle", true);
        }
    }

    /** 新编辑器可以显式开关组件标题；旧编辑器仍能删除或替换已认识的视频轮播。 */
    @Test
    void currentVideoEditorCanChangeComponentTitleSwitchAndLegacyDeletionStillWorks() {
        var existing = new PortfolioConfigDto();
        existing.setEditorSchemaRevision(14);
        existing.setComponents(List.of(displayComponent("video", PortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode(),
                JSONObject.of("title", "婚礼电影", "showComponentTitle", false))));
        var incoming = new PortfolioConfigDto();
        incoming.setEditorSchemaRevision(14);
        for (boolean enabled : List.of(true, false)) {
            incoming.setComponents(List.of(displayComponent("video", PortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode(),
                    JSONObject.of("title", "婚礼电影", "showComponentTitle", enabled))));
            assertThat(PortfolioComponentCompatibilityMerger.merge(incoming, existing).getComponents().getFirst().getConfig())
                    .containsEntry("showComponentTitle", enabled).containsEntry("title", "婚礼电影");
        }
        incoming.setEditorSchemaRevision(13);
        incoming.setComponents(List.of());
        assertThat(PortfolioComponentCompatibilityMerger.merge(incoming, existing).getComponents()).isEmpty();
        incoming.setComponents(List.of(displayComponent("video", PortfolioComponentTypeDict.TEXT_SECTION.getCode(),
                JSONObject.of("content", "新文本"))));
        assertThat(PortfolioComponentCompatibilityMerger.merge(incoming, existing).getComponents().getFirst().getConfig())
                .doesNotContainKey("showComponentTitle");
    }

    /** 旧编辑器修改高度并移动分割线时保留未知颜色，新版可以主动修改颜色。 */
    @Test
    void legacyDividerEditorPreservesHexColorAcrossMenusOnly() {
        var existing = new PortfolioConfigDto();
        existing.setEditorSchemaRevision(PortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        existing.setComponents(List.of(displayComponent("divider", PortfolioComponentTypeDict.DIVIDER.getCode(),
                JSONObject.of("color", "#12ABCD", "heightPx", 16))));
        for (Integer revision : new Integer[]{null, 2, 7, PortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT}) {
            var incoming = new PortfolioConfigDto();
            incoming.setEditorSchemaRevision(revision);
            incoming.setComponents(List.of());
            var moved = displayComponent("divider", PortfolioComponentTypeDict.DIVIDER.getCode(),
                    JSONObject.of("color", "GRAY", "heightPx", 32));
            incoming.setBottomNav(bottomNav(menu("home", "首页", null), menu("detail", "详情", List.of(moved))));
            var merged = PortfolioComponentCompatibilityMerger.merge(incoming, existing);
            String expected = revision != null && revision == PortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT ? "GRAY" : "#12ABCD";
            assertThat(merged.getBottomNav().getItems().get(1).getComponents().getFirst().getConfig())
                    .containsEntry("color", expected).containsEntry("heightPx", 32);
            assertThat(moved.getConfig()).containsEntry("color", "GRAY");
        }
    }

    /** 旧枚举可照常修改，已知分割线可删除，同键更换组件类型不复制颜色。 */
    @Test
    void legacyDividerEditorStillChangesEnumsDeletesAndReplacesComponents() {
        var existing = config(PortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT,
                component("divider", PortfolioComponentTypeDict.DIVIDER.getCode(), 1000,
                        Map.of("color", "TRANSPARENT", "heightPx", 16)));
        var incoming = config(7, component("divider", PortfolioComponentTypeDict.DIVIDER.getCode(), 1000,
                Map.of("color", "BLACK", "heightPx", 24)));
        assertThat(PortfolioComponentCompatibilityMerger.merge(incoming, existing).getComponents().getFirst().getConfig())
                .containsEntry("color", "BLACK").containsEntry("heightPx", 24);
        existing.getComponents().getFirst().setConfig(Map.of("color", "#F5F6F8", "heightPx", 16));
        incoming.setComponents(List.of());
        assertThat(PortfolioComponentCompatibilityMerger.merge(incoming, existing).getComponents()).isEmpty();
        incoming.setComponents(List.of(component("divider", PortfolioComponentTypeDict.TEXT_SECTION.getCode(), 1000,
                Map.of("content", "替换后的正文"))));
        assertThat(PortfolioComponentCompatibilityMerger.merge(incoming, existing).getComponents().getFirst().getConfig())
                .doesNotContainKey("color");
    }

    /** 旧请求只有已知类型时仍保护新字段，跨菜单移动与音频缺省组合不丢数据。 */
    @Test void protectsNewFieldsBeforeTypeEarlyReturnAcrossMenus() {
        for (Integer revision : new Integer[]{null, 2, 5}) {
            PortfolioConfigDto existing = new PortfolioConfigDto(); existing.setEditorSchemaRevision(6);
            var saved = displayComponent("single", PortfolioComponentTypeDict.SINGLE_WORK.getCode(),
                    JSONObject.of("openMode", "DETAIL_PAGE", "detailOptions", JSONObject.of("showTitle", false, "showDescription", true), "showDescription", true));
            existing.setComponents(List.of(saved));
            PortfolioConfigDto incoming = new PortfolioConfigDto(); incoming.setEditorSchemaRevision(revision);
            var moved = displayComponent("single", PortfolioComponentTypeDict.SINGLE_WORK.getCode(), JSONObject.of("showDescription", false));
            incoming.setComponents(List.of());
            incoming.setBottomNav(bottomNav(menu("home", "首页", null), menu("detail", "详情", List.of(moved))));
            PortfolioConfigDto merged = PortfolioComponentCompatibilityMerger.merge(incoming, existing);
            assertThat(merged.getBottomNav().getItems().get(1).getComponents()).hasSize(1);
            var result = PortfolioComponentTraversal.listComponentLocations(merged).getFirst().component();
            assertThat(result.getConfig()).containsEntry("openMode", "DETAIL_PAGE").containsEntry("showDescription", false);
            assertThat((java.util.Map<?, ?>) result.getConfig().get("detailOptions")).isEqualTo(JSONObject.of("showTitle", false, "showDescription", true));
            assertThat(moved.getConfig()).doesNotContainKey("openMode");
        }
    }

    /** 新客户端显式 false 可修改；旧客户端删除已知组件不恢复，同键不同类型不套字段。 */
    @Test void currentChangesDeletionAndTypeIdentityRemainAuthoritative() {
        PortfolioConfigDto existing = new PortfolioConfigDto(); existing.setEditorSchemaRevision(6);
        existing.setComponents(List.of(displayComponent("video", PortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode(),
                JSONObject.of("displayStyle", "PORTRAIT_CARDS", "showDescription", true))));
        PortfolioConfigDto incoming = new PortfolioConfigDto(); incoming.setEditorSchemaRevision(6);
        incoming.setComponents(List.of(displayComponent("video", PortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode(),
                JSONObject.of("displayStyle", "STACKED", "showDescription", false))));
        assertThat(PortfolioComponentCompatibilityMerger.merge(incoming, existing).getComponents().getFirst().getConfig())
                .containsEntry("displayStyle", "STACKED").containsEntry("showDescription", false);
        incoming.setEditorSchemaRevision(5); incoming.setComponents(List.of());
        assertThat(PortfolioComponentCompatibilityMerger.merge(incoming, existing).getComponents()).isEmpty();
        incoming.setComponents(List.of(displayComponent("video", PortfolioComponentTypeDict.TEXT_SECTION.getCode(), JSONObject.of("content", "新文本"))));
        assertThat(PortfolioComponentCompatibilityMerger.merge(incoming, existing).getComponents().getFirst().getConfig()).doesNotContainKeys("displayStyle", "showDescription");
    }

    /** 旧编辑器无法识别的联系与网格组件按类型原样保护。 */
    @Test void protectsNewComponentTypes() {
        PortfolioConfigDto existing = new PortfolioConfigDto(); existing.setEditorSchemaRevision(6);
        existing.setComponents(List.of(displayComponent("contact", PortfolioComponentTypeDict.CONTACT_INFO.getCode(), JSONObject.of("contactPhone", "123")),
                displayComponent("grid", PortfolioComponentTypeDict.TEXT_GRID.getCode(), JSONObject.of("rows", 2))));
        PortfolioConfigDto incoming = new PortfolioConfigDto(); incoming.setEditorSchemaRevision(5); incoming.setComponents(List.of());
        assertThat(PortfolioComponentCompatibilityMerger.merge(incoming, existing).getComponents()).extracting(PortfolioConfigDto.Component::getComponentKey).containsExactly("contact", "grid");
    }


    /** 已认识网格的上一版编辑器跨菜单移动时，开关可修改而新边框字段受保护。 */
    @Test void previousGridEditorPreservesBorderOptionsAcrossMenus() {
        var existing = new PortfolioConfigDto(); existing.setEditorSchemaRevision(PortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        var saved = displayComponent("grid", PortfolioComponentTypeDict.TEXT_GRID.getCode(),
                JSONObject.of("cellBorder", true, "cellBorderWidthRpx", 8, "cellBorderColor", "#112233"));
        existing.setComponents(List.of(saved));
        for (boolean explicitUnsupportedOptions : List.of(false, true)) {
            var incoming = new PortfolioConfigDto(); incoming.setEditorSchemaRevision(6); incoming.setComponents(List.of());
            var values = JSONObject.of("cellBorder", false);
            if (explicitUnsupportedOptions) { values.put("cellBorderWidthRpx", 1); values.put("cellBorderColor", "AUTO"); }
            var moved = displayComponent("grid", PortfolioComponentTypeDict.TEXT_GRID.getCode(), values);
            incoming.setBottomNav(bottomNav(menu("home", "首页", null), menu("detail", "详情", List.of(moved))));
            var merged = PortfolioComponentCompatibilityMerger.merge(incoming, existing);
            assertThat(merged.getComponents()).isEmpty();
            assertThat(merged.getBottomNav().getItems().get(1).getComponents()).hasSize(1);
            assertThat(PortfolioComponentTraversal.listComponentLocations(merged).getFirst().component().getConfig())
                    .containsEntry("cellBorder", false).containsEntry("cellBorderWidthRpx", 8)
                    .containsEntry("cellBorderColor", "#112233");
            assertThat(moved.getConfig()).doesNotContainEntry("cellBorderWidthRpx", 8);
        }
    }

    /** 当前编辑器可重设宽度颜色，旧编辑器仍可删除其认识的网格。 */
    @Test void currentGridEditorCanChangeBorderAndPreviousEditorCanDeleteGrid() {
        var existing = new PortfolioConfigDto(); existing.setEditorSchemaRevision(PortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        existing.setComponents(List.of(displayComponent("grid", PortfolioComponentTypeDict.TEXT_GRID.getCode(),
                JSONObject.of("cellBorderWidthRpx", 8, "cellBorderColor", "#112233"))));
        var incoming = new PortfolioConfigDto(); incoming.setEditorSchemaRevision(PortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        incoming.setComponents(List.of(displayComponent("grid", PortfolioComponentTypeDict.TEXT_GRID.getCode(),
                JSONObject.of("cellBorderWidthRpx", 2, "cellBorderColor", "#FFFFFF"))));
        assertThat(PortfolioComponentCompatibilityMerger.merge(incoming, existing).getComponents().getFirst().getConfig())
                .containsEntry("cellBorderWidthRpx", 2).containsEntry("cellBorderColor", "#FFFFFF");
        incoming.setEditorSchemaRevision(6); incoming.setComponents(List.of());
        assertThat(PortfolioComponentCompatibilityMerger.merge(incoming, existing).getComponents()).isEmpty();
    }

    /** 无版本编辑器仍按未知组件保护整格配置，边框字段不会被缺省或移动请求抹掉。 */
    @Test void nullRevisionRetainsGridAndBorderAtOriginalLocation() {
        var existing = new PortfolioConfigDto(); existing.setEditorSchemaRevision(PortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        existing.setComponents(List.of(displayComponent("grid", PortfolioComponentTypeDict.TEXT_GRID.getCode(),
                JSONObject.of("cellBorder", true, "cellBorderWidthRpx", 8, "cellBorderColor", "#112233"))));
        var incoming = new PortfolioConfigDto(); incoming.setComponents(List.of());
        incoming.setBottomNav(bottomNav(menu("home", "首页", null), menu("detail", "详情", List.of(
                displayComponent("grid", PortfolioComponentTypeDict.TEXT_GRID.getCode(), JSONObject.of("cellBorder", false))))));
        var merged = PortfolioComponentCompatibilityMerger.merge(incoming, existing);
        assertThat(PortfolioComponentTraversal.listComponentLocations(merged)).hasSize(1);
        assertThat(merged.getComponents().getFirst().getConfig()).containsEntry("cellBorder", true)
                .containsEntry("cellBorderWidthRpx", 8).containsEntry("cellBorderColor", "#112233");
    }

    /** 创建测试信封。 */
    private PortfolioConfigDto.Component displayComponent(String key, String type, JSONObject values) {
        var component = new PortfolioConfigDto.Component(); component.setComponentKey(key); component.setComponentType(type);
        component.setEnabled(true); component.setSortOrder(1000); component.setConfig(values); return component;
    }

    @Test
    void mergeShouldKeepProtectedComponentBetweenKnownAnchorsAfterPayloadReorder() {
        PortfolioConfigDto existing = config(4,
                component("A", PortfolioComponentTypeDict.DIVIDER.getCode(), 1000, Map.of()),
                component("VC", PortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode(), 2000,
                        Map.of("workIds", List.of(11L, 12L, 13L))),
                component("B", PortfolioComponentTypeDict.TEXT_SECTION.getCode(), 3000, Map.of()),
                component("C", PortfolioComponentTypeDict.QR_CONTACT.getCode(), 4000, Map.of()));
        PortfolioConfigDto incoming = config(3,
                component("A", PortfolioComponentTypeDict.DIVIDER.getCode(), 1000, Map.of()),
                component("C", PortfolioComponentTypeDict.QR_CONTACT.getCode(), 2000, Map.of()),
                component("B", PortfolioComponentTypeDict.TEXT_SECTION.getCode(), 3000, Map.of()));

        PortfolioConfigDto merged = PortfolioComponentCompatibilityMerger.merge(incoming, existing);

        assertThat(merged.getComponents())
                .extracting(PortfolioConfigDto.Component::getComponentKey)
                .containsExactly("A", "VC", "C", "B");
        assertThat(merged.getComponents())
                .extracting(PortfolioConfigDto.Component::getSortOrder)
                .containsExactly(1000, 2000, 3000, 4000);
    }

    @Test
    void mergeShouldReplacePayloadMutationWithDeepCopiedDatabaseComponent() {
        Map<String, Object> databaseConfig = new LinkedHashMap<>();
        databaseConfig.put("title", "数据库标题");
        databaseConfig.put("workIds", List.of(11L, 12L, 13L));
        PortfolioConfigDto existing = config(4,
                component("A", PortfolioComponentTypeDict.DIVIDER.getCode(), 1000, Map.of()),
                component("VC", PortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode(), 2000, databaseConfig));
        PortfolioConfigDto incoming = config(3,
                component("A", PortfolioComponentTypeDict.DIVIDER.getCode(), 1000, Map.of()),
                component("VC", PortfolioComponentTypeDict.TEXT_SECTION.getCode(), 2000,
                        Map.of("content", "旧客户端篡改")));

        PortfolioConfigDto merged = PortfolioComponentCompatibilityMerger.merge(incoming, existing);

        assertThat(merged.getComponents()).extracting(PortfolioConfigDto.Component::getComponentType)
                .containsExactly(PortfolioComponentTypeDict.DIVIDER.getCode(),
                        PortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode());
        assertThat(merged.getComponents().get(1).getConfig()).isEqualTo(databaseConfig);
        databaseConfig.put("title", "修改源对象");
        assertThat(merged.getComponents().get(1).getConfig().get("title")).isEqualTo("数据库标题");
    }

    @Test
    void mergeShouldKeepProtectedComponentInItsOriginalNavigationMenu() {
        PortfolioConfigDto existing = config(4,
                component("HOME", PortfolioComponentTypeDict.PROFILE.getCode(), 1000, Map.of()));
        existing.setBottomNav(bottomNav(
                menu("nav_home", "主页", null),
                menu("nav_works", "作品", List.of(
                        component("A", PortfolioComponentTypeDict.DIVIDER.getCode(), 1000, Map.of()),
                        component("VC", PortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode(), 2000,
                                Map.of("workIds", List.of(11L, 12L, 13L))),
                        component("B", PortfolioComponentTypeDict.TEXT_SECTION.getCode(), 3000, Map.of())))
        ));
        PortfolioConfigDto incoming = config(3,
                component("HOME", PortfolioComponentTypeDict.PROFILE.getCode(), 1000, Map.of()));
        incoming.setBottomNav(bottomNav(
                menu("nav_home", "主页", null),
                menu("nav_works", "作品", List.of(
                        component("B", PortfolioComponentTypeDict.TEXT_SECTION.getCode(), 1000, Map.of()),
                        component("A", PortfolioComponentTypeDict.DIVIDER.getCode(), 2000, Map.of())))
        ));

        PortfolioConfigDto merged = PortfolioComponentCompatibilityMerger.merge(incoming, existing);

        assertThat(merged.getComponents()).extracting(PortfolioConfigDto.Component::getComponentKey)
                .containsExactly("HOME");
        assertThat(merged.getBottomNav().getItems().get(1).getComponents())
                .extracting(PortfolioConfigDto.Component::getComponentKey)
                .containsExactly("VC", "B", "A");
    }

    @Test
    void mergeShouldUseSingleAnchorAndBoundedOriginalIndexFallbacks() {
        PortfolioConfigDto existing = config(4,
                component("A", PortfolioComponentTypeDict.DIVIDER.getCode(), 1000, Map.of()),
                component("VC_AFTER", PortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode(), 2000, Map.of()),
                component("MISSING", PortfolioComponentTypeDict.TEXT_SECTION.getCode(), 3000, Map.of()),
                component("VC_BOUNDED", PortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode(), 4000, Map.of()));
        PortfolioConfigDto incoming = config(3,
                component("A", PortfolioComponentTypeDict.DIVIDER.getCode(), 1000, Map.of()));

        PortfolioConfigDto merged = PortfolioComponentCompatibilityMerger.merge(incoming, existing);

        assertThat(merged.getComponents()).extracting(PortfolioConfigDto.Component::getComponentKey)
                .containsExactly("A", "VC_AFTER", "VC_BOUNDED");
    }

    private static PortfolioConfigDto config(int revision, PortfolioConfigDto.Component... components) {
        PortfolioConfigDto config = new PortfolioConfigDto();
        config.setSchemaVersion(PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1);
        config.setEditorSchemaRevision(revision);
        config.setComponents(Arrays.asList(components));
        return config;
    }

    private static PortfolioConfigDto.Component component(
            String key,
            String type,
            int sortOrder,
            Map<String, Object> config
    ) {
        PortfolioConfigDto.Component component = new PortfolioConfigDto.Component();
        component.setComponentKey(key);
        component.setComponentType(type);
        component.setSortOrder(sortOrder);
        component.setEnabled(true);
        component.setConfig(config);
        return component;
    }

    private static PortfolioConfigDto.BottomNav bottomNav(PortfolioConfigDto.BottomNavItem... items) {
        PortfolioConfigDto.BottomNav bottomNav = new PortfolioConfigDto.BottomNav();
        bottomNav.setEnabled(true);
        bottomNav.setItems(Arrays.asList(items));
        return bottomNav;
    }

    private static PortfolioConfigDto.BottomNavItem menu(
            String key,
            String title,
            List<PortfolioConfigDto.Component> components
    ) {
        PortfolioConfigDto.BottomNavItem item = new PortfolioConfigDto.BottomNavItem();
        item.setKey(key);
        item.setTitle(title);
        item.setComponents(components);
        return item;
    }
}
