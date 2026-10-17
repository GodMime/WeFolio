package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.message.PortfolioAiTaskMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.jxc.wefolio.service.portfolioai.PortfolioAiMediaContract.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 证明实际图片分辨率约束视觉预算，并在降低数量时保留连续时间覆盖。 */
class PortfolioAiFrameSelectionTest {
    /** 真实生产特征字段和版本，仅使用人工统计构造可解释的选择边界。 */
    private static final String FEATURES = "visualFeatures";
    private static final String FEATURES_VERSION = "visual-features-v1";
    private static final String DHASH = "dHash";
    private static final String LUMINANCE = "luminance";
    private static final String SHARPNESS = "sharpness";
    /** 测试对象键不指向真实媒体，反差哈希用于验证去重复评分。 */
    private static final String FRAME_PREFIX = "fixture/frame-";
    private static final String SAME_HASH = "0000000000000000";
    private static final String OPPOSITE_HASH = "ffffffffffffffff";
    /** 冻结版本和额外字段使用固定输入，不依赖业务模型、网络或系统时间。 */
    private static final String POLICY_VERSION = "resolution-budget-v1";
    private static final String UNKNOWN_VERSION = "unknown-version";
    private static final String EXTRA_FIELD = "unexpected";
    /** 已上线第一版冻结样本独立于当前默认选帧逻辑，防止策略升级悄悄改变历史预算。 */
    private static final String FROZEN_V1_SELECTION = """
            {"policyVersion":"resolution-budget-v1","frames":[
              {"objectKey":"fixture/frame-1","bytes":100,"timeMs":1000,"width":960,"height":540},
              {"objectKey":"fixture/frame-4","bytes":100,"timeMs":4000,"width":960,"height":540},
              {"objectKey":"fixture/frame-7","bytes":100,"timeMs":7000,"width":960,"height":540},
              {"objectKey":"fixture/frame-10","bytes":100,"timeMs":10000,"width":960,"height":540},
              {"objectKey":"fixture/frame-13","bytes":100,"timeMs":13000,"width":960,"height":540},
              {"objectKey":"fixture/frame-16","bytes":100,"timeMs":16000,"width":960,"height":540},
              {"objectKey":"fixture/frame-19","bytes":100,"timeMs":19000,"width":960,"height":540},
              {"objectKey":"fixture/frame-22","bytes":100,"timeMs":22000,"width":960,"height":540}
            ],"estimatedInputTokens":16384}
            """;
    /** 双工程源码相对路径用于阻止策略发布漂移。 */
    private static final String SOURCE_PATH = "src/main/java/com/jxc/wefolio/service/portfolioai/PortfolioAiFrameSelection.java";
    private static final String RUNTIME_MODULE = "wefolio-java-runtime";
    private static final String JOB_MODULE = "wefolio-java-job";

    /** 高分辨率二十四帧分为八段，每段一张，开头和结尾所在分段均有代表。 */
    @Test
    void largeFramesRetainEightTimeSegmentsWithinBudget() {
        ObjectNode result = PortfolioAiFrameSelection.select(frames(24, 960, 540));
        assertThat(times(result)).containsExactly(1_000L, 4_000L, 7_000L, 10_000L, 13_000L, 16_000L, 19_000L, 22_000L);
        assertThat(PortfolioAiFrameSelection.estimatedInputTokens(result)).isEqualTo(16_384);
        assertThat(result.path(KEY_POLICY_VERSION).textValue()).isEqualTo(POLICY_VERSION);
        List<String> fields = new ArrayList<>();
        result.path(KEY_FRAMES).get(0).fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly(KEY_OBJECT_KEY, KEY_BYTES, KEY_TIME_MS, KEY_WIDTH, KEY_HEIGHT);
        assertThat(result.toString()).isEqualTo(PortfolioAiFrameSelection.select(frames(24, 960, 540)).toString());
    }

