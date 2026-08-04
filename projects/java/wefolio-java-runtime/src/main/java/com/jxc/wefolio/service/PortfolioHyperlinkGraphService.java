package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.dict.PortfolioConfigScopeDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.dto.PortfolioHyperlinkTargetResponse;
import com.jxc.wefolio.dto.PortfolioReferenceFailureResponse;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.exception.PortfolioValidationException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.PortfolioReferenceEntityMapper;
import com.jxc.wefolio.message.PortfolioMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 个人作品集超链接引用图服务。
 */
@Service
@RequiredArgsConstructor
public class PortfolioHyperlinkGraphService {

    /** 图错误码：目标不可用。 */
    private static final String ERROR_TARGET_INVALID = "PORTFOLIO_HYPERLINK_TARGET_INVALID";

    /** 图错误码：选择自身。 */
    private static final String ERROR_SELF_TARGET = "PORTFOLIO_HYPERLINK_SELF";

    /** 图错误码：循环引用。 */
    private static final String ERROR_CYCLE = "PORTFOLIO_HYPERLINK_CYCLE";

    /** 删除引用错误码。 */
    private static final String ERROR_PERSONAL_REFERENCED = "PERSONAL_PORTFOLIO_REFERENCED";

    /** 未命名作品集标题。 */
    private static final String UNTITLED_PORTFOLIO = "未命名作品集";

    /** 失效当前选择通用标题。 */
    private static final String UNAVAILABLE_SELECTION_TITLE = "当前选择已不可用";

    /** 循环候选禁用原因。 */
    private static final String CYCLE_DISABLED_REASON = "选择后会形成循环引用";

    /** 作品集 Mapper。 */
    private final PortfolioEntityMapper portfolioEntityMapper;

    /** 引用 Mapper。 */
    private final PortfolioReferenceEntityMapper portfolioReferenceEntityMapper;

    /**
     * 已加锁的同一用户个人作品集图。
     *
     * @param ownerId 用户 ID
     * @param source 来源作品集
     * @param portfolios 按主键升序锁定的全部作品集
     */
    public record LockedGraph(Long ownerId, PortfolioEntity source, List<PortfolioEntity> portfolios) {
    }

