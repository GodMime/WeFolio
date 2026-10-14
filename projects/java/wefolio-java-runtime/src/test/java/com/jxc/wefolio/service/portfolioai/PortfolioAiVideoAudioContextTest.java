package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static com.jxc.wefolio.service.portfolioai.PortfolioAiVideoAudioContext.*;
import static org.assertj.core.api.Assertions.*;

/** 真实 Unicode、JSON 转义与五十来源边界在两个独立工程使用相同测试。 */
class PortfolioAiVideoAudioContextTest {
    /** 仅包含视频路线的内部测试来源。 */
    private ObjectNode entry() { return StrictAiJson.mapper().createObjectNode().put("route", "VIDEO"); }

    /** 五十个最大来源均得到确定额度，不损坏 emoji 代理项；实际文本块预算覆盖转义膨胀。 */
    @Test void fiftySourcesAreBalancedUnicodeSafeAndByteBounded() {
        List<Source> sources = new ArrayList<>();
        String original = "😀\u0001\"\\".repeat(4000);
        for (int index = 0; index < 50; index++) { sources.add(new Source(entry(), original)); }
        freeze(sources);
        int total = 0;
        ArrayNode entries = StrictAiJson.mapper().createArrayNode();
        for (Source source : sources) {
            String text = source.entry().path(TEXT).asText();
            assertThat(text.codePointCount(0, text.length())).isEqualTo(480);
            assertThat(source.entry().path(EXCERPT).asBoolean()).isTrue();
            assertThat(inputTokenUpperBound(source.entry())).isGreaterThan(text.length());
            assertThat(inputTokenUpperBound(source.entry())).isGreaterThanOrEqualTo(modelBlock(source.entry()).toString().getBytes(StandardCharsets.UTF_8).length);
            assertThat(StrictAiJson.parseObject(source.entry().toString(), 10000, 8)).isEqualTo(source.entry());
            total += text.codePointCount(0, text.length());
            entries.add(source.entry());
        }
        assertThat(total).isEqualTo(TOTAL_LIMIT);
        validateAndNormalize(entries, true);
    }

    /** 短文字释放额度给长文字，每件仍受六千上限且调用顺序稳定。 */
    @Test void shortTextRedistributesBudgetAndSentenceBoundaryKeepsExcerptMarker() {
        List<Source> sources = new ArrayList<>();
        sources.add(new Source(entry(), "短文"));
        for (int index = 0; index < 4; index++) { sources.add(new Source(entry(), "长".repeat(5900) + "。" + "余".repeat(300))); }
        freeze(sources);
        assertThat(sources.getFirst().entry().path(TEXT).asText()).isEqualTo("短文");
        assertThat(sources.getFirst().entry().path(EXCERPT).asBoolean()).isFalse();
        for (Source source : sources.subList(1, sources.size())) {
            assertThat(source.entry().path(TEXT).asText()).hasSize(5901).endsWith("。");
            assertThat(source.entry().path(EXCERPT).asBoolean()).isTrue();
        }
    }

    /** 数据库全文不随投影清理变化，全文与实际输入身份分开且区分空值。 */
    @Test void projectionRedactsContactsAndKeepsSeparateOriginalHash() {
        String original = "舞台\n联系13812345678 https://example.test/a";
        ObjectNode value = entry();
        freeze(List.of(new Source(value, original)));
        assertThat(value.path(TEXT).asText()).doesNotContain("13812345678", "https://").contains("已隐藏联系方式", "已隐藏链接");
        assertThat(value.path(FULL_HASH).asText()).isEqualTo(fullHash(original));
        assertThat(value.path(INPUT_HASH).asText()).isEqualTo(inputHash(value.path(TEXT).asText()));
        assertThat(fullHash(null)).isNotEqualTo(fullHash(""));
        ObjectNode projected = entry(); project(value, projected);
        assertThat(projected.toString()).doesNotContain(FULL_HASH, INPUT_HASH, POLICY);
    }

    /** 空结果和尚未处理都不增加模型文字，内部全文身份仍可阻止旧缓存写入。 */
    @Test void emptyNullAndLegacyInputsDoNotAddModelText() {
        ObjectNode missing = entry(); ObjectNode empty = entry();
        freeze(List.of(new Source(missing, null), new Source(empty, "")));
        assertThat(missing.has(TEXT)).isFalse(); assertThat(empty.has(TEXT)).isFalse();
        assertThat(modelBlock(missing)).isNull();
        assertThat(inputTokenUpperBound(empty)).isZero();
        assertThat(missing.path(FULL_HASH)).isNotEqualTo(empty.path(FULL_HASH));
        ObjectNode legacy = entry(); freeze(List.of(new Source(legacy, "旧任务不读取")));
        var entries = StrictAiJson.mapper().createArrayNode().add(legacy);
        validateAndNormalize(entries, false);
        assertThat(legacy.has(TEXT)).isFalse(); assertThat(legacy.has(FULL_HASH)).isFalse();
    }

    /** 越界、篡改、非视频和错误策略均在任何模型费用动作前拒绝。 */
    @Test void rejectsCorruptOrOversizedFrozenContext() {
        for (String failure : List.of("hash", "policy", "route", "size", "aggregate")) {
            ObjectNode value = entry(); freeze(List.of(new Source(value, "正常文字")));
            ArrayNode entries = StrictAiJson.mapper().createArrayNode().add(value);
            switch (failure) {
                case "hash" -> value.put(TEXT, "被修改");
                case "policy" -> value.put(POLICY, "unknown");
                case "route" -> value.put("route", "IMAGE");
                case "size" -> { value.put(TEXT, "字".repeat(6001)); value.put(INPUT_HASH, inputHash(value.path(TEXT).asText())); }
                case "aggregate" -> {
                    value.put(TEXT, "字".repeat(6000)).put(INPUT_HASH, inputHash("字".repeat(6000)));
                    for (int index = 0; index < 4; index++) { entries.add(value.deepCopy()); }
                }
                default -> throw new AssertionError();
            }
            assertThatThrownBy(() -> validateAndNormalize(entries, true)).isInstanceOf(AiProtocolException.class);
        }
    }
}
