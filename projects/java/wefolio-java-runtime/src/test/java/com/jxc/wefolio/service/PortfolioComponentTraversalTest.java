package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.PortfolioComponentTypeDict;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 标准个人作品集组件遍历测试 — 固定第一菜单单一数据源与次级菜单路径语义。
 */
class PortfolioComponentTraversalTest {

    /**
     * 全菜单遍历必须保持菜单顺序并输出真实配置路径。
     */
    @Test
    void listComponentLocationsShouldIncludeTopLevelAndSecondaryMenuComponents() {
        PortfolioConfigDto config = navigationConfig();

        List<PortfolioComponentTraversal.ComponentLocation> locations =
                PortfolioComponentTraversal.listComponentLocations(config);

        assertThat(locations).extracting(PortfolioComponentTraversal.ComponentLocation::menuIndex)
                .containsExactly(0, 1);
        assertThat(locations).extracting(PortfolioComponentTraversal.ComponentLocation::menuKey)
                .containsExactly("nav_home", "nav_works");
        assertThat(locations).extracting(PortfolioComponentTraversal.ComponentLocation::menuTitle)
                .containsExactly("主页", "作品");
        assertThat(locations).extracting(PortfolioComponentTraversal.ComponentLocation::componentPath)
                .containsExactly("components[0]", "bottomNav.items[1].components[0]");
        assertThat(locations).extracting(location -> location.component().getComponentKey())
                .containsExactly("c_profile", "c_schedule");
    }

    /**
     * 组件查找必须覆盖次级菜单，同时忽略禁用或类型不匹配的组件。
     */
    @Test
    void findEnabledComponentShouldFindMatchingSecondaryMenuComponent() {
        PortfolioConfigDto config = navigationConfig();

        PortfolioComponentTraversal.ComponentLocation location =
                PortfolioComponentTraversal.findEnabledComponent(
                        config,
                        "c_schedule",
                        PortfolioComponentTypeDict.SCHEDULE_QUERY.getCode()
                ).orElseThrow();

        assertThat(location.menuIndex()).isEqualTo(1);
        assertThat(location.componentPath()).isEqualTo("bottomNav.items[1].components[0]");
        assertThat(PortfolioComponentTraversal.findEnabledComponent(
                config,
                "c_schedule",
                PortfolioComponentTypeDict.CONTACT_FORM.getCode()
        )).isEmpty();
    }

    /**
     * 新组件类型不得破坏次级菜单的通用定位能力。
     */
    @Test
    void findEnabledComponentShouldLocateVideoCarouselInSecondaryMenu() {
        PortfolioConfigDto config = navigationConfig();
        config.getBottomNav().getItems().get(1).setComponents(List.of(component(
                "c_video_carousel",
                PortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode(),
                true
        )));

        PortfolioComponentTraversal.ComponentLocation location =
                PortfolioComponentTraversal.findEnabledComponent(
                        config,
                        "c_video_carousel",
                        PortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode()
                ).orElseThrow();

        assertThat(location.menuIndex()).isEqualTo(1);
        assertThat(location.componentPath()).isEqualTo("bottomNav.items[1].components[0]");
    }

    /**
     * 无导航旧配置仍只遍历顶层组件，并提供稳定的默认菜单元数据。
     */
    @Test
    void listComponentLocationsShouldSupportLegacyConfigWithoutNavigation() {
        PortfolioConfigDto config = new PortfolioConfigDto();
        config.setComponents(List.of(component(
                "c_text",
                PortfolioComponentTypeDict.TEXT_SECTION.getCode(),
                true
        )));

        List<PortfolioComponentTraversal.ComponentLocation> locations =
                PortfolioComponentTraversal.listComponentLocations(config);

        assertThat(locations).hasSize(1);
        assertThat(locations.get(0).menuIndex()).isZero();
        assertThat(locations.get(0).menuKey()).isEmpty();
        assertThat(locations.get(0).menuTitle()).isEmpty();
        assertThat(locations.get(0).componentPath()).isEqualTo("components[0]");
    }

    /**
     * 构造启用两项导航的配置。
     */
    private PortfolioConfigDto navigationConfig() {
        PortfolioConfigDto config = new PortfolioConfigDto();
        config.setComponents(List.of(component(
                "c_profile",
                PortfolioComponentTypeDict.PROFILE.getCode(),
                true
        )));

        PortfolioConfigDto.BottomNavItem firstItem = new PortfolioConfigDto.BottomNavItem();
        firstItem.setKey("nav_home");
        firstItem.setTitle("主页");

        PortfolioConfigDto.BottomNavItem secondItem = new PortfolioConfigDto.BottomNavItem();
        secondItem.setKey("nav_works");
        secondItem.setTitle("作品");
        secondItem.setComponents(List.of(component(
                "c_schedule",
                PortfolioComponentTypeDict.SCHEDULE_QUERY.getCode(),
                true
        )));

        PortfolioConfigDto.BottomNav bottomNav = new PortfolioConfigDto.BottomNav();
        bottomNav.setEnabled(true);
        bottomNav.setItems(List.of(firstItem, secondItem));
        config.setBottomNav(bottomNav);
        return config;
    }

    /**
     * 构造最小组件配置。
     */
    private PortfolioConfigDto.Component component(String key, String type, boolean enabled) {
        PortfolioConfigDto.Component component = new PortfolioConfigDto.Component();
        component.setComponentKey(key);
        component.setComponentType(type);
        component.setEnabled(enabled);
        component.setConfig(Map.of());
        return component;
    }
}
