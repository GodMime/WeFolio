package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 时间轴增量处理器：封闭目标、稳定节点身份、角色补丁及有界结构动作。 */
public final class AiTimelineProtocol {
    /** 普通组件类型和角色字段。 */
    public static final String TYPE = "TIMELINE";
    public static final String NODE_KEY = "nodeKey";
    public static final String TEXT_ROLE = "textRole";
    public static final String NODES = "nodes";
    public static final String STYLES = "textStyles";
    private static final String CONFIG = "config";
    private static final String FONT_ID = "fontId";
    private static final String FONT_FAMILY = "fontFamily";
    private static final String BINDINGS = "bindings";
    private static final String UPDATE_STRUCTURE = "UPDATE_STRUCTURE";
    private static final Set<String> ROLES = Set.of("heading", "date", "title", "description");
    private static final Set<String> NODE_ROLES = Set.of("date", "title", "description");
    private static final Set<String> POSITION = Set.of("START", "END", "BEFORE", "AFTER");

    /** 时间轴协议固定值：AFTER。 */
    private static final String VALUE_AFTER = "AFTER";
    /** 时间轴协议固定值：BIND_RESOURCES。 */
    private static final String VALUE_BIND_RESOURCES = "BIND_RESOURCES";
    /** 自定义节点使用日期字段承载原始标签，不增加第二份文字真值。 */
    private static final String VALUE_CUSTOM = "CUSTOM";
    /** 自定义节点标签的 Unicode 码点上限。 */
    private static final int MAX_CUSTOM_LABEL_CODE_POINTS = 20;
    /** 自定义标签为空白时的稳定提示。 */
    private static final String CUSTOM_LABEL_REQUIRED_MESSAGE = "时间轴自定义标签不能为空白";
    /** 自定义标签禁止控制符和换行时的稳定提示。 */
    private static final String CUSTOM_LABEL_SINGLE_LINE_MESSAGE = "时间轴自定义标签必须为单行且不包含控制字符";
    /** 时间轴协议固定值：DAY。 */
    private static final String VALUE_DAY = "DAY";
    /** 时间轴协议固定值：END。 */
    private static final String VALUE_END = "END";
    /** 时间轴协议固定值：FONT。 */
    private static final String VALUE_FONT = "FONT";
    /** 时间轴协议固定值：INSERT_TIMELINE_NODE。 */
    private static final String VALUE_INSERT_TIMELINE_NODE = "INSERT_TIMELINE_NODE";
    /** 时间轴协议固定值：INVALID_OUTPUT。 */
    private static final String VALUE_INVALID_OUTPUT = "INVALID_OUTPUT";
    /** 时间轴协议固定值：MONTH。 */
    private static final String VALUE_MONTH = "MONTH";
    /** 时间轴协议固定值：MOVE_TIMELINE_NODE。 */
    private static final String VALUE_MOVE_TIMELINE_NODE = "MOVE_TIMELINE_NODE";
    /** 时间轴协议固定值：REMOVE_TIMELINE_NODE。 */
    private static final String VALUE_REMOVE_TIMELINE_NODE = "REMOVE_TIMELINE_NODE";
    /** 时间轴协议固定值：RESET_TIMELINE_TEXT_STYLE。 */
    private static final String VALUE_RESET_TIMELINE_TEXT_STYLE = "RESET_TIMELINE_TEXT_STYLE";
    /** 时间轴协议固定值：START。 */
    private static final String VALUE_START = "START";
    /** 时间轴协议固定值：SYSTEM。 */
    private static final String VALUE_SYSTEM = "SYSTEM";
    /** 时间轴协议固定值：TIMELINE_NODE。 */
    private static final String VALUE_TIMELINE_NODE = "TIMELINE_NODE";
    /** 时间轴协议固定值：TIMELINE_TEXT_STYLE。 */
    private static final String VALUE_TIMELINE_TEXT_STYLE = "TIMELINE_TEXT_STYLE";
    /** 时间轴协议固定值：UPDATE_PARAMS。 */
    private static final String VALUE_UPDATE_PARAMS = "UPDATE_PARAMS";
    /** 时间轴协议固定值：UPDATE_TEXT。 */
    private static final String VALUE_UPDATE_TEXT = "UPDATE_TEXT";
    /** 时间轴协议固定值：YEAR。 */
    private static final String VALUE_YEAR = "YEAR";
    /** 时间轴协议固定值：[0-9]{4}。 */
    private static final String YEAR_PATTERN = "[0-9]{4}";
    /** 时间轴协议固定值：[0-9]{4}-[0-9]{2}。 */
    private static final String MONTH_PATTERN = "[0-9]{4}-[0-9]{2}";
    /** 时间轴协议固定值：[0-9]{4}-[0-9]{2}-[0-9]{2}。 */
    private static final String DAY_PATTERN = "[0-9]{4}-[0-9]{2}-[0-9]{2}";
    /** 时间轴协议字段：action。 */
    private static final String FIELD_ACTION = "action";
    /** 时间轴协议字段：allowedSlots。 */
    private static final String FIELD_ALLOWED_SLOTS = "allowedSlots";
    /** 时间轴协议字段：anchorNodeKey。 */
    private static final String FIELD_ANCHOR_NODE_KEY = "anchorNodeKey";
    /** 时间轴协议字段：color。 */
    private static final String FIELD_COLOR = "color";
    /** 时间轴协议字段：componentCatalog。 */
    private static final String FIELD_COMPONENT_CATALOG = "componentCatalog";
    /** 时间轴协议字段：componentKey。 */
    private static final String FIELD_COMPONENT_KEY = "componentKey";
    /** 时间轴协议字段：componentType。 */
    private static final String FIELD_COMPONENT_TYPE = "componentType";
    /** 时间轴协议字段：date。 */
    private static final String FIELD_DATE = "date";
    /** 时间轴协议字段：datePrecision。 */
    private static final String FIELD_DATE_PRECISION = "datePrecision";
    /** 时间轴协议字段：description。 */
    private static final String FIELD_DESCRIPTION = "description";
    /** 时间轴协议字段：direction。 */
    private static final String FIELD_DIRECTION = "direction";
    /** 时间轴协议字段：expectedHash。 */
    private static final String FIELD_EXPECTED_HASH = "expectedHash";
    /** 时间轴协议字段：facts。 */
    private static final String FIELD_FACTS = "facts";
    /** 时间轴协议字段：field。 */
    private static final String FIELD_FIELD = "field";
    /** 时间轴协议字段：fields。 */
    private static final String FIELD_FIELDS = "fields";
    /** 时间轴协议字段：font。 */
    private static final String FIELD_FONT = "font";
    /** 时间轴协议字段：fontSizeRpx。 */
    private static final String FIELD_FONT_SIZE_RPX = "fontSizeRpx";
    /** 时间轴协议字段：inherited。 */
    private static final String FIELD_INHERITED = "inherited";
    /** 时间轴协议字段：items。 */
    private static final String FIELD_ITEMS = "items";
    /** 时间轴协议字段：kind。 */
    private static final String FIELD_KIND = "kind";
    /** 时间轴协议字段：menuKey。 */
    private static final String FIELD_MENU_KEY = "menuKey";
    /** 时间轴协议字段：minGapRpx。 */
    private static final String FIELD_MIN_GAP_RPX = "minGapRpx";
    /** 时间轴协议字段：node。 */
    private static final String FIELD_NODE = "node";
    /** 时间轴协议字段：nodeParams。 */
    private static final String FIELD_NODE_PARAMS = "nodeParams";
    /** 时间轴协议固定值：node_。 */
    private static final String NODE_ALIAS_PREFIX = "node_";
    /** 时间轴协议固定值：node_[1-9][0-9]{0,2}。 */
    private static final String NODE_ALIAS_PATTERN = "node_[1-9][0-9]{0,2}";
    /** 时间轴协议字段：op。 */
    private static final String FIELD_OP = "op";
    /** 时间轴协议字段：params。 */
    private static final String FIELD_PARAMS = "params";
    /** 时间轴协议字段：payload。 */
    private static final String FIELD_PAYLOAD = "payload";
    /** 时间轴协议字段：position。 */
    private static final String FIELD_POSITION = "position";
    /** 时间轴协议字段：present。 */
    private static final String FIELD_PRESENT = "present";
    /** 时间轴协议字段：ref。 */
    private static final String FIELD_REF = "ref";
    /** 时间轴协议字段：reuseOnly。 */
    private static final String FIELD_REUSE_ONLY = "reuseOnly";
    /** 时间轴协议字段：showKicker。 */
    private static final String FIELD_SHOW_KICKER = "showKicker";
    /** 时间轴协议字段：slot。 */
    private static final String FIELD_SLOT = "slot";
    /** 时间轴协议字段：sourceNos。 */
    private static final String FIELD_SOURCE_NOS = "sourceNos";
    /** 时间轴协议字段：target。 */
    private static final String FIELD_TARGET = "target";
    /** 时间轴协议字段：title。 */
    private static final String FIELD_TITLE = "title";
    /** 时间轴协议字段：value。 */
    private static final String FIELD_VALUE = "value";
    /** 时间轴协议字段：variant。 */
    private static final String FIELD_VARIANT = "variant";
    private AiTimelineProtocol() { }

