package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.message.PortfolioAiBindingMessage;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 本工程复用的 AI 协议引擎，按冻结本地规格生成契约、投影并校验输出。 */
public final class AiPortfolioProtocol {
    /** 旧客户端和历史任务保留的 V1 中间协议。 */
    public static final String CONTRACT_VERSION = "ai-portfolio-v1";
    /** V1 长期保留的提示词标识。 */
    public static final String PROMPT_VERSION = "portfolio-ai-2026-10-10.2";
    /** 模型只看到当前菜单别名。 */
    public static final String MENU_ALIAS = "menu_current";
    /** 首次生成从用户意图编排正文，不把初始化占位组件当作既有页面。 */
    private static final String CREATE_MODE = "CREATE";
    /** 首次生成和整体描述修改页面两种模式。 */
    private static final Set<String> MODES = Set.of(CREATE_MODE, "MODIFY_PAGE");
    /** 不允许通过普通参数注入的身份、地址和结构字段。 */
    private static final Set<String> STRUCTURES = Set.of("blocks", "cells", "runs", "items", "type", "row", "column", "rowSpan", "columnSpan", "rows", "columns");
    /** 已实现的文字字段。 */
    private static final Set<String> TEXT_FIELDS = Set.of("content", "text", "items", "title", "description", "bio", "intro", "promptText");
    /** 可信规则资源路径。 */
    private static final String RESOURCE = "/portfolioai/contract-v1.json";
    /** 用户可见文案不得展示协议别名。 */
    private static final Pattern INTERNAL_ALIAS = Pattern.compile("(?:cmp_\\d+|menu_current|cell_\\d+|block_\\d+|run_\\d+|member_\\d+)");
    /** 时间轴内部别名只属于 V2 可见文案边界，保持 V1 原语义。 */
    private static final Pattern TIMELINE_ALIAS = Pattern.compile("node_\\d+");
    /** 原始地址不能由模型文案产生资源绑定。 */
    private static final Pattern RAW_URL = Pattern.compile("(?i)(?:https?://|javascript:|data:|file://|www\\.)");
    /** 作品和成员作品集拥有独立业务编号空间。 */
    private static final String WORK_SOURCE_KIND = "WORK";
    /** 仅向模型公开服务端冻结的作品媒体、封面 COS 直链。 */
    private static final Set<String> WORK_URL_FIELDS = Set.of("mediaUrl", "coverUrl");
    /** COS 公共访问直链必须使用 HTTPS。 */
    private static final String HTTPS_SCHEME = "https";
    /** 成员作品集整体来源，不展开成员作品。 */
    private static final String MEMBER_SOURCE_KIND = "MEMBER_PORTFOLIO";
    /** 使用成员作品集来源的组件类型。 */
    private static final Set<String> MEMBER_COMPONENT_TYPES = Set.of("MEMBER_PORTFOLIO_LIST", "MEMBER_PORTFOLIO_GRID");
    /** 团队成员别名只在同一冻结来源首次出现顺序内分配，不暴露用户身份。 */
    private static final String MEMBER_ALIAS_PREFIX = "member_";
    /** 素材错误仍使用原有分类，不改变任务重试及前端映射。 */
    private static final String INVALID_OUTPUT_CODE = "INVALID_OUTPUT";
    /** 首次生成组件的固定字段路径模板，索引从零开始。 */
    private static final String CREATE_COMPONENT_PATH = "page.components[%d]";
    /** 新建组件操作的固定字段路径模板。 */
    private static final String INSERT_COMPONENT_PATH = "operations[%d].payload.component";
    /** 已有组件重绑及全局设置操作的固定字段路径模板。 */
    private static final String OPERATION_PAYLOAD_PATH = "operations[%d].payload";
    /** 全局设置的固定路径前缀。 */
    private static final String CREATE_SETTING_PATH = "page.settings.";
    /** 主素材数组字段。 */
    private static final String SOURCE_NUMBERS_FIELD = "sourceNos";
    /** 资源槽位数组字段。 */
    private static final String BINDINGS_FIELD = "bindings";
    /** 文字背景只能通过该独立槽位绑定。 */
    private static final String BACKGROUND_WORK_SLOT = "backgroundWorkId";
    /** 新建结构化文字组件的块级字体与组件级背景独立绑定。 */
    private static final String STRUCTURED_TEXT_COMPONENT = "STRUCTURED_TEXT_SECTION";
    /** 结构化文字块数组字段。 */
    private static final String BLOCKS_FIELD = "blocks";
    /** 只有可信 FONT 句柄可用于文字节点字体。 */
    private static final String FONT_KIND = "FONT";
    /** 字体资源写入普通配置的固定槽位。 */
    private static final String FONT_SLOT = "fontId";
    /** 可信槽位目录使用资源类型字段校验句柄。 */
    private static final String RESOURCE_KIND_FIELD = "kind";
    /** 新建组件内部文字块的固定字段路径模板。 */
    private static final String STRUCTURED_BLOCK_PATH = ".params.blocks[%d]";
    /** 目录在进程启动时读取，调用时返回独立副本。 */
    private static final ObjectNode RULES = loadRules();

    private AiPortfolioProtocol() { }

