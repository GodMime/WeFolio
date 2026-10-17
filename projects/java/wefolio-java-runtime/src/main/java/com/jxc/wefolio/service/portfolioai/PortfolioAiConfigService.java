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

import static com.jxc.wefolio.service.portfolioai.PortfolioAiConstants.*;

/** AI 基准和候选使用真实普通校验与只读渲染，不写作品集。 */
@Service
@RequiredArgsConstructor
public class PortfolioAiConfigService {
    /** 节点构建工厂。 */
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    /** 三类文字组件的行高字段路径后缀，普通规范化固定使用一位小数。 */
    private static final String LINE_HEIGHT_PATH_SUFFIX = "." + PortfolioTextLineHeightSupport.LINE_HEIGHT;
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
        boolean isTeam = PortfolioOwnerTypeDict.TEAM.getCode().equals(input.path(KEY_OWNER_TYPE).asText());
        AdvancedPortfolioConfigGuard.validate(config, isTeam);
        StrictAiJson.parseObject(config.toString(), properties.getMaxConfigBytes(), properties.getMaxJsonDepth());
        Long owner = PortfolioAiInput.id(input, KEY_OWNER_ID, false);
        ObjectNode normalized;
        if (isTeam) {
            TeamPortfolioConfigDto dto = JSON.parseObject(config.toString(), TeamPortfolioConfigDto.class);
            TeamPortfolioConfigDto previous = portfolio == null ? null : JSON.parseObject(portfolio.getDraftConfigJson(), TeamPortfolioConfigDto.class);
            normalized = node(team.normalizeForDraft(dto, previous, context(input, portfolio)));
        } else {
            PortfolioConfigDto dto = JSON.parseObject(config.toString(), PortfolioConfigDto.class);
            PortfolioConfigDto previous = portfolio == null ? null : JSON.parseObject(portfolio.getDraftConfigJson(), PortfolioConfigDto.class);
            PortfolioConfigDto value = personal.normalizeForDraft(owner, dto, previous);
            normalized = node(value);
            if (portfolio != null) {
                var locked = graph.lockAdvancedUserGraph(owner, portfolio.getId());
                graph.validateReferences(locked, PortfolioConfigScopeDict.DRAFT.getCode(),
                        personal.buildReferences(portfolio.getId(), owner, PortfolioConfigScopeDict.DRAFT.getCode(), value), value);
            } else { requireUnsavedHyperlinkTargets(owner, normalized); }
        }
        requireExplicitValuesPreserved(config, normalized, "");
        if (countComponents(normalized) > AiPortfolioProtocol.limits().path(KEY_MAX_COMPONENTS).intValue()) { throw new BusinessException(PortfolioAiMessage.CONFIG_LIMIT); }
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
        if (path.equals(FONTS_PATH) && before.isObject() && before.isEmpty() && after.isNull()) {
            return; // 普通规范化把无依赖字体表保存为空值，不改变任何字体选择。
        }
        if (before.isObject()) {
            if (!after.isObject()) { throw normalization(ERROR_NORMALIZATION_TYPE_CHANGED); }
            before.fields().forEachRemaining(e -> {
                if (KEY_NAME.equals(e.getKey()) && path.endsWith(COMPONENT_ITEM_PATH_SUFFIX) && !after.has(KEY_NAME)) {
                    return; // 已明确登记的编辑器组件名称元数据不进入普通持久 DTO。
                }
                if (!after.has(e.getKey())) { throw normalization(ERROR_NORMALIZATION_FIELD_DROPPED); }
                requireExplicitValuesPreserved(e.getValue(), after.get(e.getKey()), path + "." + e.getKey());
            });
        } else if (before.isArray()) {
            if (!after.isArray()) { throw normalization(ERROR_NORMALIZATION_TYPE_CHANGED); }
            if (before.size() != after.size()) { throw normalization(ERROR_NORMALIZATION_ARRAY_CHANGED); }
            for (int i = 0; i < before.size(); i++) { requireExplicitValuesPreserved(before.get(i), after.get(i), path + "[]"); }
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
        } else if (before.isTextual() && after.isTextual() && path.endsWith(TITLE_PATH_SUFFIX)
                && before.textValue().strip().equals(after.textValue())) {
            // 分享标题沿普通规则去掉首尾空白。
        } else if (!before.equals(after)) {
            throw normalization(before.getNodeType() != after.getNodeType()
                    ? ERROR_NORMALIZATION_TYPE_CHANGED : ERROR_NORMALIZATION_VALUE_CHANGED);
        }
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
