package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.PortfolioComponentTypeDict;
import com.jxc.wefolio.dto.PortfolioConfigDto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 个人作品集组件级兼容合并器。
 * <p>
 * 低版本客户端无法在保存请求中携带尚未认识的组件。本合并器以数据库草稿为基准，
 * 在请求列表中恢复这些高版本组件，同时允许客户端正常调整其已知组件。
 */
public final class PortfolioComponentCompatibilityMerger {

    /** 未携带能力版本的客户端按 revision 2 识别组件。 */
    private static final int LEGACY_EDITOR_REVISION = 2;

    /** 规范化后的排序间隔。 */
    private static final int SORT_ORDER_STEP = 1000;

    /** 工具类不允许实例化。 */
    private PortfolioComponentCompatibilityMerger() {
    }

    /**
     * 合并本次请求与数据库已有草稿中的高版本组件。
     *
     * @param incomingConfig 本次请求配置
     * @param existingDraftConfig 数据库当前草稿
     * @return 不修改任一入参的深拷贝合并结果
     */
    public static PortfolioConfigDto merge(
            PortfolioConfigDto incomingConfig,
            PortfolioConfigDto existingDraftConfig
    ) {
        PortfolioConfigDto merged = copyConfig(incomingConfig);
        if (existingDraftConfig == null) {
            return merged;
        }
        int incomingRevision = effectiveRevision(incomingConfig.getEditorSchemaRevision());
        Set<String> protectedKeys = collectProtectedKeys(existingDraftConfig, incomingRevision);
        if (protectedKeys.isEmpty()) {
            return merged;
        }

        removeProtectedKeys(merged, protectedKeys);
        merged.setComponents(mergeComponentList(
                merged.getComponents(), existingDraftConfig.getComponents(), incomingRevision));
        mergeNavigationLists(merged, existingDraftConfig, incomingRevision);
        return merged;
    }

    /** 合并各底部导航菜单中的受保护组件。 */
    private static void mergeNavigationLists(
            PortfolioConfigDto merged,
            PortfolioConfigDto existing,
            int incomingRevision
    ) {
        PortfolioConfigDto.BottomNav mergedNav = merged.getBottomNav();
        PortfolioConfigDto.BottomNav existingNav = existing.getBottomNav();
        if (mergedNav == null || !Boolean.TRUE.equals(mergedNav.getEnabled())
                || existingNav == null || existingNav.getItems() == null) {
            return;
        }
        List<PortfolioConfigDto.BottomNavItem> mergedItems = mutableList(mergedNav.getItems());
        List<PortfolioConfigDto.BottomNavItem> existingItems = existingNav.getItems();
        for (int existingIndex = 1; existingIndex < existingItems.size(); existingIndex++) {
            PortfolioConfigDto.BottomNavItem existingItem = existingItems.get(existingIndex);
            if (existingItem == null || !hasProtectedComponent(existingItem.getComponents(), incomingRevision)) {
                continue;
            }
            int mergedIndex = indexOfMenu(mergedItems, existingItem.getKey());
            if (mergedIndex >= 0) {
                PortfolioConfigDto.BottomNavItem mergedItem = mergedItems.get(mergedIndex);
                mergedItem.setComponents(mergeComponentList(
                        mergedItem.getComponents(), existingItem.getComponents(), incomingRevision));
                continue;
            }
            PortfolioConfigDto.BottomNavItem preservedMenu = copyBottomNavItem(existingItem);
            preservedMenu.setComponents(mergeComponentList(
                    List.of(), existingItem.getComponents(), incomingRevision));
            mergedItems.add(Math.min(existingIndex, mergedItems.size()), preservedMenu);
        }
        mergedNav.setItems(mergedItems);
    }

    /** 从请求的所有列表移除受保护键，避免旧客户端移动或篡改高版本组件。 */
    private static void removeProtectedKeys(PortfolioConfigDto config, Set<String> protectedKeys) {
        config.setComponents(withoutKeys(config.getComponents(), protectedKeys));
        PortfolioConfigDto.BottomNav bottomNav = config.getBottomNav();
        if (bottomNav == null || bottomNav.getItems() == null) {
            return;
        }
        for (PortfolioConfigDto.BottomNavItem item : bottomNav.getItems()) {
            if (item != null && item.getComponents() != null) {
                item.setComponents(withoutKeys(item.getComponents(), protectedKeys));
            }
        }
    }

