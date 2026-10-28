package com.jxc.wefolio.service.portfolioai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.common.PortfolioTextLineHeightSupport;
import com.jxc.wefolio.config.PortfolioAiProperties;
import com.jxc.wefolio.constant.TeamPortfolioConstants;
import com.jxc.wefolio.dict.PortfolioConfigScopeDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioAiMessage;
import com.jxc.wefolio.service.PortfolioConfigValidator;
import com.jxc.wefolio.service.PortfolioEditingCapabilities;
import com.jxc.wefolio.service.advancedportfolio.AdvancedVersionRegistry;
import com.jxc.wefolio.service.advancedportfolio.AdvancedPortfolioVersionSupport;
import com.jxc.wefolio.service.PortfolioHyperlinkGraphService;
import com.jxc.wefolio.service.PortfolioRenderService;
import com.jxc.wefolio.service.advancedportfolio.AdvancedPortfolioConfigGuard;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioConfigValidator;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioRenderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.jxc.wefolio.service.portfolioai.PortfolioAiConstants.*;

/** AI 基准和候选使用真实普通校验与只读渲染，不写作品集。 */
@Service
@RequiredArgsConstructor
public class PortfolioAiConfigService {
    /** 节点构建工厂。 */
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    /** 三类文字组件的行高字段路径后缀，普通规范化固定使用一位小数。 */
    private static final String LINE_HEIGHT_PATH_SUFFIX = "." + PortfolioTextLineHeightSupport.LINE_HEIGHT;
    /** 时间轴角色的原子字体选择字段。 */
    private static final String KEY_FONT_FAMILY = "fontFamily";
    /** 时间轴保真递归位置：ROLE。 */
    private static final String POSITION_ROLE = "ROLE";
    /** 时间轴保真递归位置：NODES。 */
    private static final String POSITION_NODES = "NODES";
    /** 时间轴保真递归位置：NODE。 */
    private static final String POSITION_NODE = "NODE";
    /** 时间轴保真递归位置：CONFIG。 */
    private static final String POSITION_CONFIG = "CONFIG";
    /** 时间轴保真递归位置：UNIFORM_STYLES。 */
    private static final String POSITION_UNIFORM_STYLES = "UNIFORM_STYLES";
    /** 时间轴保真递归位置：NODE_STYLES。 */
    private static final String POSITION_NODE_STYLES = "NODE_STYLES";
    /** 时间轴统一样式的固定文字角色。 */
    private static final Set<String> TIMELINE_UNIFORM_ROLES = Set.of("heading", "date", "title", "description");
    /** 时间轴节点只能覆盖日期、标题和描述。 */
    private static final Set<String> TIMELINE_NODE_ROLES = Set.of("date", "title", "description");
    /** AI 独立运行预算。 */
    private final PortfolioAiProperties properties;
    /** 个人完整业务校验器。 */
    private final PortfolioConfigValidator personal;
    /** 团队完整业务校验器。 */
    private final TeamPortfolioConfigValidator team;
    /** 内部链接范围与环路校验。 */
    private final PortfolioHyperlinkGraphService graph;
    /** 个人只读渲染器。 */
    private final PortfolioRenderService personalRenderer;
    /** 团队只读渲染器。 */
    private final TeamPortfolioRenderService teamRenderer;

    /** 检查并规范化完整配置，精确拒绝被普通兼容规则悄悄丢弃或强转的显式值。 */
    @Transactional(rollbackFor = Exception.class)
    public ObjectNode validate(ObjectNode input, PortfolioEntity portfolio, ObjectNode config) {
        int version = AdvancedVersionRegistry.requestVersion(input);
        AdvancedPortfolioVersionSupport.Metadata metadata = AdvancedPortfolioVersionSupport.extract(config, version);
        if (MODE_CREATE.equals(input.path(KEY_MODE).asText())) { metadata = new AdvancedPortfolioVersionSupport.Metadata(true, version); }
        if (portfolio != null) {
            AdvancedPortfolioVersionSupport.requireCanWrite(version, portfolio.getDraftConfigJson());
            metadata = AdvancedPortfolioVersionSupport.stored(portfolio.getDraftConfigJson());
        }
        ObjectNode expected = AdvancedPortfolioVersionSupport.restore(config, metadata);
        return normalize(input, portfolio, expected, portfolio == null ? null : portfolio.getDraftConfigJson(), AiPortfolioRunSpecRegistry.forVersion(version));
    }

