package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 两端持久摘要合同、身份匹配、严格 JSON 和文字边界，全部使用本地数据。 */
class PortfolioAiStoredVideoSummaryTest {
    /** 测试身份不含真实作品或远端链接。 */
    private static final String SOURCE = PortfolioAiStoredVideoSummary.sourceVersion("OWNER/work/video/test.mp4", "etag", 1000, "sha");
    /** 包装哈希和模型只用于验证结构，不涉及真实模型调用。 */
    private static final String INPUT_HASH = "a".repeat(64);

    /** 合法正文保留三字段结构。 */
    private ObjectNode content() {
        ObjectNode value = StrictAiJson.mapper().createObjectNode().put("summary", "婚礼舞台");
        value.putArray("observations").add("暖色灯光");
        value.putArray("limitations").add("无法独立确认原声");
        return value;
    }

    /** 合法包装包含历史时间，不依赖帧和转写状态。 */
    private ObjectNode document() {
        return PortfolioAiStoredVideoSummary.create(SOURCE, INPUT_HASH, "glm-5.3-flash", "direct-video-v1",
                Instant.parse("2000-01-01T00:00:00Z"), content());
    }

    /** 同源摘要无 TTL，模型升级不强制替换，包装不要求帧或音频字段。 */
    @Test void validHistoricalSummaryHasNoTtlAndRequiresOnlyOriginalSourceIdentity() {
        ObjectNode value = document();
        assertThat(PortfolioAiStoredVideoSummary.contentOrNull(value.toString(), SOURCE)).isEqualTo(content());
        assertThat(value.size()).isEqualTo(8);
        assertThat(value.toString()).doesNotContain("manifestHash", "audioFullHash", "expiresAt");
        assertThat(PortfolioAiStoredVideoSummary.sourceVersion("OWNER/work/video/test.mp4", " \"ETAG\" ", 1000, "sha"))
                .isEqualTo(SOURCE);
        for (String changed : List.of(
                PortfolioAiStoredVideoSummary.sourceVersion("OWNER/work/video/changed.mp4", "etag", 1000, "sha"),
                PortfolioAiStoredVideoSummary.sourceVersion("OWNER/work/video/test.mp4", "new-etag", 1000, "sha"),
                PortfolioAiStoredVideoSummary.sourceVersion("OWNER/work/video/test.mp4", "etag", 1001, "sha"),
                PortfolioAiStoredVideoSummary.sourceVersion("OWNER/work/video/test.mp4", "etag", 1000, "new-sha"))) {
            assertThat(PortfolioAiStoredVideoSummary.contentOrNull(value.toString(), changed)).isNull();
        }
    }

    /** 非法持久数据只作为缺失，已冻结的损坏内容则在收费前拒绝。 */
    @Test void corruptMissingOrMismatchedDocumentsNeverBecomeFacts() {
        for (String invalid : new String[]{null, "", " ", "null", "[]", "{}", "{broken", document() + "{}"}) {
            assertThat(PortfolioAiStoredVideoSummary.contentOrNull(invalid, SOURCE)).isNull();
        }
        List<Consumer<ObjectNode>> mutations = List.of(
                node -> node.put("schemaVersion", "unknown"), node -> node.put("inputMode", "FRAMES"),
                node -> node.put("inputHash", "bad"), node -> node.put("sourceVersion", "b".repeat(64)),
                node -> node.put("generatedAt", "yesterday"), node -> node.put("model", " "),
                node -> node.put("promptVersion", "\nsecret"), node -> node.put("signedUrl", "forbidden"),
                node -> node.remove("content"), node -> node.putNull("content"));
        for (Consumer<ObjectNode> mutate : mutations) {
            ObjectNode value = document(); mutate.accept(value);
            assertThat(PortfolioAiStoredVideoSummary.documentOrNull(value.toString(), SOURCE)).isNull();
            ObjectNode entry = StrictAiJson.mapper().createObjectNode().put("route", "STORED_VIDEO_SUMMARY").put("sourceVersion", SOURCE);
            entry.set(PortfolioAiStoredVideoSummary.FIELD, value);
            assertThatThrownBy(() -> PortfolioAiStoredVideoSummary.frozenContent(entry)).isInstanceOf(AiProtocolException.class);
        }
        assertThat(PortfolioAiStoredVideoSummary.documentOrNull(document().toString().replace("\"content\":", "\"model\":\"other\",\"content\":"), SOURCE)).isNull();
    }

