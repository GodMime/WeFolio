package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.message.PortfolioAiMessage;
import com.jxc.wefolio.service.CosService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import static com.jxc.wefolio.service.portfolioai.PortfolioAiMediaContract.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import java.util.Locale;

/** 冻结当前作品媒体身份及输入路线，禁止作品集请求临时取帧或文字降级。 */
@Service
@RequiredArgsConstructor
public class PortfolioAiMediaPlanService {
    /** 当前作品读取器。 */
    private final WorkEntityMapper works;
    /** COS 只读元数据，无原作品下载。 */
    private final CosService cos;

    /** Facts 已检查本人或团队授权，本步骤重新检查作品状态并冻结真实 HEAD。 */
    public void freeze(ObjectNode input, ObjectNode facts) {
        ObjectNode plan = facts.putObject(KEY_MEDIA_PLAN).put(KEY_VERSION, PLAN_VERSION);
        var entries = plan.putArray(KEY_ENTRIES);
        for (JsonNode source : facts.path(KEY_SOURCES)) {
            if (!source.path(KEY_CAN_BIND).asBoolean() || !SOURCE_WORK.equals(source.path(KEY_KIND).asText())) { continue; }
            WorkEntity work = works.selectById(Long.parseLong(source.path(KEY_SOURCE_ID).asText()));
            if (work == null || !WorkStatusDict.ACTIVE.getCode().equals(work.getStatus())
                    || !WorkAuditStatusDict.PASSED.getCode().equals(work.getAuditStatus())
                    || work.getDeleted() != null && work.getDeleted() != 0L) { throw unavailable(); }
            if (!ROUTE_VIDEO.equals(work.getMediaType()) && !ROUTE_IMAGE.equals(work.getMediaType())) {
                ObjectNode summary = ((ObjectNode) source).putObject(KEY_MEDIA_SUMMARY).put(SUMMARY, "");
                summary.putArray(OBSERVATIONS);
                summary.putArray(LIMITATIONS).add("该媒体类型尚不读取内容，仅使用作者提供的标题与标签。");
                ((ObjectNode) source).remove(List.of(KEY_MEDIA_URL, KEY_COVER_URL));
                continue;
            }
            var head = cos.headObjectVersion(work.getMediaObjectKey());
            if (head == null || head.contentLength() <= 0 || head.eTag() == null || head.eTag().isBlank()) { throw unavailable(); }
            String identity = fingerprint(work.getMediaObjectKey(), head.eTag(), head.contentLength(), work.getMediaSha256());
            ObjectNode entry = entries.addObject().put(KEY_SOURCE_NO, source.path(KEY_SOURCE_NO).asInt())
                    .put(KEY_WORK_ID, work.getId()).put(KEY_USER_ID, work.getUserId())
                    .put(KEY_SOURCE_OBJECT_KEY, work.getMediaObjectKey()).put(KEY_SOURCE_VERSION, identity)
                    .put(KEY_SOURCE_ETAG, normalize(head.eTag())).put(KEY_SOURCE_BYTES, head.contentLength())
                    .put(KEY_MEDIA_SHA256, work.getMediaSha256() == null ? "" : work.getMediaSha256());
            if (work.getDurationMs() != null) { entry.put(KEY_DURATION_MS, work.getDurationMs()); }
            if (ROUTE_VIDEO.equals(work.getMediaType())) {
                // 已有审核持久帧优先复用；历史无帧短视频保留原有直传边界。
                if (work.getVideoFramesJson() == null && withinDirectVideoLimits(head.contentLength(), work.getDurationMs())) {
                    entry.put(KEY_ROUTE, ROUTE_VIDEO)
                            .put(KEY_ESTIMATED_INPUT_TOKENS, estimateVideoInputTokens(work.getDurationMs()));
                } else { frames(work, identity, entry); }
            } else if (ROUTE_IMAGE.equals(work.getMediaType())) {
                if (head.contentLength() > DIRECT_MEDIA_MAX_BYTES) { throw unavailable(); }
                entry.put(KEY_ROUTE, ROUTE_IMAGE).put(KEY_ESTIMATED_INPUT_TOKENS, IMAGE_ESTIMATED_INPUT_TOKENS);
            } else { throw unavailable(); }
            // 新链路最终生成只消费媒体摘要，不把旧公开地址附在文字内容中。
            ((ObjectNode) source).remove(List.of(KEY_MEDIA_URL, KEY_COVER_URL));
        }
    }