    /** 生产查询从完整冻结请求取得限制，不能重新选择同一 C 的当前默认规格。 */
    @Transactional(rollbackFor = Exception.class)
    public ObjectNode validateCandidate(ObjectNode request, PortfolioEntity portfolio, ObjectNode candidate) {
        var spec = AiPortfolioRunSpecRegistry.resolve(request);
        ObjectNode frozenBase = AiPortfolioProtocol.baseConfig(request);
        if (!AdvancedPortfolioVersionSupport.stored(frozenBase).equals(AdvancedPortfolioVersionSupport.stored(candidate))) { throw invalid(); }
        return normalize((ObjectNode) request.path(KEY_INPUT), portfolio, candidate, frozenBase.toString(), spec);
    }

    /** 高级外层元数据与普通内容严格分离，完整保真在恢复元数据之后执行。 */
    private ObjectNode normalize(ObjectNode input, PortfolioEntity portfolio, ObjectNode config, String previousJson, AiPortfolioRunSpecRegistry.Spec spec) {
        int version = AdvancedVersionRegistry.requestVersion(input);
        boolean isTeam = PortfolioOwnerTypeDict.TEAM.getCode().equals(input.path(KEY_OWNER_TYPE).asText());
        var metadata = AdvancedPortfolioVersionSupport.extract(config, version);
        AdvancedPortfolioConfigGuard.validate(config, isTeam, version);
        ObjectNode content = AdvancedPortfolioVersionSupport.content(config);
        var capabilities = PortfolioEditingCapabilities.advanced(input.path(KEY_EDITOR_SCHEMA_REVISION).intValue(), version, isTeam);
        StrictAiJson.parseObject(config.toString(), properties.getMaxConfigBytes(), properties.getMaxJsonDepth());
        Long owner = PortfolioAiInput.id(input, KEY_OWNER_ID, false);
        ObjectNode normalized;
        if (isTeam) {
            TeamPortfolioConfigDto dto = JSON.parseObject(content.toString(), TeamPortfolioConfigDto.class);
            TeamPortfolioConfigDto previous = previousJson == null ? null : JSON.parseObject(previousJson, TeamPortfolioConfigDto.class);
            normalized = node(team.normalizeForDraft(dto, previous, context(input, portfolio), capabilities));
        } else {
            PortfolioConfigDto dto = JSON.parseObject(content.toString(), PortfolioConfigDto.class);
            PortfolioConfigDto previous = previousJson == null ? null : JSON.parseObject(previousJson, PortfolioConfigDto.class);
            PortfolioConfigDto value = personal.normalizeForDraft(owner, dto, previous, capabilities);
            normalized = node(value);
            if (portfolio != null) {
                var locked = graph.lockAdvancedUserGraph(owner, portfolio.getId());
                graph.validateReferences(locked, PortfolioConfigScopeDict.DRAFT.getCode(),
                        personal.buildReferences(portfolio.getId(), owner, PortfolioConfigScopeDict.DRAFT.getCode(), value), value);
            } else { requireUnsavedHyperlinkTargets(owner, normalized); }
        }
        normalized = AdvancedPortfolioVersionSupport.restore(normalized, metadata);
        requireExplicitValuesPreserved(config, normalized, "");
        if (countComponents(normalized) > spec.limit(KEY_MAX_COMPONENTS)) { throw new BusinessException(PortfolioAiMessage.CONFIG_LIMIT); }
        return normalized;
    }