    /** Unicode 按码点计数，结构与条目上限严格执行，联系方式不会进入最终事实。 */
    @Test void summaryValidationSharesExactContentLimitsAndSanitizesContactDetails() {
        ObjectNode value = content().put("summary", "🎤".repeat(600));
        assertThat(PortfolioAiStoredVideoSummary.validateContent(value.toString()).path("summary").asText()).isEqualTo("🎤".repeat(600));
        value.put("summary", "🎤".repeat(601));
        assertThatThrownBy(() -> PortfolioAiStoredVideoSummary.validateContent(value.toString())).isInstanceOf(AiProtocolException.class);
        value.put("summary", "https://private.example 13800138000 微信:abcde");
        assertThat(PortfolioAiStoredVideoSummary.validateContent(value.toString()).toString()).doesNotContain("private.example", "13800138000", "abcde");
        value.withArray("observations").removeAll();
        for (int index = 0; index < 9; index++) { value.withArray("observations").add("观察"); }
        assertThatThrownBy(() -> PortfolioAiStoredVideoSummary.validateContent(value.toString())).isInstanceOf(AiProtocolException.class);
        assertThatThrownBy(() -> PortfolioAiStoredVideoSummary.validateContent("{\"summary\":\"a\",\"summary\":\"b\",\"observations\":[],\"limitations\":[]}"))
                .isInstanceOf(AiProtocolException.class);
    }

    /** 原视频生产端将模型超量条目按顺序规整，结果仍可被现有严格持久合同读取。 */
    @Test void modelOverflowPreservesFirstItemsAndRoundTripsThroughStrictStoredContract() {
        ObjectNode value = overflowingContent();
        value.withArray("observations").set(0, StrictAiJson.mapper().getNodeFactory().textNode("🎤".repeat(120)));
        value.withArray("observations").set(1, StrictAiJson.mapper().getNodeFactory().textNode("https://private.example 13800138000 微信:abcde"));
        var normalized = PortfolioAiStoredVideoSummary.normalizeModelContent(value.toString());
        assertThat(normalized.changed()).isTrue();
        assertThat(normalized.observationsBefore()).isEqualTo(12);
        assertThat(normalized.limitationsBefore()).isEqualTo(6);
        assertThat(normalized.content().path("observations")).hasSize(8);
        assertThat(normalized.content().path("limitations")).hasSize(4);
        assertThat(normalized.content().path("observations").get(0).asText()).isEqualTo("🎤".repeat(120));
        assertThat(normalized.content().path("observations").get(7).asText()).isEqualTo("观察7");
        assertThat(normalized.content().path("limitations").get(3).asText()).isEqualTo("限制3");
        assertThat(normalized.content().toString()).doesNotContain("private.example", "13800138000", "abcde", "观察8", "限制4");
        ObjectNode document = PortfolioAiStoredVideoSummary.create(SOURCE, INPUT_HASH, "model", "prompt", Instant.EPOCH, normalized.content());
        assertThat(PortfolioAiStoredVideoSummary.parse(document.toString(), SOURCE).path("content")).isEqualTo(normalized.content());
        assertThat(PortfolioAiStoredVideoSummary.validateContent(normalized.content().toString())).isEqualTo(normalized.content());
        assertThat(contentFailure(value).diagnostic()).contains("rule=ARRAY_COUNT");
        document.set("content", value);
        var storedFailure = assertThrows(PortfolioAiStoredVideoSummary.ValidationException.class,
                () -> PortfolioAiStoredVideoSummary.parse(document.toString(), SOURCE));
        assertThat(storedFailure.getCode()).isEqualTo(PortfolioAiMediaContract.ERROR_INVALID_SUMMARY);
        assertThat(storedFailure.getMessage()).isEqualTo("媒体摘要条目不合法");
        assertThat(storedFailure.diagnostic()).contains("rule=ARRAY_COUNT");
    }

