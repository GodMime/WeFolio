package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 参考附件独立冻结协议；仅解释本期原件，不引入作品绑定或摘要缓存。 */
public final class PortfolioAiReferenceContract {
    /** 原件目录与 Runtime 保持相同历史拼写。 */
    public static final String DIRECTORY = "/protfolio/ai-reference/";
    /** 独立参考摘要字段，不进入来源能力列表。 */
    public static final String SUMMARIES = "referenceSummaries";
    /** 不可延期的上传和终态保存窗口。 */
    public static final long RETENTION_SECONDS = 86_400;
    /** 单次附件理解的保守输入和输出预留。 */
    public static final int INPUT_TOKENS = 950_000;
    public static final int OUTPUT_TOKENS = 16_384;
    /** 产品数量及文字限制。 */
    public static final int MAX_ITEMS = 6;
    public static final int SUMMARY_CODE_POINTS = 2_000;
    /** 所有 JSON 均有字节和深度上限。 */
    private static final int MAX_JSON_BYTES = 65_536;
    public static final String VERSION = "version";
    public static final String ITEMS = "items";
    public static final String NEXT = "nextReferenceNo";
    public static final String ID = "attachmentId";
    public static final String NUMBER = "referenceNo";
    public static final String KEY = "objectKey";
    public static final String TYPE = "mediaType";
    public static final String MIME = "mimeType";
    public static final String SIZE = "sizeBytes";
    public static final String WIDTH = "width";
    public static final String HEIGHT = "height";
    public static final String DURATION = "durationMs";
    public static final String CONTENT = "contentVersion";
    public static final String ETAG = "etag";
    public static final String MODIFIED = "lastModifiedEpochMs";
    public static final String NAME = "originalFileName";
    public static final String NOTE = "note";
    public static final String IMAGE = "IMAGE";
    public static final String VIDEO = "VIDEO";
    public static final String SUMMARY = "summary";
    /** 固定媒体 MIME、编号及字节规格，两工程按同一白名单解释。 */
    public static final String JPEG_MIME = "image/jpeg", PNG_MIME = "image/png", VIDEO_MIME = "video/mp4";
    public static final String IMAGE_URL = "image_url", VIDEO_URL = "video_url";
    public static final String OWNER_USER = "USER", OWNER_TEAM = "TEAM";
    public static final String ERROR_UNAVAILABLE = "REFERENCE_UNAVAILABLE", ERROR_ANALYSIS = "REFERENCE_ANALYSIS_FAILED";
    private static final String ID_PATTERN = "[a-f0-9]{32}";
    private static final String JPG_SUFFIX = ".jpg", PNG_SUFFIX = ".png", VIDEO_SUFFIX = ".mp4";
    private static final long MAX_IMAGE_BYTES = 4_000_000, MAX_VIDEO_BYTES = 100_000_000;
    private static final long MAX_DURATION_MS = 300_000, MAX_NEXT_NUMBER = 1_000_000_000;
    private static final int MAX_EDGE = 4096, MAX_NOTE_CODE_POINTS = 200;
    private static final Pattern RESOURCE_TEXT = Pattern.compile("(?i)(https?://|www\\.|/protfolio/|ai-reference/)");
    private static final Pattern OBJECT_KEY = Pattern.compile("[A-Za-z0-9_-]+/protfolio/ai-reference/[a-f0-9]{32}\\.(jpg|png|mp4)");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("A[1-9][0-9]{0,8}");
    private static final Set<String> ITEM_FIELDS = Set.of(ID, NUMBER, KEY, TYPE, MIME, SIZE, WIDTH, HEIGHT,
            DURATION, CONTENT, NAME, NOTE);
    /** 静态合同不需要实例。 */
    private PortfolioAiReferenceContract() { }

    /** 非空列包括损坏快照都必须走媒体恢复，不能重新按普通文字免费重试。 */
    public static boolean hasReferences(AiGenerationTaskEntity task) {
        return task.getReferenceAttachmentsJson() != null;
    }

