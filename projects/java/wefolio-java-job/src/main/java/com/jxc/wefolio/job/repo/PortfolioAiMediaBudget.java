package com.jxc.wefolio.job.repo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 任务独立费用 JSON 的封闭协议，执行仓储与隐私清理共用解析，损坏记录不能被当作零费用。 */
public final class PortfolioAiMediaBudget {
    /** 本地费用协议及字段，不复用冻结请求或合法模型结果。 */
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
    /** 最多一百次真实调用加三个执行代次的未发送占位，旧调用序号不能被退款改写。 */
    private static final int MAX_CALL_RECORDS = 103;
    private static final Set<String> CALL_FIELDS = Set.of(ATTEMPT, NUMBER, DAY, RESERVED, CHARGED, KNOWN, PROMPT, COMPLETION);
    /** 按发送顺序保留所有代次、日期和未知调用。 */
    private final List<Call> calls;

    /** 只从受限解析或合法追加建立账本。 */
    private PortfolioAiMediaBudget(List<Call> calls) { this.calls = calls; }

    /** 空列代表从未付费；任何非法格式都明确报错而非清空账本。 */
    public static PortfolioAiMediaBudget parse(String json) {
        if (json == null) { return new PortfolioAiMediaBudget(new ArrayList<>()); }
        ObjectNode value = StrictAiJson.parseObject(json, 128 * 1024, 8);
        if (value.size() != 2 || !VERSION.equals(value.path(VERSION_FIELD).asText())
                || !value.path(CALLS).isArray() || value.path(CALLS).size() > MAX_CALL_RECORDS) { throw invalid(); }
        List<Call> parsed = new ArrayList<>();
        for (JsonNode entry : value.path(CALLS)) {
            if (!entry.isObject() || entry.size() != CALL_FIELDS.size()) { throw invalid(); }
            entry.fieldNames().forEachRemaining(field -> { if (!CALL_FIELDS.contains(field)) { throw invalid(); } });
            int attempt = positiveInt(entry.path(ATTEMPT));
            int number = positiveInt(entry.path(NUMBER));
            if (number != parsed.size() + 1 || !entry.path(DAY).isTextual() || !entry.path(KNOWN).isBoolean()) { throw invalid(); }
            LocalDate day;
            try { day = LocalDate.parse(entry.path(DAY).asText()); }
            catch (RuntimeException exception) { throw invalid(); }
            long reserved = nonnegativeLong(entry.path(RESERVED));
            Long charged = null;
            Long prompt = null;
            Long completion = null;
            if (entry.path(KNOWN).asBoolean()) {
                charged = nonnegativeLong(entry.path(CHARGED));
                prompt = nonnegativeLong(entry.path(PROMPT));
                completion = nonnegativeLong(entry.path(COMPLETION));
            } else if (!entry.path(CHARGED).isNull() || !entry.path(PROMPT).isNull() || !entry.path(COMPLETION).isNull()) { throw invalid(); }
            // 零预留只表示已确认未发送；未知用量或真实费用不能通过此标记消失。
            if (reserved == 0 && (!Long.valueOf(0).equals(charged) || !Long.valueOf(0).equals(prompt)
                    || !Long.valueOf(0).equals(completion))) { throw invalid(); }
            parsed.add(new Call(attempt, number, day, reserved, charged, prompt, completion));
        }
        return new PortfolioAiMediaBudget(parsed);
    }

    /** 调用前追加一次预留，序号跨执行代次累计。 */
    public int reserve(int attempt, int cents, LocalDate day) {
        if (attempt < 1 || cents < 1 || calls.size() >= MAX_CALL_RECORDS) { throw invalid(); }
        int number = calls.size() + 1;
        calls.add(new Call(attempt, number, day, cents, null, null, null));
        return number;
    }

    /** 找到原调用；结算必须同时匹配原代次和全任务调用序号。 */
    public Call find(int attempt, int number) {
        return calls.stream().filter(call -> call.attempt() == attempt && call.number() == number).findFirst().orElse(null);
    }

    /** 已结算记录不可被重复或不同 usage 改写。 */
    public boolean settle(Call call, long cents, long prompt, long completion) {
        if (call.charged() != null) { return false; }
        calls.set(call.number() - 1, new Call(call.attempt(), call.number(), call.day(), call.reserved(), cents, prompt, completion));
        return true;
    }

    /** 仅由发送前失败路径取消尚未结算的预留，留下零预留占位以保护后续调用身份。 */
    public boolean releaseUnsent(Call call) {
        if (call.charged() != null) { return false; }
        calls.set(call.number() - 1, new Call(call.attempt(), call.number(), call.day(), 0, 0L, 0L, 0L));
        return true;
    }

    /** 未知费用不能随失败、取消、清理或进程重启退还。 */
    public int unknownCount() { return (int) calls.stream().filter(call -> call.charged() == null).count(); }
    /** 全任务真实调用次数。 */
    public int callCount() { return (int) calls.stream().filter(call -> call.reserved() > 0).count(); }
    /** 全任务费用以已知实际费用或未知保守预留累计。 */
    public long total() { return total(null); }
    /** 每日按调用发送日期归属，跨午夜任务可以有两个费用日。 */
    public long total(LocalDate day) {
        long total = 0;
        for (Call call : calls) {
            if (day == null || day.equals(call.day())) { total = Math.addExact(total, call.charged() == null ? call.reserved() : call.charged()); }
        }
        return total;
    }

    /** 写回只包含有界费用元数据，不含媒体链接、请求、提示词或模型原文。 */
    public String json() {
        ObjectNode root = StrictAiJson.mapper().createObjectNode().put(VERSION_FIELD, VERSION);
        var items = root.putArray(CALLS);
        for (Call call : calls) {
            ObjectNode item = items.addObject().put(ATTEMPT, call.attempt()).put(NUMBER, call.number())
                    .put(DAY, call.day().toString()).put(RESERVED, call.reserved()).put(KNOWN, call.charged() != null);
            if (call.charged() == null) { item.putNull(CHARGED).putNull(PROMPT).putNull(COMPLETION); }
            else { item.put(CHARGED, call.charged()).put(PROMPT, call.prompt()).put(COMPLETION, call.completion()); }
        }
        return root.toString();
    }

    /** 单次模型调用的费用身份与已知用量。 */
    public record Call(int attempt, int number, LocalDate day, long reserved, Long charged, Long prompt, Long completion) { }
    /** 费用数值必须能无损解析为正整数。 */
    private static int positiveInt(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.asInt() <= 0) { throw invalid(); }
        return node.asInt();
    }
    /** 金额与 token 用量使用非负长整数，禁止浮点、字符串和溢出。 */
    private static long nonnegativeLong(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() < 0) { throw invalid(); }
        return node.asLong();
    }
    /** 安全错误不打印原费用 JSON。 */
    private static IllegalStateException invalid() { return new IllegalStateException("媒体费用记录格式不合法，保留费用并停止调用"); }
}