    /** 合并单个组件列表并按最终顺序重建 sortOrder。 */
    private static List<PortfolioConfigDto.Component> mergeComponentList(
            List<PortfolioConfigDto.Component> incomingComponents,
            List<PortfolioConfigDto.Component> existingComponents,
            int incomingRevision
    ) {
        List<PortfolioConfigDto.Component> existing = ordered(existingComponents);
        List<PortfolioConfigDto.Component> result = ordered(incomingComponents);
        Map<String, Integer> existingIndexes = new HashMap<>();
        Map<String, Integer> protectedIndexes = new HashMap<>();
        List<PortfolioConfigDto.Component> protectedComponents = new ArrayList<>();
        for (int index = 0; index < existing.size(); index++) {
            PortfolioConfigDto.Component component = existing.get(index);
            existingIndexes.put(component.getComponentKey(), index);
            if (isProtected(component, incomingRevision)) {
                protectedIndexes.put(component.getComponentKey(), index);
                protectedComponents.add(component);
            }
        }

        Set<String> insertedProtectedKeys = new HashSet<>();
        for (PortfolioConfigDto.Component protectedComponent : protectedComponents) {
            int originalIndex = existingIndexes.get(protectedComponent.getComponentKey());
            int insertionIndex = insertionIndex(
                    result,
                    existing,
                    originalIndex,
                    incomingRevision,
                    protectedIndexes,
                    insertedProtectedKeys);
            result.add(insertionIndex, copyComponent(protectedComponent));
            insertedProtectedKeys.add(protectedComponent.getComponentKey());
        }
        rebuildSortOrders(result);
        return result;
    }