    /** 仅处理目录明确登记的时间轴。 */
    public static boolean matches(JsonNode component) { return TYPE.equals(component.path(FIELD_COMPONENT_TYPE).asText()); }

    /** 真实节点按冻结数组生成的别名定位，不接受真实 key。 */
    public static ObjectNode frozenNode(ObjectNode request, ObjectNode target) {
        ObjectNode component = AiPortfolioProtocol.resolveComponent(request, target.path(FIELD_COMPONENT_KEY).asText());
        ObjectNode config = (ObjectNode) component.path(CONFIG);
        if (!target.has(NODE_KEY)) { return config; }
        String alias = target.path(NODE_KEY).asText();
        require(alias.matches(NODE_ALIAS_PATTERN), "时间轴节点别名不合法");
        int index = Integer.parseInt(alias.substring(5)) - 1;
        require(config.path(NODES).isArray() && index < config.path(NODES).size(), "时间轴节点不存在");
        return (ObjectNode) config.path(NODES).get(index);
    }

    /** 角色不存在也能定位；返回副本以保证解析不会向冻结基准补字段。 */
    public static ObjectNode resolve(ObjectNode request, ObjectNode target) {
        validateTarget(target);
        ObjectNode node = frozenNode(request, target);
        if (!target.has(TEXT_ROLE)) { return node; }
        JsonNode patch = node.path(STYLES).path(target.path(TEXT_ROLE).asText());
        return patch.isObject() ? (ObjectNode) patch.deepCopy() : object();
    }