    /** 读取完整封闭协议；清理也使用同一严格解释，坏数据不得被当成空引用。 */
    public static List<JsonNode> items(String raw, boolean portfolio) {
        if (raw == null) { return List.of(); }
        ObjectNode root;
        try { root = StrictAiJson.parseObject(raw, MAX_JSON_BYTES, 8); }
        catch (RuntimeException exception) { throw invalid(); }
        fields(root, portfolio ? Set.of(VERSION, ITEMS, NEXT) : Set.of(VERSION, ITEMS));
        if (!root.path(VERSION).isIntegralNumber() || !root.path(VERSION).canConvertToInt() || root.path(VERSION).asInt() != 1
                || !root.path(ITEMS).isArray() || root.path(ITEMS).size() > MAX_ITEMS
                || portfolio && (!positive(root.path(NEXT)) || root.path(NEXT).asLong() > MAX_NEXT_NUMBER)) { throw invalid(); }
        List<JsonNode> result = new ArrayList<>();
        Set<String> identities = new HashSet<>(); Set<String> numbers = new HashSet<>();
        int videos = 0;
        for (JsonNode item : root.path(ITEMS)) {
            fields(item, ITEM_FIELDS);
            String id = text(item, ID); String key = text(item, KEY); String no = text(item, NUMBER);
            String type = text(item, TYPE); String mime = text(item, MIME);
            if (!id.matches(ID_PATTERN) || !OBJECT_KEY.matcher(key).matches()
                    || !key.substring(key.lastIndexOf('/') + 1).startsWith(id + ".")
                    || !NUMBER_PATTERN.matcher(no).matches() || !identities.add(id) || !numbers.add(no)
                    || !positive(item.path(SIZE)) || !positive(item.path(WIDTH)) || !positive(item.path(HEIGHT))
                    || !item.path(WIDTH).canConvertToInt() || !item.path(HEIGHT).canConvertToInt()) { throw invalid(); }
            if (portfolio && Long.parseLong(no.substring(1)) >= root.path(NEXT).asLong()) { throw invalid(); }
            if (VIDEO.equals(type)) {
                videos++;
                if (videos > 1 || !key.endsWith(VIDEO_SUFFIX) || !VIDEO_MIME.equals(mime)
                        || item.path(SIZE).asLong() > MAX_VIDEO_BYTES || !positive(item.path(DURATION))
                        || item.path(DURATION).asLong() > MAX_DURATION_MS) { throw invalid(); }
            } else if (IMAGE.equals(type)) {
                if (!(key.endsWith(JPG_SUFFIX) && JPEG_MIME.equals(mime) || key.endsWith(PNG_SUFFIX) && PNG_MIME.equals(mime))
                        || item.path(SIZE).asLong() > MAX_IMAGE_BYTES || item.path(WIDTH).asLong() > MAX_EDGE
                        || item.path(HEIGHT).asLong() > MAX_EDGE || !item.path(DURATION).isNull()) { throw invalid(); }
            } else { throw invalid(); }
            String note = text(item, NOTE); String name = text(item, NAME);
            if (note.codePointCount(0, note.length()) > MAX_NOTE_CODE_POINTS || name.codePointCount(0, name.length()) > MAX_NOTE_CODE_POINTS
                    || name.contains("/") || name.contains("\\") || name.codePoints().anyMatch(Character::isISOControl)) { throw invalid(); }
            JsonNode version = item.path(CONTENT);
            fields(version, Set.of(ETAG, MODIFIED));
            if (text(version, ETAG).isBlank() || !positive(version.path(MODIFIED))
                    || version.path(MODIFIED).asLong() > Long.MAX_VALUE - RETENTION_SECONDS * 1000) { throw invalid(); }
            result.add(item.deepCopy());
        }
        return List.copyOf(result);
    }

    /** 精确原件命名白名单，也用于扫描过滤。 */
    public static boolean isObjectKey(String key) { return key != null && OBJECT_KEY.matcher(key).matches(); }

    /** 图片一次组请求、视频一次独立请求，沿冻结顺序保持编号语义。 */
    public static List<List<JsonNode>> groups(List<JsonNode> items) {
        List<JsonNode> images = items.stream().filter(item -> IMAGE.equals(item.path(TYPE).asText())).toList();
        List<JsonNode> videos = items.stream().filter(item -> VIDEO.equals(item.path(TYPE).asText())).toList();
        List<List<JsonNode>> groups = new ArrayList<>();
        if (!images.isEmpty()) { groups.add(images); }
        if (!videos.isEmpty()) { groups.add(videos); }
        return groups;
    }