    /** 低分辨率保留全部二十四帧，横竖方向不影响最长边预算。 */
    @Test
    void smallFramesKeepAllCandidatesInEitherOrientation() {
        for (int[] size : List.of(new int[]{512, 288}, new int[]{288, 512})) {
            ObjectNode result = PortfolioAiFrameSelection.select(frames(24, size[0], size[1]));
            assertThat(result.path(KEY_FRAMES).size()).isEqualTo(24);
            assertThat(times(result)).startsWith(0L).endsWith(23_000L);
            assertThat(PortfolioAiFrameSelection.estimatedInputTokens(result)).isEqualTo(16_384);
        }
    }

    /** 中档边界选十二帧，两端尺寸边界只能向较贵档位进位。 */
    @Test
    void sizeTierBoundariesUseActualLongestEdge() {
        for (int[] boundary : List.of(new int[]{512, 24}, new int[]{513, 12}, new int[]{768, 12}, new int[]{769, 8}, new int[]{960, 8})) {
            ObjectNode result = PortfolioAiFrameSelection.select(frames(24, 100, boundary[0]));
            assertThat(result.path(KEY_FRAMES).size()).as("最长边 %s", boundary[0]).isEqualTo(boundary[1]);
            assertThat(PortfolioAiFrameSelection.estimatedInputTokens(result)).isEqualTo(16_384);
        }
        assertThat(times(PortfolioAiFrameSelection.select(frames(24, 768, 432))))
                .containsExactly(0L, 2_000L, 4_000L, 6_000L, 8_000L, 10_000L, 12_000L, 14_000L, 16_000L, 18_000L, 20_000L, 22_000L);
    }

    /** 混合分辨率按候选中最贵一张定数，再按实际选中图片的档位累计预估。 */
    @Test
    void mixedResolutionsUseMostExpensiveCandidateToLimitCount() {
        ArrayNode input = frames(24, 512, 288);
        for (int index = 0; index < input.size(); index += 3) { ((ObjectNode) input.get(index)).put(KEY_WIDTH, 960); }
        ObjectNode result = PortfolioAiFrameSelection.select(input);
        assertThat(result.path(KEY_FRAMES).size()).isEqualTo(8);
        assertThat(PortfolioAiFrameSelection.estimatedInputTokens(result)).isEqualTo(8_192);
    }

    /** 少量合法输入不为凑满预算而复制帧，旧清单按真实时间排序且不被修改。 */
    @Test
    void legacyManifestIsSortedWithoutMutationOrSyntheticFrames() {
        ArrayNode input = frames(8, 960, 540);
        ArrayNode reversed = StrictAiJson.mapper().createArrayNode();
        for (int index = input.size() - 1; index >= 0; index--) { reversed.add(input.get(index)); }
        ArrayNode before = reversed.deepCopy();
        ObjectNode result = PortfolioAiFrameSelection.select(reversed);
        assertThat(times(result)).containsExactly(0L, 1_000L, 2_000L, 3_000L, 4_000L, 5_000L, 6_000L, 7_000L);
        assertThat(reversed).isEqualTo(before);
        ((ObjectNode) result.path(KEY_FRAMES).get(0)).put(KEY_BYTES, 1);
        assertThat(input.get(0).path(KEY_BYTES).longValue()).isEqualTo(100);
    }

    /** 原时间中心附近为黑白过场时，在同一时间段内选择正常曝光图。 */
    @Test
    void visualFeaturesAvoidBlackAndWhiteCandidatesInsideTheirSegments() {
        ArrayNode input = framesWithFeatures();
        features(input, 1).put(LUMINANCE, 0).put(SHARPNESS, 1_000);
        features(input, 4).put(LUMINANCE, 255).put(SHARPNESS, 1_000);
        ObjectNode result = PortfolioAiFrameSelection.select(input);
        assertThat(times(result)).startsWith(0L, 3_000L);
        for (JsonNode frame : result.path(KEY_FRAMES)) { assertThat(frame.has(FEATURES)).isFalse(); }
        assertThat(PortfolioAiFrameSelection.estimatedInputTokens(result)).isEqualTo(16_384);
    }

