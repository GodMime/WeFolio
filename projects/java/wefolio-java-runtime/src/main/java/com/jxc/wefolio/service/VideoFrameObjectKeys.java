package com.jxc.wefolio.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.service.portfolioai.PortfolioAiMediaContract;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashSet;
import java.util.Set;

/** 删除作品时只收集该作品拥有的持久帧，不能使用清单中的 URL 删除其他资源。 */
@Slf4j
public final class VideoFrameObjectKeys {
    /** 允许的完整清单字节预算。 */
    private static final int MAX_JSON_LENGTH = 64 * 1024;
    /** 只接受服务端生成的无转义 JPEG 对象键。 */
    private static final String SAFE_OBJECT_KEY = "[A-Za-z0-9/_-]+\\.jpg";
    /** 清单图片数组字段。 */
    private static final String FRAMES_FIELD = "frames";
    /** 清单图片数量字段。 */
    private static final String COUNT_FIELD = "frameCount";
    /** COS 对象键字段。 */
    private static final String KEY_FIELD = "objectKey";
    /** 拒绝重复键，避免同一清单出现歧义。 */
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    /** 静态收集器无需实例。 */
    private VideoFrameObjectKeys() { }

    /** 从可信作品身份和完整清单收集对象键；损坏或跨作品清单只告警，不妨碍原删除流程。 */
    public static Set<String> collect(WorkEntity work) {
        Set<String> keys = new LinkedHashSet<>();
        if (work == null || !MediaTypeDict.VIDEO.getCode().equals(work.getMediaType())
                || work.getVideoFramesJson() == null || work.getVideoFramesJson().isBlank()) {
            return keys;
        }
        try {
            String sourceKey = work.getMediaObjectKey();
            if (work.getId() == null || sourceKey == null || sourceKey.indexOf('/') <= 0
                    || work.getVideoFramesJson().length() > MAX_JSON_LENGTH) {
                throw new IllegalArgumentException();
            }
            String prefix = sourceKey.substring(0, sourceKey.indexOf('/'))
                    + PortfolioAiMediaContract.FRAME_DIRECTORY + work.getId() + "/";
            JsonNode manifest = JSON.readTree(work.getVideoFramesJson());
            JsonNode frames = manifest.path(FRAMES_FIELD);
            if (!manifest.isObject() || !frames.isArray() || frames.isEmpty()
                    || frames.size() > PortfolioAiMediaContract.FRAME_MAX_COUNT
                    || !manifest.path(COUNT_FIELD).isIntegralNumber()
                    || manifest.path(COUNT_FIELD).asInt() != frames.size()) {
                throw new IllegalArgumentException();
            }
            for (JsonNode frame : frames) {
                JsonNode key = frame.path(KEY_FIELD);
                if (!key.isTextual() || !key.asText().startsWith(prefix) || !key.asText().matches(SAFE_OBJECT_KEY)) {
                    throw new IllegalArgumentException();
                }
                keys.add(key.asText());
            }
            return keys;
        } catch (Exception exception) {
            // 不记录原始 JSON 或地址；整体拒绝可疑清单，不能按其中某些地址删除其他作品。
            log.warn("作品持久帧清单无法安全清理 workId={}, exceptionType={}",
                    work.getId(), exception.getClass().getSimpleName());
            return Set.of();
        }
    }
}
