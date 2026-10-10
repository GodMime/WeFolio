package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioAiMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;

import static com.jxc.wefolio.service.portfolioai.PortfolioAiConstants.*;

/** 将受限中间协议确定性编译到完整普通配置；不会把模型对象写给实体。 */
public final class PortfolioAiCompiler {
    /** 固定节点工厂。 */
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    /** 普通单资源组件。 */
    private static final Set<String> SINGLE_SOURCE = Set.of(COMPONENT_SINGLE_WORK, COMPONENT_HYPERLINK);
    /** 普通成员作品集整体入口组件。 */
    private static final Set<String> MEMBER_SOURCES = Set.of(COMPONENT_MEMBER_PORTFOLIO_LIST, COMPONENT_MEMBER_PORTFOLIO_GRID);
    /** 普通作品组的默认稳定键与名称。 */
    private static final String DEFAULT_GROUP_KEY = "g_all", DEFAULT_GROUP_NAME = "全部作品";
    /** 普通文字网格默认行高。 */
    private static final int DEFAULT_GRID_ROW_HEIGHT = 180;
    /** 工具类不可实例化。 */
    private PortfolioAiCompiler() { }

    /** 输出经过共享规则复验，从冻结基准的深副本开始编译。 */
    public static ObjectNode compile(Long taskId, ObjectNode request, ObjectNode envelope) {
        AiPortfolioProtocol.validate(request, envelope.toString());
        if (!RESULT_CANDIDATE.equals(envelope.path(KEY_STATUS).asText())) { throw invalid(); }
        ObjectNode result = AiPortfolioProtocol.baseConfig(request).deepCopy();
        ArrayNode current = components(request, result);
        String mode = request.path(KEY_INPUT).path(KEY_MODE).asText();
        if (MODE_CREATE.equals(mode)) {
            ArrayNode replacement = NODES.arrayNode();
            int index = 0;
            for (JsonNode proposal : envelope.path(KEY_PAGE).path(KEY_COMPONENTS)) {
                replacement.add(proposal(taskId, ++index, request, (ObjectNode) proposal));
            }
            current.removeAll(); current.addAll(replacement);
            envelope.path(KEY_PAGE).path(KEY_SETTINGS).fields().forEachRemaining(e -> {
                ObjectNode section = objectSection(result, e.getKey());
                merge(section, e.getValue().path(KEY_PARAMS));
                bindings(section, e.getValue().path(KEY_BINDINGS), request, result);
            });
        } else {
            assertNoOverlappingRemoval(envelope.path(KEY_OPERATIONS));
            int proposalIndex = 0;
            for (JsonNode raw : envelope.path(KEY_OPERATIONS)) {
                ObjectNode operation = (ObjectNode) raw;
                ObjectNode target = (ObjectNode) operation.path(KEY_TARGET);
                JsonNode payload = operation.path(KEY_PAYLOAD);
                String op = operation.path(KEY_OP).asText();
                ObjectNode component = target.has(KEY_COMPONENT_KEY) ? locateComponent(request, current, target.path(KEY_COMPONENT_KEY).asText()) : null;
                ObjectNode node = component == null ? null : locateNode(component, target);
                switch (op) {
                    case OP_UPDATE_TEXT -> {
                        String field = payload.path(KEY_FIELD).asText();
                        if (KEY_BIO.equals(field) && node.has(KEY_PROFILE)) { node = (ObjectNode) node.path(KEY_PROFILE); }
                        if (KEY_INTRO.equals(field) && node.has(KEY_TEAM)) { node = (ObjectNode) node.path(KEY_TEAM); }
                        node.set(field, payload.get(KEY_VALUE).deepCopy());
                    }
                    case OP_UPDATE_PARAMS -> merge(node, payload.path(KEY_PARAMS));
                    case OP_BIND_RESOURCES -> {
                        primary(component.path(KEY_COMPONENT_TYPE).asText(), node, payload.path(KEY_SOURCE_NOS), request);
                        bindings(node, payload.path(KEY_BINDINGS), request, result);
                    }
                    case OP_REMOVE_COMPONENT -> current.remove(indexOf(current, component.path(KEY_COMPONENT_KEY).asText()));
                    case OP_MOVE_COMPONENT -> {
                        current.remove(indexOf(current, component.path(KEY_COMPONENT_KEY).asText()));
                        insert(current, component, payload, request);
                    }
                    case OP_INSERT_COMPONENT -> insert(current, proposal(taskId, ++proposalIndex, request,
                            (ObjectNode) payload.path(KEY_COMPONENT)), payload, request);
                    case OP_UPDATE_GLOBAL -> {
                        ObjectNode section = objectSection(result, payload.path(KEY_SECTION).asText());
                        merge(section, payload.path(KEY_PARAMS));
                        bindings(section, payload.path(KEY_BINDINGS), request, result);
                    }
                    default -> throw invalid();
                }
            }
        }
        for (int i = 0; i < current.size(); i++) { ((ObjectNode) current.get(i)).put(KEY_SORT_ORDER, (i + 1) * 1000); }
        alignFontReferences(result, request, envelope);
        assertProtectedScope(request, envelope, result);
        return result;
    }

