package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** 调用用量查询边界：完整与部分回执、历史数据、占位、损坏和数值溢出。 */
class PortfolioAiTokenUsageTest {
    /** 创建与 Job 写入格式一致的安全账本，不依赖费用配置或真实模型调用。 */
    private ObjectNode ledger() {
        ObjectNode root = StrictAiJson.mapper().createObjectNode().put("version", "media-budget-v1");
        root.putArray("calls");
        return root;
    }

    /** 追加一次已结算调用，代次可表达媒体分析、重试和最终生成。 */
    private ObjectNode known(ObjectNode root, int attempt, long prompt, long completion) {
        ArrayNode calls = root.withArray("calls");
        int number = calls.size() + 1;
        return calls.addObject().put("attemptNo", attempt).put("callNo", number)
                .put("budgetDay", "2026-10-13").put("reservedCents", 20).put("chargedCents", 1)
                .put("usageKnown", true).put("promptTokens", prompt).put("completionTokens", completion);
    }

    /** 追加已预留但没有真实用量回执的调用，不以费用估算 token。 */
    private void unknown(ObjectNode root, int attempt) {
        ObjectNode call = known(root, attempt, 0, 0);
        call.put("usageKnown", false).putNull("chargedCents").putNull("promptTokens").putNull("completionTokens");
    }

    /** 损坏账本只得到未知响应，不泄露内部字段。 */
    private void assertUnknown(String json) {
        Map<String, Object> usage = PortfolioAiTokenUsage.summarize(7L, json, null);
        assertThat(usage).containsOnlyKeys("complete", "totalTokens");
        assertThat(usage.get("complete")).isEqualTo(false);
        assertThat(usage).containsEntry("totalTokens", null);
    }

    /** 用量覆盖全部已知调用代次，输出已包含思考，不引入额外费用或估算值。 */
    @Test void completeUsageTotalsMediaAndGenerationAcrossRetries() {
        ObjectNode root = ledger();
        known(root, 1, 100, 40);
        known(root, 1, 200, 80);
        known(root, 2, 300, 120);
        assertThat(PortfolioAiTokenUsage.summarize(7L, root.toString(), null))
                .isEqualTo(Map.of("complete", true, "totalTokens", 840L));
    }

    /** 真正调用已知返回零 token 时，仍为完整回执而非未发送占位。 */
    @Test void sentKnownZeroUsageRemainsComplete() {
        ObjectNode root = ledger(); known(root, 1, 0, 0);
        assertThat(PortfolioAiTokenUsage.summarize(7L, root.toString(), null))
                .isEqualTo(Map.of("complete", true, "totalTokens", 0L));
    }

    /** 部分未知调用保留已知累计数，并明确不完整。 */
    @Test void partialUsageReturnsOnlyKnownReceipts() {
        ObjectNode root = ledger(); known(root, 1, 123, 456); unknown(root, 2);
        assertThat(PortfolioAiTokenUsage.summarize(7L, root.toString(), null))
                .isEqualTo(Map.of("complete", false, "totalTokens", 579L));
    }

    /** 有已知零回执与未知调用时，部分累计零仍与完全未知区分。 */
    @Test void knownZeroAndUnknownRemainPartialZero() {
        ObjectNode root = ledger(); known(root, 1, 0, 0); unknown(root, 2);
        assertThat(PortfolioAiTokenUsage.summarize(7L, root.toString(), null))
                .isEqualTo(Map.of("complete", false, "totalTokens", 0L));
    }

    /** 全部真实调用回执未知时，不返回假零或预留费用推算值。 */
    @Test void allUnknownUsageReturnsNullCount() {
        ObjectNode root = ledger(); unknown(root, 1); unknown(root, 2);
        assertUnknown(root.toString());
    }

    /** 历史无账本、空调用和全部未发送占位均隐藏用量，不误报零消费。 */
    @Test void legacyEmptyAndUnsentOnlyLedgersHaveNoUsage() {
        assertThat(PortfolioAiTokenUsage.summarize(7L, null, null)).isNull();
        ObjectNode root = ledger();
        assertThat(PortfolioAiTokenUsage.summarize(7L, root.toString(), null)).isNull();
        known(root, 1, 0, 0).put("reservedCents", 0).put("chargedCents", 0);
        known(root, 2, 0, 0).put("reservedCents", 0).put("chargedCents", 0);
        assertThat(PortfolioAiTokenUsage.summarize(7L, root.toString(), null)).isNull();
    }

    /** 已确认未发送的零占位不影响真实调用的累计和完整性。 */
    @Test void unsentPlaceholdersAreIgnoredInCompleteUsage() {
        ObjectNode root = ledger();
        known(root, 1, 0, 0).put("reservedCents", 0).put("chargedCents", 0);
        known(root, 2, 60, 40);
        assertThat(PortfolioAiTokenUsage.summarize(7L, root.toString(), null))
                .isEqualTo(Map.of("complete", true, "totalTokens", 100L));
    }