    /** 帧列表必须完整、同源且有稳定对象键；实际对象再次由 Job 在调用前核验。 */
    private void frames(WorkEntity work, String identity, ObjectNode entry) {
        if (work.getVideoFramesJson() == null) { throw unprepared(); }
        ObjectNode manifest = StrictAiJson.parseObject(work.getVideoFramesJson(), 128 * 1024, 12);
        int count = manifest.path(KEY_FRAME_COUNT).asInt();
        if (!FRAME_SCHEMA.equals(manifest.path(KEY_SCHEMA_VERSION).asText())
                || !identity.equals(manifest.path(KEY_SOURCE_VERSION).asText())
                || !manifest.path(KEY_FRAME_COUNT).isIntegralNumber() || !manifest.path(KEY_FRAME_COUNT).canConvertToInt()
                || !isSupportedFrameCount(count) || !manifest.path(KEY_FRAMES).isArray()
                || manifest.path(KEY_FRAMES).size() != count) { throw unprepared(); }
        Set<String> keys = new HashSet<>(); Set<Long> times = new HashSet<>();
        String ownerCode = work.getMediaObjectKey().split("/", 2)[0];
        String prefix = ownerCode + FRAME_DIRECTORY + work.getId() + "/";
        var frozen = entry.putArray(KEY_FRAMES);
        List<JsonNode> sorted = new ArrayList<>();
        manifest.path(KEY_FRAMES).forEach(sorted::add);
        sorted.sort(Comparator.comparingLong(frame -> frame.path(KEY_TIME_MS).asLong(-1)));
        for (JsonNode frame : sorted) {
            String key = frame.path(KEY_OBJECT_KEY).asText(); long time = frame.path(KEY_TIME_MS).asLong(-1);
            if (!key.startsWith(prefix) || key.contains("..") || key.contains("%") || key.contains("\\") || !keys.add(key)
                    || !frame.path(KEY_TIME_MS).isIntegralNumber() || !frame.path(KEY_TIME_MS).canConvertToLong()
                    || time < 0 || !times.add(time) || !frame.path(KEY_BYTES).isIntegralNumber() || !frame.path(KEY_BYTES).canConvertToLong()
                    || frame.path(KEY_BYTES).asLong() <= 0 || frame.path(KEY_BYTES).asLong() > FRAME_MAX_BYTES
                    || !frame.path(KEY_WIDTH).isIntegralNumber() || !frame.path(KEY_WIDTH).canConvertToInt()
                    || !frame.path(KEY_HEIGHT).isIntegralNumber() || !frame.path(KEY_HEIGHT).canConvertToInt() || frame.path(KEY_WIDTH).asInt() <= 0
                    || frame.path(KEY_HEIGHT).asInt() <= 0 || Math.max(frame.path(KEY_WIDTH).asInt(), frame.path(KEY_HEIGHT).asInt()) > FRAME_MAX_EDGE) {
                throw unprepared();
            }
            frozen.addObject().put(KEY_OBJECT_KEY, key).put(KEY_BYTES, frame.path(KEY_BYTES).asLong()).put(KEY_TIME_MS, time);
        }
        // 全局帧合同扩容时，默认选帧策略可能暂不支持；保持未准备完成的业务失败语义。
        ObjectNode selection;
        try { selection = PortfolioAiFrameSelection.select(manifest.path(KEY_FRAMES)); }
        catch (IllegalArgumentException exception) { throw unprepared(); }
        entry.set(PortfolioAiFrameSelection.FIELD, selection);
        entry.put(KEY_ROUTE, ROUTE_FRAMES).put(KEY_MANIFEST_HASH, hash(manifest.toString()))
                .put(KEY_ESTIMATED_INPUT_TOKENS, selection.path(KEY_ESTIMATED_INPUT_TOKENS).asLong());
    }

    /** 两工程使用相同源身份算法，不信任客户端媒体长度。 */
    public static String fingerprint(String key, String etag, long bytes, String sha256) {
        return hash(key + "\n" + normalize(etag) + "\n" + bytes + "\n" + (sha256 == null ? "" : sha256));
    }
    /** 去除 SDK ETag 可选引号，保持内容大小写。 */
    private static String normalize(String value) { return value == null ? "" : value.replace("\"", "").strip().toLowerCase(Locale.ROOT); }
    /** 稳定 SHA256 摘要只用于身份与缓存，不向模型暴露真实主键。 */
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance(SHA256_ALGORITHM).digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256不可用", exception); }
    }
    /** 授权来源无可读媒体。 */
    private static BusinessException unavailable() { return new BusinessException(PortfolioAiMessage.MEDIA_UNAVAILABLE); }
    /** 未准备的长视频不能在作品集内取帧。 */
    private static BusinessException unprepared() { return new BusinessException(PortfolioAiMessage.MEDIA_NOT_PREPARED); }
}
