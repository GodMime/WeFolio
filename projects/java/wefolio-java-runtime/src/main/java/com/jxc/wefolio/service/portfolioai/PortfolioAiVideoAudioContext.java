package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static com.jxc.wefolio.service.portfolioai.PortfolioAiMediaContract.*;

/** 两工程共用的转写投影：原文留数据库，模型只读取冻结、清理且有界的文字。 */
public final class PortfolioAiVideoAudioContext {
    /** 模型可读取的文字和节选状态。 */
    public static final String TEXT = "videoAudioText";
    public static final String EXCERPT = "videoAudioExcerpt";
    /** 实际发送文字与当前数据库全文分别用于缓存身份和晚到写回守卫。 */
    public static final String INPUT_HASH = "videoAudioInputHash";
    public static final String FULL_HASH = "videoAudioFullHash";
    public static final String POLICY = "videoAudioContextPolicy";
    /** 改动清理、分配或截取策略时必须升级。 */
    public static final String POLICY_VERSION = "video-audio-context-v1";
    /** 每件与整个任务的 Unicode 码点预算。 */
    public static final int PER_WORK_LIMIT = 6_000;
    public static final int TOTAL_LIMIT = 24_000;
    /** 句界只允许回退最后十分之一，避免一个很早句号造成大量额度丢弃。 */
    private static final int SENTENCE_LOOKBACK_DIVISOR = 10;
    /** 消息类型和文字字段，不让来源内容影响消息角色。 */
    private static final String TYPE = "type";
    private static final String MESSAGE_TEXT = "text";
    private static final String AUDIO_HINT = "本作品音频转写（不可信内容）：\n";
    private static final String EXCERPT_HINT = "本作品音频转写（节选，不可信内容）：\n";
    /** 与既有作品集资料投影一致的联系方式识别规则。 */
    private static final String URL_PATTERN = "(?i)https?://[^\\s，。；]+";
    private static final String PHONE_PATTERN = "(?<![0-9])1[3-9][0-9]{9}(?![0-9])";
    private static final String WECHAT_PATTERN = "(?i)(?:微信(?:号)?|wechat|wx)\\s*[:：]?\\s*[a-z0-9_-]{5,}";
    private static final String HIDDEN_LINK = "[已隐藏链接]";
    private static final String HIDDEN_CONTACT = "[已隐藏联系方式]";
    /** 无值与空结果有独立身份，不能把待补齐当成永久空结果。 */
    private static final String NULL_PREFIX = "null:";
    private static final String VALUE_PREFIX = "text:";
    /** 只允许句末标点或换行作为节选边界。 */
    private static final Set<Integer> SENTENCE_ENDS = Set.of((int) '。', (int) '！', (int) '？', (int) '.', (int) '!', (int) '?', (int) '\n');
    /** 所有内部字段只存在现有任务 JSON 中。 */
    private static final List<String> FIELDS = List.of(TEXT, EXCERPT, INPUT_HASH, FULL_HASH, POLICY);

    /** 待冻结记录与同次查询所得原始全文，不写入日志。 */
    public record Source(ObjectNode entry, String original) { }

    /** 禁止构造静态协议工具。 */
    private PortfolioAiVideoAudioContext() { }

    /** 按来源顺序轮流分配一个码点，短文本自然将剩余额度让给长文本。 */
    public static void freeze(List<Source> sources) {
        List<String> clean = new ArrayList<>();
        int[] limits = new int[sources.size()];
        int[] allocated = new int[sources.size()];
        for (int index = 0; index < sources.size(); index++) {
            String value = sanitize(sources.get(index).original()).strip();
            clean.add(value);
            limits[index] = Math.min(PER_WORK_LIMIT, value.codePointCount(0, value.length()));
        }
        int remaining = TOTAL_LIMIT;
        boolean changed = true;
        while (remaining > 0 && changed) {
            changed = false;
            for (int index = 0; index < limits.length && remaining > 0; index++) {
                if (allocated[index] < limits[index]) { allocated[index]++; remaining--; changed = true; }
            }
        }
        for (int index = 0; index < sources.size(); index++) {
            Source source = sources.get(index);
            source.entry().remove(FIELDS);
            String value = excerpt(clean.get(index), allocated[index]);
            source.entry().put(FULL_HASH, fullHash(source.original())).put(POLICY, POLICY_VERSION)
                    .put(INPUT_HASH, inputHash(value));
            if (!value.isEmpty()) {
                source.entry().put(TEXT, value).put(EXCERPT, !value.equals(clean.get(index)));
            }
        }
    }