    /** 内容重复时选择同段内差异较大的图，不能因去重而删掉某个时间段。 */
    @Test
    void visualFeaturesPreferDetailAndDifferenceWithoutDroppingSegments() {
        ArrayNode detailed = framesWithFeatures();
        features(detailed, 2).put(SHARPNESS, 100);
        assertThat(times(PortfolioAiFrameSelection.select(detailed))).startsWith(2_000L);

        ArrayNode diverse = framesWithFeatures();
        features(diverse, 5).put(DHASH, OPPOSITE_HASH);
        ObjectNode result = PortfolioAiFrameSelection.select(diverse);
        assertThat(times(result)).startsWith(1_000L, 5_000L);
        List<Long> times = times(result);
        for (int group = 0; group < 8; group++) {
            assertThat(times.get(group)).isBetween(group * 3_000L, group * 3_000L + 2_000);
        }
    }

    /** 混用新旧或损坏特征时整份清单采用同一中心回退，不把缺少特征的帧排除。 */
    @Test
    void incompleteOrMalformedFeaturesFallBackToSegmentCenters() {
        List<ObjectNode> invalidFeatures = List.of(
                StrictAiJson.mapper().createObjectNode(),
                feature().put(KEY_VERSION, UNKNOWN_VERSION), feature().put(DHASH, "FFFFFFFFFFFFFFFF"),
                feature().put(LUMINANCE, 256), feature().put(SHARPNESS, -1), feature().put(SHARPNESS, 1.5));
        for (ObjectNode invalid : invalidFeatures) {
            ArrayNode input = framesWithFeatures();
            features(input, 1).put(LUMINANCE, 0);
            ((ObjectNode) input.get(23)).set(FEATURES, invalid);
            assertThat(times(PortfolioAiFrameSelection.select(input))).startsWith(1_000L, 4_000L);
        }
        ArrayNode partial = framesWithFeatures();
        ((ObjectNode) partial.get(0)).remove(FEATURES);
        assertThat(PortfolioAiFrameSelection.select(partial)).isEqualTo(PortfolioAiFrameSelection.select(frames(24, 960, 540)));
    }

    /** 不合法数量、空清单或必需字段缺失都必须拒绝，不能静默减少输入。 */
    @Test
    void invalidManifestShapeAndMandatoryFieldsAreRejected() {
        assertInvalid(null);
        assertInvalid(StrictAiJson.mapper().createObjectNode());
        assertInvalid(frames(7, 960, 540));
        assertInvalid(frames(25, 960, 540));
        for (String field : List.of(KEY_OBJECT_KEY, KEY_BYTES, KEY_TIME_MS, KEY_WIDTH, KEY_HEIGHT)) {
            ArrayNode input = frames(8, 960, 540);
            ((ObjectNode) input.get(7)).remove(field);
            assertInvalid(input);
        }
    }

    /** 必需值禁止字符串、浮点、越界整数和重复帧身份。 */
    @Test
    void invalidValuesAndDuplicatesCannotEnterASelection() {
        List<ObjectNode> invalidFrames = List.of(
                frame(0, 960, 540).put(KEY_BYTES, 0), frame(0, 960, 540).put(KEY_BYTES, FRAME_MAX_BYTES + 1),
                frame(0, 960, 540).put(KEY_TIME_MS, -1), frame(0, 960, 540).put(KEY_TIME_MS, BigInteger.ONE.shiftLeft(64)),
                frame(0, 960, 540).put(KEY_WIDTH, 961), frame(0, 960, 540).put(KEY_HEIGHT, 0),
                frame(0, 960, 540).put(KEY_WIDTH, "960"), frame(0, 960, 540).put(KEY_BYTES, 100.5),
                frame(0, 960, 540).put(KEY_OBJECT_KEY, " "), frame(0, 960, 540).put(KEY_OBJECT_KEY, 123));
        for (ObjectNode invalidFrame : invalidFrames) {
            ArrayNode input = frames(8, 960, 540);
            input.set(0, invalidFrame);
            assertInvalid(input);
        }
        ArrayNode duplicateKey = frames(8, 960, 540);
        ((ObjectNode) duplicateKey.get(7)).put(KEY_OBJECT_KEY, duplicateKey.get(0).path(KEY_OBJECT_KEY).textValue());
        assertInvalid(duplicateKey);
        ArrayNode duplicateTime = frames(8, 960, 540);
        ((ObjectNode) duplicateTime.get(7)).put(KEY_TIME_MS, 0);
        assertInvalid(duplicateTime);
    }