    /** 新会话没有图中的源节点，但每个内部目标仍须当前本人普通已发布且可选择。 */
    private void requireUnsavedHyperlinkTargets(Long owner, ObjectNode config) {
        List<JsonNode> components = new ArrayList<>(); config.path(KEY_COMPONENTS).forEach(components::add);
        for (JsonNode menu : config.path(KEY_BOTTOM_NAV).path(KEY_ITEMS)) { menu.path(KEY_COMPONENTS).forEach(components::add); }
        for (JsonNode component : components) {
            JsonNode parameters = component.path(KEY_CONFIG);
            if (COMPONENT_HYPERLINK.equals(component.path(KEY_COMPONENT_TYPE).asText())
                    && ACTION_INTERNAL_PORTFOLIO.equals(parameters.path(KEY_ACTION_TYPE).asText())) {
                long targetId = parameters.path(KEY_TARGET_PORTFOLIO_ID).longValue();
                boolean selectable = graph.listTargets(owner, null, targetId, PortfolioTemplateTypeDict.ADVANCED.getCode())
                        .getPortfolios().stream().anyMatch(target -> target.isSelectable() && target.getPortfolioId() == targetId);
                if (!selectable) { throw invalid(); }
            }
        }
    }

    /** 构建新建会话的普通基础配置，最终 CREATE 候选会替换当前根正文。 */
    public ObjectNode initialConfig(ObjectNode input) {
        boolean isTeam = PortfolioOwnerTypeDict.TEAM.getCode().equals(input.path(KEY_OWNER_TYPE).asText());
        ObjectNode config = NODES.objectNode();
        config.put(AdvancedPortfolioVersionSupport.FIELD, AdvancedVersionRegistry.requestVersion(input));
        config.put(KEY_SCHEMA_VERSION, isTeam ? TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1 : PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1);
        config.put(KEY_EDITOR_SCHEMA_REVISION, input.path(KEY_EDITOR_SCHEMA_REVISION).intValue());
        config.putObject(KEY_FONTS);
        config.putObject(KEY_SHARE).put(KEY_TITLE, DEFAULT_PORTFOLIO_TITLE);
        ObjectNode component = config.putArray(KEY_COMPONENTS).addObject();
        component.put(KEY_COMPONENT_KEY, INITIAL_PROFILE_COMPONENT_KEY).put(KEY_COMPONENT_TYPE, isTeam ? COMPONENT_TEAM_PROFILE : COMPONENT_PROFILE)
                .put(KEY_SORT_ORDER, 1000).put(KEY_ENABLED, true).putObject(KEY_CONFIG);
        return config;
    }

    /** 将可用候选渲染为与普通预览相同的只读数据，未保存交互不产生真实事件。 */
    public Object render(ObjectNode input, PortfolioEntity portfolio, ObjectNode candidate) {
        if (PortfolioOwnerTypeDict.TEAM.getCode().equals(input.path(KEY_OWNER_TYPE).asText())) {
            return teamRenderer.render(candidate.toString(), context(input, portfolio));
        }
        PortfolioEntity previewContext = portfolio;
        if (previewContext == null) {
            previewContext = new PortfolioEntity();
            previewContext.setOwnerId(PortfolioAiInput.id(input, KEY_OWNER_ID, false));
            previewContext.setOwnerType(PortfolioOwnerTypeDict.USER.getCode());
            previewContext.setTemplateType(PortfolioTemplateTypeDict.ADVANCED.getCode());
            // ID 和分享码保持空值，只有真实归属用于只读资源与背景授权过滤。
        }
        return personalRenderer.render(previewContext, JSON.parseObject(candidate.toString(), PortfolioConfigDto.class), true, false, null, null);
    }

