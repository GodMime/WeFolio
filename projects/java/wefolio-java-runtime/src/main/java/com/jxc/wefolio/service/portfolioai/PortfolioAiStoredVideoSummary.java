package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static com.jxc.wefolio.service.portfolioai.PortfolioAiMediaContract.*;

/** 原视频持久摘要的两工程共享合同：只绑定源身份，不依赖帧、转写或有效期。 */
public final class PortfolioAiStoredVideoSummary {
    /** 持久结构、来源模式与冻结字段均独立于七天帧摘要缓存。 */
    public static final String SCHEMA_VERSION = "video-summary-v1";
    public static final String INPUT_MODE = "DIRECT_VIDEO";
    public static final String FIELD = "storedVideoSummary";
    /** 包装字段使用封闭结构，不能混入签名链接或供应商原始响应。 */
    public static final String KEY_INPUT_MODE = "inputMode";
    public static final String KEY_INPUT_HASH = "inputHash";
    public static final String KEY_PROMPT_VERSION = "promptVersion";
    public static final String KEY_GENERATED_AT = "generatedAt";
    public static final String KEY_CONTENT = "content";
    /** 内容限制由原视频生产者与作品集兜底共同使用。 */
    public static final int SUMMARY_MAX_CODE_POINTS = 600;
    public static final int ITEM_MAX_CODE_POINTS = 120;
    public static final int OBSERVATIONS_MAX_COUNT = 8;
    public static final int LIMITATIONS_MAX_COUNT = 4;
    /** 包装元数据有界，摘要使用严格 JSON 解析器拒绝重复字段和尾随内容。 */
    private static final int MAX_DOCUMENT_BYTES = 24 * 1024;
    private static final int MAX_CONTENT_BYTES = 16 * 1024;
    private static final int MAX_METADATA_CODE_POINTS = 128;
    private static final String HASH_PATTERN = "[0-9a-f]{64}";
    private static final Set<String> DOCUMENT_FIELDS = Set.of(KEY_SCHEMA_VERSION, KEY_INPUT_MODE,
            KEY_SOURCE_VERSION, KEY_INPUT_HASH, KEY_MODEL, KEY_PROMPT_VERSION, KEY_GENERATED_AT, KEY_CONTENT);
    private static final Set<String> CONTENT_FIELDS = Set.of(SUMMARY, OBSERVATIONS, LIMITATIONS);
    /** 诊断路径只由合同字段常量和数组下标组成。 */
    private static final String ROOT_PATH = "$";
    /** 诊断输出模板只承载可信规则、类型和数值，不接受模型正文。 */
    private static final String DIAGNOSTIC_FORMAT = "path=%s rule=%s%s%s";
    private static final String TYPE_FORMAT = " actualType=%s";
    private static final String LENGTH_FORMAT = " actualCodePoints=%d maxCodePoints=%d";
    private static final String COUNT_FORMAT = " actualCount=%d maxCount=%d";
    private static final String FIELDS_FORMAT = " actualFields=%d expectedFields=%d unknownFields=%d missingFields=%s";
    private static final String PARSER_FORMAT = " parserReason=%s actualChars=%d maxBytes=%d maxDepth=%d";

    /** 规则名由代码闭合集合产生，禁止让模型值充当诊断类别。 */
    private enum Rule {
        JSON_INVALID, FIELDS, EXPECTED_VALUE, HASH_FORMAT, SOURCE_MISMATCH,
        METADATA_TYPE, METADATA_BLANK, METADATA_LENGTH, METADATA_CONTROL, INSTANT_FORMAT,
        NO_FACTS, TEXT_TYPE, TEXT_LENGTH, ARRAY_TYPE, ARRAY_COUNT, CLEAN_TEXT_LENGTH
    }

    /** 摘要校验异常保留既有错误合同，同时提供不含模型原文的安全诊断。 */
    public static final class ValidationException extends AiProtocolException {
        /** 只允许本合同在抛出点构造的可信诊断。 */
        private final String diagnostic;

        /** 私有构造避免调用方将不可信原文混入日志诊断。 */
        private ValidationException(String code, String message, String diagnostic) {
            super(code, message);
            this.diagnostic = diagnostic;
        }

        /** 返回固定字段路径、规则、类型和数值边界，不返回字段原值。 */
        public String diagnostic() { return diagnostic; }
    }