    /** 无超量时不报告条目规整，保留空数组、码点上限及原有联系方式清理行为。 */
    @Test void modelNormalizationLeavesBoundedContentAndUnicodeTextIntact() {
        ObjectNode value = content().put("summary", "🎤".repeat(600));
        value.withArray("observations").removeAll();
        for (int index = 0; index < 8; index++) { value.withArray("observations").add("🎤".repeat(120)); }
        value.withArray("limitations").removeAll();
        var normalized = PortfolioAiStoredVideoSummary.normalizeModelContent(value.toString());
        assertThat(normalized.changed()).isFalse();
        assertThat(normalized.observationsBefore()).isEqualTo(8);
        assertThat(normalized.limitationsBefore()).isZero();
        assertThat(normalized.content()).isEqualTo(value);
        value.put("summary", "🎤".repeat(601));
        var failure = assertThrows(PortfolioAiStoredVideoSummary.ValidationException.class,
                () -> PortfolioAiStoredVideoSummary.normalizeModelContent(value.toString()));
        assertThat(failure.diagnostic()).contains("path=$.summary", "rule=TEXT_LENGTH", "actualCodePoints=601");
    }

    /** 舍弃的尾项仍完整校验，类型、原文长度和清理后扩长都不能借规整绕过。 */
    @Test void modelNormalizationValidatesEveryDiscardedItemWithoutLeakingItsContent() throws Exception {
        for (String field : List.of("observations", "limitations")) {
            int index = field.equals("observations") ? 8 : 4;
            for (String invalid : List.of("123", "null", "\"" + "🎤".repeat(121) + "\"", "\"" + "wx:abcde ".repeat(13) + "\"")) {
                ObjectNode value = overflowingContent();
                value.withArray(field).set(index, StrictAiJson.mapper().readTree(invalid));
                var failure = assertThrows(PortfolioAiStoredVideoSummary.ValidationException.class,
                        () -> PortfolioAiStoredVideoSummary.normalizeModelContent(value.toString()));
                assertThat(failure.getCode()).isEqualTo(PortfolioAiMediaContract.ERROR_INVALID_SUMMARY);
                assertThat(failure.diagnostic()).contains("path=$." + field + "[" + index + "]")
                        .doesNotContain("abcde", "🎤", "rule=ARRAY_COUNT");
            }
        }
    }

    /** 生产端只放宽条目数量，JSON 字节、深度、重复键、封闭字段和数组类型继续严格拒绝。 */
    @Test void modelNormalizationRetainsStrictJsonAndShapeBoundaries() {
        ObjectNode extraField = content().put("secret", "private");
        ObjectNode wrongArray = content().put("observations", "private");
        ObjectNode wrongSummary = content().put("summary", 1);
        String deep = "{\"summary\":\"舞台\",\"observations\":" + "[".repeat(9) + "0" + "]".repeat(9) + ",\"limitations\":[]}";
        for (String raw : List.of(extraField.toString(), wrongArray.toString(), wrongSummary.toString(), deep,
                content() + " ".repeat(16 * 1024), content() + "{}",
                "{\"summary\":\"a\",\"summary\":\"b\",\"observations\":[],\"limitations\":[]}")) {
            var expected = assertThrows(PortfolioAiStoredVideoSummary.ValidationException.class,
                    () -> PortfolioAiStoredVideoSummary.validateContent(raw));
            var actual = assertThrows(PortfolioAiStoredVideoSummary.ValidationException.class,
                    () -> PortfolioAiStoredVideoSummary.normalizeModelContent(raw));
            assertThat(actual.getCode()).isEqualTo(expected.getCode());
            assertThat(actual.getMessage()).isEqualTo(expected.getMessage());
            assertThat(actual.diagnostic()).isEqualTo(expected.diagnostic()).doesNotContain("secret", "private");
        }
    }

