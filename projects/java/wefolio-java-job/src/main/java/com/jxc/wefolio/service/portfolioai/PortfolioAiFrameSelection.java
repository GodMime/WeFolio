package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.message.PortfolioAiTaskMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static com.jxc.wefolio.service.portfolioai.PortfolioAiMediaContract.*;

/** 按实际图片尺寸控制作品集视觉预算，冻结策略按版本执行；不代替完整审核或语义识别。 */
public final class PortfolioAiFrameSelection {
    /** 冻结计划中的可选选帧字段，旧计划没有该字段时由消费者沿用原路径。 */
    public static final String FIELD = "frameSelection";
    /** 各版本共用的冻结信封仅声明版本、帧集合和预算。 */
    private static final Set<String> SELECTION_FIELDS = Set.of(KEY_POLICY_VERSION, KEY_FRAMES, KEY_ESTIMATED_INPUT_TOKENS);
    /** 默认生产策略只影响新任务；升级时新增实现，保留历史策略分派。 */
    private static final SelectionPolicy DEFAULT_POLICY = ResolutionBudgetV1.INSTANCE;

    /** 工具类只提供纯函数，不持有调用状态。 */
    private PortfolioAiFrameSelection() { }

    /** 新任务按当前默认策略冻结，默认变更不得改写既有版本实现。 */
    public static ObjectNode select(JsonNode manifestFrames) { return DEFAULT_POLICY.select(manifestFrames); }

    /** 从冻结策略版本重算预算，不跟随新任务的默认策略。 */
    public static long estimatedInputTokens(JsonNode selection) { return frozenPolicy(selection).estimatedInputTokens(selection); }

    /** 按冻结版本复核预算及完整选择，返回可信预估；落库整数节点变化不影响规范比较。 */
    public static long validateFrozen(JsonNode manifestFrames, JsonNode selection) {
        SelectionPolicy policy = frozenPolicy(selection);
        long estimate = policy.estimatedInputTokens(selection);
        if (!StrictAiJson.canonical(policy.select(manifestFrames)).equals(StrictAiJson.canonical(selection))) { throw invalid(); }
        return estimate;
    }

    /** 按已验证冻结选择的策略统计可选特征，避免默认策略升级后误报历史任务的降级范围。 */
    public static FeatureCoverage featureCoverage(JsonNode manifestFrames, JsonNode frozenSelection) {
        return frozenPolicy(frozenSelection).featureCoverage(manifestFrames);
    }

    /** 先检查版本字段结构；未知文本版本与损坏数据分开，供消费方映射合同错误。 */
    private static SelectionPolicy frozenPolicy(JsonNode selection) {
        requireFields(selection, SELECTION_FIELDS);
        if (!selection.path(KEY_POLICY_VERSION).isTextual()
                || selection.path(KEY_POLICY_VERSION).textValue().isBlank()) { throw invalid(); }
        if (ResolutionBudgetV1.VERSION.equals(selection.path(KEY_POLICY_VERSION).textValue())) { return ResolutionBudgetV1.INSTANCE; }
        throw new UnsupportedPolicyVersionException();
    }

    /** 固定对象结构拒绝多余字段和缺失字段，避免不同消费者解释不一致。 */
    private static void requireFields(JsonNode value, Set<String> fields) {
        if (value == null || !value.isObject() || value.size() != fields.size()) { throw invalid(); }
        value.fieldNames().forEachRemaining(field -> { if (!fields.contains(field)) { throw invalid(); } });
    }

    /** 固定提示不携带 COS 对象键、清单原文或其它不可信内容。 */
    private static IllegalArgumentException invalid() { return new IllegalArgumentException(PortfolioAiTaskMessage.TASK_FAILED_MESSAGE); }

    /** 消费端尚未实现冻结策略版本；不把版本原文加入用户提示或日志。 */
    public static final class UnsupportedPolicyVersionException extends IllegalArgumentException {
        /** 异常序列化身份不随策略实现变化。 */
        private static final long serialVersionUID = 1L;

        /** 由版本分派统一创建，调用方只依异常类型分类。 */
        private UnsupportedPolicyVersionException() { super(PortfolioAiTaskMessage.TASK_FAILED_MESSAGE); }
    }

