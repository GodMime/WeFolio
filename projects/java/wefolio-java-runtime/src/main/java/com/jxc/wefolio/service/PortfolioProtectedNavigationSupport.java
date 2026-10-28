package com.jxc.wefolio.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.function.Function;
import java.util.function.Predicate;

/** 按稳定菜单身份恢复旧客户端不能编辑的组件；不新增隐藏菜单存储语义。 */
public final class PortfolioProtectedNavigationSupport {
    /** 逻辑菜单视图，首页正文同样挂到稳定菜单身份。 */
    private record Menu<C, I>(String key, I item, List<C> components) { }
    /** 合并后正文及菜单；恢复导航时由调用者复制原导航元数据。 */
    public record Result<C, I>(List<C> components, List<I> items, boolean restoreNavigation) { }
    /** 工具类不可实例化。 */
    private PortfolioProtectedNavigationSupport() { }

    /** 两套 DTO 使用同一逻辑，组件内部兼容与顺序规则由原合并器提供。 */
    public static <C, I> Result<C, I> merge(List<C> incomingRoot, List<I> incomingItems, boolean incomingEnabled,
            List<C> existingRoot, List<I> existingItems, boolean existingEnabled,
            Function<I, String> menuKey, Function<I, List<C>> menuComponents,
            BiConsumer<I, List<C>> setComponents, Function<I, I> copyMenu,
            Function<C, String> componentKey, Predicate<C> protectedComponent,
            BiFunction<List<C>, List<C>, List<C>> mergeComponents) {
        Set<String> protectedKeys = new LinkedHashSet<>();
        addKeys(protectedKeys, existingRoot, componentKey, protectedComponent);
        for (I item : safe(existingItems)) { if (item != null) { addKeys(protectedKeys, menuComponents.apply(item), componentKey, protectedComponent); } }
        if (protectedKeys.isEmpty()) { return new Result<>(incomingRoot, incomingItems, false); }
        List<C> root = without(incomingRoot, protectedKeys, componentKey);
        List<I> items = new ArrayList<>(safe(incomingItems));
        if (incomingEnabled) {
            Set<String> identities = new LinkedHashSet<>();
            for (I item : items) {
                if (item == null || menuKey.apply(item) == null || !identities.add(menuKey.apply(item))) {
                    // 原校验器负责报告非法菜单；兼容恢复不能消除请求中的身份错误。
                    return new Result<>(incomingRoot, incomingItems, false);
                }
            }
        }
        for (I item : items) { if (item != null) { setComponents.accept(item, without(menuComponents.apply(item), protectedKeys, componentKey)); } }
        if (!existingEnabled || safe(existingItems).isEmpty()) {
            return new Result<>(mergeComponents.apply(root, existingRoot), items, false);
        }
        List<Menu<C, I>> old = views(existingRoot, existingItems, menuKey, menuComponents);
        boolean protectedSecondary = old.stream().skip(1).anyMatch(menu -> hasProtected(menu.components(), protectedComponent));
        if (!incomingEnabled || items.isEmpty()) {
            if (!protectedSecondary) {
                return new Result<>(mergeComponents.apply(root, existingRoot), items, false);
            }
            List<Menu<C, I>> restored = new ArrayList<>();
            Menu<C, I> home = old.getFirst();
            restored.add(new Menu<>(home.key(), copyMenu.apply(home.item()), mergeComponents.apply(root, home.components())));
            for (int index = 1; index < old.size(); index++) {
                Menu<C, I> menu = old.get(index);
                if (hasProtected(menu.components(), protectedComponent)) {
                    restored.add(new Menu<>(menu.key(), copyMenu.apply(menu.item()), mergeComponents.apply(List.of(), menu.components())));
                }
            }
            return serialize(restored, setComponents, true);
        }
        List<Menu<C, I>> current = views(root, items, menuKey, menuComponents);
        // 原首页删除或子菜单提升为首页时，按 key 关联正文，不能按旧数组位置恢复。
        for (int index = 0; index < old.size(); index++) {
            Menu<C, I> previous = old.get(index);
            if (!hasProtected(previous.components(), protectedComponent)) { continue; }
            int target = indexOf(current, previous.key());
            if (target >= 0) {
                Menu<C, I> incoming = current.get(target);
                current.set(target, new Menu<>(incoming.key(), incoming.item(), mergeComponents.apply(incoming.components(), previous.components())));
            } else {
                int insertion = insertionIndex(current, old, index);
                current.add(insertion, new Menu<>(previous.key(), copyMenu.apply(previous.item()), mergeComponents.apply(List.of(), previous.components())));
            }
        }
        return serialize(current, setComponents, false);
    }
    /** 按旧相邻菜单锚点插回，现有请求菜单相对顺序不改。 */
    private static <C, I> int insertionIndex(List<Menu<C, I>> current, List<Menu<C, I>> old, int index) {
        for (int next = index + 1; next < old.size(); next++) {
            int position = indexOf(current, old.get(next).key());
            if (position >= 0) { return position; }
        }
        for (int previous = index - 1; previous >= 0; previous--) {
            int position = indexOf(current, old.get(previous).key());
            if (position >= 0) { return position + 1; }
        }
        return Math.min(index, current.size());
    }
    /** 序列化时始终令首菜单正文位于根。 */
    private static <C, I> Result<C, I> serialize(List<Menu<C, I>> menus,
            BiConsumer<I, List<C>> setter, boolean restore) {
        List<I> items = new ArrayList<>();
        for (int index = 0; index < menus.size(); index++) {
            Menu<C, I> menu = menus.get(index);
            setter.accept(menu.item(), index == 0 ? null : menu.components());
            items.add(menu.item());
        }
        return new Result<>(menus.getFirst().components(), items, restore);
    }
    /** 逻辑视图不把首菜单的空 components 当成正文。 */
    private static <C, I> List<Menu<C, I>> views(List<C> root, List<I> items, Function<I, String> key, Function<I, List<C>> components) {
        List<Menu<C, I>> result = new ArrayList<>();
        for (int index = 0; index < safe(items).size(); index++) {
            I item = items.get(index);
            // 非法菜单留给原校验器处理，不在兼容层制造新的身份。
            if (item == null) { continue; }
            result.add(new Menu<>(key.apply(item), item, index == 0 ? root : safe(components.apply(item))));
        }
        return result;
    }
    /** 查找稳定菜单身份。 */
    private static <C, I> int indexOf(List<Menu<C, I>> menus, String key) {
        for (int i = 0; i < menus.size(); i++) { if (Objects.equals(key, menus.get(i).key())) { return i; } }
        return -1;
    }
    /** 只恢复可信草稿中的受保护身份。 */
    private static <C> void addKeys(Set<String> keys, List<C> components, Function<C, String> key, Predicate<C> protectedComponent) {
        for (C component : safe(components)) { if (component != null && protectedComponent.test(component)) { keys.add(key.apply(component)); } }
    }
    /** 删除所有列表中伪造、改型或被移走的受保护副本。 */
    private static <C> List<C> without(List<C> source, Set<String> keys, Function<C, String> key) {
        return safe(source).stream().filter(component -> component == null || !keys.contains(key.apply(component))).collect(Collectors.toCollection(ArrayList::new));
    }
    /** 判断菜单是否必须恢复。 */
    private static <C> boolean hasProtected(List<C> components, Predicate<C> predicate) { return safe(components).stream().anyMatch(predicate); }
    /** 统一缺省列表。 */
    private static <T> List<T> safe(List<T> source) { return source == null ? List.of() : source; }
}
