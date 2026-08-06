package com.jxc.wefolio.service.teamportfolio;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.dict.TeamPortfolioComponentTypeDict;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 新旧团队编辑器配置兼容合并器。
 * <p>
 * 字段按各自引入版本选择请求或数据库草稿；组件则按组件类型引入版本保护，
 * 防止低版本客户端因无法识别新组件而在保存时将其删除或篡改。
 */
public final class TeamPortfolioConfigMerger {

    /** 未携带能力版本的客户端仍识别首版组件。 */
    private static final int LEGACY_COMPONENT_REVISION = 2;

    /** 页面样式和底部导航首次引入版本。 */
    private static final int STYLE_AND_NAV_REVISION = 2;

    /** 规范化后的排序间隔。 */
    private static final int SORT_ORDER_STEP = 1000;

    /** 工具类不允许实例化。 */
    private TeamPortfolioConfigMerger() {
    }

    /**
     * 合并本次请求与数据库当前草稿。
     *
     * @param incomingConfig 本次请求配置
     * @param existingDraftConfig 数据库当前草稿，首次创建时为 {@code null}
     * @return 不修改任一入参的深拷贝合并结果
     */
    public static TeamPortfolioConfigDto merge(
            TeamPortfolioConfigDto incomingConfig,
            TeamPortfolioConfigDto existingDraftConfig
    ) {
        TeamPortfolioConfigDto merged = deepCopy(incomingConfig, TeamPortfolioConfigDto.class);
        if (existingDraftConfig == null) {
            return merged;
        }

        Integer incomingRevision = incomingConfig.getEditorSchemaRevision();
        Integer existingRevision = existingDraftConfig.getEditorSchemaRevision();
        merged.setEditorSchemaRevision(existingRevision != null
                && (incomingRevision == null || incomingRevision < existingRevision)
                ? existingRevision : incomingRevision);

        boolean incomingHasStyleAndNav = incomingRevision != null
                && incomingRevision >= STYLE_AND_NAV_REVISION;
        boolean existingHasStyleAndNav = existingRevision != null
                && existingRevision >= STYLE_AND_NAV_REVISION;
        if (!incomingHasStyleAndNav && existingHasStyleAndNav) {
            merged.setStyle(deepCopy(existingDraftConfig.getStyle(), TeamPortfolioConfigDto.Style.class));
            merged.setBottomNav(deepCopy(
                    existingDraftConfig.getBottomNav(), TeamPortfolioConfigDto.BottomNav.class));
        } else if (!incomingHasStyleAndNav) {
            merged.setStyle(null);
            merged.setBottomNav(null);
        }

        int effectiveRevision = incomingRevision == null
                ? LEGACY_COMPONENT_REVISION : Math.max(LEGACY_COMPONENT_REVISION, incomingRevision);
        Set<String> protectedKeys = collectProtectedKeys(existingDraftConfig, effectiveRevision);
        if (protectedKeys.isEmpty()) {
            return merged;
        }
        removeProtectedKeys(merged, protectedKeys);
        merged.setComponents(mergeComponentList(
                merged.getComponents(), existingDraftConfig.getComponents(), effectiveRevision));
        mergeNavigationLists(merged, existingDraftConfig, effectiveRevision);
        return merged;
    }

    /** 合并各底部导航菜单中的受保护组件。 */
    private static void mergeNavigationLists(
            TeamPortfolioConfigDto merged,
            TeamPortfolioConfigDto existing,
            int incomingRevision
    ) {
        TeamPortfolioConfigDto.BottomNav mergedNav = merged.getBottomNav();
        TeamPortfolioConfigDto.BottomNav existingNav = existing.getBottomNav();
        if (mergedNav == null || !Boolean.TRUE.equals(mergedNav.getEnabled())
                || existingNav == null || existingNav.getItems() == null) {
            return;
        }
        List<TeamPortfolioConfigDto.BottomNavItem> mergedItems = mutableList(mergedNav.getItems());
        List<TeamPortfolioConfigDto.BottomNavItem> existingItems = existingNav.getItems();
        for (int existingIndex = 1; existingIndex < existingItems.size(); existingIndex++) {
            TeamPortfolioConfigDto.BottomNavItem existingItem = existingItems.get(existingIndex);
            if (existingItem == null || !hasProtectedComponent(existingItem.getComponents(), incomingRevision)) {
                continue;
            }
            int mergedIndex = indexOfMenu(mergedItems, existingItem.getKey());
            if (mergedIndex >= 0) {
                TeamPortfolioConfigDto.BottomNavItem mergedItem = mergedItems.get(mergedIndex);
                mergedItem.setComponents(mergeComponentList(
                        mergedItem.getComponents(), existingItem.getComponents(), incomingRevision));
                continue;
            }
            TeamPortfolioConfigDto.BottomNavItem preservedMenu = deepCopy(
                    existingItem, TeamPortfolioConfigDto.BottomNavItem.class);
            preservedMenu.setComponents(mergeComponentList(
                    List.of(), existingItem.getComponents(), incomingRevision));
            mergedItems.add(Math.min(existingIndex, mergedItems.size()), preservedMenu);
        }
        mergedNav.setItems(mergedItems);
    }