    /**
     * 可选特征覆盖；总数等于可用、缺失与非法三类之和。
     * @param total 完整候选帧数
     * @param usable 可按冻结策略特征定义解析的数量
     * @param missing 未提供特征或显式空值的数量
     * @param invalid 提供了特征但版本、结构或值不合法的数量
     */
    public record FeatureCoverage(int total, int usable, int missing, int invalid) { }

    /** 每个已发布版本同时冻结选帧与预算语义，生产默认值只引用其中一个。 */
    private interface SelectionPolicy {
        /** 对完整可信候选执行本版本算法。 */
        ObjectNode select(JsonNode manifestFrames);
        /** 严格验证本版本冻结结构和预算。 */
        long estimatedInputTokens(JsonNode selection);
        /** 以本版本实际使用的特征定义统计覆盖，不跟随其它版本改变解释。 */
        FeatureCoverage featureCoverage(JsonNode manifestFrames);
    }

    /** 第一版预算、评分及输入合同；后续升级新增兄弟实现，不就地修改。 */
    private static final class ResolutionBudgetV1 implements SelectionPolicy {
        /** 历史策略身份；已冻结任务仍使用此实现，新增策略不得修改此版本的行为。 */
        private static final String VERSION = "resolution-budget-v1";
        /** 单视频图片部分的保守视觉预算，不含固定指令裕量。 */
        private static final long VISUAL_TOKEN_BUDGET = 12_288;
        /** 小尺寸与中尺寸档位按最长边计算，横竖图适用相同规则。 */
        private static final int SMALL_EDGE = 512;
        private static final int MEDIUM_EDGE = 768;
        /** 各档位是保守预估，真实供应商回执仍由调用账本单独结算。 */
        private static final long SMALL_TOKENS = 512;
        private static final long MEDIUM_TOKENS = 1_024;
        private static final long LARGE_TOKENS = 1_536;
        /** 可选视觉特征的字段与版本，不纳入下游最终图片输入。 */
        private static final String FEATURES = "visualFeatures";
        private static final String FEATURES_VERSION = "visual-features-v1";
        private static final String DHASH = "dHash";
        private static final String LUMINANCE = "luminance";
        private static final String SHARPNESS = "sharpness";
        /** 摘要固定为六十四位差值哈希的小写十六进制表示。 */
        private static final Pattern DHASH_PATTERN = Pattern.compile("[0-9a-f]{16}");
        /** 近黑白帧降权；细节分在常见图片的梯度范围内饱和，避免偏好极端噪声。 */
        private static final int DARK_LIMIT = 12;
        private static final int LIGHT_LIMIT = 243;
        private static final int DETAIL_SATURATION = 100;
        /** 亮度优先级高于全部次级评分；多样性其次，时间中心只用于轻量择优。 */
        private static final double EXPOSURE_WEIGHT = 1_000;
        private static final double DETAIL_WEIGHT = 80;
        private static final double DIVERSITY_WEIGHT = 160;
        private static final double CENTER_WEIGHT = 10;
        /** 输出字段顺序固定，避免集合迭代顺序在进程重启后改变冻结文本和缓存身份。 */
        private static final List<String> FRAME_FIELD_ORDER = List.of(KEY_OBJECT_KEY, KEY_BYTES, KEY_TIME_MS, KEY_WIDTH, KEY_HEIGHT);
        /** 字段集合只用于结构检查，不能决定输出顺序。 */
        private static final Set<String> FRAME_FIELDS = Set.copyOf(FRAME_FIELD_ORDER);

        /** 第一版独立保存读取范围，避免全局合同扩容时改变历史冻结校验。 */
        private static final int FRAME_MIN_COUNT = 8;
        private static final int FRAME_MAX_COUNT = 24;
        private static final int FRAME_MAX_EDGE = 960;
        private static final int FRAME_MAX_BYTES = 200 * 1024;
        /** 第一版固定指令裕量，不引用未来可能变化的默认预算。 */
        private static final long INPUT_OVERHEAD_TOKENS = 4_096;
        /** 无状态的历史版本实现，供默认生产及冻结版本分派复用。 */
        private static final ResolutionBudgetV1 INSTANCE = new ResolutionBudgetV1();

        /** 第一版实现仅由内部版本分派持有。 */
        private ResolutionBudgetV1() { }