    /**
     * 原视频模型正文的规整结果，不携带被舍弃的正文。
     * @param content 已清理并按合同限制条目数量的正文
     * @param observationsBefore 模型原始观察条数，仅用于安全日志
     * @param limitationsBefore 模型原始限制条数，仅用于安全日志
     */
    public record NormalizedContent(ObjectNode content, int observationsBefore, int limitationsBefore) {
        /** 只报告是否减少条目数量，联系方式清理沿用原行为。 */
        public boolean changed() {
            return observationsBefore != content.path(OBSERVATIONS).size()
                    || limitationsBefore != content.path(LIMITATIONS).size();
        }
    }

    /** 禁止构造静态合同。 */
    private PortfolioAiStoredVideoSummary() { }

    /** 与既有帧清单和媒体冻结一致的源身份，ETag 引号与大小写差异不改变身份。 */
    public static String sourceVersion(String key, String etag, long bytes, String sha256) {
        String value = key + "\n" + (etag == null ? "" : etag.replace("\"", "").strip().toLowerCase(Locale.ROOT))
                + "\n" + bytes + "\n" + (sha256 == null ? "" : sha256);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance(SHA256_ALGORITHM).digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 不可用", exception); }
    }

    /** 生产者保存前采用与消费者完全相同的封闭校验，不添加额外数据库状态字段。 */
    public static ObjectNode create(String sourceVersion, String inputHash, String model,
                                    String promptVersion, Instant generatedAt, JsonNode content) {
        ObjectNode value = StrictAiJson.mapper().createObjectNode().put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                .put(KEY_INPUT_MODE, INPUT_MODE).put(KEY_SOURCE_VERSION, sourceVersion).put(KEY_INPUT_HASH, inputHash)
                .put(KEY_MODEL, model).put(KEY_PROMPT_VERSION, promptVersion)
                .put(KEY_GENERATED_AT, generatedAt == null ? null : generatedAt.toString());
        value.set(KEY_CONTENT, content);
        return parse(value.toString(), sourceVersion);
    }

    /** 返回已校验并清理的完整文档；模型或提示词升级不使已生成内容失效。 */
    public static ObjectNode parse(String raw, String expectedSourceVersion) {
        ObjectNode value = parseObject(raw, MAX_DOCUMENT_BYTES, 10, ROOT_PATH, false);
        requireFields(value, DOCUMENT_FIELDS, ROOT_PATH, false);
        requireValue(value.path(KEY_SCHEMA_VERSION), SCHEMA_VERSION, child(ROOT_PATH, KEY_SCHEMA_VERSION));
        requireValue(value.path(KEY_INPUT_MODE), INPUT_MODE, child(ROOT_PATH, KEY_INPUT_MODE));
        requireHash(value.path(KEY_SOURCE_VERSION), child(ROOT_PATH, KEY_SOURCE_VERSION));
        if (expectedSourceVersion == null || !expectedSourceVersion.equals(value.path(KEY_SOURCE_VERSION).asText())) {
            throw invalid(child(ROOT_PATH, KEY_SOURCE_VERSION), Rule.SOURCE_MISMATCH, value.path(KEY_SOURCE_VERSION), "");
        }
        requireHash(value.path(KEY_INPUT_HASH), child(ROOT_PATH, KEY_INPUT_HASH));
        requireMetadata(value.path(KEY_MODEL), child(ROOT_PATH, KEY_MODEL));
        requireMetadata(value.path(KEY_PROMPT_VERSION), child(ROOT_PATH, KEY_PROMPT_VERSION));
        requireMetadata(value.path(KEY_GENERATED_AT), child(ROOT_PATH, KEY_GENERATED_AT));
        try { Instant.parse(value.path(KEY_GENERATED_AT).asText()); }
        catch (DateTimeParseException exception) {
            throw invalid(child(ROOT_PATH, KEY_GENERATED_AT), Rule.INSTANT_FORMAT, value.path(KEY_GENERATED_AT), "");
        }
        String contentPath = child(ROOT_PATH, KEY_CONTENT);
        ObjectNode content = validateContent(value.path(KEY_CONTENT).toString(), contentPath);
        boolean hasFacts = !content.path(SUMMARY).asText().isBlank();
        for (JsonNode item : content.path(OBSERVATIONS)) { hasFacts |= !item.asText().isBlank(); }
        if (!hasFacts) { throw invalid(contentPath, Rule.NO_FACTS, content, ""); }
        requireCleanLength(content.path(SUMMARY), SUMMARY_MAX_CODE_POINTS, child(contentPath, SUMMARY));
        for (String field : Set.of(OBSERVATIONS, LIMITATIONS)) {
            int index = 0;
            for (JsonNode item : content.path(field)) {
                requireCleanLength(item, ITEM_MAX_CODE_POINTS, itemPath(child(contentPath, field), index++));
            }
        }
        value.set(KEY_CONTENT, content);
        return value;
    }