    /** 高级团队仅在独立上下文允许普通和高级成员个人入口。 */
    private TeamPortfolioComponentContext context(ObjectNode input, PortfolioEntity portfolio) {
        Long ownerId = PortfolioAiInput.id(input, KEY_OWNER_ID, false);
        return portfolio == null ? TeamPortfolioComponentContext.unsavedAdvancedCandidate(ownerId, input.path(KEY_BASE_DRAFT_REVISION).intValue())
                : new TeamPortfolioComponentContext(ownerId, portfolio.getId(), input.path(KEY_BASE_DRAFT_REVISION).intValue(), null, true);
    }

    /** 总组件数包含所有菜单，不能用单页上限绕过预算。 */
    static int countComponents(ObjectNode config) {
        int count = config.path(KEY_COMPONENTS).size();
        for (JsonNode item : config.path(KEY_BOTTOM_NAV).path(KEY_ITEMS)) { count += item.path(KEY_COMPONENTS).size(); }
        return count;
    }

    /** 逐个显式字段核对；仅允许已声明的 ID、颜色、标题与行高表示等价。 */
    static void requireExplicitValuesPreserved(JsonNode before, JsonNode after, String path) {
        requireExplicitValuesPreserved(before, after, path, null, "");
    }

    /** 仅为时间轴真实角色登记字体派生，正文和补丁继承仍保持完整保真。 */
    private static void requireExplicitValuesPreserved(JsonNode before, JsonNode after, String path,
                                                        String componentType, String position) {
        String currentType = before.isObject() && before.path(KEY_COMPONENT_TYPE).isTextual()
                ? before.path(KEY_COMPONENT_TYPE).asText() : componentType;
        boolean timeline = AiTimelineProtocol.TYPE.equals(currentType);
        if (path.equals(FONTS_PATH) && before.isObject() && before.isEmpty() && after.isNull()) {
            return; // 普通规范化把无依赖字体表保存为空值，不改变任何字体选择。
        }
        if (before.isObject()) {
            if (!after.isObject()) { throw normalization(ERROR_NORMALIZATION_TYPE_CHANGED); }
            JsonNode expected = timeline && POSITION_ROLE.equals(position) ? timelineFontExpectation(before, after) : before;
            expected.fields().forEachRemaining(e -> {
                if (KEY_NAME.equals(e.getKey()) && path.endsWith(COMPONENT_ITEM_PATH_SUFFIX) && !after.has(KEY_NAME)) {
                    return; // 已明确登记的编辑器组件名称元数据不进入普通持久 DTO。
                }
                if (!after.has(e.getKey())) { throw normalization(ERROR_NORMALIZATION_FIELD_DROPPED); }
                requireExplicitValuesPreserved(e.getValue(), after.get(e.getKey()), path + "." + e.getKey(), currentType,
                        timelinePosition(before, e.getKey(), position, timeline));
            });
        } else if (before.isArray()) {
            if (!after.isArray()) { throw normalization(ERROR_NORMALIZATION_TYPE_CHANGED); }
            if (before.size() != after.size()) { throw normalization(ERROR_NORMALIZATION_ARRAY_CHANGED); }
            for (int i = 0; i < before.size(); i++) {
                requireExplicitValuesPreserved(before.get(i), after.get(i), path + "[]", currentType, POSITION_NODES.equals(position) ? POSITION_NODE : "");
            }
        } else if (before.isIntegralNumber() && after.isIntegralNumber() && before.longValue() == after.longValue()) {
            // 整数容器宽度变化没有改变实际值。
        } else if (path.endsWith(LINE_HEIGHT_PATH_SUFFIX) && before.isNumber() && after.isNumber()
                && before.decimalValue().compareTo(after.decimalValue()) == 0) {
            // 合法整数行高如 2 会规范化为 2.0，只有精确数值相等才视为同一作者选择。
        } else if (before.isTextual() && path.endsWith(ID_FIELD_SUFFIX) && after.isIntegralNumber()
                && before.textValue().equals(after.asText())) {
            // 普通 HTTP ID 支持字符串，数据库配置保持既有整数表示。
        } else if (before.isTextual() && after.isTextual() && path.toLowerCase(Locale.ROOT).endsWith(KEY_COLOR)
                && before.textValue().equalsIgnoreCase(after.textValue())) {
            // 普通颜色规范化只调整十六进制大小写，实际颜色保持不变。
        } else if (before.isTextual() && after.isTextual() && path.endsWith(TITLE_PATH_SUFFIX) && !timeline
                && before.textValue().strip().equals(after.textValue())) {
            // 分享标题沿普通规则去掉首尾空白。
        } else if (!before.equals(after)) {
            throw normalization(before.getNodeType() != after.getNodeType()
                    ? ERROR_NORMALIZATION_TYPE_CHANGED : ERROR_NORMALIZATION_VALUE_CHANGED);
        }
    }