    /** 封闭 JSON 协议拒绝空白、重复键、附加内容、不匹配版本和异常根结构。 */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "{", "[]", "null", "{}", "{\"version\":\"media-budget-v1\",\"calls\":null}",
            "{\"version\":\"future-version\",\"calls\":[]}",
            "{\"version\":\"media-budget-v1\",\"version\":\"media-budget-v1\",\"calls\":[]}",
            "{\"version\":\"media-budget-v1\",\"calls\":[],\"extra\":1}",
            "{\"version\":\"media-budget-v1\",\"calls\":[]} {}"})
    void malformedJsonReturnsUnknown(String json) { assertUnknown(json); }

    /** 逐个字段覆盖 Job 协议约束，损坏调用不能被忽略或部分采信。 */
    @Test void invalidCallFieldsReturnUnknown() {
        List<Consumer<ObjectNode>> mutations = List.of(
                call -> call.remove("chargedCents"), call -> call.put("extra", "hidden"),
                call -> call.put("attemptNo", 0), call -> call.put("attemptNo", 2147483648L),
                call -> call.put("callNo", 2), call -> call.put("callNo", "1"),
                call -> call.put("budgetDay", "not-a-date"), call -> call.putNull("budgetDay"),
                call -> call.put("usageKnown", "true"), call -> call.put("reservedCents", -1),
                call -> call.put("chargedCents", -1), call -> call.put("promptTokens", -1),
                call -> call.put("completionTokens", 2.5), call -> call.put("completionTokens", "2"),
                call -> call.put("usageKnown", false), call -> call.putNull("promptTokens"),
                call -> call.put("reservedCents", 0));
        for (Consumer<ObjectNode> mutation : mutations) {
            ObjectNode root = ledger(); ObjectNode call = known(root, 1, 10, 20);
            mutation.accept(call); assertUnknown(root.toString());
        }
        ObjectNode unknownZero = ledger(); unknown(unknownZero, 1);
        ((ObjectNode) unknownZero.withArray("calls").get(0)).put("reservedCents", 0);
        assertUnknown(unknownZero.toString());
    }

    /** 数值超出 Long 或累加溢出时不返回错误累计。 */
    @Test void tokenOverflowReturnsUnknown() {
        ObjectNode root = ledger(); known(root, 1, Long.MAX_VALUE, 1);
        assertUnknown(root.toString());
        root = ledger(); known(root, 1, Long.MAX_VALUE, 0); known(root, 2, 1, 0);
        assertUnknown(root.toString());
        root = ledger(); known(root, 1, 1, 1);
        assertUnknown(root.toString().replace("\"promptTokens\":1", "\"promptTokens\":9223372036854775808"));
    }

    /** HTTP 数值不可超出小程序 JavaScript 精确整数边界。 */
    @Test void javascriptSafeIntegerBoundaryIsPreserved() {
        ObjectNode root = ledger(); known(root, 1, 9_007_199_254_740_990L, 1);
        assertThat(PortfolioAiTokenUsage.summarize(7L, root.toString(), null))
                .isEqualTo(Map.of("complete", true, "totalTokens", 9_007_199_254_740_991L));
        known(root, 2, 1, 0); assertUnknown(root.toString());
    }

    /** 未知计数独立列存在时须与账本一致；历史空列兼容，不把坏索引视为完整。 */
    @Test void unknownIndexMustMatchLedgerWhenPresent() {
        ObjectNode root = ledger(); known(root, 1, 20, 10);
        assertThat(PortfolioAiTokenUsage.summarize(7L, root.toString(), 0))
                .isEqualTo(Map.of("complete", true, "totalTokens", 30L));
        Map<String, Object> invalid = PortfolioAiTokenUsage.summarize(7L, root.toString(), 1);
        assertThat(invalid).containsEntry("complete", false).containsEntry("totalTokens", null);
        unknown(root, 2);
        assertThat(PortfolioAiTokenUsage.summarize(7L, root.toString(), 1))
                .isEqualTo(Map.of("complete", false, "totalTokens", 30L));
        assertThat(PortfolioAiTokenUsage.summarize(7L, root.toString(), 0))
                .containsEntry("complete", false).containsEntry("totalTokens", null);
        assertThat(PortfolioAiTokenUsage.summarize(7L, root.toString(), -1))
                .containsEntry("complete", false).containsEntry("totalTokens", null);
        assertThat(PortfolioAiTokenUsage.summarize(7L, null, 0)).isNull();
        assertThat(PortfolioAiTokenUsage.summarize(7L, null, 1))
                .containsEntry("complete", false).containsEntry("totalTokens", null);
        assertThat(PortfolioAiTokenUsage.summarize(7L, ledger().toString(), 1))
                .containsEntry("complete", false).containsEntry("totalTokens", null);
    }

    /** 费用数值虽不外传，但总额溢出亦代表 Job 无法使用的损坏账本。 */
    @Test void budgetTotalOverflowReturnsUnknown() {
        ObjectNode root = ledger();
        known(root, 1, 1, 1).put("chargedCents", Long.MAX_VALUE);
        known(root, 2, 1, 1).put("chargedCents", 1);
        assertUnknown(root.toString());
        root = ledger(); unknown(root, 1); unknown(root, 2);
        ((ObjectNode) root.withArray("calls").get(0)).put("reservedCents", Long.MAX_VALUE);
        assertUnknown(root.toString());
    }

    /** 解析严格限制调用数量、字节和深度，异常数据不会绕过 JSON 边界。 */
    @Test void ledgerResourceBudgetsAreBounded() {
        ObjectNode root = ledger();
        for (int number = 0; number < 103; number++) { known(root, 1, 1, 1); }
        assertThat(PortfolioAiTokenUsage.summarize(7L, root.toString(), null))
                .isEqualTo(Map.of("complete", true, "totalTokens", 206L));
        known(root, 1, 1, 1); assertUnknown(root.toString());
        assertUnknown("{\"private\":\"" + "x".repeat(128 * 1024) + "\"}");
        assertUnknown("{\"nested\":".repeat(9) + "{}" + "}".repeat(9));
    }
}
