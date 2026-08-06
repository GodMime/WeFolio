package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.PortfolioComponentTypeDict;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 个人作品集低版本客户端组件兼容合并测试。 */
class PortfolioComponentCompatibilityMergerTest {

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