    /** 冻结结果的自报预算、策略版本及封闭字段不能被后续输入改变。 */
    @Test
    void frozenSelectionRejectsForgedBudgetsVersionsAndExtraFields() {
        ObjectNode valid = PortfolioAiFrameSelection.select(frames(24, 960, 540));
        assertInvalidSelection(valid.deepCopy().put(KEY_ESTIMATED_INPUT_TOKENS, 1));
        assertInvalidSelection(valid.deepCopy().put(KEY_ESTIMATED_INPUT_TOKENS, "16384"));
        assertInvalidSelection(valid.deepCopy().put(KEY_POLICY_VERSION, UNKNOWN_VERSION));
        assertInvalidSelection(valid.deepCopy().put(EXTRA_FIELD, true));
        ObjectNode extraFrame = valid.deepCopy();
        ((ObjectNode) extraFrame.path(KEY_FRAMES).get(0)).putObject(FEATURES);
        assertInvalidSelection(extraFrame);
        ObjectNode missingField = valid.deepCopy();
        ((ObjectNode) missingField.path(KEY_FRAMES).get(0)).remove(KEY_WIDTH);
        assertInvalidSelection(missingField);
    }

    /** 校验器重算尺寸档位，并拒绝无序或重复对象与时间，避免冻结后悄悄换图。 */
    @Test
    void frozenSelectionRejectsDimensionAndOrderTampering() {
        ObjectNode valid = PortfolioAiFrameSelection.select(frames(24, 960, 540));
        ObjectNode wrongSize = valid.deepCopy();
        ((ObjectNode) wrongSize.path(KEY_FRAMES).get(0)).put(KEY_WIDTH, 512).put(KEY_HEIGHT, 288);
        assertInvalidSelection(wrongSize);
        ObjectNode wrongOrder = valid.deepCopy();
        ArrayNode reversed = StrictAiJson.mapper().createArrayNode();
        for (int index = 7; index >= 0; index--) { reversed.add(valid.path(KEY_FRAMES).get(index)); }
        wrongOrder.set(KEY_FRAMES, reversed);
        assertInvalidSelection(wrongOrder);
        ObjectNode duplicate = valid.deepCopy();
        ((ArrayNode) duplicate.path(KEY_FRAMES)).set(7, duplicate.path(KEY_FRAMES).get(0));
        assertInvalidSelection(duplicate);
    }

    /** 即使自报累计值准确，超过总额或最贵帧定数规则的冻结输入仍不合法。 */
    @Test
    void frozenSelectionCannotExceedThePolicyBudget() {
        ObjectNode overBudget = PortfolioAiFrameSelection.select(frames(24, 512, 288));
        overBudget.set(KEY_FRAMES, frames(24, 960, 540));
        overBudget.put(KEY_ESTIMATED_INPUT_TOKENS, FRAMES_INPUT_OVERHEAD_TOKENS + 24 * 1_536);
        assertInvalidSelection(overBudget);
        ObjectNode mixed = PortfolioAiFrameSelection.select(frames(12, 512, 288));
        ((ObjectNode) mixed.path(KEY_FRAMES).get(0)).put(KEY_WIDTH, 960);
        mixed.put(KEY_ESTIMATED_INPUT_TOKENS, FRAMES_INPUT_OVERHEAD_TOKENS + 11 * 512 + 1_536);
        assertInvalidSelection(mixed);
    }