    /** 读取两工程完全相同的版本化规则，不从请求或模型接收白名单。 */
    private static ObjectNode loadRules() {
        try (InputStream stream = AiPortfolioProtocol.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) { throw new IllegalStateException("AI 可信目录缺失"); }
            return StrictAiJson.parseObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8),
                    StrictAiJson.CONFIG_MAX_BYTES, StrictAiJson.MAX_DEPTH);
        } catch (IOException exception) { throw new IllegalStateException("AI 可信目录无法读取"); }
    }

    /** 验证冻结版本，版本不匹配不能改用新规则解释。 */
    public static void checkVersion(ObjectNode request) {
        AiPortfolioRunSpecRegistry.resolve(request);
        require(MODES.contains(request.path("input").path("mode").asText()), "INVALID_OUTPUT", "任务模式不支持");
    }

    /** 生成只包含已实现字段与操作的可信契约，目录不含任何业务原文。 */
    public static ObjectNode contract(ObjectNode request) {
        checkVersion(request);
        String mode = request.path("input").path("mode").asText();
        var spec = AiPortfolioRunSpecRegistry.resolve(request);
        ObjectNode rules = spec.rules();
        ObjectNode result = object();
        result.put("schemaVersion", spec.contractVersion()).put("promptVersion", spec.promptVersion()).put("mode", mode);
        ArrayNode ops = result.putArray("allowedOps");
        if ("MODIFY_PAGE".equals(mode)) {
            ops.add("UPDATE_TEXT").add("UPDATE_PARAMS").add("BIND_RESOURCES")
                    .add("MOVE_COMPONENT").add("INSERT_COMPONENT").add("REMOVE_COMPONENT").add("UPDATE_GLOBAL");
        }
        ObjectNode catalog = rules.withObject("componentCatalog").deepCopy();
        if ("TEAM".equals(request.path("input").path("ownerType").asText())) {
            catalog.remove(Set.of("PROFILE", "WORK_GRID", "WORK_LIST", "HYPERLINK"));
        } else { catalog.remove(Set.of("TEAM_PROFILE", "MEMBER_PORTFOLIO_GRID", "MEMBER_PORTFOLIO_LIST")); }
        boolean textOnly = AiPortfolioRunSpecRegistry.TEXT_ONLY.equals(request.path("input").path(AiPortfolioRunSpecRegistry.SOURCE_MODE).asText());
        if (textOnly) {
            require(spec.advancedVersion() >= 2 && CREATE_MODE.equals(mode) && request.path("facts").path("sources").isArray() && request.path("facts").path("sources").isEmpty(), "INVALID_OUTPUT", "纯文字模式仅支持新建");
            catalog.retain("TIMELINE", "TEXT_SECTION", "STRUCTURED_TEXT_SECTION", "TEXT_GRID");
            catalog.forEach(component -> ((ObjectNode) component.path("bindingSlots")).retain("fontId"));
        }
        result.set("componentCatalog", catalog);
        ObjectNode settings = rules.withObject("settings").deepCopy();
        if ("TEAM".equals(request.path("input").path("ownerType").asText())) {
            ((ObjectNode) settings.path("style").path("params")).remove("componentSpacingRpx");
            ((ObjectNode) settings.path("share").path("bindingSlots")).remove("avatarUrl");
            ((ObjectNode) settings.path("share").path("params")).set("description", object().put("type", "string").put("maxCodePoints", 2000)
                    .put("description", "团队作品集的整体简介；不替代正文中的团队介绍或文字组件。"));
        }
        if (textOnly) {
            settings.remove("backgroundAudio");
            settings.forEach(setting -> ((ObjectNode) setting.path("bindingSlots")).removeAll());
        }
        result.set("createSettings", "CREATE".equals(mode) ? settings.deepCopy() : object());
        result.set("globalFields", "MODIFY_PAGE".equals(mode) ? settings.deepCopy() : object());
        // 未实现的结构动作不进入模型契约，避免模型许诺不可落地的修改。
        result.set("structureActions", rules.path("structureActions").isObject() ? rules.path("structureActions").deepCopy() : object());
        if (spec.advancedVersion() >= 2 && "MODIFY_PAGE".equals(mode)) { ops.add("UPDATE_STRUCTURE"); }
        result.set("nodeParams", rules.path("nodeParams").deepCopy());
        result.set("limits", rules.path("limits").deepCopy());
        return result;
    }

    /** 读取规范化完整基准，CREATE 的原始 input.baseConfig 仍可保持 null。 */
    public static ObjectNode baseConfig(ObjectNode request) {
        JsonNode base = request.path("facts").path("baseConfig");
        if (!base.isObject()) { base = request.path("input").path("baseConfig"); }
        require(base.isObject(), "CONTRACT_UNAVAILABLE", "缺少完整作品集基准");
        return (ObjectNode) base;
    }

    /** 按当前菜单读取正文，首菜单正文仍存根 components。 */
    public static ArrayNode currentComponents(ObjectNode request) {
        ObjectNode base = baseConfig(request);
        String target = request.path("input").path("targetMenuKey").asText("__root__");
        JsonNode components = base.path("components");
        if (!"__root__".equals(target) && base.path("bottomNav").path("enabled").asBoolean()) {
            JsonNode items = base.path("bottomNav").path("items");
            int index = 0; boolean found = false;
            for (JsonNode item : items) {
                if (target.equals(item.path("key").asText())) {
                    components = index == 0 ? base.path("components") : item.path("components"); found = true; break;
                }
                index++;
            }
            require(found, "INVALID_OUTPUT", "目标菜单不存在");
        } else { require("__root__".equals(target), "INVALID_OUTPUT", "目标菜单不存在"); }
        require(components.isArray(), "INVALID_OUTPUT", "菜单正文必须为数组");
        return (ArrayNode) components;
    }

    /** 从确定性别名解析原始组件，拒绝数组路径和真实 key。 */
    public static ObjectNode resolveComponent(ObjectNode request, String alias) {
        return indexed(currentComponents(request), alias, "cmp_");
    }

    /** 解析文字内部节点，调用方仅修改该节点且保留其它结构。 */
    public static ObjectNode resolveNode(ObjectNode request, ObjectNode target) {
        ObjectNode component = resolveComponent(request, target.path("componentKey").asText());
        if (AiTimelineProtocol.matches(component)) { return AiTimelineProtocol.resolve(request, target); }
        ObjectNode node = (ObjectNode) component.path("config");
        if (target.has("cellKey")) { node = indexed(node.path("cells"), target.path("cellKey").asText(), "cell_"); }
        if (target.has("blockKey")) { node = indexed(node.path("blocks"), target.path("blockKey").asText(), "block_"); }
        if (target.has("runKey")) { node = indexed(node.path("runs"), target.path("runKey").asText(), "run_"); }
        return node;
    }

    /** 别名按原始数组顺序生成且不可跳到其它父节点。 */
    private static ObjectNode indexed(JsonNode array, String alias, String prefix) {
        require(array.isArray() && alias.matches(Pattern.quote(prefix) + "[1-9][0-9]{0,2}"), "INVALID_OUTPUT", "目标别名不存在");
        int index = Integer.parseInt(alias.substring(prefix.length())) - 1;
        require(index < array.size() && array.get(index).isObject(), "INVALID_OUTPUT", "目标别名不存在");
        return (ObjectNode) array.get(index);
    }

    /** 对完整原始目标对象计算 opaque 基准，不从模型计算结果接受 hash。 */
    public static String expectedHash(JsonNode original) { return StrictAiJson.canonicalHash(original); }

    /** 隐藏真实身份、联系方式和自由地址，仅保留已授权作品的 COS 直链。 */
    public static ObjectNode project(ObjectNode request) {
        ObjectNode rule = contract(request);
        ObjectNode input = (ObjectNode) request.path("input");
        limitedArray(request.path("facts").path("sources"), limit(request, "maxSourceCount"));
        ObjectNode result = object();
        result.put("schemaVersion", rule.path("schemaVersion").asText()).put("promptVersion", rule.path("promptVersion").asText());
        if (AiPortfolioRunSpecRegistry.resolve(request).advancedVersion() >= 2) { result.put(AiPortfolioRunSpecRegistry.SOURCE_MODE, input.path(AiPortfolioRunSpecRegistry.SOURCE_MODE).asText(AiPortfolioRunSpecRegistry.SELECTED_SOURCES)); }
        result.put("mode", input.path("mode").asText()).put("ownerType", "TEAM".equals(input.path("ownerType").asText()) ? "TEAM" : "PERSONAL");
        result.put("userIntent", safeText(request.path("facts").path("safePrompt").asText(input.path("prompt").asText())));
        result.putObject("target").put("menuKey", MENU_ALIAS);
        // CREATE 的基准正文仅供服务端校验与编译使用，不能引导模型保留默认资料或复制旧正文。
        ArrayNode originals = CREATE_MODE.equals(input.path("mode").asText()) ? array() : currentComponents(request);
        ArrayNode components = array();
        for (int i = 0; i < originals.size(); i++) {
            ObjectNode original = (ObjectNode) originals.get(i);
            String alias = "cmp_" + (i + 1);
            ObjectNode projected = object().put("componentKey", alias)
                    .put("type", original.path("componentType").asText())
                    .put("displayLabel", "页面第" + (i + 1) + "项：" + displayName(original.path("componentType").asText()))
                    .put("expectedHash", expectedHash(original));
            JsonNode schema = rule.path("componentCatalog").path(original.path("componentType").asText()).path("params");
            ObjectNode params = projected.putObject("params");
            original.path("config").fields().forEachRemaining(field -> {
                if (schema.has(field.getKey()) && !Set.of("blocks", "cells", "nodes", "textStyles").contains(field.getKey())) {
                    params.set(field.getKey(), scrub(field.getValue()));
                }
            });
            if ("PROFILE".equals(original.path("componentType").asText()) && original.path("config").path("profile").path("bio").isTextual()) {
                params.put("bio", safeText(original.path("config").path("profile").path("bio").asText()));
            }
            if ("TEAM_PROFILE".equals(original.path("componentType").asText()) && original.path("config").path("team").path("intro").isTextual()) {
                params.put("intro", safeText(original.path("config").path("team").path("intro").asText()));
            }
            projectNodes(original.path("config"), params, rule.path("nodeParams"));
            if (AiTimelineProtocol.matches(original)) { AiTimelineProtocol.project(original.path("config"), params); AiTimelineProtocol.projectBindings(request, original, params); }
            projected.set("sourceNos", existingSourceNos(original, request.path("facts").path("sources"),
                    "TEAM".equals(input.path("ownerType").asText())));
            ArrayNode bindings = projected.putArray("bindings");
            for (JsonNode binding : request.path("facts").path("bindings")) {
                if (!AiTimelineProtocol.matches(original) && original.path("componentKey").asText().equals(binding.path("componentKey").asText())) {
                    for (JsonNode slot : binding.path("allowedSlots")) {
                        bindings.add(object().put("slot", slot.asText()).put("ref", binding.path("ref").asText())
                                .put("reuseOnly", binding.path("reuseOnly").asBoolean()));
                    }
                }
            }
            components.add(projected);
        }
        ObjectNode page = result.putObject("currentPage").put("menuKey", MENU_ALIAS).put("expectedHash", expectedHash(originals));
        page.set("components", components);
        ArrayNode sources = result.putArray("sources");
        Map<String, String> memberAliases = new LinkedHashMap<>();
        for (JsonNode source : request.path("facts").path("sources")) {
            ObjectNode summary = object();
            for (String field : Set.of("sourceNo", "kind", "mediaType", "title", "tags")) {
                if (source.has(field)) { summary.set(field, scrub(source.get(field))); }
            }
            summary.put("availability", source.path("canBind").asBoolean(true) ? "SELECTED" : "UNAVAILABLE");
            summary.put("canBind", source.path("canBind").asBoolean(true));
            if (WORK_SOURCE_KIND.equals(source.path("kind").asText()) && source.path("canBind").asBoolean(false)) {
                for (String field : WORK_URL_FIELDS) { projectWorkUrl(source, summary, field); }
            }
            String memberId = source.path("memberUserId").asText("");
            if ("TEAM".equals(input.path("ownerType").asText()) && source.path("canBind").asBoolean(true)
                    && !memberId.isBlank()) {
                String memberRef = memberAliases.computeIfAbsent(memberId,
                        ignored -> MEMBER_ALIAS_PREFIX + (memberAliases.size() + 1));
                summary.put("memberRef", memberRef);
                if (source.path("memberName").isTextual()) { summary.put("memberName", safeText(source.path("memberName").textValue())); }
            }
            sources.add(summary);
        }
        ArrayNode resources = result.putArray("resources");
        for (JsonNode binding : request.path("facts").path("bindings")) {
            if (AiPortfolioRunSpecRegistry.TEXT_ONLY.equals(input.path(AiPortfolioRunSpecRegistry.SOURCE_MODE).asText())
                    && !FONT_KIND.equals(binding.path("kind").asText())) { continue; }
            ObjectNode safe = object();
            for (String field : Set.of("ref", "kind", "allowedSlots", "reuseOnly", "label", "sourceNo")) {
                if (binding.has(field)) { safe.set(field, scrub(binding.get(field))); }
            }
            resources.add(safe);
        }
        ArrayNode facts = result.putArray("facts");
        JsonNode profile = request.path("facts").path("profile");
        for (String field : Set.of("nickname", "nickName", "displayName", "bio", "intro", "profession", "city", "teamName")) {
            if (!AiPortfolioRunSpecRegistry.TEXT_ONLY.equals(input.path(AiPortfolioRunSpecRegistry.SOURCE_MODE).asText()) && profile.path(field).isTextual()) { facts.add(object().put("kind", "PROFILE_TEXT").put("field", field).put("displayText", safeText(profile.path(field).asText()))); }
        }
        ObjectNode globals = result.putObject("globalState");
        for (String section : Set.of("share", "style", "backgroundAudio")) {
            JsonNode value = baseConfig(request).path(section);
            // 基准未配置的设置也提供同一缺失值的 hash；字段按当前归属契约投影，包含团队简介。
            ObjectNode safe = globals.putObject(section).put("expectedHash", expectedHash(value));
            ObjectNode params = safe.putObject("params");
            JsonNode schema = rule.path("CREATE".equals(input.path("mode").asText()) ? "createSettings" : "globalFields")
                    .path(section).path("params");
            value.fields().forEachRemaining(field -> {
                if (schema.has(field.getKey())) { params.set(field.getKey(), scrub(field.getValue())); }
            });
        }
        JsonNode answer = request.path("facts").get("safeClarificationAnswer");
        if (answer != null) { result.set("clarificationAnswer", scrub(answer)); }
        return result;
    }

    /** 地址必须是无凭据、无签名查询的公共直链；损坏快照不向模型泄漏地址。 */
    private static void projectWorkUrl(JsonNode source, ObjectNode summary, String field) {
        JsonNode value = source.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) { return; }
        try {
            URI uri = URI.create(value.textValue());
            if (HTTPS_SCHEME.equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null) {
                summary.put(field, value.textValue());
            }
        } catch (IllegalArgumentException ignored) {
            // 不记录地址原文；合法素材号仍可用于页面编排。
        }
    }

    /** 为已存在文字节点生成稳定别名与节点原始基准，不暴露持久 key。 */
    private static void projectNodes(JsonNode original, ObjectNode projected, JsonNode nodeParams) {
        for (String field : Set.of("cells", "blocks", "runs")) {
            if (!original.path(field).isArray()) { continue; }
            ArrayNode nodes = projected.putArray(field);
            String aliasKey = switch (field) { case "cells" -> "cellKey"; case "blocks" -> "blockKey"; default -> "runKey"; };
            String prefix = aliasKey.substring(0, aliasKey.length() - 3) + "_";
            for (int i = 0; i < original.path(field).size(); i++) {
                JsonNode raw = original.path(field).get(i);
                ObjectNode safe = object().put(aliasKey, prefix + (i + 1)).put("expectedHash", expectedHash(raw));
                String type = switch (field) { case "cells" -> "GRID_CELL"; case "runs" -> "GRID_RUN";
                    default -> raw.has("runs") ? "GRID_BLOCK" : "STRUCTURED_BLOCK"; };
                raw.fields().forEachRemaining(entry -> {
                    if (nodeParams.path(type).has(entry.getKey())
                            && !Set.of("cells", "blocks", "runs").contains(entry.getKey())) {
                        safe.set(entry.getKey(), scrub(entry.getValue()));
                    }
                });
                projectNodes(raw, safe, nodeParams); nodes.add(safe);
            }
        }
    }

    /** 映射已有主资源号码；未在当前来源池的既有引用不被重新激活。 */
    private static ArrayNode existingSourceNos(JsonNode component, JsonNode sources, boolean team) {
        ArrayNode result = array();
        JsonNode config = component.path("config");
        String kind = MEMBER_COMPONENT_TYPES.contains(component.path("componentType").asText())
                ? MEMBER_SOURCE_KIND : WORK_SOURCE_KIND;
        Set<OriginalSource> originals = new LinkedHashSet<>();
        if (WORK_SOURCE_KIND.equals(kind)) {
            collectExistingWorks(config, team, originals);
        }
        config.path("items").forEach(item -> {
            JsonNode id = item.get(MEMBER_SOURCE_KIND.equals(kind) ? "portfolioId" : "workId");
            if (id != null && !id.isNull()) {
                originals.add(new OriginalSource(kind, id.asText(), team ? item.path("memberUserId").asText() : ""));
            }
        });
        for (OriginalSource original : originals) {
            for (JsonNode source : sources) {
                OriginalSource candidate = new OriginalSource(source.path("kind").asText(), source.path("sourceId").asText(),
                        team ? source.path("memberUserId").asText() : "");
                if (original.equals(candidate)) { result.add(source.get("sourceNo")); break; }
            }
        }
        return result;
    }

    /** 普通分组作品读取真实展示顺序；团队来源同时冻结成员归属，避免同号误匹配。 */
    private static void collectExistingWorks(JsonNode config, boolean team, Set<OriginalSource> originals) {
        String member = team ? config.path("memberUserId").asText() : "";
        if (config.hasNonNull("workId")) {
            originals.add(new OriginalSource(WORK_SOURCE_KIND, config.path("workId").asText(), member));
        }
        config.path("workIds").forEach(id -> originals.add(new OriginalSource(WORK_SOURCE_KIND, id.asText(), member)));
        config.path("groups").forEach(group -> collectExistingWorks(group, team, originals));
    }

    /** 相同数值 ID 不能跨作品、成员作品集或成员身份互相替代。 */
    private record OriginalSource(String kind, String id, String memberId) { }

    /** 模型输出先严格解析，再按封闭字段、类型、来源及操作范围静态检查。 */
    public static ObjectNode validate(ObjectNode request, String raw) {
        return validateOutput(raw, request);
    }

    /** 对数据库或供应商返回也重复执行相同校验，不依赖宽松 DTO。 */
    public static ObjectNode validateOutput(String raw, ObjectNode request) {
        ObjectNode output = StrictAiJson.parseObject(raw, StrictAiJson.RESPONSE_MAX_BYTES, StrictAiJson.MAX_DEPTH);
        ObjectNode contract = contract(request);
        ObjectNode projected = project(request);
        exact(output, Set.of("schemaVersion", "promptVersion", "status", "mode", "page", "operations", "questions", "notes"), Set.of());
        require(contract.path("schemaVersion").asText().equals(text(output.get("schemaVersion"))) && contract.path("promptVersion").asText().equals(text(output.get("promptVersion")))
                && request.path("input").path("mode").asText().equals(text(output.get("mode"))), "INVALID_OUTPUT", "输出版本或模式不匹配");
        String status = text(output.get("status")); String mode = text(output.get("mode"));
        limitedArray(output.get("operations"), limit(request, "maxOperations"));
        limitedArray(output.get("questions"), limit(request, "maxQuestions"));
        limitedArray(output.get("notes"), limit(request, "maxNotes"));
        output.get("notes").forEach(note -> visible(note, limit(request, "maxNoteCodePoints"), request));
        if ("NEEDS_CLARIFICATION".equals(status)) {
            require(output.get("page").isNull() && output.get("operations").isEmpty() && output.get("questions").size() == 1,
                    "INVALID_OUTPUT", "澄清必须只有一个问题且不得附候选");
            questions(output.get("questions"), request); return output;
        }
        require("CANDIDATE".equals(status) && output.get("questions").isEmpty(), "INVALID_OUTPUT", "候选状态或问题互斥规则不合法");
        if ("CREATE".equals(mode)) {
            require(output.get("operations").isEmpty(), "INVALID_OUTPUT", "生成页面不能同时输出修改操作");
            page(output.get("page"), request, contract, projected);
        } else {
            require(output.get("page").isNull(), "INVALID_OUTPUT", "修改模式 page 必须为 null");
            operations(output.get("operations"), request, contract, projected);
        }
        return output;
    }

    /** 校验澄清问题及纯文本选项，选项不携带动作。 */
    private static void questions(JsonNode questions, ObjectNode request) {
        for (JsonNode question : questions) {
            exact(question, Set.of("id", "prompt", "options"), Set.of());
            identifier(question.get("id")); visible(question.get("prompt"), limit(request, "maxQuestionCodePoints"), request);
            limitedArray(question.get("options"), limit(request, "maxOptions"));
            Set<String> ids = new HashSet<>();
            for (JsonNode option : question.get("options")) {
                exact(option, Set.of("id", "label", "answerText"), Set.of()); identifier(option.get("id"));
                require(ids.add(option.get("id").asText()), "INVALID_OUTPUT", "问题选项标识不能重复");
                visible(option.get("label"), limit(request, "maxOptionLabelCodePoints"), request);
                visible(option.get("answerText"), limit(request, "maxOptionAnswerCodePoints"), request);
            }
        }
    }

    /** 校验首次生成页面、全局设置及固定结构的新组件提案。 */
    private static void page(JsonNode page, ObjectNode request, ObjectNode contract, ObjectNode projected) {
        exact(page, Set.of("menuKey", "components", "settings"), Set.of());
        require(MENU_ALIAS.equals(text(page.get("menuKey"))), "INVALID_OUTPUT", "不能生成其它菜单");
        limitedArray(page.get("components"), limit(request, "maxComponents"));
        for (int index = 0; index < page.get("components").size(); index++) {
            proposal(page.get("components").get(index), request, contract, index, false);
        }
        exact(page.get("settings"), Set.of(), fieldSet(contract.path("createSettings")));
        page.get("settings").fields().forEachRemaining(entry -> group(entry.getValue(), contract.path("createSettings").path(entry.getKey()), request, null,
                new BindingContext(PortfolioAiBindingMessage.CREATE_SETTING.formatted(entry.getKey()), CREATE_SETTING_PATH + entry.getKey())));
        require(totalComponents(baseConfig(request)) - currentComponents(request).size() + page.get("components").size() <= limit(request, "maxComponents"),
                "INVALID_OUTPUT", "全部菜单组件数超过上限");
    }

    /** 对组件 params、主来源及槽位独立检查，不接受模型提供真实 ID。 */
    private static void proposal(JsonNode component, ObjectNode request, ObjectNode contract, int index, boolean inserted) {
        exact(component, Set.of("type", "sourceNos", "params", "bindings"), Set.of());
        String type = text(component.get("type")); JsonNode definition = contract.path("componentCatalog").get(type);
        require(definition != null, "INVALID_OUTPUT", "当前归属不支持该组件");
        BindingContext context = new BindingContext(
                (inserted ? PortfolioAiBindingMessage.INSERT_COMPONENT : PortfolioAiBindingMessage.CREATE_COMPONENT).formatted(index + 1, type),
                (inserted ? INSERT_COMPONENT_PATH : CREATE_COMPONENT_PATH).formatted(index));
        params(component.get("params"), definition.path("params"), true);
        for (JsonNode required : definition.path("requiredParams")) {
            require(component.get("params").has(required.asText()), "INVALID_OUTPUT", "新组件缺少必需普通参数");
        }
        primaryWithFeedback(component.get("sourceNos"), definition.path("primarySource"), request, false, definition.path("bindingSlots"), context);
        bindings(component.get("bindings"), definition.path("bindingSlots"), request, null, null, context);
        structures(component.get("params"), type);
        if (AiTimelineProtocol.TYPE.equals(type)) { AiTimelineProtocol.validateProposal(request, component.path("params")); }
        structuredFontBindings(component.get("params"), type, request, context);
    }

    /** 新建文字块只接受可新增的可信字体，不能复制已有组件或节点的原位引用。 */
    private static void structuredFontBindings(JsonNode params, String type, ObjectNode request, BindingContext context) {
        if (!STRUCTURED_TEXT_COMPONENT.equals(type)) { return; }
        JsonNode blocks = params.path(BLOCKS_FIELD);
        ObjectNode slots = object().set(FONT_SLOT, object().put(RESOURCE_KIND_FIELD, FONT_KIND));
        for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
            JsonNode block = blocks.get(blockIndex);
            if (!block.has(BINDINGS_FIELD)) { continue; }
            BindingContext blockContext = new BindingContext(context.description(), context.path() + STRUCTURED_BLOCK_PATH.formatted(blockIndex));
            bindings(block.get(BINDINGS_FIELD), slots, request, null, null, blockContext);
        }
    }

    /** 校验设置组包装，绑定与普通参数严格分开。 */
    private static void group(JsonNode group, JsonNode definition, ObjectNode request, ObjectNode original, BindingContext context) {
        exact(group, Set.of("params", "bindings"), Set.of());
        params(group.get("params"), definition.path("params"), false);
        bindings(group.get("bindings"), definition.path("bindingSlots"), request, original, null, context);
    }

    /** 校验操作序列及互斥写集合，所有 hash 都对照同一冻结基准。 */
    private static void operations(JsonNode operations, ObjectNode request, ObjectNode contract, ObjectNode projected) {
        Set<String> written = new HashSet<>(), removed = new HashSet<>(), moved = new HashSet<>(), insertionPoints = new HashSet<>();
        Set<String> componentWrites = new HashSet<>();
        Map<String, ObjectNode> timelines = new LinkedHashMap<>();
        int count = totalComponents(baseConfig(request));
        for (int operationIndex = 0; operationIndex < operations.size(); operationIndex++) {
            JsonNode operation = operations.get(operationIndex);
            exact(operation, Set.of("op", "target", "expectedHash", "payload", "reason"), Set.of());
            String op = text(operation.get("op"));
            require(contains(contract.path("allowedOps"), op), "INVALID_OUTPUT", "该模式不支持操作");
            visible(operation.get("reason"), limit(request, "maxReasonCodePoints"), request);
            JsonNode rawTarget = operation.get("target");
            exact(rawTarget, Set.of("menuKey"), AiPortfolioRunSpecRegistry.resolve(request).advancedVersion() >= 2
                    ? Set.of("componentKey", "cellKey", "blockKey", "runKey", "nodeKey", "textRole")
                    : Set.of("componentKey", "cellKey", "blockKey", "runKey"));
            ObjectNode target = (ObjectNode) rawTarget;
            require(MENU_ALIAS.equals(text(target.get("menuKey"))), "INVALID_OUTPUT", "操作不能越出当前菜单");
            boolean pageTarget = Set.of("INSERT_COMPONENT", "UPDATE_GLOBAL").contains(op);
            require(pageTarget ? target.size() == 1 : target.has("componentKey"), "INVALID_OUTPUT", "操作目标层级不合法");
            require(!target.has("runKey") || target.has("blockKey"), "INVALID_OUTPUT", "文字片段必须有区块目标");
            ObjectNode component = pageTarget ? null : resolveComponent(request, text(target.get("componentKey")));
            String alias = target.path("componentKey").asText();
            require(!removed.contains(alias), "INVALID_OUTPUT", "不能继续操作已移除组件");
            JsonNode payload = operation.get("payload");
            require(component == null || AiTimelineProtocol.matches(component) || !target.has("nodeKey") && !target.has("textRole"), "INVALID_OUTPUT", "非时间轴不接受时间轴目标");
            JsonNode hashBase = pageTarget ? currentComponents(request) : target.size() == 2 ? component
                    : AiTimelineProtocol.matches(component) ? AiTimelineProtocol.hashBase(request, target) : resolveNode(request, target);
            if ("UPDATE_GLOBAL".equals(op)) { hashBase = baseConfig(request).path(payload.path("section").asText()); }
            require(expectedHash(hashBase).equals(text(operation.get("expectedHash"))), "INVALID_OUTPUT", "操作基准不匹配");
            JsonNode definition = component == null ? null : contract.path("componentCatalog").path(component.path("componentType").asText());
            String targetId = StrictAiJson.canonical(target);
            if (component != null && AiTimelineProtocol.matches(component)
                    && Set.of("UPDATE_TEXT", "UPDATE_PARAMS", "BIND_RESOURCES", "UPDATE_STRUCTURE").contains(op)) {
                require(AiPortfolioRunSpecRegistry.resolve(request).advancedVersion() >= 2, "INVALID_OUTPUT", "当前版本不支持时间轴");
                ObjectNode timeline = timelines.computeIfAbsent(alias, ignored -> component.deepCopy());
                AiTimelineProtocol.apply(request, timeline, (ObjectNode) operation, "ai_check_" + operationIndex);
                componentWrites.add(alias);
                continue;
            }
            switch (op) {
                case "UPDATE_TEXT" -> {
                    exact(payload, Set.of("field", "value"), Set.of()); String field = text(payload.get("field"));
                    JsonNode schema = nodeSchemas(request, component, target, definition).get(field);
                    if (schema == null && target.size() == 2) { schema = definition.path("textFields").get(field); }
                    require(TEXT_FIELDS.contains(field) && schema != null, "INVALID_OUTPUT", "目标没有开放该文字字段");
                    value(payload.get("value"), schema);
                    if (target.has("blockKey") && !target.has("cellKey")
                            && "STRUCTURED_TEXT_SECTION".equals(component.path("componentType").asText())) {
                        requireNonblankStructuredText(payload.get("value"));
                    }
                    write(written, targetId, field);
                }
                case "UPDATE_PARAMS" -> {
                    exact(payload, Set.of("params"), Set.of());
                    JsonNode schema = nodeSchemas(request, component, target, definition);
                    params(payload.get("params"), schema, false);
                    if (target.size() == 2) { structures(payload.get("params"), component.path("componentType").asText()); }
                    payload.get("params").fieldNames().forEachRemaining(field -> {
                        require(!TEXT_FIELDS.contains(field), "INVALID_OUTPUT", "修改文字须使用文字操作"); write(written, targetId, field);
                    });
                }
                case "MOVE_COMPONENT" -> {
                    require(target.size() == 2 && moved.add(alias), "INVALID_OUTPUT", "组件不能重复移动或移动内部节点");
                    exact(payload, Set.of("position", "anchorComponentKey"), Set.of());
                    position(payload, request, removed, alias);
                }
                case "INSERT_COMPONENT" -> {
                    exact(payload, Set.of("position", "anchorComponentKey", "component"), Set.of());
                    position(payload, request, removed, null);
                    require(insertionPoints.add(payload.path("position").asText() + ":" + payload.path("anchorComponentKey").asText()),
                            "INVALID_OUTPUT", "不能连续插入同一锚点");
                    proposal(payload.get("component"), request, contract, operationIndex, true); count++;
                }
                case "REMOVE_COMPONENT" -> {
                    exact(payload, Set.of(), Set.of());
                    require(target.size() == 2 && !componentWrites.contains(alias), "INVALID_OUTPUT", "移除操作与其它修改冲突");
                    removed.add(alias); count--;
                }
                case "BIND_RESOURCES" -> {
                    exact(payload, Set.of("sourceNos", "bindings"), Set.of());
                    // 类型只能取已在可信目录中的原组件类型，不能回显模型提供的身份或业务值。
                    String type = component.path("componentType").asText();
                    String safeType = contract.path("componentCatalog").has(type) ? type : PortfolioAiBindingMessage.UNKNOWN_COMPONENT;
                    BindingContext context = new BindingContext(PortfolioAiBindingMessage.BIND_COMPONENT.formatted(operationIndex + 1, safeType),
                            OPERATION_PAYLOAD_PATH.formatted(operationIndex));
                    primaryWithFeedback(payload.get("sourceNos"), target.size() == 2 ? definition.path("primarySource") : null, request, true,
                            target.size() == 2 ? definition.path("bindingSlots") : object(), context);
                    JsonNode bindingSlots = target.size() == 2 ? definition.path("bindingSlots") : fontSlots(component, target);
                    bindings(payload.get("bindings"), bindingSlots, request, component, resolveNode(request, target), context);
                    if (!payload.get("sourceNos").isEmpty()) { write(written, targetId, "primarySource"); }
                    payload.get("bindings").forEach(binding -> write(written, targetId, binding.path("slot").asText()));
                }
                case "UPDATE_GLOBAL" -> {
                    exact(payload, Set.of("section", "params", "bindings"), Set.of());
                    String section = text(payload.get("section")); JsonNode setting = contract.path("globalFields").get(section);
                    require(setting != null, "INVALID_OUTPUT", "全局字段未开放");
                    ObjectNode settingGroup = object();
                    settingGroup.set("params", payload.get("params"));
                    settingGroup.set("bindings", payload.get("bindings"));
                    group(settingGroup, setting, request, null, new BindingContext(
                            PortfolioAiBindingMessage.UPDATE_SETTING.formatted(operationIndex + 1, section), OPERATION_PAYLOAD_PATH.formatted(operationIndex)));
                    payload.get("params").fieldNames().forEachRemaining(field -> write(written, section, field));
                    payload.get("bindings").forEach(binding -> write(written, section, binding.path("slot").asText()));
                }
                default -> throw reject("尚未实现该结构动作");
            }
            if (!pageTarget) { componentWrites.add(alias); }
            require(count <= limit(request, "maxComponents"), "INVALID_OUTPUT", "全部菜单组件数超过上限");
        }
        timelines.values().forEach(component -> AiTimelineProtocol.validateContent(component.path("config"), true));
    }

    /** 根据内部层级选定封闭字段集，结构数组不能用 UPDATE_PARAMS 重建。 */
    private static JsonNode nodeSchemas(ObjectNode request, ObjectNode component, ObjectNode target, JsonNode definition) {
        ObjectNode rules = AiPortfolioRunSpecRegistry.resolve(request).rules();
        if (target.has("runKey")) { return rules.path("nodeParams").path("GRID_RUN"); }
        if (target.has("blockKey")) {
            if (target.has("cellKey")) { return rules.path("nodeParams").path("GRID_BLOCK"); }
            ObjectNode schema = rules.withObject("nodeParams").withObject("STRUCTURED_BLOCK").deepCopy();
            String type = indexed(component.path("config").path("blocks"), target.path("blockKey").asText(), "block_").path("type").asText();
            if ("SPACER".equals(type)) { schema.retain("heightRpx", "marginTopRpx", "marginBottomRpx"); }
            else { schema.remove("heightRpx"); schema.remove("LIST".equals(type) ? "content" : "items"); }
            return schema;
        }
        if (target.has("cellKey")) { return rules.path("nodeParams").path("GRID_CELL"); }
        return definition.path("params");
    }

    /** 普通网格只有文字片段有字体；结构化留白、格子和网格区块不接受字体槽位。 */
    private static ObjectNode fontSlots(ObjectNode component, ObjectNode target) {
        boolean run = target.has("runKey") && "TEXT_GRID".equals(component.path("componentType").asText());
        boolean structuredText = target.has("blockKey") && !target.has("cellKey")
                && "STRUCTURED_TEXT_SECTION".equals(component.path("componentType").asText())
                && !"SPACER".equals(indexed(component.path("config").path("blocks"), target.path("blockKey").asText(), "block_").path("type").asText());
        return run || structuredText ? object().set(FONT_SLOT, object().put(RESOURCE_KIND_FIELD, FONT_KIND)) : object();
    }

    /**
     * 只携带代码构造的位置及可信目录类型的素材反馈上下文。
     * @param description 可读组件或操作位置
     * @param path 固定协议字段路径，不接受模型提供的路径
     */
    private record BindingContext(String description, String path) {
        /** 保留原分类及安全摘要，追加字段位置与固定纠正提示。 */
        private AiProtocolException reject(String field, AiProtocolException failure, String correction) {
            if (!INVALID_OUTPUT_CODE.equals(failure.getCode())) { return failure; }
            return new AiProtocolException(failure.getCode(), PortfolioAiBindingMessage.FIELD_CORRECTION
                    .formatted(description, path + "." + field, failure.getMessage(), correction));
        }
    }

    /** 在原主来源校验外补充重试位置，拒绝规则及校验顺序保持不变。 */
    private static void primaryWithFeedback(JsonNode sourceNos, JsonNode rule, ObjectNode request, boolean mayKeep,
                                            JsonNode slots, BindingContext context) {
        try {
            primary(sourceNos, rule, request, mayKeep);
        } catch (AiProtocolException failure) {
            String correction;
            if (rule == null || rule.isNull() || rule.isMissingNode()) {
                correction = PortfolioAiBindingMessage.NO_PRIMARY_CORRECTION;
                if (slots.has(BACKGROUND_WORK_SLOT)) { correction += PortfolioAiBindingMessage.BACKGROUND_CORRECTION; }
            } else {
                correction = PortfolioAiBindingMessage.PRIMARY_CORRECTION.formatted(rule.path("kind").asText(),
                        rule.path("min").asInt(), rule.path("max").asInt(), rule.path("mediaTypes"));
            }
            throw context.reject(SOURCE_NUMBERS_FIELD, failure, correction);
        }
    }

    /** 检查主来源数量、媒体类型、去重与已选可新增状态。 */
    private static void primary(JsonNode sourceNos, JsonNode rule, ObjectNode request, boolean mayKeep) {
        limitedArray(sourceNos, limit(request, "maxSourceCount"));
        if (sourceNos.isEmpty() && mayKeep) { return; }
        if (rule == null || rule.isNull() || rule.isMissingNode()) { require(sourceNos.isEmpty(), "INVALID_OUTPUT", "该节点没有主素材槽位"); return; }
        require(sourceNos.size() >= rule.path("min").asInt() && sourceNos.size() <= rule.path("max").asInt(), "INVALID_OUTPUT", "素材数量不符合组件要求");
        Set<Integer> numbers = new HashSet<>();
        for (JsonNode number : sourceNos) {
            JsonNode source = source(request, number);
            require(numbers.add(number.intValue()) && rule.path("kind").asText().equals(source.path("kind").asText()), "INVALID_OUTPUT", "素材重复或类型不符合要求");
            if (!rule.path("mediaTypes").isEmpty()) {
                require(contains(rule.path("mediaTypes"), source.path("mediaType").asText()), "INVALID_OUTPUT", "素材媒体类型不符合要求");
            }
        }
    }

    /** 读取可信来源号，不接受字符串、小数或已移除号码。 */
    private static JsonNode source(ObjectNode request, JsonNode number) {
        require(number != null && number.isIntegralNumber() && number.canConvertToInt() && number.intValue() > 0, "INVALID_OUTPUT", "sourceNo 必须为正整数");
        for (JsonNode source : request.path("facts").path("sources")) {
            if (source.path("sourceNo").equals(number) || source.path("sourceNo").isIntegralNumber()
                    && source.path("sourceNo").intValue() == number.intValue()) {
                require(source.path("canBind").asBoolean(true), "INVALID_OUTPUT", "来源不可新增绑定"); return source;
            }
        }
        throw reject("来源编号已移除或不可用");
    }

    /** 检查槽位、资源句柄、来源与 reuseOnly 的原位置约束。 */
    private static void bindings(JsonNode bindings, JsonNode slots, ObjectNode request, ObjectNode original, ObjectNode node, BindingContext context) {
        String correction = bindingCorrection(slots);
        try {
            limitedArray(bindings, Math.max(slots.size(), 0));
        } catch (AiProtocolException failure) {
            throw context.reject(BINDINGS_FIELD, failure, correction);
        }
        Set<String> written = new HashSet<>();
        for (int bindingIndex = 0; bindingIndex < bindings.size(); bindingIndex++) {
            JsonNode binding = bindings.get(bindingIndex);
            try {
                exact(binding, Set.of("slot"), Set.of("sourceNo", "ref"));
                String slot = text(binding.get("slot")); JsonNode spec = slots.get(slot);
                require(spec != null && written.add(slot) && binding.has("ref") != binding.has("sourceNo"), "INVALID_OUTPUT", "槽位未知、重复或绑定身份不唯一");
                if (binding.has("sourceNo")) {
                    JsonNode selected = source(request, binding.get("sourceNo"));
                    require(spec.path("kind").asText().equals(selected.path("kind").asText()), "INVALID_OUTPUT", "槽位来源类型不符合要求");
                    if (spec.has("mediaTypes")) { require(contains(spec.path("mediaTypes"), selected.path("mediaType").asText()), "INVALID_OUTPUT", "槽位素材媒体类型不符合要求"); }
                } else {
                    String ref = text(binding.get("ref")); JsonNode found = null;
                    for (JsonNode value : request.path("facts").path("bindings")) {
                        if (ref.equals(value.path("ref").asText())) { found = value; break; }
                    }
                    require(found != null && contains(found.path("allowedSlots"), slot)
                            && spec.path("kind").asText().equals(found.path("kind").asText()), "INVALID_OUTPUT", "资源句柄或槽位不合法");
                    if (found.path("reuseOnly").asBoolean()) {
                        require(original != null && original.path("componentKey").asText().equals(found.path("componentKey").asText()), "INVALID_OUTPUT", "已有引用不能复制到新组件");
                        if (found.has("nodeKey")) {
                            require(node != null && containsNodeKey(node, found.path("nodeKey").asText()), "INVALID_OUTPUT", "已有引用不能改变文字位置");
                        }
                    }
                }
            } catch (AiProtocolException failure) {
                throw context.reject(BINDINGS_FIELD + "[" + bindingIndex + "]", failure, correction);
            }
        }
    }

    /** 只列出可信目录的少量槽位名，不回显模型填写的槽位、来源号或引用值。 */
    private static String bindingCorrection(JsonNode slots) {
        if (slots.isEmpty()) { return PortfolioAiBindingMessage.NO_BINDING_CORRECTION; }
        Set<String> names = new LinkedHashSet<>();
        slots.fieldNames().forEachRemaining(names::add);
        return PortfolioAiBindingMessage.BINDING_CORRECTION.formatted(String.join(", ", names));
    }

    /** 校验引用原节点的真实稳定 key。 */
    private static boolean containsNodeKey(ObjectNode node, String key) {
        return Set.of("cellKey", "blockKey", "runKey").stream().anyMatch(field -> key.equals(node.path(field).asText()));
    }

    /** 按已注册具体字段逐层检查类型，不把非法值交给普通默认值修复。 */
    static void params(JsonNode params, JsonNode schema, boolean structureAllowed) {
        exact(params, Set.of(), fieldSet(schema));
        params.fields().forEachRemaining(entry -> {
            require(structureAllowed || !STRUCTURES.contains(entry.getKey())
                    || "items".equals(entry.getKey()) && schema.path("items").path("items").path("type").asText().equals("string"),
                    "INVALID_OUTPUT", "局部参数不得覆盖结构数组或结构身份");
            value(entry.getValue(), schema.get(entry.getKey()));
        });
    }

    /** 递归检查固定 schema；数组始终具有数量上限，对象始终具有封闭字段集。 */
    static void value(JsonNode value, JsonNode schema) {
        String type = schema.path("type").asText();
        boolean valid = switch (type) {
            case "string" -> value.isTextual(); case "boolean" -> value.isBoolean();
            case "integer" -> value.isIntegralNumber() && value.canConvertToInt();
            case "number" -> value.isNumber() && Double.isFinite(value.doubleValue());
            case "array" -> value.isArray(); case "object" -> value.isObject(); default -> false;
        };
        require(valid, "INVALID_OUTPUT", "普通参数类型不符合契约");
        if (schema.has("enum")) { require(containsNode(schema.get("enum"), value), "INVALID_OUTPUT", "参数枚举值不符合契约"); }
        if (value.isTextual()) {
            int length = value.textValue().codePointCount(0, value.textValue().length());
            require(length <= schema.path("maxCodePoints").asInt(2000) && length >= schema.path("minCodePoints").asInt(0), "INVALID_OUTPUT", "参数文字长度不符合契约");
            require(!RAW_URL.matcher(value.textValue()).find(), "INVALID_OUTPUT", "资源地址必须使用可信绑定");
            if (schema.has("pattern")) { require(value.textValue().matches(schema.get("pattern").asText()), "INVALID_OUTPUT", "参数格式不符合契约"); }
        }
        if (value.isNumber()) {
            BigDecimal number = value.decimalValue();
            if (schema.has("min")) { require(number.compareTo(schema.get("min").decimalValue()) >= 0, "INVALID_OUTPUT", "参数低于普通范围"); }
            if (schema.has("max")) { require(number.compareTo(schema.get("max").decimalValue()) <= 0, "INVALID_OUTPUT", "参数超过普通范围"); }
            if (schema.has("multipleOf")) { require(number.remainder(schema.get("multipleOf").decimalValue()).signum() == 0, "INVALID_OUTPUT", "参数步进不符合普通范围"); }
        }
        if (value.isArray()) {
            require(schema.has("maxItems") && schema.has("items"), "INVALID_OUTPUT", "数组契约没有固定上限");
            require(value.size() >= schema.path("minItems").asInt(0), "INVALID_OUTPUT", "参数数组数量不足");
            limitedArray(value, schema.get("maxItems").intValue()); value.forEach(item -> value(item, schema.get("items")));
        }
        if (value.isObject()) {
            Set<String> required = new HashSet<>(); schema.path("required").forEach(item -> required.add(item.asText()));
            exact(value, required, fieldSet(schema.path("properties")));
            value.fields().forEachRemaining(entry -> value(entry.getValue(), schema.path("properties").get(entry.getKey())));
        }
    }

    /** 新结构提案检查区块互斥及网格覆盖，不能靠普通规范化丢字段。 */
    private static void structures(JsonNode params, String type) {
        if ("CONTACT_FORM".equals(type) && params.has("fields")) {
            Set<String> fields = new HashSet<>(); params.get("fields").forEach(field -> fields.add(field.asText()));
            require(fields.size() == params.get("fields").size() && fields.contains("contactName")
                    && (fields.contains("phone") || fields.contains("wechat")), "INVALID_OUTPUT", "联系表单字段组合不合法");
        }
        if ("SCHEDULE_QUERY".equals(type) && params.has("queryRange")) {
            JsonNode range = params.get("queryRange"); String rangeType = range.path("type").asText();
            switch (rangeType) {
                case "UNLIMITED" -> require(range.size() == 1, "INVALID_OUTPUT", "不限制查询范围不得附日期或天数");
                case "FUTURE_DAYS" -> require(range.size() == 2 && range.has("futureDays"), "INVALID_OUTPUT", "未来天数范围只接受对应天数");
                case "DATE_RANGE" -> {
                    require(range.size() == 3 && range.has("startDate") && range.has("endDate"), "INVALID_OUTPUT", "日期范围必须提供起止日期");
                    try {
                        var start = java.time.LocalDate.parse(range.path("startDate").asText());
                        var end = java.time.LocalDate.parse(range.path("endDate").asText());
                        require(!start.isAfter(end), "INVALID_OUTPUT", "日期范围起止顺序不合法");
                    } catch (java.time.format.DateTimeParseException exception) { throw reject("日期格式不合法"); }
                }
                default -> throw reject("查询范围类型不合法");
            }
        }
        if ("STRUCTURED_TEXT_SECTION".equals(type) && params.has("blocks")) {
            int length = 0; boolean content = false;
            for (JsonNode block : params.get("blocks")) {
                String blockType = block.path("type").asText();
                if ("SPACER".equals(blockType)) {
                    require(!block.has("content") && !block.has("items") && !block.has("lineHeight")
                            && !block.has("fontFamily") && !block.has("fontSizeRpx") && !block.has("fontWeight")
                            && !block.has("color") && !block.has("alignment") && !block.has(BINDINGS_FIELD), "INVALID_OUTPUT", "留白区块不能含正文或文字样式");
                } else {
                    content = true;
                    require(!block.has("heightRpx"), "INVALID_OUTPUT", "文字区块不能含留白高度");
                    if ("LIST".equals(blockType)) {
                        require(block.has("items") && !block.has("content"), "INVALID_OUTPUT", "列表区块必须只使用 items");
                        requireNonblankStructuredText(block.get("items"));
                        for (JsonNode item : block.get("items")) { length += codePoints(item); }
                    } else {
                        require(block.has("content") && !block.has("items"), "INVALID_OUTPUT", "文字区块必须只使用 content");
                        requireNonblankStructuredText(block.get("content")); length += codePoints(block.get("content"));
                    }
                }
            }
            require(content && length <= 2000, "INVALID_OUTPUT", "结构化文字总长度或正文不合法");
        }
        if ("TEXT_GRID".equals(type) && params.has("cells")) {
            int rows = params.path("rows").asInt(), columns = params.path("columns").asInt(), length = 0;
            boolean[][] covered = new boolean[rows][columns];
            if (params.has("columnWeights")) { require(params.get("columnWeights").size() == columns, "INVALID_OUTPUT", "列宽数量与列数不一致"); }
            if (params.has("rowMinHeightsRpx")) { require(params.get("rowMinHeightsRpx").size() == rows, "INVALID_OUTPUT", "行高数量与行数不一致"); }
            for (JsonNode cell : params.get("cells")) {
                int r = cell.path("row").asInt(), c = cell.path("column").asInt();
                int rs = cell.path("rowSpan").asInt(), cs = cell.path("columnSpan").asInt();
                require(r + rs <= rows && c + cs <= columns, "INVALID_OUTPUT", "网格单元超出范围");
                for (int i = r; i < r + rs; i++) { for (int j = c; j < c + cs; j++) {
                    require(!covered[i][j], "INVALID_OUTPUT", "网格单元重叠"); covered[i][j] = true;
                } }
                for (JsonNode block : cell.path("blocks")) { for (JsonNode run : block.path("runs")) { length += codePoints(run.path("text")); } }
            }
            for (boolean[] row : covered) { for (boolean cell : row) { require(cell, "INVALID_OUTPUT", "网格单元未覆盖完整区域"); } }
            require(length <= 2000, "INVALID_OUTPUT", "网格文字总长度超过普通上限");
        }
    }

    /** 普通结构化正文和列表项均不能为空白，网格仍沿其独立空白草稿规则。 */
    private static void requireNonblankStructuredText(JsonNode node) {
        if (node.isArray()) { node.forEach(AiPortfolioProtocol::requireNonblankStructuredText); }
        else { require(node.isTextual() && !node.textValue().isBlank(), "INVALID_OUTPUT", "结构化正文不得为空白"); }
    }

    /** 校验移动或插入锚点，只接受同页仍存在的其他原组件。 */
    private static void position(JsonNode payload, ObjectNode request, Set<String> removed, String self) {
        String position = text(payload.get("position"));
        require(Set.of("START", "END", "BEFORE", "AFTER").contains(position), "INVALID_OUTPUT", "位置枚举不合法");
        if (Set.of("START", "END").contains(position)) { require(payload.get("anchorComponentKey").isNull(), "INVALID_OUTPUT", "首尾位置不接受锚点"); }
        else {
            String anchor = text(payload.get("anchorComponentKey")); resolveComponent(request, anchor);
            require(!removed.contains(anchor) && !anchor.equals(self), "INVALID_OUTPUT", "锚点已经移除或指向自身");
        }
    }

    /** 记录非重叠字段写入，拒绝最后一项静默覆盖前一项。 */
    private static void write(Set<String> writes, String target, String field) {
        require(writes.add(target + ":" + field), "INVALID_OUTPUT", "操作重复写入同一字段");
    }

    /** 遍历全部菜单计算组件数量，首菜单数组不重复计入。 */
    public static int totalComponents(ObjectNode config) {
        int count = config.path("components").size(), index = 0;
        for (JsonNode item : config.path("bottomNav").path("items")) { if (index++ > 0) { count += item.path("components").size(); } }
        return count;
    }

    /** 返回协议数量预算；两工程及提示词使用相同资源。 */
    public static int limit(String key) { return RULES.path("limits").path(key).intValue(); }

    /** 实际任务的限制取冻结规格，不读当前默认目录。 */
    private static int limit(ObjectNode request, String key) { return AiPortfolioRunSpecRegistry.resolve(request).limit(key); }

    /** 返回独立的协议预算副本，供服务能力响应使用。 */
    public static ObjectNode limits() { return RULES.withObject("limits").deepCopy(); }

    /** 冻结任务的完整限制，不使用当前默认规格。 */
    public static ObjectNode limits(ObjectNode request) { return AiPortfolioRunSpecRegistry.resolve(request).rules().withObject("limits").deepCopy(); }

    /** 封闭对象键检查；在任何 DTO 转换前执行。 */
    static void exact(JsonNode node, Set<String> required, Set<String> optional) {
        require(node != null && node.isObject(), "INVALID_OUTPUT", "协议字段必须为对象");
        require(fieldSet(node).containsAll(required), "INVALID_OUTPUT", "缺少协议必需字段");
        Set<String> allowed = new HashSet<>(required); allowed.addAll(optional);
        require(allowed.containsAll(fieldSet(node)), "INVALID_OUTPUT", "出现协议未允许的字段");
    }

    /** 取得对象键集合。 */
    private static Set<String> fieldSet(JsonNode node) {
        Set<String> result = new HashSet<>(); if (node != null) { node.fieldNames().forEachRemaining(result::add); } return result;
    }

    /** 严格字符串读取，不接受类型强转。 */
    private static String text(JsonNode node) {
        require(node != null && node.isTextual(), "INVALID_OUTPUT", "协议字段必须为字符串"); return node.textValue();
    }

    /** 检查固定数组数量。 */
    private static void limitedArray(JsonNode node, int limit) {
        require(node != null && node.isArray() && node.size() <= limit, "INVALID_OUTPUT", "协议数组类型或数量不合法");
    }

    /** 校验纯文本预算，禁止内部别名或模型输入中的资源地址进入可见输出。 */
    private static void visible(JsonNode node, int maximum, ObjectNode request) {
        String value = text(node);
        require(codePoints(node) <= maximum && !INTERNAL_ALIAS.matcher(value).find(), "INVALID_OUTPUT", "用户可见文字超过上限或包含内部别名");
        require(!RAW_URL.matcher(value).find(), "INVALID_OUTPUT", "用户可见文字不得包含资源地址");
        require(AiPortfolioRunSpecRegistry.resolve(request).advancedVersion() < 2 || !TIMELINE_ALIAS.matcher(value).find(),
                "INVALID_OUTPUT", "用户可见文字不得包含时间轴内部别名");
    }

    /** 问题和选项的稳定标识为有限纯文本。 */
    private static void identifier(JsonNode node) { require(text(node).matches("[A-Za-z0-9_-]{1,64}"), "INVALID_OUTPUT", "问题标识不合法"); }

    /** Unicode 码点计数。 */
    private static int codePoints(JsonNode node) { String value = text(node); return value.codePointCount(0, value.length()); }

    /** 数组枚举包含检查。 */
    private static boolean contains(JsonNode values, String value) { for (JsonNode item : values) { if (item.isTextual() && value.equals(item.textValue())) { return true; } } return false; }

    /** 严格数值或文本枚举包含检查。 */
    private static boolean containsNode(JsonNode values, JsonNode value) { for (JsonNode item : values) { if (item.equals(value)) { return true; } } return false; }

    /** 对模型可读文字移除原始地址、手机号与微信号；真实绑定仍只存在冻结事实。 */
    private static String safeText(String text) {
        return text.replaceAll("(?i)https?://[^\\s\\u3000]+", "[已确认链接]")
                .replaceAll("(?<![0-9])1[3-9][0-9]{9}(?![0-9])", "[联系方式]")
                .replaceAll("(?i)(?:微信(?:\\s*号)?|wechat|wx)\\s*[:：]?\\s*[a-z0-9_-]{5,}", "[已确认微信]");
    }

    /** 递归复制安全显示内容，结构身份只由投影显式生成。 */
    static JsonNode scrub(JsonNode node) {
        if (node.isTextual()) { return StrictAiJson.mapper().getNodeFactory().textNode(safeText(node.textValue())); }
        if (node.isArray()) { ArrayNode result = array(); node.forEach(item -> result.add(scrub(item))); return result; }
        if (node.isObject()) { ObjectNode result = object(); node.fields().forEachRemaining(field -> result.set(field.getKey(), scrub(field.getValue()))); return result; }
        return node.deepCopy();
    }

    /** 以普通名称构造用户可识别的位置提示。 */
    private static String displayName(String type) {
        return switch (type) {
            case "TEXT_SECTION", "STRUCTURED_TEXT_SECTION" -> "文字"; case "TEXT_GRID" -> "文字网格";
            case "SINGLE_WORK" -> "作品"; case "CAROUSEL" -> "图片轮播"; case "VIDEO_CAROUSEL" -> "视频轮播";
            case "MEMBER_PORTFOLIO_LIST", "MEMBER_PORTFOLIO_GRID" -> "成员作品集"; default -> "组件";
        };
    }

    /** 创建独立对象。 */
    private static ObjectNode object() { return StrictAiJson.mapper().createObjectNode(); }
    /** 创建独立数组。 */
    private static ArrayNode array() { return StrictAiJson.mapper().createArrayNode(); }
    /** 创建安全拒绝摘要。 */
    private static AiProtocolException reject(String message) { return new AiProtocolException("INVALID_OUTPUT", message); }
    /** 统一固定错误码与摘要条件检查。 */
    private static void require(boolean valid, String code, String message) { if (!valid) { throw new AiProtocolException(code, message); } }
}