    /** 构造数量超过持久合同但每项均合法的模型正文。 */
    private ObjectNode overflowingContent() {
        ObjectNode value = content();
        value.withArray("observations").removeAll();
        value.withArray("limitations").removeAll();
        for (int index = 0; index < 12; index++) { value.withArray("observations").add("观察" + index); }
        for (int index = 0; index < 6; index++) { value.withArray("limitations").add("限制" + index); }
        return value;
    }

    /** 文本与数组失败给出可信字段、类型和实际边界，不改变既有错误文案。 */
    @Test void contentDiagnosticsIdentifyExactTextAndArrayBoundaries() {
        ObjectNode value = content().put("summary", "🎤".repeat(601));
        assertThat(contentFailure(value).diagnostic()).isEqualTo(
                "path=$.summary rule=TEXT_LENGTH actualType=STRING actualCodePoints=601 maxCodePoints=600");
        assertThat(contentFailure(value).getMessage()).isEqualTo("媒体摘要文本超过限制");
        value.putNull("summary");
        assertThat(contentFailure(value).diagnostic()).contains("path=$.summary", "rule=TEXT_TYPE", "actualType=NULL");
        value = content();
        value.withArray("observations").add(123);
        assertThat(contentFailure(value).diagnostic()).contains("path=$.observations[1]", "rule=TEXT_TYPE", "actualType=NUMBER");
        value.withArray("observations").removeAll().add("字".repeat(121));
        assertThat(contentFailure(value).diagnostic()).contains("path=$.observations[0]", "actualCodePoints=121", "maxCodePoints=120");
        value = content().put("limitations", "不可信文本");
        assertThat(contentFailure(value).diagnostic()).contains("path=$.limitations", "rule=ARRAY_TYPE", "actualType=STRING");
        value = content();
        for (int index = 0; index < 4; index++) { value.withArray("limitations").add("未知"); }
        assertThat(contentFailure(value).diagnostic()).contains("path=$.limitations", "rule=ARRAY_COUNT", "actualCount=5", "maxCount=4");
        assertThat(contentFailure(value).getMessage()).isEqualTo("媒体摘要条目不合法");
    }

    /** 字段集合诊断只披露可信缺失项和未知字段数量，模型自定义键和值均不输出。 */
    @Test void fieldDiagnosticsNeverExposeUntrustedKeysOrValues() {
        ObjectNode value = content();
        value.remove("summary");
        value.put("https://private.example?token=secret\n13800138000", "微信:abcde");
        var failure = contentFailure(value);
        assertThat(failure.getMessage()).isEqualTo("媒体摘要字段不合法");
        assertThat(failure.diagnostic()).contains("path=$", "rule=FIELDS", "unknownFields=1", "missingFields=summary")
                .doesNotContain("private.example", "token", "secret", "13800138000", "abcde", "\n");
        value.removeAll();
        assertThat(contentFailure(value).getMessage()).isEqualTo("媒体摘要结构不合法");
        assertThat(contentFailure(value).diagnostic()).contains("actualFields=0", "expectedFields=3");
    }