    /** 新建与整体修改插入组件时补确定性身份，不复用模型提供的已有组件提案。 */
    private static ObjectNode proposal(Long taskId, int index, ObjectNode request, ObjectNode proposal) {
        String type = proposal.path(KEY_TYPE).asText();
        ObjectNode component = NODES.objectNode().put(KEY_COMPONENT_KEY, AI_COMPONENT_KEY_PREFIX + taskId + STABLE_KEY_SEPARATOR + index)
                .put(KEY_COMPONENT_TYPE, type).put(KEY_SORT_ORDER, index * 1000).put(KEY_ENABLED, true);
        ObjectNode config = component.putObject(KEY_CONFIG);
        if (COMPONENT_PROFILE.equals(type)) { config.set(KEY_PROFILE, request.path(KEY_FACTS).path(KEY_PROFILE).deepCopy()); }
        if (COMPONENT_TEAM_PROFILE.equals(type)) { config.set(KEY_TEAM, request.path(KEY_FACTS).path(KEY_PROFILE).deepCopy()); }
        ObjectNode params = ((ObjectNode) proposal.path(KEY_PARAMS)).deepCopy();
        nodeKeys(params, AI_NODE_KEY_PREFIX + taskId + STABLE_KEY_SEPARATOR + index, new int[]{0});
        merge(config, params);
        if (COMPONENT_TEXT_GRID.equals(type)) { gridDefaults(config); }
        primary(type, config, proposal.path(KEY_SOURCE_NOS), request);
        // 生成组件的字体依赖在后续完整配置集合时统一合并。
        bindings(config, proposal.path(KEY_BINDINGS), request, null);
        return component;
    }

    /** 主来源只从冻结来源号映射真实 ID，不接受模型中的业务 ID。 */
    private static void primary(String type, ObjectNode config, JsonNode sourceNos, ObjectNode request) {
        if (sourceNos.isEmpty()) { return; }
        boolean team = OWNER_TEAM.equals(request.path(KEY_INPUT).path(KEY_OWNER_TYPE).asText());
        if (SINGLE_SOURCE.contains(type)) {
            JsonNode source = source(request, sourceNos.get(0).intValue());
            config.put(KEY_WORK_ID, source.path(KEY_SOURCE_ID).asLong());
            if (team) { config.put(KEY_MEMBER_USER_ID, source.path(KEY_MEMBER_USER_ID).asLong()); }
        } else if (MEMBER_SOURCES.contains(type) || team) {
            ArrayNode items = config.putArray(KEY_ITEMS);
            for (JsonNode n : sourceNos) {
                JsonNode source = source(request, n.intValue());
                items.addObject().put(KEY_MEMBER_USER_ID, source.path(KEY_MEMBER_USER_ID).asLong())
                        .put(MEMBER_SOURCES.contains(type) ? KEY_PORTFOLIO_ID : KEY_WORK_ID, source.path(KEY_SOURCE_ID).asLong());
            }
        } else {
            ArrayNode ids = NODES.arrayNode();
            for (JsonNode n : sourceNos) { ids.add(source(request, n.intValue()).path(KEY_SOURCE_ID).asLong()); }
            if (Set.of(COMPONENT_WORK_GRID, COMPONENT_WORK_LIST).contains(type)) {
                groupedWorks(config, ids);
            } else { config.set(KEY_WORK_IDS, ids); }
        }
    }