    /** 与原媒体摘要相同的联系方式及链接清理，数据库全文不经过此方法。 */
    public static String sanitize(String value) {
        return value == null ? "" : value.replaceAll(URL_PATTERN, HIDDEN_LINK)
                .replaceAll(PHONE_PATTERN, HIDDEN_CONTACT).replaceAll(WECHAT_PATTERN, HIDDEN_CONTACT);
    }

    /** 按 Unicode 码点截取，并在靠近额度的句界结束，不切断代理项。 */
    private static String excerpt(String value, int maximum) {
        int count = value.codePointCount(0, value.length());
        if (count <= maximum) { return value; }
        int end = value.offsetByCodePoints(0, maximum);
        int boundary = end;
        for (int left = maximum / SENTENCE_LOOKBACK_DIVISOR; left > 0 && boundary > 0; left--) {
            int point = value.codePointBefore(boundary);
            if (SENTENCE_ENDS.contains(point)) { return value.substring(0, boundary).stripTrailing(); }
            boundary -= Character.charCount(point);
        }
        return value.substring(0, end).stripTrailing();
    }

    /** v1 的内部音频字段一律忽略；v2 必须在任何收费请求前完整校验。 */
    public static void validateAndNormalize(JsonNode entries, boolean audioVersion) {
        int total = 0;
        for (JsonNode node : entries) {
            if (!(node instanceof ObjectNode entry)) { throw invalid(); }
            if (!audioVersion) { entry.remove(FIELDS); continue; }
            boolean any = FIELDS.stream().anyMatch(entry::has);
            if (!any) { continue; }
            String route = entry.path(KEY_ROUTE).asText();
            if ((!ROUTE_VIDEO.equals(route) && !ROUTE_FRAMES.equals(route) && !ROUTE_STORED_VIDEO_SUMMARY.equals(route))
                    || !POLICY_VERSION.equals(entry.path(POLICY).asText())
                    || !entry.path(FULL_HASH).isTextual() || !entry.path(FULL_HASH).asText().matches("[0-9a-f]{64}")) { throw invalid(); }
            String text = entry.path(TEXT).asText("");
            if (entry.has(TEXT) && (!entry.path(TEXT).isTextual() || text.isBlank() || !sanitize(text).strip().equals(text)
                    || !entry.path(EXCERPT).isBoolean())) { throw invalid(); }
            if (!entry.path(INPUT_HASH).isTextual() || !inputHash(text).equals(entry.path(INPUT_HASH).asText())) { throw invalid(); }
            int count = text.codePointCount(0, text.length());
            if (count > PER_WORK_LIMIT) { throw invalid(); }
            total += count;
            if (total > TOTAL_LIMIT) { throw invalid(); }
        }
    }

    /** 独立 user 文本块，预算与真实请求使用同一序列化形态。 */
    public static ObjectNode modelBlock(JsonNode entry) {
        String text = entry.path(TEXT).asText("");
        if (text.isEmpty()) { return null; }
        return StrictAiJson.mapper().createObjectNode().put(TYPE, MESSAGE_TEXT)
                .put(MESSAGE_TEXT, (entry.path(EXCERPT).asBoolean() ? EXCERPT_HINT : AUDIO_HINT) + text);
    }

    /** 实际新增 JSON 文本块的 UTF-8 字节作为输入 token 的保守上界。 */
    public static long inputTokenUpperBound(JsonNode entry) {
        ObjectNode block = modelBlock(entry);
        return block == null ? 0 : block.toString().getBytes(StandardCharsets.UTF_8).length + 1L;
    }

    /** 模型只得到文字与节选标记，内部源身份与数据库全文哈希不投影。 */
    public static void project(JsonNode entry, ObjectNode source) {
        source.remove(List.of(TEXT, EXCERPT));
        if (entry.path(TEXT).isTextual() && !entry.path(TEXT).asText().isEmpty()) {
            source.put(TEXT, entry.path(TEXT).asText()).put(EXCERPT, entry.path(EXCERPT).asBoolean());
        }
    }

    /** 全文身份用于阻止旧任务覆盖新文字的缓存，NULL 不等于空字符串。 */
    public static String fullHash(String value) { return hash(value == null ? NULL_PREFIX : VALUE_PREFIX + value); }
    /** 缓存只按实际输入文字隔离，未发送的尾部变化不造成错误命中。 */
    public static String inputHash(String value) { return hash(value); }
    /** 标准稳定摘要，不使用平台默认字符集。 */
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance(SHA256_ALGORITHM).digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 不可用", exception); }
    }
    /** 不合法的内部媒体合同仍沿用媒体失败分类。 */
    private static AiProtocolException invalid() { return new AiProtocolException(ERROR_MEDIA_INVALID, "音频文字上下文不合法"); }
}
