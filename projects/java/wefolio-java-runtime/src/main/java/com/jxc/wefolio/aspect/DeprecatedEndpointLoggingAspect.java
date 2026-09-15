package com.jxc.wefolio.aspect;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.common.auth.VisitorContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 弃用接口日志切面 — 统一记录弃用 Controller 接口的调用信息。 */
@Slf4j
@Aspect
@Component
@Order(AspectOrders.DEPRECATED_ENDPOINT_LOGGING)
public class DeprecatedEndpointLoggingAspect {

    /** 敏感字段占位符。 */
    private static final String MASKED_VALUE = "***";

    /** 入参序列化失败占位符。 */
    private static final String SERIALIZATION_FAILED_VALUE = "[参数序列化失败]";

    /** 字符串截断标记。 */
    private static final String STRING_TRUNCATED_VALUE = "[字符串已截断]";

    /** 集合截断标记。 */
    private static final String COLLECTION_TRUNCATED_VALUE = "[剩余元素已截断]";

    /** 对象字段截断标记。 */
    private static final String FIELDS_TRUNCATED_VALUE = "[剩余字段已截断]";

    /** 递归深度截断标记。 */
    private static final String DEPTH_TRUNCATED_VALUE = "[递归深度已截断]";

    /** 循环引用占位符。 */
    private static final String CIRCULAR_REFERENCE_VALUE = "[循环引用已截断]";

    /** 全局节点截断标记。 */
    private static final String NODES_TRUNCATED_VALUE = "[入参节点已截断]";

    /** 最终日志超限标记。 */
    private static final String PARAMETERS_TOO_LARGE_VALUE = "[入参日志超过限制，已省略]";

    /** 对象字段截断标记键。 */
    private static final String TRUNCATED_FIELD_NAME = "_logTruncated";

    /** 可反射读取的项目 DTO 包前缀。 */
    private static final String DTO_PACKAGE_PREFIX = "com.jxc.wefolio.dto";

    /** 单个安全字符串正文最大字符数。 */
    private static final int MAX_STRING_LENGTH = 256;

    /** Map 字段名最大字符数。 */
    private static final int MAX_FIELD_NAME_LENGTH = 64;

    /** 单个集合最多记录的元素数。 */
    private static final int MAX_COLLECTION_ITEMS = 20;

    /** 单个对象最多记录的字段数。 */
    private static final int MAX_OBJECT_FIELDS = 50;

    /** 入参最多递归层数。 */
    private static final int MAX_NESTING_DEPTH = 6;

    /** 单次调用最多处理的入参节点数。 */
    private static final int MAX_SANITIZED_NODES = 300;

    /** 最终入参 JSON 最大字符数。 */
    private static final int MAX_PARAMETERS_JSON_LENGTH = 8192;

    /** 敏感字段名片段，命中后不记录字段值。 */
    private static final Set<String> SENSITIVE_FIELD_MARKERS = Set.of(
            "authorization", "token", "secret", "password", "passwd", "privatekey",
            "signature", "cookie", "session", "openid", "unionid", "phone", "mobile",
            "wechat", "visitorkey", "nonce", "body", "encrypt", "contactname", "needs",
            "intro", "avatar", "url", "idempotencykey"
    );

    /** 允许记录明文的非敏感字符串字段。 */
    private static final Set<String> SAFE_STRING_FIELD_NAMES = Set.of(
            "sharecode", "componentkey", "scope", "startdate", "enddate", "sourcetype",
            "consentversion", "scenecode", "businesstype", "businessid", "signtype"
    );

    /** 允许安全遍历的容器实现包前缀。 */
    private static final Set<String> TRUSTED_CONTAINER_PACKAGE_PREFIXES = Set.of(
            "java.util", "com.alibaba.fastjson2"
    );

