package com.jxc.wefolio.service.advancedportfolio;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.AdvancedPortfolioMessage;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 高级保存专有元数据；在普通最终保存短事务内校验并与配置一致写入。 */
public record AdvancedPortfolioSaveMetadata(JSONObject context, boolean contextPresent,
                                            String prompt, boolean promptPresent, String fingerprint) {
    /** 上下文结构版本。 */
    private static final int CONTEXT_VERSION = 1;
    /** 来源上下文大小上限。 */
    private static final int CONTEXT_MAX_BYTES = 128 * 1024;
    /** 上下文顶层允许字段。 */
    private static final Set<String> CONTEXT_KEYS = Set.of("version", "nextSourceNo", "sources");
    /** 来源条目允许字段。 */
    private static final Set<String> SOURCE_KEYS = Set.of("sourceNo", "kind", "sourceId");
    /** 支持的来源类型。 */
    private static final Set<String> SOURCE_KINDS = Set.of("WORK", "MEMBER_PORTFOLIO");

    /** 校验高水位和旧映射，在锁内重新读取的实体上应用明确提交的可选字段。 */
    public void apply(PortfolioEntity portfolio) {
        if (contextPresent) {
            validateContext(context, portfolio.getAiContextJson());
            portfolio.setAiContextJson(context.toJSONString());
        }
        if (promptPresent) {
            portfolio.setAiPrompt(prompt);
        }
    }

    /** 高级来源号只能保留既有映射或使用不低于旧高水位的新号。 */
    public static void validateContext(JSONObject context, String previousJson) {
        if (context == null || !context.keySet().equals(CONTEXT_KEYS)
                || context.toJSONString().getBytes(StandardCharsets.UTF_8).length > CONTEXT_MAX_BYTES
                || integer(context.get("version")) != CONTEXT_VERSION) {
            throw new BusinessException(AdvancedPortfolioMessage.INVALID_CONTEXT);
        }
        long next = integer(context.get("nextSourceNo"));
        if (next < 1 || next > Integer.MAX_VALUE) { throw new BusinessException(AdvancedPortfolioMessage.INVALID_CONTEXT); }
        Map<Long,String> old = new HashMap<>();
        long oldNext = 1;
        if (previousJson != null && !previousJson.isBlank()) {
            JSONObject previous = JSON.parseObject(previousJson);
            oldNext = integer(previous.get("nextSourceNo"));
            for (Object item : previous.getJSONArray("sources")) {
                JSONObject source = (JSONObject) item;
                old.put(integer(source.get("sourceNo")), identity(source));
            }
        }
        if (next < oldNext || !(context.get("sources") instanceof List<?> sources)) {
            throw new BusinessException(AdvancedPortfolioMessage.INVALID_CONTEXT);
        }
        Set<Long> numbers = new HashSet<>();
        Set<String> identities = new HashSet<>();
        for (Object value : sources) {
            if (!(value instanceof JSONObject source) || !source.keySet().equals(SOURCE_KEYS)
                    || !(source.get("kind") instanceof String kind) || !SOURCE_KINDS.contains(kind)
                    || !(source.get("sourceId") instanceof String id) || !isSourceId(id)) {
                throw new BusinessException(AdvancedPortfolioMessage.INVALID_CONTEXT);
            }
            long no = integer(source.get("sourceNo"));
            String key = identity(source);
            if (no < 1 || no >= next || !numbers.add(no) || !identities.add(key)
                    || no < oldNext && !key.equals(old.get(no))) {
                throw new BusinessException(AdvancedPortfolioMessage.INVALID_CONTEXT);
            }
        }
    }

    /** 仅协议整数类型可用作来源号。 */
    private static long integer(Object value) {
        if (!(value instanceof Integer) && !(value instanceof Long)) { throw new BusinessException(AdvancedPortfolioMessage.INVALID_CONTEXT); }
        return ((Number) value).longValue();
    }

    /** 来源身份使用数据库正 Long 范围，不能用未引用的上下文条目保存溢出号码。 */
    private static boolean isSourceId(String value) {
        if (!value.matches("[1-9][0-9]{0,18}")) { return false; }
        try { return Long.parseLong(value) > 0; }
        catch (NumberFormatException exception) { return false; }
    }

    /** 生成无歧义来源身份。 */
    private static String identity(JSONObject source) {
        return source.getString("kind") + ":" + source.getString("sourceId");
    }
}