    /** JSON 拒绝保留严格解析器安全原因，但不携带异常原文或敏感字段。 */
    @Test void jsonDiagnosticsPreserveSafeParserReasonsAndOriginalErrorContracts() {
        for (String raw : List.of("{\"https://private.example?token=secret\":", "{\"a\":1,\"a\":2}", "[]", "{}{}")) {
            var failure = assertThrows(PortfolioAiStoredVideoSummary.ValidationException.class,
                    () -> PortfolioAiStoredVideoSummary.validateContent(raw));
            assertThat(failure.getCode()).isEqualTo(PortfolioAiMediaContract.ERROR_INVALID_SUMMARY);
            assertThat(failure.getMessage()).isEqualTo("媒体摘要JSON不合法");
            assertThat(failure.diagnostic()).contains("path=$", "rule=JSON_INVALID", "parserReason=JSON")
                    .doesNotContain("private.example", "token", "secret");
        }
        var documentFailure = assertThrows(PortfolioAiStoredVideoSummary.ValidationException.class,
                () -> PortfolioAiStoredVideoSummary.parse("[]", SOURCE));
        assertThat(documentFailure.getCode()).isEqualTo("INVALID_OUTPUT");
        assertThat(documentFailure.getMessage()).isEqualTo("JSON 根必须为对象");
        assertThat(documentFailure.diagnostic()).contains("parserReason=JSON 根必须为对象");
    }

    /** 包装元数据、来源身份和时间失败可独立定位，字段原值不出现在诊断中。 */
    @Test void documentDiagnosticsIdentifyMetadataSourceAndTimeFailures() {
        assertDocumentDiagnostic(node -> node.put("schemaVersion", "secret"), "$.schemaVersion", "EXPECTED_VALUE");
        assertDocumentDiagnostic(node -> node.put("inputMode", "secret"), "$.inputMode", "EXPECTED_VALUE");
        assertDocumentDiagnostic(node -> node.put("sourceVersion", "secret"), "$.sourceVersion", "HASH_FORMAT");
        assertDocumentDiagnostic(node -> node.put("sourceVersion", "b".repeat(64)), "$.sourceVersion", "SOURCE_MISMATCH");
        assertDocumentDiagnostic(node -> node.putNull("inputHash"), "$.inputHash", "HASH_FORMAT");
        assertDocumentDiagnostic(node -> node.putNull("model"), "$.model", "METADATA_TYPE");
        assertDocumentDiagnostic(node -> node.put("model", " "), "$.model", "METADATA_BLANK");
        assertDocumentDiagnostic(node -> node.put("model", "secret".repeat(22)), "$.model", "METADATA_LENGTH");
        assertDocumentDiagnostic(node -> node.put("promptVersion", "secret\nvalue"), "$.promptVersion", "METADATA_CONTROL");
        assertDocumentDiagnostic(node -> node.put("generatedAt", "secret"), "$.generatedAt", "INSTANT_FORMAT");
        ObjectNode value = document();
        value.remove("content");
        var failure = assertThrows(PortfolioAiStoredVideoSummary.ValidationException.class,
                () -> PortfolioAiStoredVideoSummary.parse(value.toString(), SOURCE));
        assertThat(failure.diagnostic()).contains("path=$", "rule=FIELDS", "missingFields=content");
    }

    /** 嵌套正文、无事实和冻结路线拒绝仍携带准确位置。 */
    @Test void nestedContentAndFrozenRouteDiagnosticsRemainPrecise() {
        ObjectNode value = document();
        ((ObjectNode) value.path("content")).put("summary", "🎤".repeat(601));
        var failure = assertThrows(PortfolioAiStoredVideoSummary.ValidationException.class,
                () -> PortfolioAiStoredVideoSummary.parse(value.toString(), SOURCE));
        assertThat(failure.diagnostic()).contains("path=$.content.summary", "rule=TEXT_LENGTH");
        ObjectNode empty = content().put("summary", " ");
        empty.withArray("observations").removeAll();
        failure = assertThrows(PortfolioAiStoredVideoSummary.ValidationException.class,
                () -> PortfolioAiStoredVideoSummary.create(SOURCE, INPUT_HASH, "model", "prompt", Instant.EPOCH, empty));
        assertThat(failure.diagnostic()).contains("path=$.content", "rule=NO_FACTS");
        failure = assertThrows(PortfolioAiStoredVideoSummary.ValidationException.class,
                () -> PortfolioAiStoredVideoSummary.frozenContent(StrictAiJson.mapper().createObjectNode().put("route", "secret")));
        assertThat(failure.diagnostic()).contains("path=$.route", "rule=EXPECTED_VALUE").doesNotContain("secret");
    }