    /** 已有分组只逐位置重绑来源，保留组边界、名称和稳定键；不能表达的数量变化整份拒绝。 */
    private static void groupedWorks(ObjectNode config, ArrayNode ids) {
        JsonNode groups = config.path(KEY_GROUPS);
        if (groups.isArray() && !groups.isEmpty()) {
            int size = 0;
            for (JsonNode group : groups) { size += group.path(KEY_WORK_IDS).size(); }
            if (size != ids.size()) { throw invalid(); }
            int position = 0;
            for (JsonNode group : groups) {
                ArrayNode old = (ArrayNode) group.path(KEY_WORK_IDS);
                for (int i = 0; i < old.size(); i++) {
                    JsonNode next = ids.get(position++);
                    if (old.get(i).longValue() != next.longValue()) { old.set(i, next); }
                }
            }
            return;
        }
        // 新组件沿普通的单组默认语义编译，避免依赖兼容转换去掉显式 workIds。
        ObjectNode group = config.putArray(KEY_GROUPS).addObject().put(KEY_GROUP_KEY, DEFAULT_GROUP_KEY)
                .put(KEY_NAME, DEFAULT_GROUP_NAME).put(KEY_SORT_ORDER, 1000);
        group.set(KEY_WORK_IDS, ids); config.remove(KEY_WORK_IDS);
    }

    /** 中间协议未填行列辅助数组时，只补普通默认值，不改模型显式参数。 */
    private static void gridDefaults(ObjectNode config) {
        if (!config.has(KEY_COLUMN_WEIGHTS)) {
            ArrayNode values = config.putArray(KEY_COLUMN_WEIGHTS);
            for (int i = 0; i < config.path(KEY_COLUMNS).asInt(); i++) { values.add(1); }
        }
        if (!config.has(KEY_ROW_MIN_HEIGHTS_RPX)) {
            ArrayNode values = config.putArray(KEY_ROW_MIN_HEIGHTS_RPX);
            for (int i = 0; i < config.path(KEY_ROWS).asInt(); i++) { values.add(DEFAULT_GRID_ROW_HEIGHT); }
        }
    }

    /** 槽位绑定均从受控事实句柄读取原值，sourceNo 仅绑定合法作品。 */
    private static void bindings(ObjectNode target, JsonNode list, ObjectNode request, ObjectNode complete) {
        for (JsonNode binding : list) {
            String slot = binding.path(KEY_SLOT).asText();
            if (binding.has(KEY_SOURCE_NO)) {
                JsonNode source = source(request, binding.path(KEY_SOURCE_NO).intValue());
                target.put(slot, source.path(KEY_SOURCE_ID).asLong());
                if (OWNER_TEAM.equals(request.path(KEY_INPUT).path(KEY_OWNER_TYPE).asText()) && KEY_BACKGROUND_WORK_ID.equals(slot)) {
                    target.put(KEY_BACKGROUND_MEMBER_USER_ID, source.path(KEY_MEMBER_USER_ID).asLong());
                }
            } else {
                JsonNode resource = resource(request, binding.path(KEY_REF).asText());
                JsonNode value = resource.get(KEY_VALUE);
                if (value == null) { throw invalid(); }
                if (KEY_FONT_ID.equals(slot) && value.isObject()) {
                    target.set(slot, value.path(KEY_FONT_ID).deepCopy());
                    if (complete != null) { objectSection(complete, KEY_FONTS).set(value.path(KEY_FONT_ID).asText(), value.path(KEY_REFERENCE).deepCopy()); }
                } else { target.set(slot, value.deepCopy()); }
            }
        }
    }

    /** 不允许 remove 与该组件任何层级的修改组合，避免用操作次序掩盖冲突。 */
    private static void assertNoOverlappingRemoval(JsonNode operations) {
        for (JsonNode removal : operations) {
            if (!OP_REMOVE_COMPONENT.equals(removal.path(KEY_OP).asText())) { continue; }
            String alias = removal.path(KEY_TARGET).path(KEY_COMPONENT_KEY).asText();
            int same = 0;
            for (JsonNode other : operations) {
                if (alias.equals(other.path(KEY_TARGET).path(KEY_COMPONENT_KEY).asText())) { same++; }
            }
            if (same != 1) { throw invalid(); }
        }
    }