        /** 第一版可选特征覆盖统计，不输出帧内容，也不改变选帧输入。 */
        @Override
        public FeatureCoverage featureCoverage(JsonNode manifestFrames) {
            if (manifestFrames == null || !manifestFrames.isArray()) { throw invalid(); }
            int usable = 0;
            int missing = 0;
            int invalid = 0;
            for (JsonNode frame : manifestFrames) {
                if (!frame.isObject()) { throw invalid(); }
                JsonNode value = frame.path(FEATURES);
                if (value.isMissingNode() || value.isNull()) { missing++; }
                else if (features(value) == null) { invalid++; }
                else { usable++; }
            }
            return new FeatureCoverage(manifestFrames.size(), usable, missing, invalid);
        }

        /** 验证完整清单后按连续时间分组取代表帧；任何必需字段损坏均拒绝整份输入。 */
        @Override
        public ObjectNode select(JsonNode manifestFrames) {
            List<Frame> candidates = readFrames(manifestFrames, false);
            candidates.sort(Comparator.comparingLong(Frame::timeMs));
            long maxTokens = candidates.stream().mapToLong(Frame::tokens).max().orElseThrow(PortfolioAiFrameSelection::invalid);
            int count = Math.min(candidates.size(), (int) (VISUAL_TOKEN_BUDGET / maxTokens));
            boolean useFeatures = candidates.stream().allMatch(frame -> frame.features() != null);
            List<Frame> chosen = new ArrayList<>();
            for (int group = 0; group < count; group++) {
                int start = group * candidates.size() / count;
                int end = (group + 1) * candidates.size() / count;
                chosen.add(representative(candidates.subList(start, end), chosen, useFeatures));
            }
            ObjectNode selection = StrictAiJson.mapper().createObjectNode().put(KEY_POLICY_VERSION, VERSION);
            ArrayNode frames = selection.putArray(KEY_FRAMES);
            long tokens = INPUT_OVERHEAD_TOKENS;
            for (Frame frame : chosen) {
                ObjectNode frozen = frames.addObject();
                // 只复制可信必需字段，不保留调用方 JsonNode 引用或可选元数据。
                for (String field : FRAME_FIELD_ORDER) { frozen.set(field, frame.source().get(field).deepCopy()); }
                tokens += frame.tokens();
            }
            selection.put(KEY_ESTIMATED_INPUT_TOKENS, tokens);
            return selection;
        }

        /** 严格复核冻结选择并重算预算，不能相信清单自报的 token 数。 */
        @Override
        public long estimatedInputTokens(JsonNode selection) {
            requireFields(selection, SELECTION_FIELDS);
            if (!selection.path(KEY_POLICY_VERSION).isTextual()
                    || !VERSION.equals(selection.path(KEY_POLICY_VERSION).textValue())) { throw invalid(); }
            List<Frame> frames = readFrames(selection.path(KEY_FRAMES), true);
            long visualTokens = 0;
            long maxTokens = 0;
            long previousTime = -1;
            for (Frame frame : frames) {
                if (frame.timeMs() <= previousTime) { throw invalid(); }
                previousTime = frame.timeMs();
                visualTokens += frame.tokens();
                maxTokens = Math.max(maxTokens, frame.tokens());
            }
            if (visualTokens > VISUAL_TOKEN_BUDGET || frames.size() * maxTokens > VISUAL_TOKEN_BUDGET) { throw invalid(); }
            long total = visualTokens + INPUT_OVERHEAD_TOKENS;
            if (integer(selection.path(KEY_ESTIMATED_INPUT_TOKENS), 0, Long.MAX_VALUE) != total) { throw invalid(); }
            return total;
        }

        /** 完整帧清单允许额外生产元数据；冻结选择则必须仅含五个身份和尺寸字段。 */
        private static List<Frame> readFrames(JsonNode frames, boolean strictFields) {
            if (frames == null || !frames.isArray() || !isSupportedFrameCount(frames.size())) { throw invalid(); }
            List<Frame> result = new ArrayList<>();
            Set<String> objectKeys = new HashSet<>();
            Set<Long> times = new HashSet<>();
            for (JsonNode frame : frames) {
                if (!frame.isObject()) { throw invalid(); }
                if (strictFields) { requireFields(frame, FRAME_FIELDS); }
                JsonNode key = frame.path(KEY_OBJECT_KEY);
                if (!key.isTextual() || key.textValue().isBlank() || !objectKeys.add(key.textValue())) { throw invalid(); }
                integer(frame.path(KEY_BYTES), 1, FRAME_MAX_BYTES);
                long time = integer(frame.path(KEY_TIME_MS), 0, Long.MAX_VALUE);
                if (!times.add(time)) { throw invalid(); }
                int width = (int) integer(frame.path(KEY_WIDTH), 1, FRAME_MAX_EDGE);
                int height = (int) integer(frame.path(KEY_HEIGHT), 1, FRAME_MAX_EDGE);
                result.add(new Frame(frame, time, tokens(Math.max(width, height)), features(frame.path(FEATURES))));
            }
            return result;
        }