    /** 角色哈希区分未设置与显式空补丁，不混入继承结果。 */
    public static JsonNode hashBase(ObjectNode request, ObjectNode target) {
        if (!target.has(TEXT_ROLE)) { return frozenNode(request, target); }
        return patchBasis(frozenNode(request, target), target.path(TEXT_ROLE).asText());
    }

    private static ObjectNode patchBasis(JsonNode node, String role) {
        JsonNode patch = node.path(STYLES).get(role);
        ObjectNode basis = object().put(FIELD_PRESENT, patch != null);
        if (patch == null) { basis.putNull(FIELD_VALUE); } else { basis.set(FIELD_VALUE, patch.deepCopy()); }
        return basis;
    }

    /** 投影全部角色和节点，真实 key、字体身份以及嵌套原值不暴露给模型。 */
    public static void project(JsonNode config, ObjectNode params) {
        params.remove(STYLES); params.remove(NODES);
        projectRoles(config, params, false);
        ArrayNode nodes = params.putArray(NODES);
        for (int index = 0; index < config.path(NODES).size(); index++) {
            JsonNode original = config.path(NODES).get(index);
            ObjectNode node = nodes.addObject().put(NODE_KEY, NODE_ALIAS_PREFIX + (index + 1))
                    .put(FIELD_EXPECTED_HASH, AiPortfolioProtocol.expectedHash(original));
            for (String field : List.of(FIELD_DATE_PRECISION, FIELD_DATE, FIELD_TITLE, FIELD_DESCRIPTION)) { node.set(field, AiPortfolioProtocol.scrub(original.path(field))); }
            projectRoles(original, node, true);
        }
    }