    /** 历史冻结文本按自身版本校验，预算和时刻均不通过当前默认策略生成期望值。 */
    @Test
    void storedFirstVersionFixtureKeepsItsOriginalSelectionAndBudget() {
        ObjectNode frozen = StrictAiJson.parseObject(FROZEN_V1_SELECTION, 16 * 1024, 8);
        assertThat(PortfolioAiFrameSelection.estimatedInputTokens(frozen)).isEqualTo(16_384);
        assertThat(PortfolioAiFrameSelection.validateFrozen(frames(24, 960, 540), frozen)).isEqualTo(16_384);
        assertThat(times(frozen)).containsExactly(1_000L, 4_000L, 7_000L, 10_000L, 13_000L, 16_000L, 19_000L, 22_000L);
    }

    /** 第一版三个尺寸档位和独立指令裕量固定，后续默认预算变化不能影响旧冻结计划。 */
    @Test
    void firstVersionFrozenBudgetsRemainValidAcrossEveryResolutionTier() {
        for (int[] tier : List.of(new int[]{512, 8_192}, new int[]{768, 12_288}, new int[]{960, 16_384})) {
            ObjectNode frozen = StrictAiJson.mapper().createObjectNode().put(KEY_POLICY_VERSION, POLICY_VERSION)
                    .put(KEY_ESTIMATED_INPUT_TOKENS, tier[1]);
            frozen.set(KEY_FRAMES, frames(8, tier[0], 288));
            assertThat(PortfolioAiFrameSelection.estimatedInputTokens(frozen)).isEqualTo(tier[1]);
            assertThat(PortfolioAiFrameSelection.validateFrozen(frames(8, tier[0], 288), frozen)).isEqualTo(tier[1]);
        }
    }

    /** 第一版视觉择优和整条特征缺失回退分别复核，冻结消费不能静默套用其它评分规则。 */
    @Test
    void frozenFirstVersionRetainsItsVisualScoringAndFallbackRules() {
        ArrayNode manifest = framesWithFeatures();
        features(manifest, 1).put(LUMINANCE, 0).put(SHARPNESS, 1_000);
        ObjectNode frozen = StrictAiJson.parseObject(FROZEN_V1_SELECTION, 16 * 1024, 8);
        ((ArrayNode) frozen.path(KEY_FRAMES)).set(0, frame(0, 960, 540));
        assertThat(PortfolioAiFrameSelection.validateFrozen(manifest, frozen)).isEqualTo(16_384);
        ((ObjectNode) manifest.get(23)).remove(FEATURES);
        ObjectNode fallback = StrictAiJson.parseObject(FROZEN_V1_SELECTION, 16 * 1024, 8);
        assertThat(PortfolioAiFrameSelection.validateFrozen(manifest, fallback)).isEqualTo(16_384);
        assertThatThrownBy(() -> PortfolioAiFrameSelection.validateFrozen(manifest, frozen))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }

    /** 即使结构及预算合法，未按冻结版本从当前清单选出的替换帧也不可通过复核。 */
    @Test
    void frozenValidationRejectsDifferentRepresentativesWithTheSameBudget() {
        ObjectNode frozen = StrictAiJson.parseObject(FROZEN_V1_SELECTION, 16 * 1024, 8);
        ((ArrayNode) frozen.path(KEY_FRAMES)).set(0, frame(0, 960, 540));
        assertThat(PortfolioAiFrameSelection.estimatedInputTokens(frozen)).isEqualTo(16_384);
        assertThatThrownBy(() -> PortfolioAiFrameSelection.validateFrozen(frames(24, 960, 540), frozen))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }

