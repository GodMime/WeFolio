package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 读取 Job 独立调用账本，仅向已授权任务查询公布累计 token 和回执完整性。 */
@Slf4j
public final class PortfolioAiTokenUsage {
    /** 查询响应的可选用量字段，不包含费用或单次调用信息。 */
    public static final String RESPONSE_KEY = "usage";
    /** 对外公布的回执完整性与已知累计用量字段。 */
    private static final String COMPLETE = "complete";
    private static final String TOTAL_TOKENS = "totalTokens";
    /** 与 Job 的 PortfolioAiMediaBudget 保持一致的封闭账本协议。 */
    private static final String VERSION = "media-budget-v1";
    private static final String VERSION_FIELD = "version";
    private static final String CALLS = "calls";
    private static final String ATTEMPT = "attemptNo";
    private static final String NUMBER = "callNo";
    private static final String DAY = "budgetDay";
    private static final String RESERVED = "reservedCents";
    private static final String CHARGED = "chargedCents";
    private static final String KNOWN = "usageKnown";
    private static final String PROMPT = "promptTokens";
    private static final String COMPLETION = "completionTokens";
    /** 最多一百次真实调用和三个未发送占位，解析预算与写入方一致。 */
    private static final int MAX_CALL_RECORDS = 103;
    private static final int MAX_BYTES = 128 * 1024;
    private static final int MAX_DEPTH = 8;
    /** 小程序 JavaScript 数值可精确表示的最大整数，避免响应累计数失真。 */
    private static final long MAX_SAFE_TOKENS = 9_007_199_254_740_991L;
    private static final Set<String> CALL_FIELDS = Set.of(ATTEMPT, NUMBER, DAY, RESERVED, CHARGED, KNOWN, PROMPT, COMPLETION);

    /** 工具类不允许实例化。 */
    private PortfolioAiTokenUsage() { }

    /** 无账本或无真实调用时不显示；损坏账本只能显示未知，不能影响原候选查询。 */
    public static Map<String, Object> summarize(Long taskId, String json, Integer unknownCount) {
        try {
            if (json == null) {
                if (unknownCount != null && unknownCount != 0) { throw invalid(); }
                return null;
            }
            return parse(json, unknownCount);
        } catch (RuntimeException exception) {
            // 原始账本及异常文案可能含不可信内容，只记录身份与固定异常类别。
            log.warn("AI 任务 token 用量账本异常: taskId={}, exceptionType={}", taskId, exception.getClass().getSimpleName());
            return usage(false, null);
        }
    }

    /** 严格验证所有调用后累计输入和输出；模型思考已包含在输出，不重复加算。 */
    private static Map<String, Object> parse(String json, Integer unknownCount) {
        ObjectNode value = StrictAiJson.parseObject(json, MAX_BYTES, MAX_DEPTH);
        if (value.size() != 2 || !VERSION.equals(value.path(VERSION_FIELD).asText())
                || !value.path(CALLS).isArray() || value.path(CALLS).size() > MAX_CALL_RECORDS) { throw invalid(); }
        int number = 0;
        boolean sent = false;
        boolean complete = true;
        boolean known = false;
        int actualUnknownCount = 0;
        long total = 0;
        long totalCents = 0;
        for (JsonNode entry : value.path(CALLS)) {
            if (!entry.isObject() || entry.size() != CALL_FIELDS.size()) { throw invalid(); }
            entry.fieldNames().forEachRemaining(field -> { if (!CALL_FIELDS.contains(field)) { throw invalid(); } });
            positiveInt(entry.path(ATTEMPT));
            if (positiveInt(entry.path(NUMBER)) != ++number || !entry.path(DAY).isTextual()
                    || !entry.path(KNOWN).isBoolean()) { throw invalid(); }
            LocalDate.parse(entry.path(DAY).asText());
            long reserved = nonnegativeLong(entry.path(RESERVED));
            Long charged = null;
            Long prompt = null;
            Long completion = null;
            if (entry.path(KNOWN).asBoolean()) {
                charged = nonnegativeLong(entry.path(CHARGED));
                prompt = nonnegativeLong(entry.path(PROMPT));
                completion = nonnegativeLong(entry.path(COMPLETION));
            } else if (!entry.path(CHARGED).isNull() || !entry.path(PROMPT).isNull()
                    || !entry.path(COMPLETION).isNull()) { throw invalid(); }
            // 费用只做账本完整性核验，任何费用明细或累计均不进入查询响应。
            totalCents = Math.addExact(totalCents, charged == null ? reserved : charged);
            // 零预留只代表发送前已释放的占位，不应展示为真实的零消费。
            if (reserved == 0) {
                if (!Long.valueOf(0).equals(charged) || !Long.valueOf(0).equals(prompt)
                        || !Long.valueOf(0).equals(completion)) { throw invalid(); }
                continue;
            }
            sent = true;
            if (prompt == null) { complete = false; actualUnknownCount++; }
            else {
                known = true;
                total = Math.addExact(total, Math.addExact(prompt, completion));
                if (total > MAX_SAFE_TOKENS) { throw invalid(); }
            }
        }
        if (unknownCount != null && unknownCount != actualUnknownCount) { throw invalid(); }
        return sent ? usage(complete, known ? total : null) : null;
    }

    /** 固定的可空响应字段，未知回执不得使用预留费用估算 token。 */
    private static Map<String, Object> usage(boolean complete, Long total) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(COMPLETE, complete);
        result.put(TOTAL_TOKENS, total);
        return result;
    }

    /** 调用代次和序号必须是可无损解析的正整数。 */
    private static int positiveInt(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) { throw invalid(); }
        return value.intValue();
    }

    /** 费用和用量必须是非负长整数，禁止字符串、浮点或溢出截断。 */
    private static long nonnegativeLong(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) { throw invalid(); }
        return value.longValue();
    }

    /** 固定校验异常不携带原账本、费用或用户输入。 */
    private static IllegalArgumentException invalid() { return new IllegalArgumentException(); }
}