    /** 持久字段缺失、损坏或过期源均按缺失处理，由作品集明确选择关键帧兜底。 */
    public static ObjectNode contentOrNull(String raw, String expectedSourceVersion) {
        ObjectNode document = documentOrNull(raw, expectedSourceVersion);
        return document == null ? null : (ObjectNode) document.path(KEY_CONTENT);
    }

    /** 冻结时保留完整合法包装，后续执行不重新读取较新的摘要或转写。 */
    public static ObjectNode documentOrNull(String raw, String expectedSourceVersion) {
        if (raw == null || raw.isBlank()) { return null; }
        try { return parse(raw, expectedSourceVersion); }
        catch (AiProtocolException exception) { return null; }
    }

    /** 已冻结任务非法时直接失败，不能通过更换路线触发额外模型请求。 */
    public static ObjectNode frozenContent(JsonNode entry) {
        requireValue(entry.path(KEY_ROUTE), ROUTE_STORED_VIDEO_SUMMARY, child(ROOT_PATH, KEY_ROUTE));
        return (ObjectNode) parse(entry.path(FIELD).toString(), entry.path(KEY_SOURCE_VERSION).asText()).path(KEY_CONTENT);
    }

    /** 封闭内容结构和 Unicode 长度，清理不可信资源地址与联系方式。 */
    public static ObjectNode validateContent(String raw) {
        return validateContent(raw, ROOT_PATH);
    }

    /** 原视频生产端专用入口；持久字段和作品集消费继续使用严格合同。 */
    public static NormalizedContent normalizeModelContent(String raw) {
        return validateContent(raw, ROOT_PATH, true);
    }

    /** 同一校验流程按调用位置附加路径，不为日志重复解析或放宽模型约束。 */
    private static ObjectNode validateContent(String raw, String path) {
        return validateContent(raw, path, false).content();
    }

    /** 生产端仅允许超量条目规整，原始全部条目仍逐一校验和清理。 */
    private static NormalizedContent validateContent(String raw, String path, boolean normalizeCounts) {
        ObjectNode value = parseObject(raw, MAX_CONTENT_BYTES, 8, path, true);
        requireFields(value, CONTENT_FIELDS, path, true);
        ObjectNode clean = StrictAiJson.mapper().createObjectNode()
                .put(SUMMARY, safeText(value.path(SUMMARY), SUMMARY_MAX_CODE_POINTS, child(path, SUMMARY)));
        if (normalizeCounts) { requireCleanLength(clean.path(SUMMARY), SUMMARY_MAX_CODE_POINTS, child(path, SUMMARY)); }
        for (String field : Set.of(OBSERVATIONS, LIMITATIONS)) {
            JsonNode values = value.path(field);
            int maximum = OBSERVATIONS.equals(field) ? OBSERVATIONS_MAX_COUNT : LIMITATIONS_MAX_COUNT;
            if (!values.isArray() || (!normalizeCounts && values.size() > maximum)) {
                throw failure("媒体摘要条目不合法", child(path, field), values.isArray() ? Rule.ARRAY_COUNT : Rule.ARRAY_TYPE,
                        values, COUNT_FORMAT.formatted(values.size(), maximum));
            }
            var array = clean.putArray(field);
            int index = 0;
            for (JsonNode item : values) {
                String itemPath = itemPath(child(path, field), index);
                String text = safeText(item, ITEM_MAX_CODE_POINTS, itemPath);
                if (normalizeCounts) { requireCleanLength(TextNode.valueOf(text), ITEM_MAX_CODE_POINTS, itemPath); }
                if (index < maximum) { array.add(text); }
                index++;
            }
        }
        return new NormalizedContent(clean, value.path(OBSERVATIONS).size(), value.path(LIMITATIONS).size());
    }

    /** 严格解析失败只透传 StrictAiJson 自身固定安全文案，不包含 Jackson 的原始异常。 */
    private static ObjectNode parseObject(String raw, int maxBytes, int maxDepth, String path, boolean content) {
        try { return StrictAiJson.parseObject(raw, maxBytes, maxDepth); }
        catch (AiProtocolException exception) {
            String detail = PARSER_FORMAT.formatted(exception.getMessage(), raw == null ? -1 : raw.length(), maxBytes, maxDepth);
            throw new ValidationException(content ? ERROR_INVALID_SUMMARY : exception.getCode(),
                    content ? "媒体摘要JSON不合法" : exception.getMessage(), diagnostic(path, Rule.JSON_INVALID, null, detail));
        }
    }