    /** 原位字体句柄放入对应角色，模型无需接触真实节点或字体身份。 */
    public static void projectBindings(ObjectNode request, ObjectNode component, ObjectNode projected) {
        for (JsonNode binding : request.path(FIELD_FACTS).path(BINDINGS)) {
            if (!component.path(FIELD_COMPONENT_KEY).equals(binding.path(FIELD_COMPONENT_KEY)) || !binding.has(TEXT_ROLE)) { continue; }
            JsonNode roleParent = projected;
            if (binding.has(NODE_KEY)) {
                int index = index((ArrayNode) component.path(CONFIG).path(NODES), binding.path(NODE_KEY).asText());
                roleParent = projected.path(NODES).get(index);
            }
            JsonNode role = roleParent.path(STYLES).path(binding.path(TEXT_ROLE).asText());
            require(role.isObject(), "字体角色未登记");
            ((ObjectNode) role).withArray(BINDINGS).addObject().put(FIELD_SLOT, FONT_ID).put(FIELD_REF, binding.path(FIELD_REF).asText()).put(FIELD_REUSE_ONLY, true);
        }
    }

    private static void projectRoles(JsonNode original, ObjectNode projected, boolean node) {
        ObjectNode roles = projected.putObject(STYLES);
        for (String role : node ? NODE_ROLES : ROLES) {
            ObjectNode safe = roles.putObject(role).put(FIELD_EXPECTED_HASH, AiPortfolioProtocol.expectedHash(patchBasis(original, role)))
                    .put(FIELD_INHERITED, !original.path(STYLES).has(role));
            ObjectNode own = safe.putObject(FIELD_PARAMS);
            original.path(STYLES).path(role).fields().forEachRemaining(entry -> {
                if (!FONT_ID.equals(entry.getKey())) { own.set(entry.getKey(), entry.getValue().deepCopy()); }
            });
        }
    }

    /** 新提案已经过 JSON schema；继续检查日期、聚合文本及嵌套字体句柄。 */
    public static void validateProposal(ObjectNode request, JsonNode config) {
        validateContent(config, false);
        validateStyles(request, config.path(STYLES), null, null);
        for (JsonNode node : config.path(NODES)) { validateStyles(request, node.path(STYLES), null, null); }
    }

    /** 普通最终配置的日期和文字聚合限制，不因局部操作绕过。 */
    public static void validateContent(JsonNode config, boolean identities) {
        require(config.path(NODES).isArray() && !config.path(NODES).isEmpty() && config.path(NODES).size() <= 20, "时间轴节点必须为 1 至 20 个");
        int total = codePoints(config.path(FIELD_TITLE), 60, true);
        Set<String> keys = new java.util.HashSet<>();
        for (JsonNode node : config.path(NODES)) {
            validateDate(node);
            if (VALUE_CUSTOM.equals(node.path(FIELD_DATE_PRECISION).asText())) {
                total += codePoints(node.path(FIELD_DATE), MAX_CUSTOM_LABEL_CODE_POINTS, false);
            }
            total += codePoints(node.path(FIELD_TITLE), 60, false) + codePoints(node.path(FIELD_DESCRIPTION), 1000, false);
            require(!node.path(FIELD_TITLE).asText().isBlank() || !node.path(FIELD_DESCRIPTION).asText().isBlank(), "时间轴节点标题和描述不能同时为空白");
            if (identities) { require(node.path(NODE_KEY).isTextual() && !node.path(NODE_KEY).asText().isBlank()
                    && node.path(NODE_KEY).asText().length() <= 64 && keys.add(node.path(NODE_KEY).asText()), "时间轴节点身份不合法"); }
        }
        require(total <= 6000, "时间轴总文字超过 6000 码点");
    }

    private static int codePoints(JsonNode value, int limit, boolean optional) {
        if (optional && value.isMissingNode()) { return 0; }
        require(value.isTextual(), "时间轴文字必须为字符串");
        int count = value.textValue().codePointCount(0, value.textValue().length());
        require(count <= limit, "时间轴文字超过字段限制"); return count;
    }

