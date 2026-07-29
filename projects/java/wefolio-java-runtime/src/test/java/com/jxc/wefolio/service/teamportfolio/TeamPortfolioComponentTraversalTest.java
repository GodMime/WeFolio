package com.jxc.wefolio.service.teamportfolio;

import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.dict.TeamPortfolioComponentTypeDict;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 团队作品集全菜单组件遍历测试。
 */
class TeamPortfolioComponentTraversalTest {

    /**
     * 导航启用时必须把第一菜单映射到顶层组件，并按菜单顺序遍历次级组件。
     */
    @Test
    void listComponentLocationsShouldTraverseTopLevelAndSecondaryMenusInOrder() {
        TeamPortfolioConfigDto config = configWithNavigation();

        List<TeamPortfolioComponentTraversal.ComponentLocation> locations =
                TeamPortfolioComponentTraversal.listComponentLocations(config);

        assertThat(locations).extracting(
                        TeamPortfolioComponentTraversal.ComponentLocation::menuIndex,
                        TeamPortfolioComponentTraversal.ComponentLocation::menuKey,
                        TeamPortfolioComponentTraversal.ComponentLocation::menuTitle,
                        TeamPortfolioComponentTraversal.ComponentLocation::componentPath)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, "nav_home", "主页", "components[0]"),
                        org.assertj.core.groups.Tuple.tuple(
                                1, "nav_works", "作品", "bottomNav.items[1].components[0]"));
    }

    /**
     * 互动组件查找必须覆盖次级菜单，并忽略已禁用组件。
     */
    @Test
    void findEnabledComponentShouldLocateSecondaryComponentByKeyAndType() {
        TeamPortfolioConfigDto config = configWithNavigation();

        assertThat(TeamPortfolioComponentTraversal.findEnabledComponent(
                config, "component-secondary", TeamPortfolioComponentTypeDict.CONTACT_FORM.getCode()))
                .get()
                .extracting(
                        TeamPortfolioComponentTraversal.ComponentLocation::menuKey,
                        TeamPortfolioComponentTraversal.ComponentLocation::componentPath)
                .containsExactly("nav_works", "bottomNav.items[1].components[0]");

        config.getBottomNav().getItems().get(1).getComponents().getFirst().setEnabled(false);
        assertThat(TeamPortfolioComponentTraversal.findEnabledComponent(
                config, "component-secondary", TeamPortfolioComponentTypeDict.CONTACT_FORM.getCode()))
                .isEmpty();
    }

    /**
     * 旧配置关闭导航时只能暴露顶层组件，不能误读残留菜单项。
     */
    @Test
    void listComponentListsShouldUseOnlyTopLevelComponentsWhenNavigationIsDisabled() {
        TeamPortfolioConfigDto config = configWithNavigation();
        config.getBottomNav().setEnabled(false);

        assertThat(TeamPortfolioComponentTraversal.listComponentLists(config))
                .singleElement()
                .satisfies(componentList -> {
                    assertThat(componentList.menuIndex()).isZero();
                    assertThat(componentList.menuKey()).isEmpty();
                    assertThat(componentList.componentPathPrefix()).isEqualTo("components");
                    assertThat(componentList.components()).extracting(
                                    TeamPortfolioConfigDto.ComponentEnvelope::getComponentKey)
                            .containsExactly("component-home");
                });
    }

    private TeamPortfolioConfigDto configWithNavigation() {
        TeamPortfolioConfigDto config = new TeamPortfolioConfigDto();
        config.setEditorSchemaRevision(2);
        TeamPortfolioConfigDto.Style style = new TeamPortfolioConfigDto.Style();
        style.setBackgroundColor("#151515");
        config.setStyle(style);
        config.setComponents(List.of(component(
                "component-home", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), true)));

        TeamPortfolioConfigDto.BottomNavItem first = new TeamPortfolioConfigDto.BottomNavItem();
        first.setKey("nav_home");
        first.setTitle("主页");
        TeamPortfolioConfigDto.BottomNavItem second = new TeamPortfolioConfigDto.BottomNavItem();
        second.setKey("nav_works");
        second.setTitle("作品");
        second.setComponents(List.of(component(
                "component-secondary", TeamPortfolioComponentTypeDict.CONTACT_FORM.getCode(), true)));
        TeamPortfolioConfigDto.BottomNav bottomNav = new TeamPortfolioConfigDto.BottomNav();
        bottomNav.setEnabled(true);
        bottomNav.setItems(List.of(first, second));
        config.setBottomNav(bottomNav);
        return config;
    }

    private TeamPortfolioConfigDto.ComponentEnvelope component(String key, String type, boolean enabled) {
        TeamPortfolioConfigDto.ComponentEnvelope component = new TeamPortfolioConfigDto.ComponentEnvelope();
        component.setComponentKey(key);
        component.setComponentType(type);
        component.setEnabled(enabled);
        component.setSortOrder(1000);
        component.setConfig(new JSONObject());
        return component;
    }
}