        /** 每个非空连续分段恰好选一张，首尾所在的分段始终保留，平分时优先较早帧。 */
        private static Frame representative(List<Frame> group, List<Frame> chosen, boolean useFeatures) {
            long first = group.getFirst().timeMs();
            long span = group.getLast().timeMs() - first;
            long middle = first + span / 2;
            Frame best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (Frame frame : group) {
                long distance = Math.abs(frame.timeMs() - middle);
                double centrality = span == 0 ? 1 : 1 - (double) distance / span;
                double score = useFeatures ? qualityScore(frame, chosen) + centrality * CENTER_WEIGHT : -distance;
                if (best == null || score > bestScore) { best = frame; bestScore = score; }
            }
            return best;
        }

        /** 使用亮度、相对梯度和差值哈希减少空白及重复帧，不推断画面语义或审核结论。 */
        private static double qualityScore(Frame frame, List<Frame> chosen) {
            Features feature = frame.features();
            double exposure = feature.luminance() >= DARK_LIMIT && feature.luminance() <= LIGHT_LIMIT ? EXPOSURE_WEIGHT : 0;
            double detail = Math.min(feature.sharpness(), DETAIL_SATURATION) * DETAIL_WEIGHT / DETAIL_SATURATION;
            int minDifference = chosen.isEmpty() ? 0 : Long.SIZE;
            for (Frame prior : chosen) { minDifference = Math.min(minDifference, Long.bitCount(feature.dHash() ^ prior.features().dHash())); }
            return exposure + detail + minDifference * DIVERSITY_WEIGHT / Long.SIZE;
        }

        /** 可选特征不完整或版本不认识时回退旧清单采样，不能因此拒绝历史可读帧。 */
        private static Features features(JsonNode value) {
            if (!value.isObject() || !FEATURES_VERSION.equals(value.path(KEY_VERSION).asText())
                    || !value.path(DHASH).isTextual() || !DHASH_PATTERN.matcher(value.path(DHASH).textValue()).matches()
                    || !inIntegerRange(value.path(LUMINANCE), 0, 255) || !inIntegerRange(value.path(SHARPNESS), 0, 1_000)) { return null; }
            return new Features(Long.parseUnsignedLong(value.path(DHASH).textValue(), 16), value.path(LUMINANCE).intValue(),
                    value.path(SHARPNESS).intValue());
        }

        /** 最长边决定估算档位，不使用原视频分辨率或压缩后的文件字节数。 */
        private static long tokens(int longestEdge) {
            if (longestEdge <= SMALL_EDGE) { return SMALL_TOKENS; }
            return longestEdge <= MEDIUM_EDGE ? MEDIUM_TOKENS : LARGE_TOKENS;
        }

        /** 必需整数不接受字符串、浮点和截断溢出。 */
        private static long integer(JsonNode value, long min, long max) {
            if (!inIntegerRange(value, min, max)) { throw invalid(); }
            return value.longValue();
        }

        /** 先验证数值类型及长整数范围，再进行边界比较。 */
        private static boolean inIntegerRange(JsonNode value, long min, long max) {
            return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= min && value.longValue() <= max;
        }

        /** 第一版候选和输出固定接受八至二十四帧。 */
        private static boolean isSupportedFrameCount(int count) { return count >= FRAME_MIN_COUNT && count <= FRAME_MAX_COUNT; }

        /** 内部帧视图仅在本次纯函数调用中使用，输出不泄漏原始节点引用。 */
        private record Frame(JsonNode source, long timeMs, long tokens, Features features) { }

        /** 已验证的低成本视觉统计，仅用于相同时间分段内择优。 */
        private record Features(long dHash, int luminance, int sharpness) { }
    }
}