    /** 整体修改编译后，当前菜单之外只允许显式授权的全局字段及派生字体依赖变化。 */
    private static void assertProtectedScope(ObjectNode request, ObjectNode envelope, ObjectNode result) {
        if (MODE_CREATE.equals(request.path(KEY_INPUT).path(KEY_MODE).asText())) { return; }
        ObjectNode before = AiPortfolioProtocol.baseConfig(request).deepCopy();
        ObjectNode after = result.deepCopy();
        components(request, before).removeAll(); components(request, after).removeAll();
        // 字体版本表只允许按最终节点依赖和可信绑定确定性合并/裁剪。
        before.set(KEY_FONTS, after.path(KEY_FONTS).deepCopy());
        for (JsonNode operation : envelope.path(KEY_OPERATIONS)) {
            if (OP_UPDATE_GLOBAL.equals(operation.path(KEY_OP).asText())) {
                String section = operation.path(KEY_PAYLOAD).path(KEY_SECTION).asText();
                ObjectNode restored = objectSection(after, section);
                JsonNode original = before.path(section);
                for (JsonNode key : operation.path(KEY_PAYLOAD).path(KEY_BINDINGS)) { restore(restored, original, key.path(KEY_SLOT).asText()); }
                operation.path(KEY_PAYLOAD).path(KEY_PARAMS).fieldNames().forEachRemaining(key -> restore(restored, original, key));
            }
        }
        if (!before.equals(after)) { throw invalid(); }
    }

    /** 最终字体表仅保留真实使用且版本已冻结的依赖，禁止用最新版代替旧选择。 */
    private static void alignFontReferences(ObjectNode result, ObjectNode request, ObjectNode output) {
        JsonNode original = AiPortfolioProtocol.baseConfig(request).path(KEY_FONTS);
        ObjectNode candidates = original.isObject() ? (ObjectNode) original.deepCopy() : NODES.objectNode();
        for (JsonNode binding : request.path(KEY_FACTS).path(KEY_BINDINGS)) {
            if (RESOURCE_KIND_FONT.equals(binding.path(KEY_KIND).asText()) && containsRef(output, binding.path(KEY_REF).asText())) {
                JsonNode value = binding.path(KEY_VALUE);
                if (candidates.has(value.path(KEY_FONT_ID).asText())
                        && !candidates.path(value.path(KEY_FONT_ID).asText()).equals(value.path(KEY_REFERENCE))) {
                    // 同一 fontId 在普通配置只有一个版本槽位，不能连带改动其它未触及节点。
                    throw invalid();
                }
                candidates.set(value.path(KEY_FONT_ID).asText(), value.path(KEY_REFERENCE).deepCopy());
            }
        }
        Set<String> used = new LinkedHashSet<>(); collectFonts(result.path(KEY_COMPONENTS), used);
        for (JsonNode menu : result.path(KEY_BOTTOM_NAV).path(KEY_ITEMS)) { collectFonts(menu.path(KEY_COMPONENTS), used); }
        ObjectNode fonts = result.putObject(KEY_FONTS);
        for (String id : used) {
            if (SYSTEM_FONT_ID.equals(id)) { continue; }
            if (!candidates.has(id)) { throw invalid(); }
            fonts.set(id, candidates.get(id).deepCopy());
        }
    }

    /** 查找中间协议是否确实使用了某个受控字体 ref。 */
    private static boolean containsRef(JsonNode node, String ref) {
        if (node.isObject() && ref.equals(node.path(KEY_REF).asText())) { return true; }
        for (JsonNode child : node) { if (containsRef(child, ref)) { return true; } }
        return false;
    }

    /** 按实际普通节点收集最终字体选择，不扫描 share 等无关文本。 */
    private static void collectFonts(JsonNode node, Set<String> used) {
        if (node.isObject() && node.path(KEY_FONT_ID).isTextual() && !node.path(KEY_FONT_ID).asText().isBlank()) {
            used.add(node.path(KEY_FONT_ID).asText());
        }
        for (JsonNode child : node) { collectFonts(child, used); }
    }

    /** 还原被允许的精确全局字段后再比较保护范围，不忽略整个配置子树。 */
    private static void restore(ObjectNode target, JsonNode original, String key) {
        if (original.has(key)) { target.set(key, original.get(key)); } else { target.remove(key); }
    }

    /** 从冻结同一目标菜单找到编译副本中的正文数组。 */
    private static ArrayNode components(ObjectNode request, ObjectNode config) {
        ObjectNode proxy = request.deepCopy();
        ((ObjectNode) proxy.path(KEY_FACTS)).set(KEY_BASE_CONFIG, config);
        return AiPortfolioProtocol.currentComponents(proxy);
    }