    private static void validateDate(JsonNode node) {
        require(node.path(FIELD_DATE_PRECISION).isTextual() && node.path(FIELD_DATE).isTextual(), "时间轴日期及精度必填");
        String date = node.path(FIELD_DATE).asText(); String precision = node.path(FIELD_DATE_PRECISION).asText();
        try {
            switch (precision) {
                case VALUE_CUSTOM -> validateCustomLabel(node.path(FIELD_DATE));
                case VALUE_YEAR -> require(date.matches(YEAR_PATTERN) && Integer.parseInt(date) >= 1, "年份不合法");
                case VALUE_MONTH -> { require(date.matches(MONTH_PATTERN) && YearMonth.parse(date).getYear() >= 1, "年月不合法"); }
                case VALUE_DAY -> { require(date.matches(DAY_PATTERN) && LocalDate.parse(date).getYear() >= 1, "日期不合法"); }
                default -> throw invalid("日期精度不合法");
            }
        } catch (DateTimeParseException exception) { throw invalid("日期不合法"); }
    }

    /** 自定义标签按原文校验，不 trim、不解析日期、不改变空格。 */
    private static void validateCustomLabel(JsonNode value) {
        codePoints(value, MAX_CUSTOM_LABEL_CODE_POINTS, false);
        String label = value.textValue();
        require(label.codePoints().anyMatch(codePoint -> !isEcmaScriptWhitespace(codePoint)), CUSTOM_LABEL_REQUIRED_MESSAGE);
        require(label.codePoints().noneMatch(codePoint -> codePoint <= 0x1F || codePoint == 0x7F
                || codePoint == 0x85 || codePoint == 0x2028 || codePoint == 0x2029), CUSTOM_LABEL_SINGLE_LINE_MESSAGE);
    }

    /** 与小程序 String.trim 的空白集合一致，不把 Java 额外控制分隔符视为可保留空白。 */
    private static boolean isEcmaScriptWhitespace(int codePoint) {
        return codePoint >= 0x2000 && codePoint <= 0x200A || switch (codePoint) {
            case 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0x1680, 0x2028, 0x2029,
                    0x202F, 0x205F, 0x3000, 0xFEFF -> true;
            default -> false;
        };
    }

    /** 按冻结真实 key 定位候选节点；删除后不可引用，移动不会改变后续目标。 */
    private static ObjectNode currentNode(ObjectNode request, ObjectNode component, ObjectNode target) {
        ObjectNode config = (ObjectNode) component.path(CONFIG);
        if (!target.has(NODE_KEY)) { return config; }
        String key = frozenNode(request, target).path(NODE_KEY).asText();
        for (JsonNode node : config.path(NODES)) { if (key.equals(node.path(NODE_KEY).asText())) { return (ObjectNode) node; } }
        throw invalid("不能继续修改已删除节点");
    }