    /** 未知文本版本明确归为合同不支持，缺失或非文本版本仍是非法冻结结构。 */
    @Test
    void unknownPolicyVersionIsDistinguishedFromMalformedFrozenData() {
        ObjectNode unknown = StrictAiJson.parseObject(FROZEN_V1_SELECTION, 16 * 1024, 8)
                .put(KEY_POLICY_VERSION, UNKNOWN_VERSION);
        assertThatThrownBy(() -> PortfolioAiFrameSelection.estimatedInputTokens(unknown))
                .isExactlyInstanceOf(PortfolioAiFrameSelection.UnsupportedPolicyVersionException.class);
        assertThatThrownBy(() -> PortfolioAiFrameSelection.validateFrozen(frames(24, 960, 540), unknown))
                .isExactlyInstanceOf(PortfolioAiFrameSelection.UnsupportedPolicyVersionException.class);
        for (ObjectNode malformed : List.of(unknown.deepCopy().put(KEY_POLICY_VERSION, 1),
                unknown.deepCopy().putNull(KEY_POLICY_VERSION), unknown.deepCopy().put(KEY_POLICY_VERSION, " "))) {
            assertThatThrownBy(() -> PortfolioAiFrameSelection.estimatedInputTokens(malformed))
                    .isExactlyInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PortfolioAiFrameSelection.validateFrozen(frames(24, 960, 540), malformed))
                    .isExactlyInstanceOf(IllegalArgumentException.class);
        }
        unknown.remove(KEY_POLICY_VERSION);
        assertThatThrownBy(() -> PortfolioAiFrameSelection.estimatedInputTokens(unknown))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }

    /** 特征覆盖区分历史缺失与已写入的非法值，统计不改变选帧输入或回退语义。 */
    @Test
    void featureCoverageCountsUsableMissingAndMalformedValuesWithoutMutation() {
        ObjectNode frozen = StrictAiJson.parseObject(FROZEN_V1_SELECTION, 16 * 1024, 8);
        assertThat(PortfolioAiFrameSelection.featureCoverage(framesWithFeatures(), frozen))
                .isEqualTo(new PortfolioAiFrameSelection.FeatureCoverage(24, 24, 0, 0));
        assertThat(PortfolioAiFrameSelection.featureCoverage(frames(24, 960, 540), frozen))
                .isEqualTo(new PortfolioAiFrameSelection.FeatureCoverage(24, 0, 24, 0));
        ArrayNode mixed = framesWithFeatures();
        ((ObjectNode) mixed.get(0)).remove(FEATURES);
        ((ObjectNode) mixed.get(1)).putNull(FEATURES);
        features(mixed, 2).put(KEY_VERSION, UNKNOWN_VERSION);
        features(mixed, 3).put(DHASH, "FFFFFFFFFFFFFFFF");
        features(mixed, 4).put(SHARPNESS, -1);
        ArrayNode before = mixed.deepCopy();
        assertThat(PortfolioAiFrameSelection.featureCoverage(mixed, frozen))
                .isEqualTo(new PortfolioAiFrameSelection.FeatureCoverage(24, 19, 2, 3));
        assertThat(mixed).isEqualTo(before);
        assertThat(PortfolioAiFrameSelection.select(mixed)).isEqualTo(PortfolioAiFrameSelection.select(frames(24, 960, 540)));
    }

    /** 覆盖统计与执行共用冻结版本分派，未知或损坏版本不能静默使用第一版解释。 */
    @Test
    void featureCoverageRejectsUnknownAndMalformedFrozenVersions() {
        ArrayNode manifest = framesWithFeatures();
        ObjectNode frozen = StrictAiJson.parseObject(FROZEN_V1_SELECTION, 16 * 1024, 8);
        ObjectNode unknown = frozen.deepCopy().put(KEY_POLICY_VERSION, UNKNOWN_VERSION);
        assertThatThrownBy(() -> PortfolioAiFrameSelection.featureCoverage(manifest, unknown))
                .isExactlyInstanceOf(PortfolioAiFrameSelection.UnsupportedPolicyVersionException.class);
        for (ObjectNode malformed : List.of(frozen.deepCopy().put(KEY_POLICY_VERSION, 1),
                frozen.deepCopy().putNull(KEY_POLICY_VERSION), frozen.deepCopy().put(KEY_POLICY_VERSION, " "))) {
            assertThatThrownBy(() -> PortfolioAiFrameSelection.featureCoverage(manifest, malformed))
                    .isExactlyInstanceOf(IllegalArgumentException.class);
        }
        frozen.remove(KEY_POLICY_VERSION);
        assertThatThrownBy(() -> PortfolioAiFrameSelection.featureCoverage(manifest, frozen))
                .isExactlyInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PortfolioAiFrameSelection.featureCoverage(manifest, null))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }

    /** 两独立发布工程必须以完全一致的源码执行选帧和调用前预算复核。 */
    @Test
    void runtimeAndJobUseIdenticalSelectionPolicySource() throws IOException {
        Path module = Path.of("").toAbsolutePath();
        String sibling = module.getFileName().toString().equals(RUNTIME_MODULE) ? JOB_MODULE : RUNTIME_MODULE;
        assertThat(Files.readString(module.resolve(SOURCE_PATH)))
                .isEqualTo(Files.readString(module.resolveSibling(sibling).resolve(SOURCE_PATH)));
    }

    /** 构造按秒均匀的完整帧清单，所有身份均独立可验证。 */
    private ArrayNode frames(int count, int width, int height) {
        ArrayNode result = StrictAiJson.mapper().createArrayNode();
        for (int index = 0; index < count; index++) { result.add(frame(index, width, height)); }
        return result;
    }

    /** 单帧测试夹具保留生产必需字段，尺寸和时间可按测试独立修改。 */
    private ObjectNode frame(int index, int width, int height) {
        return StrictAiJson.mapper().createObjectNode().put(KEY_OBJECT_KEY, FRAME_PREFIX + index).put(KEY_BYTES, 100)
                .put(KEY_TIME_MS, index * 1_000L).put(KEY_WIDTH, width).put(KEY_HEIGHT, height);
    }

    /** 相同基础特征确保测试只观察指定质量或差异因素的影响。 */
    private ArrayNode framesWithFeatures() {
        ArrayNode result = frames(24, 960, 540);
        for (JsonNode frame : result) { ((ObjectNode) frame).set(FEATURES, feature()); }
        return result;
    }

    /** 非黑白、具备少量细节的合法视觉特征。 */
    private ObjectNode feature() {
        return StrictAiJson.mapper().createObjectNode().put(KEY_VERSION, FEATURES_VERSION).put(DHASH, SAME_HASH)
                .put(LUMINANCE, 128).put(SHARPNESS, 20);
    }

    /** 仅修改测试目标帧的特征，不依赖真实图片编码差异。 */
    private ObjectNode features(ArrayNode frames, int index) { return (ObjectNode) frames.get(index).path(FEATURES); }

    /** 提取实际冻结时间点以检查覆盖和排序，不只比对最终帧数。 */
    private List<Long> times(ObjectNode selection) {
        List<Long> result = new ArrayList<>();
        selection.path(KEY_FRAMES).forEach(frame -> result.add(frame.path(KEY_TIME_MS).longValue()));
        return result;
    }

    /** 错误只返回固定安全提示，不带对象键和不可信值。 */
    private void assertInvalid(JsonNode input) {
        assertThatThrownBy(() -> PortfolioAiFrameSelection.select(input)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(PortfolioAiTaskMessage.TASK_FAILED_MESSAGE);
    }

    /** 冻结结果与输入清单使用同一安全异常边界。 */
    private void assertInvalidSelection(JsonNode input) {
        assertThatThrownBy(() -> PortfolioAiFrameSelection.estimatedInputTokens(input)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(PortfolioAiTaskMessage.TASK_FAILED_MESSAGE);
    }
}