    /**
     * 锁定当前用户个人作品集图并取得来源作品集。
     *
     * @param ownerId 用户 ID
     * @param sourcePortfolioId 来源作品集 ID
     * @return 已锁定图
     */
    public LockedGraph lockUserGraph(Long ownerId, Long sourcePortfolioId) {
        try {
            List<PortfolioEntity> portfolios = safeList(
                    portfolioEntityMapper.lockActiveStandardPersonalByOwnerId(ownerId));
            PortfolioEntity source = portfolios.stream()
                    .filter(item -> Objects.equals(sourcePortfolioId, item.getId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(PortfolioMessage.PORTFOLIO_NOT_FOUND_MESSAGE));
            return new LockedGraph(ownerId, source, List.copyOf(portfolios));
        } catch (QueryTimeoutException | PessimisticLockingFailureException exception) {
            throw new PortfolioValidationException(
                    PortfolioMessage.PORTFOLIO_GRAPH_BUSY_MESSAGE,
                    Map.of("errorCode", "PORTFOLIO_GRAPH_LOCK_TIMEOUT"),
                    exception
            );
        }
    }

    /**
     * 在锁内校验候选作用域引用的目标和循环。
     *
     * @param graph 已锁定图
     * @param configScope 配置作用域
     * @param candidateReferences 候选完整引用
     * @param config 候选完整配置，用于保持菜单错误前缀
     */
    public void validateReferences(
            LockedGraph graph,
            String configScope,
            List<PortfolioReferenceEntity> candidateReferences,
            PortfolioConfigDto config
    ) {
        Long sourceId = graph.source().getId();
        Map<Long, PortfolioEntity> portfolioMap = graph.portfolios().stream()
                .collect(LinkedHashMap::new, (map, item) -> map.put(item.getId(), item), Map::putAll);
        Map<Long, Set<Long>> adjacency = buildAdjacency(
                loadLinks(configScope, portfolioIds(graph.portfolios())),
                sourceId
        );
        for (PortfolioReferenceEntity reference : safeList(candidateReferences)) {
            if (!isLinkedPortfolioReference(reference)) {
                continue;
            }
            Long targetId = reference.getReferenceId();
            if (Objects.equals(sourceId, targetId)) {
                throw graphError(
                        PortfolioMessage.HYPERLINK_SELF_TARGET_MESSAGE,
                        ERROR_SELF_TARGET,
                        reference,
                        config
                );
            }
            PortfolioEntity target = portfolioMap.get(targetId);
            if (!isAvailablePublishedTarget(graph.ownerId(), target)) {
                throw graphError(
                        PortfolioMessage.HYPERLINK_TARGET_REQUIRED_MESSAGE,
                        ERROR_TARGET_INVALID,
                        reference,
                        config
                );
            }
            adjacency.computeIfAbsent(sourceId, ignored -> new LinkedHashSet<>()).add(targetId);
            if (canReach(adjacency, targetId, sourceId)) {
                throw graphError(PortfolioMessage.HYPERLINK_CYCLE_MESSAGE, ERROR_CYCLE, reference, config);
            }
        }
    }

    /**
     * 查询维护端可选内部作品集目标。
     *
     * @param ownerId 用户 ID
     * @param sourcePortfolioId 可选来源作品集 ID
     * @param selectedTargetPortfolioId 可选当前选择 ID
     * @return 目标列表
     */
    public PortfolioHyperlinkTargetResponse listTargets(
            Long ownerId,
            Long sourcePortfolioId,
            Long selectedTargetPortfolioId
    ) {
        List<PortfolioEntity> ownedPortfolios = safeList(portfolioEntityMapper.selectList(
                Wrappers.<PortfolioEntity>query()
                        .eq("owner_type", PortfolioOwnerTypeDict.USER.getCode())
                        .eq("owner_id", ownerId)
                        .eq("template_type", PortfolioTemplateTypeDict.STANDARD.getCode())
                        .eq("status", PortfolioStatusDict.ACTIVE.getCode())
        ));
        if (sourcePortfolioId != null && ownedPortfolios.stream()
                .noneMatch(item -> Objects.equals(sourcePortfolioId, item.getId()))) {
            throw new BusinessException(PortfolioMessage.PORTFOLIO_NOT_FOUND_MESSAGE);
        }

        Map<Long, Set<Long>> adjacency = sourcePortfolioId == null
                ? Map.of()
                : buildAdjacency(
                        loadLinks(
                                PortfolioConfigScopeDict.DRAFT.getCode(),
                                portfolioIds(ownedPortfolios)
                        ),
                        sourcePortfolioId
                );
        List<PortfolioHyperlinkTargetResponse.TargetItem> candidates = ownedPortfolios.stream()
                .filter(item -> !Objects.equals(sourcePortfolioId, item.getId()))
                .filter(item -> isAvailablePublishedTarget(ownerId, item))
                .sorted(Comparator
                        .comparing(PortfolioEntity::getUpdatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(PortfolioEntity::getId, Comparator.reverseOrder()))
                .map(item -> buildTargetItem(item, sourcePortfolioId, adjacency))
                .toList();

        PortfolioHyperlinkTargetResponse response = new PortfolioHyperlinkTargetResponse();
        response.setPortfolios(candidates);
        boolean selectedInCandidates = selectedTargetPortfolioId == null || candidates.stream()
                .anyMatch(item -> Objects.equals(selectedTargetPortfolioId, item.getPortfolioId()));
        if (!selectedInCandidates) {
            PortfolioHyperlinkTargetResponse.TargetItem current = new PortfolioHyperlinkTargetResponse.TargetItem();
            current.setPortfolioId(selectedTargetPortfolioId);
            current.setTitle(UNAVAILABLE_SELECTION_TITLE);
            current.setCoverUrl("");
            current.setSelectable(false);
            current.setDisabledReason(UNAVAILABLE_SELECTION_TITLE);
            response.setCurrentSelection(current);
        }
        return response;
    }

    /**
     * 校验目标作品集没有个人作品集入向引用。
     *
     * @param graph 已锁定图
     * @param targetPortfolioId 待删除目标作品集 ID
     */
    public void assertNoIncomingLinks(LockedGraph graph, Long targetPortfolioId) {
        List<PortfolioReferenceEntity> incoming = safeList(portfolioReferenceEntityMapper.selectList(
                Wrappers.<PortfolioReferenceEntity>query()
                        .eq("reference_type", ReferenceTypeDict.LINKED_PORTFOLIO.getCode())
                        .eq("reference_id", targetPortfolioId)
                        .eq("is_valid", 1)
                        .in("portfolio_id", portfolioIds(graph.portfolios()))
        ));
        if (incoming.isEmpty()) {
            return;
        }
        Map<Long, PortfolioEntity> portfolioMap = graph.portfolios().stream()
                .collect(LinkedHashMap::new, (map, item) -> map.put(item.getId(), item), Map::putAll);
        Map<String, PortfolioReferenceFailureResponse.ReferenceItem> unique = new LinkedHashMap<>();
        for (PortfolioReferenceEntity reference : incoming) {
            PortfolioEntity source = portfolioMap.get(reference.getPortfolioId());
            if (source == null || Objects.equals(targetPortfolioId, source.getId())) {
                continue;
            }
            String scope = reference.getConfigScope();
            if (!PortfolioConfigScopeDict.DRAFT.getCode().equals(scope)
                    && !PortfolioConfigScopeDict.PUBLISHED.getCode().equals(scope)) {
                continue;
            }
            String key = source.getId() + ":" + scope;
            unique.computeIfAbsent(key, ignored -> buildReferenceItem(source, scope));
        }
        if (unique.isEmpty()) {
            return;
        }

        PortfolioReferenceFailureResponse data = new PortfolioReferenceFailureResponse();
        data.setErrorCode(ERROR_PERSONAL_REFERENCED);
        data.setReferences(new ArrayList<>(unique.values()));
        throw new PortfolioValidationException(buildReferenceMessage(data.getReferences()), data);
    }

    /** 构建目标选择项。 */
    private PortfolioHyperlinkTargetResponse.TargetItem buildTargetItem(
            PortfolioEntity portfolio,
            Long sourcePortfolioId,
            Map<Long, Set<Long>> adjacency
    ) {
        PortfolioHyperlinkTargetResponse.TargetItem item = new PortfolioHyperlinkTargetResponse.TargetItem();
        item.setPortfolioId(portfolio.getId());
        item.setTitle(resolveTitle(portfolio.getPublishedConfigJson()));
        item.setCoverUrl(resolveCoverUrl(portfolio.getPublishedConfigJson()));
        item.setUpdatedAt(portfolio.getUpdatedAt());
        boolean cycle = sourcePortfolioId != null && canReach(adjacency, portfolio.getId(), sourcePortfolioId);
        item.setSelectable(!cycle);
        item.setDisabledReason(cycle ? CYCLE_DISABLED_REASON : "");
        return item;
    }

    /** 构建删除引用来源项。 */
    private PortfolioReferenceFailureResponse.ReferenceItem buildReferenceItem(
            PortfolioEntity source,
            String scope
    ) {
        PortfolioReferenceFailureResponse.ReferenceItem item = new PortfolioReferenceFailureResponse.ReferenceItem();
        item.setSourcePortfolioId(source.getId());
        item.setConfigScope(scope);
        String configJson = PortfolioConfigScopeDict.PUBLISHED.getCode().equals(scope)
                ? source.getPublishedConfigJson()
                : source.getDraftConfigJson();
        item.setSourceTitle(resolveTitle(configJson));
        return item;
    }

    /** 构建最多展示前三处的删除提示。 */
    private String buildReferenceMessage(List<PortfolioReferenceFailureResponse.ReferenceItem> references) {
        List<String> visible = references.stream().limit(3).map(item -> {
            String scopeText = PortfolioConfigScopeDict.PUBLISHED.getCode().equals(item.getConfigScope())
                    ? "已发布版本"
                    : "草稿版本";
            return "《" + item.getSourceTitle() + "》（" + scopeText + "）";
        }).toList();
        String more = references.size() > 3 ? "等 " + (references.size() - 3) + " 处" : "";
        return "作品集被" + String.join("、", visible) + more + "引用，请先移除引用";
    }

    /** 加载指定作用域、指定来源作品集集合内的有效内部跳转边。 */
    private List<PortfolioReferenceEntity> loadLinks(String configScope, List<Long> sourcePortfolioIds) {
        if (sourcePortfolioIds == null || sourcePortfolioIds.isEmpty()) {
            return List.of();
        }
        return safeList(portfolioReferenceEntityMapper.selectList(
                Wrappers.<PortfolioReferenceEntity>query()
                        .eq("reference_type", ReferenceTypeDict.LINKED_PORTFOLIO.getCode())
                        .eq("config_scope", configScope)
                        .eq("is_valid", 1)
                        .in("portfolio_id", sourcePortfolioIds)
        ));
    }

    /** 提取有效作品集主键，并保留锁定顺序。 */
    private List<Long> portfolioIds(List<PortfolioEntity> portfolios) {
        return safeList(portfolios).stream()
                .map(PortfolioEntity::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /** 构建邻接表，并移除来源全部已保存旧出边。 */
    private Map<Long, Set<Long>> buildAdjacency(
            List<PortfolioReferenceEntity> references,
            Long excludedSourceId
    ) {
        Map<Long, Set<Long>> adjacency = new LinkedHashMap<>();
        for (PortfolioReferenceEntity reference : safeList(references)) {
            if (!isLinkedPortfolioReference(reference)
                    || Objects.equals(excludedSourceId, reference.getPortfolioId())) {
                continue;
            }
            adjacency.computeIfAbsent(reference.getPortfolioId(), ignored -> new LinkedHashSet<>())
                    .add(reference.getReferenceId());
        }
        return adjacency;
    }

    /** 判断从起点能否到达终点。 */
    private boolean canReach(Map<Long, Set<Long>> adjacency, Long start, Long target) {
        if (Objects.equals(start, target)) {
            return true;
        }
        Set<Long> visited = new LinkedHashSet<>();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            Long current = stack.pop();
            if (!visited.add(current)) {
                continue;
            }
            for (Long next : adjacency.getOrDefault(current, Set.of())) {
                if (Objects.equals(next, target)) {
                    return true;
                }
                stack.push(next);
            }
        }
        return false;
    }

    /** 判断目标满足内部跳转的正式可用条件。 */
    private boolean isAvailablePublishedTarget(Long ownerId, PortfolioEntity portfolio) {
        return portfolio != null
                && Objects.equals(ownerId, portfolio.getOwnerId())
                && PortfolioOwnerTypeDict.USER.getCode().equals(portfolio.getOwnerType())
                && PortfolioTemplateTypeDict.STANDARD.getCode().equals(portfolio.getTemplateType())
                && PortfolioStatusDict.ACTIVE.getCode().equals(portfolio.getStatus())
                && PortfolioPublicationStatusDict.PUBLISHED.getCode().equals(portfolio.getPublicationStatus())
                && hasText(portfolio.getShareCode())
                && parsePublishedConfig(portfolio.getPublishedConfigJson()) != null;
    }

    /** 解析可渲染的正式配置。 */
    private PortfolioConfigDto parsePublishedConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return null;
        }
        try {
            PortfolioConfigDto config = JSON.parseObject(configJson, PortfolioConfigDto.class);
            if (config == null
                    || !PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1.equals(config.getSchemaVersion())
                    || PortfolioComponentTraversal.listComponentLocations(config).stream()
                    .map(PortfolioComponentTraversal.ComponentLocation::component)
                    .noneMatch(component -> component != null && !Boolean.FALSE.equals(component.getEnabled()))) {
                return null;
            }
            return config;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** 读取配置分享标题。 */
    private String resolveTitle(String configJson) {
        PortfolioConfigDto config = parsePublishedConfig(configJson);
        if (config == null || config.getShare() == null || config.getShare().getTitle() == null
                || config.getShare().getTitle().isBlank()) {
            return UNTITLED_PORTFOLIO;
        }
        return config.getShare().getTitle().strip();
    }

    /** 读取配置分享封面。 */
    private String resolveCoverUrl(String configJson) {
        PortfolioConfigDto config = parsePublishedConfig(configJson);
        if (config == null || config.getShare() == null || config.getShare().getCoverUrl() == null) {
            return "";
        }
        return config.getShare().getCoverUrl().strip();
    }

    /** 构建可定位图错误。 */
    private PortfolioValidationException graphError(
            String message,
            String errorCode,
            PortfolioReferenceEntity reference,
            PortfolioConfigDto config
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorCode", errorCode);
        data.put("componentKey", reference.getComponentKey());
        data.put("targetPortfolioId", reference.getReferenceId());
        return new PortfolioValidationException(withMenuPrefix(message, reference, config), data);
    }

    /** 菜单模式下按组件键补齐既有【菜单名】错误前缀。 */
    private String withMenuPrefix(
            String message,
            PortfolioReferenceEntity reference,
            PortfolioConfigDto config
    ) {
        if (config == null
                || config.getBottomNav() == null
                || !Boolean.TRUE.equals(config.getBottomNav().getEnabled())
                || reference == null) {
            return message;
        }
        return PortfolioComponentTraversal.listComponentLocations(config).stream()
                .filter(location -> location.component() != null)
                .filter(location -> Objects.equals(
                        reference.getComponentKey(),
                        location.component().getComponentKey()
                ))
                .map(PortfolioComponentTraversal.ComponentLocation::menuTitle)
                .filter(this::hasText)
                .findFirst()
                .map(menuTitle -> String.format(PortfolioMessage.MENU_ERROR_PREFIX_TEMPLATE, menuTitle, message))
                .orElse(message);
    }

    /** 判断引用是有效内部作品集边。 */
    private boolean isLinkedPortfolioReference(PortfolioReferenceEntity reference) {
        return reference != null
                && ReferenceTypeDict.LINKED_PORTFOLIO.getCode().equals(reference.getReferenceType())
                && reference.getPortfolioId() != null
                && reference.getReferenceId() != null
                && (reference.getIsValid() == null || reference.getIsValid() == 1);
    }

    /** 空列表兜底。 */
    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    /** 判断字符串包含非空白内容。 */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