    /** 校验并模拟单次操作；编译和协议验证使用相同的有界功能处理器。 */
    public static void apply(ObjectNode request, ObjectNode component, ObjectNode operation, String newNodeKey) {
        ObjectNode target = (ObjectNode) operation.path(FIELD_TARGET);
        validateTarget(target);
        ObjectNode parent = currentNode(request, component, target);
        String op = operation.path(FIELD_OP).asText(); JsonNode payload = operation.path(FIELD_PAYLOAD);
        JsonNode rules = AiPortfolioRunSpecRegistry.resolve(request).rules();
        JsonNode definition = rules.path(FIELD_COMPONENT_CATALOG).path(TYPE);
        ObjectNode node = parent;
        if (target.has(TEXT_ROLE) && !UPDATE_STRUCTURE.equals(op)) {
            node = section(section(parent, STYLES), target.path(TEXT_ROLE).asText());
        }
        switch (op) {
            case VALUE_UPDATE_TEXT -> {
                require(!target.has(TEXT_ROLE), "样式角色不能修改正文");
                AiPortfolioProtocol.exact(payload, Set.of(FIELD_FIELD, FIELD_VALUE), Set.of());
                String field = payload.path(FIELD_FIELD).asText();
                require((target.has(NODE_KEY) ? Set.of(FIELD_TITLE, FIELD_DESCRIPTION) : Set.of(FIELD_TITLE)).contains(field), "时间轴文字字段不合法");
                JsonNode schema = target.has(NODE_KEY) ? rules.path(FIELD_NODE_PARAMS).path(VALUE_TIMELINE_NODE) : definition.path(FIELD_PARAMS);
                AiPortfolioProtocol.value(payload.path(FIELD_VALUE), schema.path(field)); node.set(field, payload.path(FIELD_VALUE).deepCopy());
            }
            case VALUE_UPDATE_PARAMS -> {
                AiPortfolioProtocol.exact(payload, Set.of(FIELD_PARAMS), Set.of());
                ObjectNode schema = (ObjectNode) (target.has(TEXT_ROLE) ? rules.path(FIELD_NODE_PARAMS).path(VALUE_TIMELINE_TEXT_STYLE)
                        : target.has(NODE_KEY) ? rules.path(FIELD_NODE_PARAMS).path(VALUE_TIMELINE_NODE) : definition.path(FIELD_PARAMS)).deepCopy();
                if (target.has(NODE_KEY) && !target.has(TEXT_ROLE)) {
                    schema.retain(FIELD_DATE_PRECISION, FIELD_DATE);
                    require(payload.path(FIELD_PARAMS).has(FIELD_DATE_PRECISION) && payload.path(FIELD_PARAMS).has(FIELD_DATE), "修改日期须同时声明精度和日期");
                } else if (!target.has(TEXT_ROLE)) { schema.retain(FIELD_VARIANT, FIELD_DIRECTION, FIELD_SHOW_KICKER, FIELD_MIN_GAP_RPX); }
                AiPortfolioProtocol.params(payload.path(FIELD_PARAMS), schema, false);
                if (target.has(TEXT_ROLE) && payload.path(FIELD_PARAMS).has(FONT_FAMILY)) { node.remove(FONT_ID); }
                merge(node, payload.path(FIELD_PARAMS));
            }
            case VALUE_BIND_RESOURCES -> {
                require(target.has(TEXT_ROLE), "时间轴字体须指定文字角色");
                AiPortfolioProtocol.exact(payload, Set.of(FIELD_SOURCE_NOS, BINDINGS), Set.of());
                require(payload.path(FIELD_SOURCE_NOS).isArray() && payload.path(FIELD_SOURCE_NOS).isEmpty(), "时间轴没有主素材");
                validateFontBindings(request, payload.path(BINDINGS), component, target);
                compileBindings(node, payload.path(BINDINGS), request);
            }
            case UPDATE_STRUCTURE -> structure(request, component, parent, target, payload, newNodeKey);
            default -> throw invalid("时间轴操作不支持该目标");
        }
    }