    /** 依据数据库中的相邻已知组件锚点计算插入位置。 */
    private static int insertionIndex(
            List<PortfolioConfigDto.Component> result,
            List<PortfolioConfigDto.Component> existing,
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

    /** 让连续受保护组件保持其数据库内相对顺序。 */
    private static int afterEarlierProtected(
            List<PortfolioConfigDto.Component> result,
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

    /** 查找仍存在于请求列表中的最近已知锚点。 */
    private static String closestKnownKey(
            List<PortfolioConfigDto.Component> existing,
            int originalIndex,
            int direction,
            int incomingRevision,
            List<PortfolioConfigDto.Component> result
    ) {
        for (int index = originalIndex + direction;
                index >= 0 && index < existing.size();
                index += direction) {
            PortfolioConfigDto.Component candidate = existing.get(index);
            if (isKnown(candidate, incomingRevision)
                    && indexOfComponent(result, candidate.getComponentKey()) >= 0) {
                return candidate.getComponentKey();
            }
        }
        return null;
    }

    /** 收集数据库草稿中所有高于请求能力版本的组件键。 */
    private static Set<String> collectProtectedKeys(PortfolioConfigDto config, int incomingRevision) {
        Set<String> keys = new LinkedHashSet<>();
        addProtectedKeys(keys, config.getComponents(), incomingRevision);
        PortfolioConfigDto.BottomNav bottomNav = config.getBottomNav();
        if (bottomNav != null && bottomNav.getItems() != null) {
            for (PortfolioConfigDto.BottomNavItem item : bottomNav.getItems()) {
                addProtectedKeys(keys, item == null ? null : item.getComponents(), incomingRevision);
            }
        }
        return keys;
    }

    /** 添加列表内的受保护组件键。 */
    private static void addProtectedKeys(
            Set<String> keys,
            List<PortfolioConfigDto.Component> components,
            int incomingRevision
    ) {
        for (PortfolioConfigDto.Component component : safeList(components)) {
            if (isProtected(component, incomingRevision) && component.getComponentKey() != null) {
                keys.add(component.getComponentKey());
            }
        }
    }

    /** 判断列表是否包含受保护组件。 */
    private static boolean hasProtectedComponent(
            List<PortfolioConfigDto.Component> components,
            int incomingRevision
    ) {
        return safeList(components).stream().anyMatch(component -> isProtected(component, incomingRevision));
    }

    /** 判断组件是否高于请求编辑器的识别版本。 */
    private static boolean isProtected(PortfolioConfigDto.Component component, int incomingRevision) {
        PortfolioComponentTypeDict type = component == null
                ? null : PortfolioComponentTypeDict.fromCode(component.getComponentType());
        return type != null && type.getIntroducedAtRevision() > incomingRevision;
    }

    /** 判断组件是否为请求编辑器已知类型。 */
    private static boolean isKnown(PortfolioConfigDto.Component component, int incomingRevision) {
        PortfolioComponentTypeDict type = component == null
                ? null : PortfolioComponentTypeDict.fromCode(component.getComponentType());
        return type != null && type.getIntroducedAtRevision() <= incomingRevision;
    }

    /** 按既有 sortOrder 取得稳定列表副本。 */
    private static List<PortfolioConfigDto.Component> ordered(List<PortfolioConfigDto.Component> components) {
        List<PortfolioConfigDto.Component> ordered = new ArrayList<>(safeList(components));
        ordered.removeIf(Objects::isNull);
        ordered.sort(Comparator.comparing(
                PortfolioConfigDto.Component::getSortOrder,
                Comparator.nullsLast(Integer::compareTo)));
        return ordered;
    }

    /** 删除指定组件键并保留原对象顺序。 */
    private static List<PortfolioConfigDto.Component> withoutKeys(
            List<PortfolioConfigDto.Component> components,
            Set<String> keys
    ) {
        return safeList(components).stream()
                .filter(Objects::nonNull)
                .filter(component -> !keys.contains(component.getComponentKey()))
                .toList();
    }

    /** 按键查找组件索引。 */
    private static int indexOfComponent(List<PortfolioConfigDto.Component> components, String key) {
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

    /** 按稳定菜单键查找菜单索引。 */
    private static int indexOfMenu(List<PortfolioConfigDto.BottomNavItem> items, String key) {
        for (int index = 0; index < items.size(); index++) {
            PortfolioConfigDto.BottomNavItem item = items.get(index);
            if (item != null && Objects.equals(key, item.getKey())) {
                return index;
            }
        }
        return -1;
    }

    /** 重建列表排序值。 */
    private static void rebuildSortOrders(List<PortfolioConfigDto.Component> components) {
        for (int index = 0; index < components.size(); index++) {
            components.get(index).setSortOrder((index + 1) * SORT_ORDER_STEP);
        }
    }

    /** 将只读或空列表转成可修改列表。 */
    private static <T> List<T> mutableList(List<T> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }

    /** 空列表兜底。 */
    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    /** 解析请求识别版本。 */
    private static int effectiveRevision(Integer revision) {
        return revision == null ? LEGACY_EDITOR_REVISION : Math.max(LEGACY_EDITOR_REVISION, revision);
    }

    /** 深拷贝完整配置，同时保留无类型 config 中数值的具体 Java 类型。 */
    private static PortfolioConfigDto copyConfig(PortfolioConfigDto source) {
        if (source == null) {
            return null;
        }
        PortfolioConfigDto copy = new PortfolioConfigDto();
        copy.setSchemaVersion(source.getSchemaVersion());
        copy.setEditorSchemaRevision(source.getEditorSchemaRevision());
        copy.setShare(copyShare(source.getShare()));
        copy.setStyle(copyStyle(source.getStyle()));
        copy.setComponents(safeList(source.getComponents()).stream()
                .filter(Objects::nonNull)
                .map(PortfolioComponentCompatibilityMerger::copyComponent)
                .toList());
        copy.setBottomNav(copyBottomNav(source.getBottomNav()));
        return copy;
    }

    /** 深拷贝分享配置。 */
    private static PortfolioConfigDto.Share copyShare(PortfolioConfigDto.Share source) {
        if (source == null) {
            return null;
        }
        PortfolioConfigDto.Share copy = new PortfolioConfigDto.Share();
        copy.setTitle(source.getTitle());
        copy.setCoverUrl(source.getCoverUrl());
        copy.setAvatarUrl(source.getAvatarUrl());
        return copy;
    }

    /** 深拷贝页面样式。 */
    private static PortfolioConfigDto.Style copyStyle(PortfolioConfigDto.Style source) {
        if (source == null) {
            return null;
        }
        PortfolioConfigDto.Style copy = new PortfolioConfigDto.Style();
        copy.setBackgroundColor(source.getBackgroundColor());
        return copy;
    }

    /** 深拷贝底部导航。 */
    private static PortfolioConfigDto.BottomNav copyBottomNav(PortfolioConfigDto.BottomNav source) {
        if (source == null) {
            return null;
        }
        PortfolioConfigDto.BottomNav copy = new PortfolioConfigDto.BottomNav();
        copy.setEnabled(source.getEnabled());
        copy.setItems(source.getItems() == null ? null : source.getItems().stream()
                .map(PortfolioComponentCompatibilityMerger::copyBottomNavItem)
                .toList());
        return copy;
    }

    /** 深拷贝单个导航菜单。 */
    private static PortfolioConfigDto.BottomNavItem copyBottomNavItem(
            PortfolioConfigDto.BottomNavItem source
    ) {
        if (source == null) {
            return null;
        }
        PortfolioConfigDto.BottomNavItem copy = new PortfolioConfigDto.BottomNavItem();
        copy.setKey(source.getKey());
        copy.setTitle(source.getTitle());
        copy.setIconUrl(source.getIconUrl());
        copy.setComponents(source.getComponents() == null ? null : source.getComponents().stream()
                .filter(Objects::nonNull)
                .map(PortfolioComponentCompatibilityMerger::copyComponent)
                .toList());
        return copy;
    }

    /** 深拷贝组件信封及其无类型配置。 */
    private static PortfolioConfigDto.Component copyComponent(PortfolioConfigDto.Component source) {
        PortfolioConfigDto.Component copy = new PortfolioConfigDto.Component();
        copy.setComponentKey(source.getComponentKey());
        copy.setComponentType(source.getComponentType());
        copy.setSortOrder(source.getSortOrder());
        copy.setEnabled(source.getEnabled());
        copy.setConfig(copyConfigMap(source.getConfig()));
        return copy;
    }

    /** 递归复制组件配置 Map，保留 Long、Integer 等不可变标量原类型。 */
    private static Map<String, Object> copyConfigMap(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, copyConfigValue(value)));
        return copy;
    }

    /** 递归复制组件配置值。 */
    private static Object copyConfigValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new java.util.LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(key, copyConfigValue(item)));
            return copy;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(PortfolioComponentCompatibilityMerger::copyConfigValue).toList();
        }
        return value;
    }
}
