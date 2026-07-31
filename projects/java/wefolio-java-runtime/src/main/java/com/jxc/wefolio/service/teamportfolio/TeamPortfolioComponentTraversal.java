package com.jxc.wefolio.service.teamportfolio;

import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 团队作品集组件遍历器，统一第一菜单和次级菜单的组件定位语义。
 */
public final class TeamPortfolioComponentTraversal {

    /** 顶层组件配置路径前缀 */
    private static final String TOP_LEVEL_COMPONENT_PATH_PREFIX = "components";

    /** 次级菜单组件配置路径模板 */
    private static final String SECONDARY_COMPONENT_PATH_TEMPLATE = "bottomNav.items[%d].components";

    /**
     * 工具类不允许实例化。
     */
    private TeamPortfolioComponentTraversal() {
    }

    /**
     * 列出配置中的全部菜单组件列表。
     *
     * @param config 团队作品集配置
     * @return 按菜单顺序排列的组件列表
     */
    public static List<ComponentList> listComponentLists(TeamPortfolioConfigDto config) {
        if (config == null) {
            return List.of();
        }
        TeamPortfolioConfigDto.BottomNav bottomNav = config.getBottomNav();
        List<TeamPortfolioConfigDto.BottomNavItem> items = bottomNav == null || bottomNav.getItems() == null
                ? List.of()
                : bottomNav.getItems();
        boolean navigationEnabled = bottomNav != null
                && Boolean.TRUE.equals(bottomNav.getEnabled())
                && !items.isEmpty();
        TeamPortfolioConfigDto.BottomNavItem firstItem = navigationEnabled ? items.getFirst() : null;

        List<ComponentList> componentLists = new ArrayList<>();
        componentLists.add(new ComponentList(
                0,
                defaultString(firstItem == null ? null : firstItem.getKey()),
                defaultString(firstItem == null ? null : firstItem.getTitle()),
                TOP_LEVEL_COMPONENT_PATH_PREFIX,
                safeComponents(config.getComponents())
        ));
        if (!navigationEnabled) {
            return List.copyOf(componentLists);
        }
        for (int menuIndex = 1; menuIndex < items.size(); menuIndex++) {
            TeamPortfolioConfigDto.BottomNavItem item = items.get(menuIndex);
            componentLists.add(new ComponentList(
                    menuIndex,
                    defaultString(item == null ? null : item.getKey()),
                    defaultString(item == null ? null : item.getTitle()),
                    SECONDARY_COMPONENT_PATH_TEMPLATE.formatted(menuIndex),
                    safeComponents(item == null ? null : item.getComponents())
            ));
        }
        return List.copyOf(componentLists);
    }

    /**
     * 列出配置中的全部组件位置。
     *
     * @param config 团队作品集配置
     * @return 按菜单和组件顺序排列的位置列表
     */
    public static List<ComponentLocation> listComponentLocations(TeamPortfolioConfigDto config) {
        List<ComponentLocation> locations = new ArrayList<>();
        for (ComponentList componentList : listComponentLists(config)) {
            for (int componentIndex = 0; componentIndex < componentList.components().size(); componentIndex++) {
                TeamPortfolioConfigDto.ComponentEnvelope component = componentList.components().get(componentIndex);
                locations.add(new ComponentLocation(
                        componentList.menuIndex(),
                        componentList.menuKey(),
                        componentList.menuTitle(),
                        componentIndex,
                        componentList.componentPathPrefix() + "[" + componentIndex + "]",
                        component
                ));
            }
        }
        return List.copyOf(locations);
    }

    /**
     * 按组件键和类型查找启用组件。
     *
     * @param config 团队作品集配置
     * @param componentKey 组件实例键
     * @param componentType 组件类型
     * @return 匹配的组件位置
     */
    public static Optional<ComponentLocation> findEnabledComponent(
            TeamPortfolioConfigDto config,
            String componentKey,
            String componentType
    ) {
        return listComponentLocations(config).stream()
                .filter(location -> location.component() != null)
                .filter(location -> !Boolean.FALSE.equals(location.component().getEnabled()))
                .filter(location -> Objects.equals(componentKey, location.component().getComponentKey()))
                .filter(location -> Objects.equals(componentType, location.component().getComponentType()))
                .findFirst();
    }

    /**
     * 安全复制组件列表。
     */
    private static List<TeamPortfolioConfigDto.ComponentEnvelope> safeComponents(
            List<TeamPortfolioConfigDto.ComponentEnvelope> components
    ) {
        return components == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(components));
    }

    /**
     * 空字符串兜底。
     */
    private static String defaultString(String value) {
        return value == null ? "" : value;
    }

    /**
     * 单个菜单的组件列表。
     *
     * @param menuIndex 菜单下标
     * @param menuKey 菜单实例键
     * @param menuTitle 菜单名称
     * @param componentPathPrefix 组件路径前缀
     * @param components 组件列表
     */
    public record ComponentList(
            int menuIndex,
            String menuKey,
            String menuTitle,
            String componentPathPrefix,
            List<TeamPortfolioConfigDto.ComponentEnvelope> components
    ) {
    }

    /**
     * 单个组件的完整定位信息。
     *
     * @param menuIndex 菜单下标
     * @param menuKey 菜单实例键
     * @param menuTitle 菜单名称
     * @param componentIndex 组件下标
     * @param componentPath 组件真实配置路径
     * @param component 组件配置
     */
    public record ComponentLocation(
            int menuIndex,
            String menuKey,
            String menuTitle,
            int componentIndex,
            String componentPath,
            TeamPortfolioConfigDto.ComponentEnvelope component
    ) {
    }
}
