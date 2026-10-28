package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.config.PortfolioAiProperties;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioAiMessage;
import com.jxc.wefolio.service.advancedportfolio.AdvancedVersionRegistry;
import java.util.Set;

import static com.jxc.wefolio.service.portfolioai.PortfolioAiConstants.*;

/** AI HTTP 输入的封闭结构和严格类型，不借用宽松 DTO 转换。 */
public final class PortfolioAiInput {
    /** 本版接受的业务模式。 */
    public static final Set<String> MODES = Set.of(MODE_CREATE, MODE_MODIFY_PAGE);
    /** 原始请求允许字段，幂等键不进入业务输入快照。 */
    private static final Set<String> FIELDS = Set.of(KEY_OWNER_TYPE, KEY_OWNER_ID, KEY_PORTFOLIO_ID, KEY_MODE,
            KEY_TARGET_MENU_KEY, KEY_BASE_DRAFT_REVISION, KEY_LOCAL_EDIT_REVISION, KEY_BASE_CONFIG,
            KEY_EDITOR_SCHEMA_REVISION, KEY_AI_CONTRACT_VERSION, KEY_CLIENT_CAPABILITIES, KEY_AI_CONTEXT, KEY_PROMPT,
            KEY_IDEMPOTENCY_KEY, KEY_CLARIFICATION_ANSWER);
    /** 身份文本只能是十进制正整数。 */
    private static final String ID_PATTERN = "[1-9][0-9]{0,18}";
    /** 工具类不可实例化。 */
    private PortfolioAiInput() { }