    /** 从请求全部列表移除受保护键，避免组件被旧客户端移动到其它菜单。 */
    private static void removeProtectedKeys(TeamPortfolioConfigDto config, Set<String> protectedKeys) {
        config.setComponents(withoutKeys(config.getComponents(), protectedKeys));
        TeamPortfolioConfigDto.BottomNav bottomNav = config.getBottomNav();
        if (bottomNav == null || bottomNav.getItems() == null) {
            return;
        }
        for (TeamPortfolioConfigDto.BottomNavItem item : bottomNav.getItems()) {
            if (item != null && item.getComponents() != null) {
                item.setComponents(withoutKeys(item.getComponents(), protectedKeys));
            }
        }
    }

    /** 合并单个组件列表并重建 sortOrder。 */
    private static List<TeamPortfolioConfigDto.ComponentEnvelope> mergeComponentList(
            List<TeamPortfolioConfigDto.ComponentEnvelope> incomingComponents,
            List<TeamPortfolioConfigDto.ComponentEnvelope> existingComponents,
            int incomingRevision
    ) {
        List<TeamPortfolioConfigDto.ComponentEnvelope> existing = ordered(existingComponents);
        List<TeamPortfolioConfigDto.ComponentEnvelope> result = ordered(incomingComponents);
        Map<String, Integer> existingIndexes = new HashMap<>();
        Map<String, Integer> protectedIndexes = new HashMap<>();
        List<TeamPortfolioConfigDto.ComponentEnvelope> protectedComponents = new ArrayList<>();
        for (int index = 0; index < existing.size(); index++) {
            TeamPortfolioConfigDto.ComponentEnvelope component = existing.get(index);
            existingIndexes.put(component.getComponentKey(), index);
            if (isProtected(component, incomingRevision)) {
                protectedIndexes.put(component.getComponentKey(), index);
                protectedComponents.add(component);
            }
        }

        Set<String> insertedProtectedKeys = new HashSet<>();
        for (TeamPortfolioConfigDto.ComponentEnvelope protectedComponent : protectedComponents) {
            int originalIndex = existingIndexes.get(protectedComponent.getComponentKey());
            int insertionIndex = insertionIndex(
                    result,
                    existing,
                    originalIndex,
                    incomingRevision,
                    protectedIndexes,
                    insertedProtectedKeys);
            result.add(insertionIndex, deepCopy(
                    protectedComponent, TeamPortfolioConfigDto.ComponentEnvelope.class));
            insertedProtectedKeys.add(protectedComponent.getComponentKey());
        }
        rebuildSortOrders(result);
        return result;
    }

    /** 按数据库已知组件锚点计算插入位置。 */
    private static int insertionIndex(
            List<TeamPortfolioConfigDto.ComponentEnvelope> result,
            List<TeamPortfolioConfigDto.ComponentEnvelope> existing,
            int originalIndex,
            int incomingRevision,
            Map<String, Integer> protectedIndexes,
            Set<String> insertedProtectedKeys
    ) {
        String previousKey = closestKnownKey(existing, originalIndex, -1, incomingRevision, result);
        String nextKey = closestKnownKey(existing, originalIndex, 1, incomingRevision, result);
        int previousIndex = indexOfComponent(result, previousKey);
        int nextIndex = indexOfComponent(result, nextKey);
        if (previousIndex >= 0 && nextIndex >= 0) {
            if (previousIndex < nextIndex) {
                return afterEarlierProtected(
                        result, previousIndex + 1, nextIndex, originalIndex,
                        protectedIndexes, insertedProtectedKeys);
            }
            return nextIndex;
        }
        if (previousIndex >= 0) {
            return afterEarlierProtected(
                    result, previousIndex + 1, result.size(), originalIndex,
                    protectedIndexes, insertedProtectedKeys);
        }
        if (nextIndex >= 0) {
            return nextIndex;
        }
        return Math.min(originalIndex, result.size());
    }

    /** 连续受保护组件按数据库原顺序排列。 */
    private static int afterEarlierProtected(
            List<TeamPortfolioConfigDto.ComponentEnvelope> result,
            int start,
            int upperBound,
            int currentOriginalIndex,
            Map<String, Integer> protectedIndexes,
            Set<String> insertedProtectedKeys
    ) {
        int insertionIndex = start;
        while (insertionIndex < upperBound && insertionIndex < result.size()) {
            String key = result.get(insertionIndex).getComponentKey();
            Integer protectedIndex = protectedIndexes.get(key);
            if (!insertedProtectedKeys.contains(key)
                    || protectedIndex == null
                    || protectedIndex >= currentOriginalIndex) {
                break;
            }
            insertionIndex++;
        }
        return insertionIndex;
    }

    /** 查找仍存在于请求中的最近已知组件锚点。 */
    private static String closestKnownKey(
            List<TeamPortfolioConfigDto.ComponentEnvelope> existing,
            int originalIndex,
            int direction,
            int incomingRevision,
            List<TeamPortfolioConfigDto.ComponentEnvelope> result
    ) {
        for (int index = originalIndex + direction;
                index >= 0 && index < existing.size();
                index += direction) {
            TeamPortfolioConfigDto.ComponentEnvelope candidate = existing.get(index);
            if (isKnown(candidate, incomingRevision)
                    && indexOfComponent(result, candidate.getComponentKey()) >= 0) {
                return candidate.getComponentKey();
            }
        }
        return null;
    }