    /**
     * 记录弃用接口调用。
     *
     * @param joinPoint 控制器方法切点
     */
    @Before("@within(org.springframework.web.bind.annotation.RestController)"
            + " && (@annotation(java.lang.Deprecated) || @within(java.lang.Deprecated))")
    public void logDeprecatedEndpoint(JoinPoint joinPoint) {
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            log.error("弃用接口被调用: interface={}, userId={}, visitorId={}, parameters={}",
                    resolveInterface(signature),
                    AuthContextHolder.getUserId().orElse(null),
                    VisitorContextHolder.getVisitorId().orElse(null),
                    serializeParameters(signature, joinPoint.getArgs()));
        } catch (Throwable throwable) {
            logFailureSafely(throwable);
        }
    }

    /** 隔离日志处理异常，避免影响接口调用链。 */
    private void logFailureSafely(Throwable throwable) {
        try {
            log.error("弃用接口日志记录失败: exceptionType={}", throwable.getClass().getSimpleName());
        } catch (Throwable ignored) {
            // 日志兜底失败时禁止继续向接口调用链抛出异常。
        }
    }

    /** 解析 HTTP 方法和 Spring MVC 匹配后的路由模板。 */
    private String resolveInterface(MethodSignature signature) {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return signature.toShortString();
        }
        Object routePattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = routePattern == null || String.valueOf(routePattern).isBlank()
                ? request.getRequestURI()
                : String.valueOf(routePattern);
        return request.getMethod() + " " + route;
    }

    /** 按控制器参数名构造脱敏后的 JSON 入参。 */
    private String serializeParameters(MethodSignature signature, Object[] arguments) {
        try {
            Map<String, Object> parameters = new LinkedHashMap<>();
            String[] parameterNames = signature.getParameterNames();
            SanitizationContext context = new SanitizationContext();
            for (int index = 0; index < arguments.length; index++) {
                String parameterName = parameterNames != null && index < parameterNames.length
                        ? parameterNames[index]
                        : "arg" + index;
                parameters.put(parameterName, sanitizeValue(arguments[index], parameterName, 0, context));
            }
            String serialized = JSON.toJSONString(parameters);
            if (serialized.length() <= MAX_PARAMETERS_JSON_LENGTH) {
                return serialized;
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("parameterNames", new ArrayList<>(parameters.keySet()));
            summary.put("summary", PARAMETERS_TOO_LARGE_VALUE);
            return JSON.toJSONString(summary);
        } catch (RuntimeException exception) {
            log.error("弃用接口入参序列化失败: interface={}, exceptionType={}",
                    resolveInterface(signature), exception.getClass().getSimpleName());
            return JSON.toJSONString(Map.of("serialization", SERIALIZATION_FAILED_VALUE));
        }
    }

    /** 递归构造有界的脱敏参数快照。 */
    private Object sanitizeValue(
            Object value,
            String fieldName,
            int depth,
            SanitizationContext context
    ) {
        if (!context.tryConsumeNode()) {
            return NODES_TRUNCATED_VALUE;
        }
        if (value == null) {
            return null;
        }
        if (isSensitiveField(fieldName)) {
            return MASKED_VALUE;
        }
        if (value instanceof CharSequence characters) {
            return sanitizeString(characters.toString(), fieldName);
        }
        if (value instanceof Character character) {
            return sanitizeString(character.toString(), fieldName);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof TemporalAccessor || value instanceof UUID) {
            return truncateString(String.valueOf(value));
        }
        if (depth >= MAX_NESTING_DEPTH) {
            return DEPTH_TRUNCATED_VALUE;
        }
        if (!context.enter(value)) {
            return CIRCULAR_REFERENCE_VALUE;
        }
        try {
            return sanitizeCompositeValue(value, depth, context);
        } finally {
            context.leave(value);
        }
    }

    /** 脱敏集合、映射、数组和项目 DTO。 */
    private Object sanitizeCompositeValue(Object value, int depth, SanitizationContext context) {
        if (value instanceof Map<?, ?> map && isTrustedContainer(value)) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            int itemCount = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (itemCount >= MAX_OBJECT_FIELDS || context.isExhausted()) {
                    sanitized.put(TRUNCATED_FIELD_NAME, FIELDS_TRUNCATED_VALUE);
                    break;
                }
                String nestedFieldName = sanitizeMapFieldName(entry.getKey(), itemCount);
                sanitized.put(nestedFieldName,
                        sanitizeValue(entry.getValue(), nestedFieldName, depth + 1, context));
                itemCount++;
            }
            return sanitized;
        }
        if (value instanceof Collection<?> collection && isTrustedContainer(value)) {
            List<Object> sanitized = new ArrayList<>();
            int itemCount = 0;
            for (Object item : collection) {
                if (itemCount >= MAX_COLLECTION_ITEMS || context.isExhausted()) {
                    sanitized.add(COLLECTION_TRUNCATED_VALUE);
                    break;
                }
                sanitized.add(sanitizeValue(item, null, depth + 1, context));
                itemCount++;
            }
            return sanitized;
        }
        if (value.getClass().isArray()) {
            List<Object> sanitized = new ArrayList<>();
            int length = Array.getLength(value);
            int recordedLength = Math.min(length, MAX_COLLECTION_ITEMS);
            for (int index = 0; index < recordedLength && !context.isExhausted(); index++) {
                sanitized.add(sanitizeValue(Array.get(value, index), null, depth + 1, context));
            }
            if (recordedLength < length || context.isExhausted()) {
                sanitized.add(COLLECTION_TRUNCATED_VALUE);
            }
            return sanitized;
        }
        return sanitizeDto(value, depth, context);
    }

    /** 仅允许遍历无一次性消费语义的常规容器实现。 */
    private boolean isTrustedContainer(Object value) {
        String packageName = value.getClass().getPackageName();
        return TRUSTED_CONTAINER_PACKAGE_PREFIXES.stream().anyMatch(packageName::startsWith);
    }

    /** 构造有界 Map 字段名，避免调用自定义键对象的字符串转换逻辑。 */
    private String sanitizeMapFieldName(Object key, int index) {
        if (!(key instanceof String fieldName)
                || fieldName.isBlank()
                || fieldName.length() > MAX_FIELD_NAME_LENGTH
                || !isSimpleFieldName(fieldName)) {
            return "field" + index;
        }
        return fieldName;
    }

    /** 判断 Map 字段名是否只包含常规标识符字符。 */
    private boolean isSimpleFieldName(String fieldName) {
        for (int index = 0; index < fieldName.length(); index++) {
            char character = fieldName.charAt(index);
            if (!Character.isLetterOrDigit(character) && character != '_' && character != '-') {
                return false;
            }
        }
        return true;
    }

    /** 仅反射项目 DTO 字段，避免读取框架对象或文件内容。 */
    private Object sanitizeDto(Object value, int depth, SanitizationContext context) {
        Package objectPackage = value.getClass().getPackage();
        if (objectPackage == null || !objectPackage.getName().startsWith(DTO_PACKAGE_PREFIX)) {
            return "[" + value.getClass().getSimpleName() + "]";
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        Class<?> currentType = value.getClass();
        while (currentType != Object.class) {
            for (Field field : currentType.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                if (sanitized.size() >= MAX_OBJECT_FIELDS || context.isExhausted()) {
                    sanitized.put(TRUNCATED_FIELD_NAME, FIELDS_TRUNCATED_VALUE);
                    return sanitized;
                }
                if (!field.trySetAccessible()) {
                    continue;
                }
                try {
                    sanitized.putIfAbsent(field.getName(),
                            sanitizeValue(field.get(value), field.getName(), depth + 1, context));
                } catch (IllegalAccessException exception) {
                    throw new IllegalStateException("读取弃用接口入参字段失败", exception);
                }
            }
            currentType = currentType.getSuperclass();
        }
        return sanitized;
    }

    /** 字符串默认脱敏，仅放行明确的非敏感字段。 */
    private String sanitizeString(String value, String fieldName) {
        String normalizedFieldName = normalizeFieldName(fieldName);
        if (!SAFE_STRING_FIELD_NAMES.contains(normalizedFieldName)) {
            return MASKED_VALUE;
        }
        return truncateString(value);
    }

    /** 截断超长安全字符串。 */
    private String truncateString(String value) {
        if (value.length() <= MAX_STRING_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_STRING_LENGTH) + STRING_TRUNCATED_VALUE;
    }

    /** 判断字段名是否属于敏感字段。 */
    private boolean isSensitiveField(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        String normalized = normalizeFieldName(fieldName);
        return SENSITIVE_FIELD_MARKERS.stream().anyMatch(normalized::contains);
    }

    /** 统一字段名格式以执行脱敏规则。 */
    private String normalizeFieldName(String fieldName) {
        if (fieldName == null) {
            return "";
        }
        return fieldName.toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "");
    }

    /** 获取当前 HTTP 请求。 */
    private HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletRequestAttributes)) {
            return null;
        }
        return servletRequestAttributes.getRequest();
    }

    /** 单次日志构造过程中的节点和循环引用状态。 */
    private static final class SanitizationContext {

        /** 当前递归路径上的对象。 */
        private final Set<Object> visiting = Collections.newSetFromMap(new IdentityHashMap<>());

        /** 剩余可处理节点数。 */
        private int remainingNodes = MAX_SANITIZED_NODES;

        /** 消耗一个节点额度。 */
        private boolean tryConsumeNode() {
            if (remainingNodes <= 0) {
                return false;
            }
            remainingNodes--;
            return true;
        }

        /** 判断节点额度是否耗尽。 */
        private boolean isExhausted() {
            return remainingNodes <= 0;
        }

        /** 进入复合对象，返回是否为首次进入。 */
        private boolean enter(Object value) {
            return visiting.add(value);
        }

        /** 离开复合对象。 */
        private void leave(Object value) {
            visiting.remove(value);
        }
    }
}