    /** 组件别名转换为真实 key 后在可变正文中定位，移动不改变身份。 */
    private static ObjectNode locateComponent(ObjectNode request, ArrayNode current, String alias) {
        String key = AiPortfolioProtocol.resolveComponent(request, alias).path(KEY_COMPONENT_KEY).asText();
        return (ObjectNode) current.get(indexOf(current, key));
    }

    /** 未开放结构重写，因此内部索引与冻结别名保持一致，原节点 key 完整保留。 */
    private static ObjectNode locateNode(ObjectNode component, ObjectNode target) {
        ObjectNode node = (ObjectNode) component.path(KEY_CONFIG);
        for (String field : List.of(KEY_CELL_KEY, KEY_BLOCK_KEY, KEY_RUN_KEY)) {
            if (target.has(field)) {
                String array = switch (field) { case KEY_CELL_KEY -> KEY_CELLS; case KEY_BLOCK_KEY -> KEY_BLOCKS; default -> KEY_RUNS; };
                String alias = target.path(field).asText();
                node = (ObjectNode) node.path(array).get(Integer.parseInt(alias.substring(alias.lastIndexOf('_') + 1)) - 1);
            }
        }
        return node;
    }

    /** 按既有锚点插入或移动，禁止临时新 key 作为锚点。 */
    private static void insert(ArrayNode current, ObjectNode component, JsonNode payload, ObjectNode request) {
        String position = payload.path(KEY_POSITION).asText();
        int index = switch (position) {
            case POSITION_START -> 0;
            case POSITION_END -> current.size();
            case POSITION_BEFORE, POSITION_AFTER -> indexOf(current, AiPortfolioProtocol.resolveComponent(request,
                    payload.path(KEY_ANCHOR_COMPONENT_KEY).asText()).path(KEY_COMPONENT_KEY).asText()) + (POSITION_AFTER.equals(position) ? 1 : 0);
            default -> throw invalid();
        };
        List<JsonNode> items = new ArrayList<>(); current.forEach(items::add); items.add(index, component);
        current.removeAll(); items.forEach(current::add);
    }

    /** 保持字段粒度合并，未填写的手动参数不能被模型删掉。 */
    private static void merge(ObjectNode target, JsonNode values) { values.fields().forEachRemaining(e -> target.set(e.getKey(), e.getValue().deepCopy())); }
    /** 创建普通全局配置对象，现有值必须为对象。 */
    private static ObjectNode objectSection(ObjectNode config, String key) {
        JsonNode value = config.get(key);
        if (value == null || value.isNull()) { return config.putObject(key); }
        if (!value.isObject()) { throw invalid(); }
        return (ObjectNode) value;
    }
    /** 查找当前正文中的持久组件身份。 */
    private static int indexOf(ArrayNode current, String key) {
        for (int i = 0; i < current.size(); i++) { if (key.equals(current.get(i).path(KEY_COMPONENT_KEY).asText())) { return i; } }
        throw invalid();
    }
    /** 来源号只按已选快照映射，不使用数组下标。 */
    private static JsonNode source(ObjectNode request, int number) {
        for (JsonNode source : request.path(KEY_FACTS).path(KEY_SOURCES)) { if (source.path(KEY_SOURCE_NO).intValue() == number) { return source; } }
        throw invalid();
    }
    /** 原值只来自服务器冻结的绑定句柄。 */
    private static JsonNode resource(ObjectNode request, String ref) {
        for (JsonNode resource : request.path(KEY_FACTS).path(KEY_BINDINGS)) { if (ref.equals(resource.path(KEY_REF).asText())) { return resource; } }
        throw invalid();
    }
    /** 为模型新生成的文字节点分配稳定 key，不把 key 分配责任交给模型。 */
    private static void nodeKeys(ObjectNode node, String prefix, int[] sequence) {
        for (String array : List.of(KEY_CELLS, KEY_BLOCKS, KEY_RUNS)) {
            for (JsonNode raw : node.path(array)) {
                ObjectNode child = (ObjectNode) raw;
                String key = switch (array) { case KEY_CELLS -> KEY_CELL_KEY; case KEY_BLOCKS -> KEY_BLOCK_KEY; default -> KEY_RUN_KEY; };
                child.put(key, prefix + STABLE_KEY_SEPARATOR + (++sequence[0])); nodeKeys(child, prefix, sequence);
            }
        }
    }
    /** 只暴露候选不合法的安全摘要。 */
    private static BusinessException invalid() { return new BusinessException(PortfolioAiMessage.CANDIDATE_INVALID); }
}