    /** 清理占位词可能使文本增长，持久边界拒绝仍明确指出清理后的长度。 */
    @Test void cleanedTextDiagnosticsDistinguishExpandedSummaryAndArrayItem() {
        ObjectNode value = content().put("summary", "wx:abcde ".repeat(66));
        var failure = assertThrows(PortfolioAiStoredVideoSummary.ValidationException.class,
                () -> PortfolioAiStoredVideoSummary.create(SOURCE, INPUT_HASH, "model", "prompt", Instant.EPOCH, value));
        assertThat(failure.diagnostic()).contains("path=$.content.summary", "rule=CLEAN_TEXT_LENGTH", "actualCodePoints=660", "maxCodePoints=600")
                .doesNotContain("abcde");
        value.put("summary", "婚礼舞台");
        value.withArray("observations").removeAll().add("wx:abcde ".repeat(13));
        failure = assertThrows(PortfolioAiStoredVideoSummary.ValidationException.class,
                () -> PortfolioAiStoredVideoSummary.create(SOURCE, INPUT_HASH, "model", "prompt", Instant.EPOCH, value));
        assertThat(failure.diagnostic()).contains("path=$.content.observations[0]", "rule=CLEAN_TEXT_LENGTH", "actualCodePoints=130", "maxCodePoints=120")
                .doesNotContain("abcde");
    }

    /** 捕获本地正文校验失败，统一检查原错误码。 */
    private PortfolioAiStoredVideoSummary.ValidationException contentFailure(ObjectNode value) {
        var failure = assertThrows(PortfolioAiStoredVideoSummary.ValidationException.class,
                () -> PortfolioAiStoredVideoSummary.validateContent(value.toString()));
        assertThat(failure.getCode()).isEqualTo(PortfolioAiMediaContract.ERROR_INVALID_SUMMARY);
        return failure;
    }

    /** 包装失败保留原提示，诊断不得包含测试中模拟的秘密内容。 */
    private void assertDocumentDiagnostic(Consumer<ObjectNode> mutation, String path, String rule) {
        ObjectNode value = document();
        mutation.accept(value);
        var failure = assertThrows(PortfolioAiStoredVideoSummary.ValidationException.class,
                () -> PortfolioAiStoredVideoSummary.parse(value.toString(), SOURCE));
        assertThat(failure.getCode()).isEqualTo(PortfolioAiMediaContract.ERROR_INVALID_SUMMARY);
        assertThat(failure.getMessage()).isEqualTo("视频摘要结构或内容不合法");
        assertThat(failure.diagnostic()).contains("path=" + path, "rule=" + rule).doesNotContain("secret", "\n");
    }

    /** 独立工程必须同步共享源码，避免部署后 producer 与 consumer 解释不同。 */
    @Test void sharedStoredSummaryAndMediaContractsStayIdenticalAcrossModules() throws Exception {
        Path self = Path.of("src/main/java/com/jxc/wefolio/service/portfolioai");
        Path other = Path.of("../wefolio-java-runtime/src/main/java/com/jxc/wefolio/service/portfolioai");
        for (String file : List.of("PortfolioAiStoredVideoSummary.java", "PortfolioAiMediaContract.java", "PortfolioAiVideoAudioContext.java")) {
            assertThat(Files.readString(self.resolve(file))).isEqualTo(Files.readString(other.resolve(file)));
        }
    }
}