    private static void structure(ObjectNode request, ObjectNode component, ObjectNode parent, ObjectNode target, JsonNode payload, String key) {
        String action = payload.path(FIELD_ACTION).asText();
        ArrayNode nodes = (ArrayNode) component.path(CONFIG).path(NODES);
        switch (action) {
            case VALUE_INSERT_TIMELINE_NODE -> {
                require(!target.has(NODE_KEY) && !target.has(TEXT_ROLE), "新增节点须指定时间轴");
                AiPortfolioProtocol.exact(payload, Set.of(FIELD_ACTION, FIELD_POSITION, FIELD_ANCHOR_NODE_KEY, FIELD_NODE), Set.of());
                JsonNode schema = AiPortfolioRunSpecRegistry.resolve(request).rules().path(FIELD_COMPONENT_CATALOG).path(TYPE).path(FIELD_PARAMS).path(NODES).path(FIELD_ITEMS);
                AiPortfolioProtocol.value(payload.path(FIELD_NODE), schema);
                ObjectNode value = (ObjectNode) payload.path(FIELD_NODE).deepCopy();
                validateDate(value); validateStyles(request, value.path(STYLES), null, null);
                compileStyles(value, request); value.put(NODE_KEY, key);
                insert(request, component, target, nodes, value, payload);
            }
            case VALUE_REMOVE_TIMELINE_NODE -> {
                require(target.has(NODE_KEY) && !target.has(TEXT_ROLE), "删除须指定节点");
                AiPortfolioProtocol.exact(payload, Set.of(FIELD_ACTION), Set.of());
                nodes.remove(index(nodes, parent.path(NODE_KEY).asText()));
            }
            case VALUE_MOVE_TIMELINE_NODE -> {
                require(target.has(NODE_KEY) && !target.has(TEXT_ROLE), "移动须指定节点");
                AiPortfolioProtocol.exact(payload, Set.of(FIELD_ACTION, FIELD_POSITION, FIELD_ANCHOR_NODE_KEY), Set.of());
                require(!target.path(NODE_KEY).equals(payload.path(FIELD_ANCHOR_NODE_KEY)), "节点不能以自身为锚点");
                nodes.remove(index(nodes, parent.path(NODE_KEY).asText())); insert(request, component, target, nodes, parent, payload);
            }
            case VALUE_RESET_TIMELINE_TEXT_STYLE -> {
                require(target.has(TEXT_ROLE), "恢复样式须指定文字角色");
                AiPortfolioProtocol.exact(payload, Set.of(FIELD_ACTION), Set.of(FIELD_FIELDS));
                String role = target.path(TEXT_ROLE).asText();
                if (!payload.has(FIELD_FIELDS)) { if (parent.path(STYLES).isObject()) { ((ObjectNode) parent.path(STYLES)).remove(role); } return; }
                JsonNode fields = payload.path(FIELD_FIELDS); require(fields.isArray() && !fields.isEmpty() && fields.size() <= 3, "恢复字段不合法");
                Set<String> seen = new java.util.HashSet<>();
                for (JsonNode field : fields) {
                    require(field.isTextual() && Set.of(FIELD_FONT, FIELD_FONT_SIZE_RPX, FIELD_COLOR).contains(field.textValue()) && seen.add(field.textValue()), "恢复字段不合法");
                    if (parent.path(STYLES).path(role).isObject()) {
                        ObjectNode patch = (ObjectNode) parent.path(STYLES).path(role);
                        if (FIELD_FONT.equals(field.textValue())) { patch.remove(List.of(FONT_ID, FONT_FAMILY)); } else { patch.remove(field.textValue()); }
                    }
                }
            }
            default -> throw invalid("未登记的时间轴结构动作");
        }
    }

    private static void insert(ObjectNode request, ObjectNode component, ObjectNode target, ArrayNode nodes, ObjectNode value, JsonNode payload) {
        String position = payload.path(FIELD_POSITION).asText(); require(POSITION.contains(position), "插入位置不合法");
        int index;
        if (Set.of(VALUE_START, VALUE_END).contains(position)) {
            require(payload.path(FIELD_ANCHOR_NODE_KEY).isNull(), "首尾插入不接受锚点"); index = VALUE_START.equals(position) ? 0 : nodes.size();
        } else {
            ObjectNode anchor = target.deepCopy(); anchor.remove(TEXT_ROLE); anchor.put(NODE_KEY, payload.path(FIELD_ANCHOR_NODE_KEY).asText());
            String anchorKey = frozenNode(request, anchor).path(NODE_KEY).asText();
            index = index(nodes, anchorKey) + (VALUE_AFTER.equals(position) ? 1 : 0);
        }
        List<JsonNode> values = new ArrayList<>(); nodes.forEach(values::add); values.add(index, value); nodes.removeAll(); values.forEach(nodes::add);
    }

    /** 新组件和新节点编译嵌套 FONT，身份来自冻结资源。 */
    public static void compileStyles(ObjectNode config, ObjectNode request) {
        for (JsonNode patch : config.path(STYLES)) {
            JsonNode bindings = ((ObjectNode) patch).remove(BINDINGS);
            if (bindings != null) { compileBindings((ObjectNode) patch, bindings, request); }
        }
        for (JsonNode node : config.path(NODES)) { compileStyles((ObjectNode) node, request); }
    }

    private static void validateStyles(ObjectNode request, JsonNode styles, ObjectNode component, ObjectNode target) {
        for (JsonNode patch : styles) {
            if (patch.has(BINDINGS)) {
                require(!patch.has(FONT_FAMILY) || VALUE_SYSTEM.equals(patch.path(FONT_FAMILY).asText()), "内置字体和远程绑定不能同时声明");
                validateFontBindings(request, patch.path(BINDINGS), component, target);
            }
        }
    }