    /** 验证原始业务请求并返回去掉幂等键的副本。 */
    public static ObjectNode normalize(ObjectNode request, PortfolioAiProperties limits) {
        int version = AdvancedVersionRegistry.requestVersion(request);
        request.fieldNames().forEachRemaining(key -> {
            if (!FIELDS.contains(key) && !(version >= 2 && Set.of(AiPortfolioRunSpecRegistry.CLIENT_VERSION, AiPortfolioRunSpecRegistry.SOURCE_MODE).contains(key))) { throw invalid(); }
        });
        String owner = text(request, KEY_OWNER_TYPE);
        if (!Set.of(OWNER_USER, OWNER_TEAM).contains(owner)) { throw invalid(); }
        id(request, KEY_OWNER_ID, false);
        id(request, KEY_PORTFOLIO_ID, true);
        String mode = text(request, KEY_MODE);
        if (!MODES.contains(mode)) { throw invalid(); }
        nonnegativeInt(request, KEY_BASE_DRAFT_REVISION);
        nonnegativeInt(request, KEY_LOCAL_EDIT_REVISION);
        nonnegativeInt(request, KEY_EDITOR_SCHEMA_REVISION);
        String prompt = text(request, KEY_PROMPT);
        if (prompt.isBlank() || prompt.codePointCount(0, prompt.length()) > limits.getMaxPromptCodePoints()) {
            throw invalid();
        }
        String key = text(request, KEY_IDEMPOTENCY_KEY);
        if (!key.matches(IDEMPOTENCY_KEY_PATTERN)) { throw invalid(); }
        if (!AdvancedVersionRegistry.resolve(version).aiContractVersion().equals(text(request, KEY_AI_CONTRACT_VERSION))) { throw invalid(); }
        AdvancedVersionRegistry.requireEditor(version, OWNER_TEAM.equals(owner), request.path(KEY_EDITOR_SCHEMA_REVISION).intValue());
        String sourceMode = AiPortfolioRunSpecRegistry.SELECTED_SOURCES;
        if (version >= 2 && request.has(AiPortfolioRunSpecRegistry.SOURCE_MODE)) {
            sourceMode = text(request, AiPortfolioRunSpecRegistry.SOURCE_MODE);
            if (!Set.of(AiPortfolioRunSpecRegistry.SELECTED_SOURCES, AiPortfolioRunSpecRegistry.TEXT_ONLY).contains(sourceMode)) { throw invalid(); }
        }
        if (AiPortfolioRunSpecRegistry.TEXT_ONLY.equals(sourceMode) && (!MODE_CREATE.equals(mode)
                || !request.path(KEY_AI_CONTEXT).path(KEY_SOURCES).isArray() || !request.path(KEY_AI_CONTEXT).path(KEY_SOURCES).isEmpty())) { throw invalid(); }
        JsonNode config = request.get(KEY_BASE_CONFIG);
        if (config == null || (!config.isNull() && !config.isObject()) || (!MODE_CREATE.equals(mode) && config.isNull())) {
            throw invalid();
        }
        if (MODE_CREATE.equals(mode) && !request.path(KEY_PORTFOLIO_ID).isNull() && request.has(KEY_PORTFOLIO_ID)) {
            throw invalid();
        }
        if (!request.hasNonNull(KEY_PORTFOLIO_ID) && request.path(KEY_BASE_DRAFT_REVISION).intValue() != 0) { throw invalid(); }
        nullableText(request, KEY_TARGET_MENU_KEY);
        JsonNode caps = request.get(KEY_CLIENT_CAPABILITIES);
        if (caps != null && !caps.isNull()) {
            if (!caps.isObject()) { throw invalid(); }
            caps.fields().forEachRemaining(e -> {
                if (!KEY_PORTFOLIO_REMOTE_FONT.equals(e.getKey()) || !e.getValue().isIntegralNumber() || !e.getValue().canConvertToInt()
                        || e.getValue().intValue() < 0 || e.getValue().intValue() > 1) { throw invalid(); }
            });
        }
        JsonNode answer = request.get(KEY_CLARIFICATION_ANSWER);
        if (answer != null && !answer.isNull()) {
            if (!answer.isObject()) { throw invalid(); }
            if (!answer.path(KEY_QUESTION_ID).isTextual() || answer.path(KEY_QUESTION_ID).asText().isBlank()
                    || !answer.path(KEY_ANSWER_TEXT).isTextual() || answer.path(KEY_ANSWER_TEXT).asText().isBlank()) { throw invalid(); }
            answer.fields().forEachRemaining(e -> {
                if (!Set.of(KEY_QUESTION_ID, KEY_OPTION_ID, KEY_ANSWER_TEXT).contains(e.getKey())
                        || !e.getValue().isTextual() || e.getValue().asText().codePointCount(0,
                        e.getValue().asText().length()) > limits.getMaxPromptCodePoints()) { throw invalid(); }
            });
        }
        if (!request.path(KEY_AI_CONTEXT).isObject()) { throw invalid(); }
        ObjectNode input = request.deepCopy();
        input.remove(KEY_IDEMPOTENCY_KEY);
        if (version >= 2) { input.put(AiPortfolioRunSpecRegistry.SOURCE_MODE, sourceMode); }
        return input;
    }

    /** 读取显式字符串，避免自动类型强转。 */
    public static String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) { throw invalid(); }
        return value.textValue();
    }

    /** 读取符合 HTTP 契约的字符串 ID。 */
    public static Long id(JsonNode object, String field, boolean optional) {
        JsonNode value = object.get(field);
        if (optional && (value == null || value.isNull())) { return null; }
        if (value == null || !value.isTextual() || !value.textValue().matches(ID_PATTERN)) { throw invalid(); }
        try { return Long.parseLong(value.textValue()); }
        catch (NumberFormatException exception) { throw invalid(); }
    }

    /** 检查协议整数，字符串和小数都不接受。 */
    private static void nonnegativeInt(ObjectNode request, String field) {
        JsonNode n = request.get(field);
        if (n == null || !n.isIntegralNumber() || !n.canConvertToInt() || n.intValue() < 0) { throw invalid(); }
    }

    /** 检查可缺省的目标 key，禁止路径表达式和无界字符串。 */
    private static void nullableText(ObjectNode request, String field) {
        JsonNode n = request.get(field);
        if (n != null && !n.isNull() && (!n.isTextual() || n.textValue().length() > 128 || n.textValue().isBlank())) {
            throw invalid();
        }
    }

    /** 对外只输出固定、安全的业务错误。 */
    private static BusinessException invalid() { return new BusinessException(PortfolioAiMessage.INVALID_INPUT); }
}
