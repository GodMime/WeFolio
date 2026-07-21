package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 微信交互日志统一脱敏组件。 */
@Slf4j
@Component
public class WechatInteractionLogSanitizer {

    /** 完全脱敏占位符。 */
    private static final String MASKED_VALUE = "***";

    /** 脱敏失败安全占位符。 */
    private static final String MASK_FAILED_VALUE = "[日志脱敏失败]";

    /** 需要完全隐藏的字段。 */
    private static final Set<String> SECRET_FIELDS = Set.of(
            "accesstoken", "refreshtoken", "sessionkey", "appsecret", "secret",
            "appkey", "paysig", "signature", "msgsignature", "messagetoken",
            "encodingaeskey", "jscode", "code", "phonecode", "encrypt", "encrypted",
            "token", "authorization", "echostr", "nonce"
    );

    /** 仅保留末四位的身份字段。 */
    private static final Set<String> TAIL_VISIBLE_FIELDS = Set.of(
            "openid", "unionid", "pluginopenid", "phonenumber", "purephonenumber",
            "countrycode"
    );

    /** 普通文本和 URL 中的敏感键值对。 */
    private static final Pattern SENSITIVE_PAIR_PATTERN = Pattern.compile(
            "(?i)\\b(access[_-]?token|refresh[_-]?token|session[_-]?key|app[_-]?secret|secret|"
                    + "app[_-]?key|pay[_-]?sig|signature|msg[_-]?signature|message[_-]?token|"
                    + "encoding[_-]?aes[_-]?key|js[_-]?code|phone[_-]?code|code|encrypt|encrypted|"
                    + "token|authorization|echostr|nonce|openid|unionid|plugin[_-]?openid|"
                    + "phone[_-]?number|pure[_-]?phone[_-]?number|country[_-]?code)"
                    + "(\\s*[=:]\\s*)([\"']?)([^\\s,;&\"']+)([\"']?)"
    );

    /** 脱敏 JSON 文本。 */
    public String sanitizeJson(String text) {
        return safely(() -> sanitizeJsonInternal(text));
    }

    /** 脱敏 XML 文本。 */
    public String sanitizeXml(String text) {
        return safely(() -> sanitizeXmlInternal(text));
    }

    /** 脱敏 URL 及其查询参数。 */
    public String sanitizeUrl(String text) {
        return safely(() -> sanitizeUnstructured(text));
    }

    /** 按文本形态选择最合适的脱敏方式。 */
    public String sanitizeText(String text) {
        return safely(() -> {
            if (text == null || text.isBlank()) {
                return text;
            }
            String stripped = text.stripLeading();
            if (stripped.startsWith("{") || stripped.startsWith("[")) {
                return sanitizeJsonInternal(text);
            }
            if (stripped.startsWith("<")) {
                return sanitizeXmlInternal(text);
            }
            return sanitizeUnstructured(text);
        });
    }

    /** 解析并递归脱敏 JSON。 */
    private String sanitizeJsonInternal(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        try {
            return JSON.toJSONString(sanitizeJsonValue(JSON.parse(text), null));
        } catch (RuntimeException exception) {
            return sanitizeUnstructured(text);
        }
    }

    /** 递归脱敏 JSON 对象、数组和字段值。 */
    private Object sanitizeJsonValue(Object value, String fieldName) {
        MaskMode mode = maskMode(fieldName);
        if (mode != MaskMode.NONE) {
            return maskValue(value == null ? null : String.valueOf(value), mode);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> sanitized.put(
                    String.valueOf(key), sanitizeJsonValue(nestedValue, String.valueOf(key))));
            return sanitized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> sanitized = new ArrayList<>();
            iterable.forEach(item -> sanitized.add(sanitizeJsonValue(item, null)));
            return sanitized;
        }
        return value;
    }

    /** 使用安全 XML 解析器脱敏节点。 */
    private String sanitizeXmlInternal(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        try {
            DocumentBuilderFactory documentFactory = DocumentBuilderFactory.newInstance();
            documentFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            documentFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            documentFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            documentFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            documentFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            documentFactory.setXIncludeAware(false);
            documentFactory.setExpandEntityReferences(false);
            Document document = documentFactory.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(text)));
            sanitizeXmlNode(document.getDocumentElement());

            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter output = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(output));
            return output.toString();
        } catch (Exception exception) {
            return sanitizeUnstructured(text);
        }
    }

    /** 递归脱敏 XML 元素文本。 */
    private void sanitizeXmlNode(Element element) {
        MaskMode mode = maskMode(element.getTagName());
        if (mode != MaskMode.NONE) {
            element.setTextContent(maskValue(element.getTextContent(), mode));
            return;
        }
        NodeList children = element.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element childElement) {
                sanitizeXmlNode(childElement);
            }
        }
    }

    /** 保守脱敏普通文本中的敏感键值对。 */
    private String sanitizeUnstructured(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        Matcher matcher = SENSITIVE_PAIR_PATTERN.matcher(text);
        StringBuilder sanitized = new StringBuilder();
        while (matcher.find()) {
            MaskMode mode = maskMode(matcher.group(1));
            String replacement = matcher.group(1) + matcher.group(2) + matcher.group(3)
                    + maskValue(matcher.group(4), mode) + matcher.group(5);
            matcher.appendReplacement(sanitized, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sanitized);
        return sanitized.toString();
    }

    /** 根据字段名选择脱敏模式。 */
    private MaskMode maskMode(String fieldName) {
        if (fieldName == null) {
            return MaskMode.NONE;
        }
        String normalized = fieldName.toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "");
        if (TAIL_VISIBLE_FIELDS.contains(normalized)) {
            return MaskMode.TAIL_FOUR;
        }
        return SECRET_FIELDS.contains(normalized) ? MaskMode.FULL : MaskMode.NONE;
    }

    /** 按模式脱敏字段值。 */
    private String maskValue(String value, MaskMode mode) {
        if (mode == MaskMode.FULL || value == null || value.length() <= 4) {
            return MASKED_VALUE;
        }
        return MASKED_VALUE + value.substring(value.length() - 4);
    }

    /** 保证脱敏异常不影响微信业务。 */
    private String safely(Supplier<String> operation) {
        try {
            return operation.get();
        } catch (RuntimeException exception) {
            log.error("微信交互日志脱敏失败 exceptionType={}", exception.getClass().getSimpleName());
            return MASK_FAILED_VALUE;
        }
    }

    /** 字段脱敏模式。 */
    private enum MaskMode {
        NONE,
        FULL,
        TAIL_FOUR
    }
}