    /** 固定诊断分类，不携带模型生成的字段名、编号或正文。 */
    public enum SummaryReason {
        EMPTY_RESPONSE, JSON_BYTES_LIMIT, JSON_PARSE, ROOT_FIELDS, VERSION_INVALID,
        VERSION_STRING, VERSION_NULL, VERSION_NON_INTEGER, VERSION_OUT_OF_RANGE, VERSION_CONTAINER_OR_BOOLEAN,
        ITEMS_TYPE, ITEM_COUNT, ITEM_FIELDS, NUMBER_TYPE, NUMBER_UNKNOWN_OR_DUPLICATE,
        SUMMARY_TYPE, SUMMARY_BLANK, SUMMARY_TOO_LONG, RESOURCE_ADDRESS, PRIVATE_IDENTITY, NUMBER_MISSING
    }

    /** 只增加内部诊断，保留既有错误码、文案与不重试语义。 */
    public static final class SummaryValidationException extends PortfolioAiCallException {
        /** 固定分类及从零开始的条目位置；未进入条目时为负一。 */
        private final SummaryReason reason;
        private final int itemIndex;
        /** 实际数量或长度及对应期望值；不适用时为负一。 */
        private final int actual;
        private final int expected;

        /** 不保留原始异常，避免解析异常夹带正文。 */
        private SummaryValidationException(SummaryReason reason, int itemIndex, int actual, int expected) {
            super(ERROR_ANALYSIS, "参考附件分析结果不完整，请重试或更换附件", false);
            this.reason = reason; this.itemIndex = itemIndex; this.actual = actual; this.expected = expected;
        }

        /** 仅返回枚举和整数，供执行边界安全记录。 */
        public String diagnostic() {
            return "reason=" + reason + ",itemIndex=" + itemIndex + ",actual=" + actual + ",expected=" + expected;
        }
    }

