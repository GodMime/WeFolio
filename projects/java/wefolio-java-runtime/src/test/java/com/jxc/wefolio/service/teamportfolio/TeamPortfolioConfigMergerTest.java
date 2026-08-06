package com.jxc.wefolio.service.teamportfolio;

import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.dict.TeamPortfolioComponentTypeDict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 新旧编辑器配置合并测试 — 覆盖所有兼容合并场景。
 */
class TeamPortfolioConfigMergerTest {

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
