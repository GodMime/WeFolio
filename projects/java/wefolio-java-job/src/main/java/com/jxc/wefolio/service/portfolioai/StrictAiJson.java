package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** 独立的严格 JSON 边界；解析过程中限制规模、深度、重复键和非法 Unicode。 */
public final class StrictAiJson {
    /** 默认完整配置字节预算。 */
    public static final int CONFIG_MAX_BYTES = 256 * 1024;
    /** 来源编号上下文字节预算。 */
    public static final int CONTEXT_MAX_BYTES = 128 * 1024;
    /** 供应商响应及冻结请求字节预算。 */
    public static final int RESPONSE_MAX_BYTES = 2 * 1024 * 1024;
    /** 根对象计第一层的最大容器深度。 */
    public static final int MAX_DEPTH = 16;
    /** 结构拒绝的固定错误码。 */
    private static final String INVALID = "INVALID_OUTPUT";
    /** JSON 序列化器不启用任意类型实例化。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 精确保留数值类型的节点工厂。 */
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private StrictAiJson() { }

    /** 在创建完整对象树前检查预算并流式解析唯一根对象。 */
    public static ObjectNode parseObject(String text, int maxBytes, int maxDepth) {
        if (text == null || maxBytes <= 0 || maxDepth <= 0
                || text.length() > maxBytes || text.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw invalid("JSON 字节数超过上限");
        }
        unicode(text);
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(maxDepth)
                        .maxStringLength(maxBytes).maxNumberLength(256).build()).build();
        try (JsonParser parser = factory.createParser(text)) {
            JsonToken token = parser.nextToken();
            if (token != JsonToken.START_OBJECT) { throw invalid("JSON 根必须为对象"); }
            ObjectNode result = (ObjectNode) read(parser, 1, maxDepth);
            if (parser.nextToken() != null) { throw invalid("JSON 后不得附加其他内容"); }
            return result;
        } catch (IOException | ArithmeticException exception) {
            throw invalid("JSON 语法、重复键、数值或解析规模不合法");
        }
    }

    /** 严格递归读取当前 token，深度限制在容器创建前执行。 */
    private static JsonNode read(JsonParser parser, int depth, int maxDepth) throws IOException {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
            if (depth > maxDepth) { throw invalid("JSON 容器深度超过上限"); }
            if (token == JsonToken.START_OBJECT) {
                ObjectNode object = NODES.objectNode();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.FIELD_NAME) { throw invalid("JSON 对象字段不合法"); }
                    String key = parser.currentName();
                    unicode(key);
                    parser.nextToken();
                    object.set(key, read(parser, depth + 1, maxDepth));
                }
                return object;
            }
            ArrayNode array = NODES.arrayNode();
            while (parser.nextToken() != JsonToken.END_ARRAY) { array.add(read(parser, depth + 1, maxDepth)); }
            return array;
        }
        if (token == JsonToken.VALUE_STRING) {
            String value = parser.getText(); unicode(value); return NODES.textNode(value);
        }
        if (token == JsonToken.VALUE_NUMBER_INT) {
            var number = parser.getBigIntegerValue();
            if (number.bitLength() > 63) { throw invalid("JSON 整数溢出"); }
            return number.bitLength() < 32 ? NODES.numberNode(number.intValue()) : NODES.numberNode(number.longValue());
        }
        if (token == JsonToken.VALUE_NUMBER_FLOAT) {
            BigDecimal value = parser.getDecimalValue();
            if (!Double.isFinite(value.doubleValue()) || value.precision() > 256
                    || Math.abs((long) value.scale()) > 1000) { throw invalid("JSON 数值溢出"); }
            return NODES.numberNode(value);
        }
        if (token == JsonToken.VALUE_TRUE) { return NODES.booleanNode(true); }
        if (token == JsonToken.VALUE_FALSE) { return NODES.booleanNode(false); }
        if (token == JsonToken.VALUE_NULL) { return NODES.nullNode(); }
        throw invalid("JSON 值不合法");
    }

    /** 拒绝未配对代理项，避免非法 Unicode 经编码替换后掩盖错误。 */
    private static void unicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw invalid("JSON 包含非法 Unicode");
                }
            } else if (Character.isLowSurrogate(ch)) { throw invalid("JSON 包含非法 Unicode"); }
        }
    }

    /** 按对象键排序并保留数组顺序生成规范文本。 */
    public static String canonical(JsonNode node) { return sorted(node).toString(); }

    /** 生成不泄露业务内容的稳定 SHA-256 基准。 */
    public static String canonicalHash(JsonNode node) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical(node).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 不可用"); }
    }

    /** 对 JSON 数据作深拷贝并固定对象键顺序。 */
    private static JsonNode sorted(JsonNode node) {
        if (node == null || node.isNull()) { return NODES.nullNode(); }
        if (node.isObject()) {
            ObjectNode result = NODES.objectNode();
            List<String> names = new ArrayList<>(); node.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> result.set(name, sorted(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = NODES.arrayNode(); node.forEach(item -> result.add(sorted(item))); return result;
        }
        return node.deepCopy();
    }

    /** 提供独立序列化器用于可信快照和协议对象转换。 */
    public static ObjectMapper mapper() { return MAPPER.copy(); }

    /** 创建不携带原文的固定解析拒绝。 */
    private static AiProtocolException invalid(String message) { return new AiProtocolException(INVALID, message); }
}