    /** 收集数据库草稿内全部受保护组件键。 */
    private static Set<String> collectProtectedKeys(
            TeamPortfolioConfigDto config,
            int incomingRevision
    ) {
        Set<String> keys = new LinkedHashSet<>();
        addProtectedKeys(keys, config.getComponents(), incomingRevision);
        TeamPortfolioConfigDto.BottomNav bottomNav = config.getBottomNav();
        if (bottomNav != null && bottomNav.getItems() != null) {
            for (TeamPortfolioConfigDto.BottomNavItem item : bottomNav.getItems()) {
                addProtectedKeys(keys, item == null ? null : item.getComponents(), incomingRevision);
            }
        }
        return keys;
    }

    /** 添加列表内的受保护组件键。 */
    private static void addProtectedKeys(
            Set<String> keys,
            List<TeamPortfolioConfigDto.ComponentEnvelope> components,
            int incomingRevision
    ) {
        for (TeamPortfolioConfigDto.ComponentEnvelope component : safeList(components)) {
            if (isProtected(component, incomingRevision) && component.getComponentKey() != null) {
                keys.add(component.getComponentKey());
            }
        }
    }

    /** 判断列表是否包含受保护组件。 */
    private static boolean hasProtectedComponent(
            List<TeamPortfolioConfigDto.ComponentEnvelope> components,
            int incomingRevision
    ) {
        return safeList(components).stream().anyMatch(component -> isProtected(component, incomingRevision));
    }

    /** 判断组件是否高于请求编辑器识别版本。 */
    private static boolean isProtected(
            TeamPortfolioConfigDto.ComponentEnvelope component,
            int incomingRevision
    ) {
        TeamPortfolioComponentTypeDict type = component == null
                ? null : TeamPortfolioComponentTypeDict.fromCode(component.getComponentType());
        return type != null && type.getIntroducedAtRevision() > incomingRevision;
    }

    /** 判断组件是否为请求编辑器已知类型。 */
    private static boolean isKnown(
            TeamPortfolioConfigDto.ComponentEnvelope component,
            int incomingRevision
    ) {
        TeamPortfolioComponentTypeDict type = component == null
                ? null : TeamPortfolioComponentTypeDict.fromCode(component.getComponentType());
        return type != null && type.getIntroducedAtRevision() <= incomingRevision;
    }

    /** 按既有 sortOrder 取得稳定列表副本。 */
    private static List<TeamPortfolioConfigDto.ComponentEnvelope> ordered(
            List<TeamPortfolioConfigDto.ComponentEnvelope> components
    ) {
        List<TeamPortfolioConfigDto.ComponentEnvelope> ordered = new ArrayList<>(safeList(components));
        ordered.removeIf(Objects::isNull);
        ordered.sort(Comparator.comparing(
                TeamPortfolioConfigDto.ComponentEnvelope::getSortOrder,
                Comparator.nullsLast(Integer::compareTo)));
        return ordered;
    }

    /** 删除指定组件键。 */
    private static List<TeamPortfolioConfigDto.ComponentEnvelope> withoutKeys(
            List<TeamPortfolioConfigDto.ComponentEnvelope> components,
            Set<String> keys
    ) {
        return safeList(components).stream()
                .filter(Objects::nonNull)
                .filter(component -> !keys.contains(component.getComponentKey()))
                .toList();
    }

    /** 按组件键查找索引。 */
    private static int indexOfComponent(
            List<TeamPortfolioConfigDto.ComponentEnvelope> components,
            String key
    ) {
        if (key == null) {
            return -1;
        }
        for (int index = 0; index < components.size(); index++) {
            if (Objects.equals(key, components.get(index).getComponentKey())) {
                return index;
            }
        }
        return -1;
    }

    /** 按稳定菜单键查找索引。 */
    private static int indexOfMenu(List<TeamPortfolioConfigDto.BottomNavItem> items, String key) {
        for (int index = 0; index < items.size(); index++) {
            TeamPortfolioConfigDto.BottomNavItem item = items.get(index);
            if (item != null && Objects.equals(key, item.getKey())) {
                return index;
            }
        }
        return -1;
    }

    /** 重建组件排序值。 */
    private static void rebuildSortOrders(List<TeamPortfolioConfigDto.ComponentEnvelope> components) {
        for (int index = 0; index < components.size(); index++) {
            components.get(index).setSortOrder((index + 1) * SORT_ORDER_STEP);
        }
    }

    /** 转成可修改列表。 */
    private static <T> List<T> mutableList(List<T> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }

    /** 空列表兜底。 */
    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    /** JSON 往返深拷贝 DTO。 */
    private static <T> T deepCopy(T source, Class<T> type) {
        return source == null ? null : JSON.parseObject(JSON.toJSONString(source), type);
    }
}