    /** 真实角色的远程字体只派生 SYSTEM 兜底，不能补出继承选择或改变身份。 */
    private static JsonNode timelineFontExpectation(JsonNode before, JsonNode after) {
        boolean selected = before.path(KEY_FONT_ID).isTextual() && !before.path(KEY_FONT_ID).asText().isBlank();
        if (!selected) {
            if (after.has(KEY_FONT_ID) || !before.has(KEY_FONT_FAMILY) && after.has(KEY_FONT_FAMILY)) {
                throw normalization(ERROR_NORMALIZATION_VALUE_CHANGED);
            }
            return before;
        }
        if (!before.path(KEY_FONT_ID).equals(after.path(KEY_FONT_ID))) { throw normalization(ERROR_NORMALIZATION_VALUE_CHANGED); }
        if (before.path(KEY_FONT_FAMILY).equals(after.path(KEY_FONT_FAMILY))) { return before; }
        if (!SYSTEM_FONT_ID.equals(after.path(KEY_FONT_FAMILY).asText())) { throw normalization(ERROR_NORMALIZATION_VALUE_CHANGED); }
        ObjectNode expected = (ObjectNode) before.deepCopy();
        if (expected.has(KEY_FONT_FAMILY)) { expected.put(KEY_FONT_FAMILY, SYSTEM_FONT_ID); }
        return expected;
    }

    /** 结构位置显式迁移，不按模糊路径后缀放行任意嵌套对象。 */
    private static String timelinePosition(JsonNode parent, String field, String position, boolean timeline) {
        if (!timeline) { return ""; }
        if (parent.has(KEY_COMPONENT_TYPE) && KEY_CONFIG.equals(field)) { return POSITION_CONFIG; }
        if (POSITION_CONFIG.equals(position) && AiTimelineProtocol.NODES.equals(field)) { return POSITION_NODES; }
        if (POSITION_CONFIG.equals(position) && AiTimelineProtocol.STYLES.equals(field)) { return POSITION_UNIFORM_STYLES; }
        if (POSITION_NODE.equals(position) && AiTimelineProtocol.STYLES.equals(field)) { return POSITION_NODE_STYLES; }
        if (POSITION_UNIFORM_STYLES.equals(position) && TIMELINE_UNIFORM_ROLES.contains(field)
                || POSITION_NODE_STYLES.equals(position) && TIMELINE_NODE_ROLES.contains(field)) { return POSITION_ROLE; }
        return "";
    }

    /** 由普通 DTO 序列化到受限 JSON，不丢失显式空值。 */
    private ObjectNode node(Object value) {
        return StrictAiJson.parseObject(JSON.toJSONString(value, JSONWriter.Feature.WriteNulls),
                properties.getMaxConfigBytes(), properties.getMaxJsonDepth());
    }
    /** 不对外暴露普通解析和规范化内部细节。 */
    private static BusinessException invalid() { return new BusinessException(PortfolioAiMessage.CANDIDATE_INVALID); }
    /** 仅记录固定规范化分类，不携带路径、未知字段名或实际字段值。 */
    private static PortfolioAiNormalizationException normalization(String code) { return new PortfolioAiNormalizationException(code); }
}