    /** 缺失字段只从可信合同取名，额外字段仅计数，绝不回显未知键。 */
    private static void requireFields(ObjectNode node, Set<String> fields, String path, boolean content) {
        int present = (int) fields.stream().filter(node::has).count();
        if (node.size() == fields.size() && present == fields.size()) { return; }
        String missing = fields.stream().filter(field -> !node.has(field)).sorted().collect(Collectors.joining(","));
        String detail = FIELDS_FORMAT.formatted(node.size(), fields.size(), node.size() - present, missing);
        if (!content) { throw invalid(path, Rule.FIELDS, node, detail); }
        throw failure(node.size() != fields.size() ? "媒体摘要结构不合法" : "媒体摘要字段不合法", path, Rule.FIELDS, node, detail);
    }

    /** 元数据不允许空白、控制字符或超长内容。 */
    private static void requireMetadata(JsonNode node, String path) {
        if (!node.isTextual()) { throw invalid(path, Rule.METADATA_TYPE, node, ""); }
        if (node.asText().isBlank()) { throw invalid(path, Rule.METADATA_BLANK, node, ""); }
        int count = codePoints(node);
        if (count > MAX_METADATA_CODE_POINTS) {
            throw invalid(path, Rule.METADATA_LENGTH, node, LENGTH_FORMAT.formatted(count, MAX_METADATA_CODE_POINTS));
        }
        if (node.asText().codePoints().anyMatch(Character::isISOControl)) { throw invalid(path, Rule.METADATA_CONTROL, node, ""); }
    }

    /** 身份必须使用标准小写 SHA256。 */
    private static void requireHash(JsonNode node, String path) {
        if (!node.isTextual() || !node.asText().matches(HASH_PATTERN)) { throw invalid(path, Rule.HASH_FORMAT, node, ""); }
    }

    /** 固定版本和路线不输出不可信实际值，保持原 asText 比较语义。 */
    private static void requireValue(JsonNode node, String expected, String path) {
        if (!expected.equals(node.asText())) { throw invalid(path, Rule.EXPECTED_VALUE, node, ""); }
    }

    /** 既有帧摘要沿用清理前的长度校验，不改变 v1/v2 模型结果语义。 */
    private static String safeText(JsonNode node, int maximum, String path) {
        if (!node.isTextual()) { throw failure("媒体摘要文本超过限制", path, Rule.TEXT_TYPE, node, ""); }
        int count = codePoints(node);
        if (count > maximum) {
            throw failure("媒体摘要文本超过限制", path, Rule.TEXT_LENGTH, node, LENGTH_FORMAT.formatted(count, maximum));
        }
        return PortfolioAiVideoAudioContext.sanitize(node.asText());
    }

    /** 新持久包装额外保证清理后的内容仍有界，旧缓存不受影响。 */
    private static void requireCleanLength(JsonNode node, int maximum, String path) {
        int count = codePoints(node);
        if (count > maximum) { throw invalid(path, Rule.CLEAN_TEXT_LENGTH, node, LENGTH_FORMAT.formatted(count, maximum)); }
    }

    /** 码点计数与原边界保持一致，不把 UTF-16 代理项计为两个字符。 */
    private static int codePoints(JsonNode node) { return node.asText().codePointCount(0, node.asText().length()); }

    /** 字段路径仅允许调用方传入本类可信常量。 */
    private static String child(String path, String field) { return path + "." + field; }

    /** 数组下标来源于本地顺序遍历，不接受模型定义的路径。 */
    private static String itemPath(String path, int index) { return path + "[" + index + "]"; }

    /** 类型使用 Jackson 固定枚举，附加内容仅来自本类固定模板。 */
    private static String diagnostic(String path, Rule rule, JsonNode node, String detail) {
        return DIAGNOSTIC_FORMAT.formatted(path, rule, node == null ? "" : TYPE_FORMAT.formatted(node.getNodeType()), detail);
    }

    /** 正文校验保留原提示和错误码，仅添加可供服务日志读取的安全诊断。 */
    private static ValidationException failure(String message, String path, Rule rule, JsonNode node, String detail) {
        return new ValidationException(ERROR_INVALID_SUMMARY, message, diagnostic(path, rule, node, detail));
    }

    /** 各应用入口可按自身用例转换为摘要缺失或模型失败。 */
    private static ValidationException invalid(String path, Rule rule, JsonNode node, String detail) {
        return failure("视频摘要结构或内容不合法", path, rule, node, detail);
    }
}