    private static void validateFontBindings(ObjectNode request, JsonNode bindings, ObjectNode component, ObjectNode target) {
        require(bindings.isArray() && bindings.size() <= 1, "字体绑定数量不合法");
        for (JsonNode binding : bindings) {
            AiPortfolioProtocol.exact(binding, Set.of(FIELD_SLOT, FIELD_REF), Set.of());
            require(FONT_ID.equals(binding.path(FIELD_SLOT).asText()), "时间轴仅接受字体槽位");
            JsonNode resource = resource(request, binding.path(FIELD_REF).asText());
            require(VALUE_FONT.equals(resource.path(FIELD_KIND).asText()) && hasFontSlot(resource.path(FIELD_ALLOWED_SLOTS)), "字体资源类型或槽位不合法");
            if (resource.path(FIELD_REUSE_ONLY).asBoolean()) {
                require(component != null && target != null && component.path(FIELD_COMPONENT_KEY).equals(resource.path(FIELD_COMPONENT_KEY))
                        && target.path(TEXT_ROLE).equals(resource.path(TEXT_ROLE)), "已有字体不能改变角色或组件");
                require(target.has(NODE_KEY) == resource.has(NODE_KEY), "已有字体不能改变节点层级");
                if (target.has(NODE_KEY)) { require(frozenNode(request, target).path(NODE_KEY).equals(resource.path(NODE_KEY)), "已有字体不能改变节点"); }
            }
        }
    }

    /** 槽位只按数组中的确切字符串匹配。 */
    private static boolean hasFontSlot(JsonNode slots) {
        if (!slots.isArray()) { return false; }
        for (JsonNode slot : slots) { if (slot.isTextual() && FONT_ID.equals(slot.textValue())) { return true; } }
        return false;
    }

    private static void compileBindings(ObjectNode patch, JsonNode bindings, ObjectNode request) {
        for (JsonNode binding : bindings) {
            patch.set(FONT_ID, resource(request, binding.path(FIELD_REF).asText()).path(FIELD_VALUE).path(FONT_ID).deepCopy());
            patch.put(FONT_FAMILY, VALUE_SYSTEM);
        }
    }

    private static JsonNode resource(ObjectNode request, String ref) {
        for (JsonNode resource : request.path(FIELD_FACTS).path(BINDINGS)) { if (ref.equals(resource.path(FIELD_REF).asText())) { return resource; } }
        throw invalid("字体句柄不存在");
    }

    private static void validateTarget(ObjectNode target) {
        AiPortfolioProtocol.exact(target, Set.of(FIELD_MENU_KEY, FIELD_COMPONENT_KEY), Set.of(NODE_KEY, TEXT_ROLE));
        if (target.has(TEXT_ROLE)) { require(target.path(TEXT_ROLE).isTextual() && (target.has(NODE_KEY) ? NODE_ROLES : ROLES).contains(target.path(TEXT_ROLE).asText()), "时间轴文字角色不合法"); }
    }
    private static int index(ArrayNode nodes, String key) {
        for (int i = 0; i < nodes.size(); i++) { if (key.equals(nodes.get(i).path(NODE_KEY).asText())) { return i; } }
        throw invalid("时间轴锚点已移除");
    }
    private static ObjectNode section(ObjectNode parent, String key) {
        if (!parent.has(key)) { return parent.putObject(key); }
        require(parent.path(key).isObject(), "时间轴补丁必须为对象"); return (ObjectNode) parent.path(key);
    }
    private static void merge(ObjectNode target, JsonNode values) { values.fields().forEachRemaining(entry -> target.set(entry.getKey(), entry.getValue().deepCopy())); }
    private static ObjectNode object() { return StrictAiJson.mapper().createObjectNode(); }
    private static void require(boolean condition, String message) { if (!condition) { throw invalid(message); } }
    private static AiProtocolException invalid(String message) { return new AiProtocolException(VALUE_INVALID_OUTPUT, message); }
}