    /** 验证规则不变，失败点使用固定分类保留诊断；不得静默裁剪或漏项。 */
    public static ArrayNode summaries(String raw, List<JsonNode> expected) {
        if (raw == null) { throw summaryInvalid(SummaryReason.EMPTY_RESPONSE, -1, -1, -1); }
        int bytes = raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes > MAX_JSON_BYTES) { throw summaryInvalid(SummaryReason.JSON_BYTES_LIMIT, -1, bytes, MAX_JSON_BYTES); }
        ObjectNode root;
        try { root = StrictAiJson.parseObject(raw, MAX_JSON_BYTES, 5); }
        catch (RuntimeException exception) { throw summaryInvalid(SummaryReason.JSON_PARSE, -1, -1, -1); }
        summaryFields(root, Set.of(VERSION, ITEMS), SummaryReason.ROOT_FIELDS, -1);
        JsonNode version = root.path(VERSION);
        if (!version.isIntegralNumber()) {
            SummaryReason reason = version.isTextual() ? SummaryReason.VERSION_STRING
                    : version.isNull() ? SummaryReason.VERSION_NULL
                    : version.isNumber() ? SummaryReason.VERSION_NON_INTEGER : SummaryReason.VERSION_CONTAINER_OR_BOOLEAN;
            throw summaryInvalid(reason, -1, -1, 1);
        }
        if (!version.canConvertToInt()) { throw summaryInvalid(SummaryReason.VERSION_OUT_OF_RANGE, -1, -1, 1); }
        if (version.asInt() != 1) {
            throw summaryInvalid(SummaryReason.VERSION_INVALID, -1, version.asInt(), 1);
        }
        if (!root.path(ITEMS).isArray()) { throw summaryInvalid(SummaryReason.ITEMS_TYPE, -1, -1, -1); }
        if (root.path(ITEMS).size() != expected.size()) {
            throw summaryInvalid(SummaryReason.ITEM_COUNT, -1, root.path(ITEMS).size(), expected.size());
        }
        Set<String> numbers = new HashSet<>(); expected.forEach(item -> numbers.add(item.path(NUMBER).asText()));
        ArrayNode result = StrictAiJson.mapper().createArrayNode();
        int index = 0;
        for (JsonNode item : root.path(ITEMS)) {
            summaryFields(item, Set.of(NUMBER, SUMMARY), SummaryReason.ITEM_FIELDS, index);
            if (!item.path(NUMBER).isTextual()) { throw summaryInvalid(SummaryReason.NUMBER_TYPE, index, -1, -1); }
            if (!item.path(SUMMARY).isTextual()) { throw summaryInvalid(SummaryReason.SUMMARY_TYPE, index, -1, -1); }
            String no = item.path(NUMBER).textValue(); String value = item.path(SUMMARY).textValue();
            if (!numbers.remove(no)) { throw summaryInvalid(SummaryReason.NUMBER_UNKNOWN_OR_DUPLICATE, index, -1, -1); }
            if (value.isBlank()) { throw summaryInvalid(SummaryReason.SUMMARY_BLANK, index, -1, -1); }
            int length = value.codePointCount(0, value.length());
            if (length > SUMMARY_CODE_POINTS) { throw summaryInvalid(SummaryReason.SUMMARY_TOO_LONG, index, length, SUMMARY_CODE_POINTS); }
            if (RESOURCE_TEXT.matcher(value).find()) { throw summaryInvalid(SummaryReason.RESOURCE_ADDRESS, index, -1, -1); }
            if (expected.stream().anyMatch(source -> value.contains(source.path(KEY).asText()) || value.contains(source.path(ID).asText()))) {
                throw summaryInvalid(SummaryReason.PRIVATE_IDENTITY, index, -1, -1);
            }
            result.add(item.deepCopy()); index++;
        }
        if (!numbers.isEmpty()) { throw summaryInvalid(SummaryReason.NUMBER_MISSING, -1, numbers.size(), 0); }
        return result;
    }

    /** 封闭字段检查只报告分类与数量，不回显不可信字段名。 */
    private static void summaryFields(JsonNode node, Set<String> allowed, SummaryReason reason, int index) {
        if (!node.isObject() || node.size() != allowed.size()) { throw summaryInvalid(reason, index, node.size(), allowed.size()); }
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) { throw summaryInvalid(reason, index, node.size(), allowed.size()); }
        });
    }

    /** 在付费前预留最坏合法摘要体积，控制字符覆盖 JSON 双重转义上界。 */
    public static ArrayNode maximumSummaries(List<JsonNode> items) {
        ArrayNode result = StrictAiJson.mapper().createArrayNode();
        for (JsonNode item : items) { result.addObject().put(NUMBER, item.path(NUMBER).asText())
                .put(NOTE, item.path(NOTE).asText()).put(SUMMARY, String.valueOf((char) 1).repeat(SUMMARY_CODE_POINTS)); }
        return result;
    }

    /** 严格封闭对象，重复键由 StrictAiJson 拒绝。 */
    private static void fields(JsonNode node, Set<String> fields) {
        if (!node.isObject() || node.size() != fields.size()) { throw invalid(); }
        node.fieldNames().forEachRemaining(field -> { if (!fields.contains(field)) { throw invalid(); } });
    }
    /** 禁止数字或布尔隐式转换成业务文字。 */
    private static String text(JsonNode node, String key) { if (!node.path(key).isTextual()) { throw invalid(); } return node.path(key).textValue(); }
    /** 精确正整数，避免溢出或浮点规格。 */
    private static boolean positive(JsonNode node) { return node.isIntegralNumber() && node.canConvertToLong() && node.asLong() > 0; }
    /** 快照损坏时停止当前执行或 owner 清理。 */
    private static PortfolioAiCallException invalid() { return new PortfolioAiCallException(ERROR_UNAVAILABLE, "参考附件不可用，请重新选择", false); }
    /** 摘要不完整或含资源时不进入最终布局。 */
    private static SummaryValidationException summaryInvalid(SummaryReason reason, int index, int actual, int expected) {
        return new SummaryValidationException(reason, index, actual, expected);
    }
}
