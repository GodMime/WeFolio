package com.jxc.wefolio.job.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.JSONArray;
import com.fasterxml.jackson.databind.JsonNode;
import com.jxc.wefolio.job.config.CosProperties;
import com.jxc.wefolio.job.config.VideoFrameConfig;
import com.jxc.wefolio.job.config.VideoFrameProperties;
import com.jxc.wefolio.service.portfolioai.PortfolioAiMediaContract;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.CannedAccessControlList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 审核与历史批处理共用的逐帧持久化，不读取原视频字节。 */
@Service
@Slf4j
public class VideoFrameStorageService {
    /** 稳定策略版本，供审核任务冻结。 */
    public static final String POLICY_VERSION = VideoFramePolicy.POLICY_VERSION;
    /** 固定安全目录与媒体合同。 */
    public static final String FRAME_DIRECTORY = PortfolioAiMediaContract.FRAME_DIRECTORY;
    private static final String VIDEO_DIRECTORY = "/work/video/";
    private static final String JPEG_TYPE = PortfolioAiMediaContract.JPEG_CONTENT_TYPE;
    private static final String IMAGE_TYPE_PREFIX = "image/";
    private static final String DURATION_SOURCE = "FRONTEND";
    private static final String COVERAGE = "APPROXIMATE_UNIFORM_SAMPLES";
    private static final String COS_HOST_SUFFIX = ".myqcloud.com";
    private static final String HTTPS_PROTOCOL = "https";
    private static final String COS_HOST_PATTERN = "[a-z0-9-]+\\.(cos|ci)\\.[a-z0-9-]+\\.myqcloud\\.com";
    private static final String SOURCE_KEY_PATTERN = "[A-Za-z0-9_-]+/work/video/[^/]+";
    private static final String SOURCE_VERSION_PATTERN = "[a-f0-9]{64}";
    private static final String TASK_TOKEN_PATTERN = "[A-Za-z0-9_-]{1,96}";
    private static final String FRAME_NAME_FORMAT = "%04d.jpg";
    private static final String OWNED_PREFIX_PATTERN = "[a-f0-9]{64}/[A-Za-z0-9_-]{1,96}/[A-Za-z0-9_-]{1,96}/";
    /** 强制重建只接受生产审核任务或随机历史任务的完整目录，不扩大到作品目录。 */
    private static final Pattern STRICT_OWNED_PREFIX = Pattern.compile("([a-f0-9]{64})/([A-Za-z0-9_-]{1,96})/"
            + "(?:audit-([1-9][0-9]*)|history-[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12})/");
    /** 严格清理沿用媒体清单解析上限，异常消息不能携带原文。 */
    private static final int CLEANUP_MANIFEST_MAX_BYTES = 128 * 1024;
    private static final int CLEANUP_MANIFEST_MAX_DEPTH = 12;
    private static final String FRAME_LIST = "FRAME_LIST";
    private static final String FRAME_NORMALIZE = "FRAME_NORMALIZE";
    private static final long MAX_TOTAL_DOWNLOAD_BYTES = 64L * 1024 * 1024;
    private static final String FRAME_DOWNLOAD = "FRAME_DOWNLOAD";
    private static final String FRAME_UPLOAD = "FRAME_UPLOAD";
    private static final String FRAME_VERIFY = "FRAME_VERIFY";
    private static final String FRAME_CLEANUP = "FRAME_CLEANUP";
    private static final String SOURCE_VERIFY = "SOURCE_VERIFY";
    private static final String PROCESS_INTERRUPTED = "PROCESS_INTERRUPTED";
    /** 只重读无计费的 HEAD，最多三次；付费截图提交保持一次。 */
    private static final int HEAD_MAX_ATTEMPTS = 3;
    private static final long HEAD_RETRY_DELAY_MILLIS = 100L;
    /** 持久清单协议字段。 */
    private static final String FIELD_SCHEMA_VERSION = "schemaVersion";
    private static final String FIELD_POLICY_VERSION = "policyVersion";
    private static final String FIELD_SOURCE_VERSION = "sourceVersion";
    private static final String FIELD_SOURCE_OBJECT_KEY = "sourceObjectKey";
    private static final String FIELD_SOURCE_ETAG = "sourceEtag";
    private static final String FIELD_SOURCE_BYTES = "sourceBytes";
    private static final String FIELD_OBJECT_PREFIX = "objectPrefix";
    private static final String FIELD_DURATION_MS = "durationMs";
    private static final String FIELD_DURATION_SOURCE = "durationSource";
    private static final String FIELD_COVERAGE = "coverage";
    private static final String FIELD_FRAME_COUNT = "frameCount";
    private static final String FIELD_FRAMES = "frames";
    private static final String FIELD_TIME_MS = "timeMs";
    private static final String FIELD_OBJECT_KEY = "objectKey";
    private static final String FIELD_COS_URL = "cosUrl";
    private static final String FIELD_WIDTH = "width";
    private static final String FIELD_HEIGHT = "height";
    private static final String FIELD_BYTES = "bytes";
    /** 可选视觉特征，不改变原始完整帧清单与采样策略。 */
    private static final String FIELD_VISUAL_FEATURES = "visualFeatures";
    private static final String FIELD_FEATURE_VERSION = "version";
    private static final String FIELD_DIFFERENCE_HASH = "dHash";
    private static final String FIELD_LUMINANCE = "luminance";
    private static final String FIELD_SHARPNESS = "sharpness";
    private static final String FIELD_FIRST_FRAME_MS = "firstFrameMs";
    private static final String FIELD_LAST_FRAME_MS = "lastFrameMs";
    private static final String FIELD_MAX_GAP_MS = "maxGapMs";
    private static final String FIELD_DURATION_MISSING = "durationMissing";
    private static final String FIELD_DURATION_MISMATCH = "durationMismatch";
    private static final String FIELD_TAIL_BLIND_MS = "tailBlindMs";
    /** 帧专用有界 COS 客户端。 */
    private final COSClient cosClient;
    /** 当前私有存储桶及地域。 */
    private final CosProperties cos;
    /** 下载边界及历史处理预算配置。 */
    private final VideoFrameProperties properties;
    /** 纯内存图片规范化。 */
    private final VideoFrameImageNormalizer normalizer;

    /** 注入无重试、有界请求的专用客户端。 */
    public VideoFrameStorageService(@Qualifier(VideoFrameConfig.COS_CLIENT_NAME) COSClient cosClient,
            CosProperties cos, VideoFrameProperties properties, VideoFrameImageNormalizer normalizer) {
        this.cosClient = cosClient;
        this.cos = cos;
        this.properties = properties;
        this.normalizer = normalizer;
    }
    /** 与审核、历史入口共用数量计算。 */
    public static int calculateFrameCount(Integer durationMs) { return VideoFramePolicy.calculateFrameCount(durationMs); }

    /** 只读 HEAD 取得源版本，拒绝非作品视频对象键。 */
    public VideoFrameSource inspectSource(String objectKey, String mediaSha256) {
        sourceOwner(objectKey);
        try {
            Instant deadline = Instant.now().plusSeconds(properties.getRemoteTimeoutSeconds() * HEAD_MAX_ATTEMPTS + 1L);
            ObjectMetadata metadata = headWithRetry(objectKey, deadline);
            if (metadata.getContentLength() <= 0 || metadata.getETag() == null || metadata.getETag().isBlank()) {
                throw failure(SOURCE_VERIFY, "源对象元数据不完整");
            }
            String etag = normalizeEtag(metadata.getETag());
            String material = objectKey + "\n" + etag + "\n" + metadata.getContentLength()
                    + "\n" + Objects.toString(mediaSha256, "");
            String version = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
            log.info("视频帧源 HEAD: objectKey={}, size={}, etag={}, sourceVersion={}",
                    objectKey, metadata.getContentLength(), etag, version);
            return new VideoFrameSource(objectKey, version, etag, metadata.getContentLength());
        } catch (VideoFrameProcessingException exception) { throw exception; }
        catch (Exception exception) { throw failure(SOURCE_VERIFY, "源对象 HEAD 失败"); }
    }
    /** 为消费者提供仅元数据读取，不下载媒体。 */
    public ObjectMetadata head(String objectKey) { return cosClient.getObjectMetadata(cos.getBucketName(), objectKey); }
    /** 原视频摘要在每次 HEAD 前为固定 COS 请求上限预留时间，不额外重试。 */
    public ObjectMetadata head(String objectKey, Instant deadline) {
        checkDeadline(deadline);
        ObjectMetadata metadata = head(objectKey);
        if (Thread.currentThread().isInterrupted() || !Instant.now().isBefore(deadline)) {
            throw failure(PROCESS_INTERRUPTED, "视频源 HEAD 已中断或超过期限");
        }
        return metadata;
    }
    /** 供摘要发送前预留最终源复验时间，与真实 COS 请求硬超时保持一致。 */
    public int headTimeoutSeconds() { return Math.max(1, properties.getRemoteTimeoutSeconds()); }
    /** 显式、有界地重读瞬时服务或连接故障；不存在、鉴权失败和业务失效立即返回。 */
    private ObjectMetadata headWithRetry(String objectKey, Instant deadline) {
        for (int attempt = 1; ; attempt++) {
            checkDeadline(deadline);
            try { return head(objectKey); }
            catch (CosClientException exception) {
                boolean retryable = !(exception instanceof CosServiceException service)
                        || service.getStatusCode() == 408 || service.getStatusCode() == 429
                        || service.getStatusCode() >= 500;
                if (!retryable || attempt >= HEAD_MAX_ATTEMPTS) { throw exception; }
                checkDeadline(deadline);
                log.warn("视频帧 HEAD 暂时失败，准备有界重读: objectKey={}, attempt={}, exceptionType={}",
                        objectKey, attempt, exception.getClass().getSimpleName());
                try { Thread.sleep(HEAD_RETRY_DELAY_MILLIS * attempt); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw failure(PROCESS_INTERRUPTED, "视频帧 HEAD 重读已中断");
                }
            }
        }
    }
    /** 只签名本桶对象，不打印短期凭证。 */
    public String signedGet(String objectKey, Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("签名链接有效期非法");
        }
        return cosClient.generatePresignedUrl(cos.getBucketName(), objectKey,
                Date.from(Instant.now().plus(ttl))).toExternalForm();
    }
    /** 检查冻结源身份仍与 HEAD 一致；不以客户端时长判断源身份。 */
    public void verifySource(VideoFrameSource source, Instant deadline) {
        checkDeadline(deadline);
        try {
            ObjectMetadata current = headWithRetry(source.objectKey(), deadline);
            if (current.getContentLength() != source.size()
                    || !Objects.equals(normalizeEtag(current.getETag()), source.etag())) {
                throw new VideoFrameSourceChangedException();
            }
        } catch (VideoFrameProcessingException exception) { throw exception; }
        catch (Exception exception) { throw failure(SOURCE_VERIFY, "源对象复核失败"); }
        checkDeadline(deadline);
    }
    /** 完整持久化后返回清单；失败清理本次新建对象，无每帧恢复状态。 */
    public String persist(VideoFrameSource source, Long workId, String taskToken, Integer durationMs,
            int expectedCount, List<VideoFrameInput> inputs, Instant deadline) {
        String prefix = targetPrefix(source, workId, taskToken);
        List<VideoFrameInput> sorted = validateInputs(expectedCount, inputs);
        List<String> uploaded = new ArrayList<>();
        List<JSONObject> frames = new ArrayList<>();
        String stage = FRAME_DOWNLOAD;
        long totalDownloadBytes = 0;
        try {
            verifySource(source, deadline);
            for (int i = 0; i < sorted.size(); i++) {
                checkDeadline(deadline);
                VideoFrameInput input = sorted.get(i);
                stage = FRAME_DOWNLOAD;
                byte[] content = download(input, source, deadline);
                totalDownloadBytes += content.length;
                if (totalDownloadBytes > MAX_TOTAL_DOWNLOAD_BYTES) { throw failure(FRAME_DOWNLOAD, "整件视频截图累计字节超过上限"); }
                stage = FRAME_NORMALIZE;
                VideoFrameImageNormalizer.NormalizedImage image = normalizer.normalize(content);
                checkDeadline(deadline);
                String target = prefix + String.format(Locale.ROOT, FRAME_NAME_FORMAT, i + 1);
                if (image.featureFailureType() != null) {
                    log.warn("视频帧视觉特征计算失败，保留基础帧: workId={}, task={}, objectKey={}, timeMs={}, exceptionType={}",
                            workId, taskToken, target, input.timeMs(), image.featureFailureType());
                }
                ObjectMetadata metadata = new ObjectMetadata();
                metadata.setContentType(JPEG_TYPE);
                metadata.setContentLength(image.bytes().length);
                PutObjectRequest request = new PutObjectRequest(cos.getBucketName(), target,
                        new ByteArrayInputStream(image.bytes()), metadata);
                request.setCannedAcl(CannedAccessControlList.Private);
                stage = FRAME_UPLOAD;
                // PUT 结果未知也清理本任务唯一对象键，避免遗留不可见半成品。
                uploaded.add(target);
                cosClient.putObject(request);
                checkDeadline(deadline);
                stage = FRAME_VERIFY;
                verifyFrame(target, image.bytes().length, deadline);
                JSONObject frame = new JSONObject();
                frame.put(FIELD_TIME_MS, input.timeMs());
                frame.put(FIELD_OBJECT_KEY, target);
                frame.put(FIELD_COS_URL, cosClient.getObjectUrl(cos.getBucketName(), target).toExternalForm());
                frame.put(FIELD_WIDTH, image.width());
                frame.put(FIELD_HEIGHT, image.height());
                frame.put(FIELD_BYTES, image.bytes().length);
                if (image.visualFeatures() != null) {
                    VideoFrameVisualFeatures features = image.visualFeatures();
                    JSONObject featureJson = new JSONObject();
                    featureJson.put(FIELD_FEATURE_VERSION, features.version());
                    featureJson.put(FIELD_DIFFERENCE_HASH, features.dHash());
                    featureJson.put(FIELD_LUMINANCE, features.luminance());
                    featureJson.put(FIELD_SHARPNESS, features.sharpness());
                    frame.put(FIELD_VISUAL_FEATURES, featureJson);
                }
                frames.add(frame);
                log.info("视频持久帧写入: workId={}, task={}, objectKey={}, timeMs={}, bytes={}, visualFeaturesPresent={}, featureFailureType={}",
                        workId, taskToken, target, input.timeMs(), image.bytes().length,
                        image.visualFeatures() != null, image.featureFailureType());
            }
            stage = SOURCE_VERIFY;
            verifySource(source, deadline);
            return buildManifest(source, durationMs, frames, prefix);
        } catch (Exception exception) {
            cleanupObjects(uploaded);
            if (exception instanceof VideoFrameProcessingException failure) { throw failure; }
            throw failure(stage, "帧处理失败，远端或图片服务异常");
        }
    }
    /** 完整清单只允许非负、不冲突的真实时刻，超过前端时长仍保留。 */
    public List<VideoFrameInput> validateInputs(int expectedCount, List<VideoFrameInput> inputs) {
        if (!PortfolioAiMediaContract.isSupportedFrameCount(expectedCount)
                || inputs == null || inputs.size() != expectedCount) {
            throw failure(FRAME_LIST, "截图数量与冻结数量不一致");
        }
        HashSet<Long> times = new HashSet<>();
        for (VideoFrameInput input : inputs) {
            if (input == null || input.timeMs() == null || input.timeMs() < 0 || !times.add(input.timeMs())
                    || (input.url() == null) == (input.objectKey() == null)) {
                throw failure(FRAME_LIST, "截图时刻或读取地址非法、缺失或冲突");
            }
        }
        return inputs.stream().sorted(Comparator.comparing(VideoFrameInput::timeMs)).toList();
    }
    /** 已完整的同源清单跳过，不强制重采旧十二帧等有效资产。 */
    public boolean hasValidManifest(String json, VideoFrameSource source, Long workId, Instant deadline) {
        if (json == null || json.isBlank()) { return false; }
        try {
            JSONObject manifest = JSON.parseObject(json);
            String originalPolicy = manifest.getString(FIELD_POLICY_VERSION);
            if (!VideoFramePolicy.SCHEMA_VERSION.equals(manifest.getString(FIELD_SCHEMA_VERSION))
                    || originalPolicy == null || !originalPolicy.matches(TASK_TOKEN_PATTERN)
                    || !source.sourceVersion().equals(manifest.getString(FIELD_SOURCE_VERSION))
                    || !source.objectKey().equals(manifest.getString(FIELD_SOURCE_OBJECT_KEY))
                    || !source.etag().equals(normalizeEtag(manifest.getString(FIELD_SOURCE_ETAG)))
                    || source.size() != manifest.getLongValue(FIELD_SOURCE_BYTES)) { return false; }
            JSONArray frames = manifest.getJSONArray(FIELD_FRAMES);
            Integer count = manifest.getInteger(FIELD_FRAME_COUNT);
            if (count == null || !PortfolioAiMediaContract.isSupportedFrameCount(count) || frames == null || frames.size() != count) { return false; }
            String ownerPrefix = sourceOwner(source.objectKey()) + FRAME_DIRECTORY + workId + "/";
            HashSet<Long> times = new HashSet<>();
            for (int i = 0; i < count; i++) {
                JSONObject frame = frames.getJSONObject(i);
                String key = frame.getString(FIELD_OBJECT_KEY);
                Long time = frame.getLong(FIELD_TIME_MS);
                Integer bytes = frame.getInteger(FIELD_BYTES);
                Integer width = frame.getInteger(FIELD_WIDTH);
                Integer height = frame.getInteger(FIELD_HEIGHT);
                if (key == null || !key.startsWith(ownerPrefix) || key.contains("..") || time == null
                        || time < 0 || !times.add(time) || bytes == null || bytes <= 0
                        || bytes > PortfolioAiMediaContract.FRAME_MAX_BYTES || width == null || height == null
                        || width <= 0 || height <= 0 || Math.max(width, height) > PortfolioAiMediaContract.FRAME_MAX_EDGE) { return false; }
                verifyFrame(key, bytes, deadline);
            }
            return true;
        } catch (VideoFrameAssetInvalidException exception) { return false; }
        catch (VideoFrameProcessingException exception) { throw exception; }
        catch (Exception exception) { return false; }
    }
    /** 清理旧失效清单必须用真实作品源与 ID 复核，不能相信 JSON 自报所有者。 */
    public void cleanupOwnedManifest(String json, VideoFrameSource source, Long workId) {
        if (json == null || json.isBlank() || source == null || workId == null || workId <= 0) { return; }
        try {
            JSONObject manifest = JSON.parseObject(json);
            String ownerPrefix = sourceOwner(source.objectKey()) + FRAME_DIRECTORY + workId + "/";
            String prefix = manifest.getString(FIELD_OBJECT_PREFIX);
            if (prefix == null || !prefix.startsWith(ownerPrefix) || prefix.contains("..")) { return; }
            String relative = prefix.substring(ownerPrefix.length());
            if (!relative.matches(OWNED_PREFIX_PATTERN)) { return; }
            JSONArray frames = manifest.getJSONArray(FIELD_FRAMES);
            if (frames == null || frames.size() > VideoFramePolicy.MAX_COUNT) { return; }
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < frames.size(); i++) {
                String key = frames.getJSONObject(i).getString(FIELD_OBJECT_KEY);
                if (key == null || !key.startsWith(prefix) || key.contains("..")
                        || key.substring(prefix.length()).contains("/")) { return; }
                keys.add(key);
            }
            cleanupObjects(keys);
        } catch (Exception exception) { log.warn("旧视频帧清理未通过作品所有权校验: workId={}", workId); }
    }

    /** 清空数据库引用前预检完整旧清单；空引用允许继续，非空坏清单必须先人工确认。 */
    public void validateOwnedManifestForCleanup(String json, VideoFrameSource source, Long workId) {
        strictCleanupKeys(json, source, workId);
    }

    /**
     * 重建流程使用的严格删除：完整校验后只逐个删除清单内帧，任何非不存在异常均中断。
     * 调用方须先解除对应数据库引用；本方法不猜测发布状态，也不列举或删除整个作品目录。
     * 每次删除前检查线程中断及单件期限，并为完整的单次远端调用预留时间。
     */
    public void cleanupOwnedManifestStrict(String json, VideoFrameSource source, Long workId, Instant deadline) {
        List<String> keys = strictCleanupKeys(json, source, workId);
        for (String key : keys) {
            checkDeadline(deadline);
            try { cosClient.deleteObject(cos.getBucketName(), key); }
            catch (CosServiceException exception) {
                if (exception.getStatusCode() != 404) { throw strictCleanupFailure(workId, key, exception); }
            } catch (RuntimeException exception) { throw strictCleanupFailure(workId, key, exception); }
            log.info("视频帧重建旧帧删除完成: workId={}, objectKey={}", workId, key);
        }
    }

    /** 旧源可以已被替换，但所有帧必须属于真实作者、当前作品和清单声明的独立任务目录。 */
    private List<String> strictCleanupKeys(String json, VideoFrameSource source, Long workId) {
        if (json == null) { return List.of(); }
        try {
            if (json.isBlank() || source == null || workId == null || workId <= 0) { throw new IllegalArgumentException(); }
            JsonNode manifest = StrictAiJson.parseObject(json, CLEANUP_MANIFEST_MAX_BYTES, CLEANUP_MANIFEST_MAX_DEPTH);
            String owner = sourceOwner(source.objectKey());
            String ownerPrefix = owner + FRAME_DIRECTORY + workId + "/";
            JsonNode prefixNode = manifest.path(FIELD_OBJECT_PREFIX);
            JsonNode countNode = manifest.path(FIELD_FRAME_COUNT);
            JsonNode frames = manifest.path(FIELD_FRAMES);
            if (!VideoFramePolicy.SCHEMA_VERSION.equals(manifest.path(FIELD_SCHEMA_VERSION).asText())
                    || !prefixNode.isTextual() || !prefixNode.textValue().startsWith(ownerPrefix)
                    || !countNode.isIntegralNumber() || !countNode.canConvertToInt()
                    || !PortfolioAiMediaContract.isSupportedFrameCount(countNode.intValue())
                    || !frames.isArray() || frames.size() != countNode.intValue()) { throw new IllegalArgumentException(); }
            String prefix = prefixNode.textValue();
            Matcher owned = STRICT_OWNED_PREFIX.matcher(prefix.substring(ownerPrefix.length()));
            if (!owned.matches() || !manifest.path(FIELD_SOURCE_VERSION).isTextual()
                    || !owned.group(1).equals(manifest.path(FIELD_SOURCE_VERSION).textValue())
                    || !manifest.path(FIELD_POLICY_VERSION).isTextual()
                    || !owned.group(2).equals(manifest.path(FIELD_POLICY_VERSION).textValue())
                    || !manifest.path(FIELD_SOURCE_OBJECT_KEY).isTextual()
                    || !owner.equals(sourceOwner(manifest.path(FIELD_SOURCE_OBJECT_KEY).textValue()))) {
                throw new IllegalArgumentException();
            }
            if (owned.group(3) != null) { Long.parseLong(owned.group(3)); }
            HashSet<String> expected = new HashSet<>();
            for (int number = 1; number <= countNode.intValue(); number++) {
                expected.add(prefix + String.format(Locale.ROOT, FRAME_NAME_FORMAT, number));
            }
            List<String> keys = new ArrayList<>();
            for (JsonNode frame : frames) {
                JsonNode key = frame.path(FIELD_OBJECT_KEY);
                if (!frame.isObject() || !key.isTextual() || !expected.remove(key.textValue())) {
                    throw new IllegalArgumentException();
                }
                keys.add(key.textValue());
            }
            return keys;
        } catch (Exception exception) { throw failure(FRAME_CLEANUP, "旧视频帧清单未通过严格所有权校验"); }
    }

    /** 删除异常只记录已验证的对象键和异常类型，不透出远端原文或签名信息。 */
    private VideoFrameProcessingException strictCleanupFailure(Long workId, String key, RuntimeException exception) {
        log.warn("视频帧重建旧帧删除失败: workId={}, objectKey={}, exceptionType={}",
                workId, key, exception.getClass().getSimpleName());
        return failure(FRAME_CLEANUP, "旧视频帧对象删除失败，重建已中止");
    }

    /** 按真实作品所有者和当前任务生成私有目录，禁止客户端控制路径。 */
    public String targetPrefix(VideoFrameSource source, Long workId, String token) {
        if (source == null || workId == null || workId <= 0 || source.sourceVersion() == null
                || !source.sourceVersion().matches(SOURCE_VERSION_PATTERN) || token == null || !token.matches(TASK_TOKEN_PATTERN)) {
            throw failure(FRAME_LIST, "帧任务目录参数非法");
        }
        return sourceOwner(source.objectKey()) + FRAME_DIRECTORY + workId + "/" + source.sourceVersion()
                + "/" + POLICY_VERSION + "/" + token + "/";
    }
    /** 同桶历史输出只读取私有帧任务目录，审核地址只允许腾讯 COS HTTPS 域名。 */
    byte[] download(VideoFrameInput input, VideoFrameSource source, Instant deadline) throws Exception {
        if (input.objectKey() != null) {
            String ownerPrefix = sourceOwner(source.objectKey()) + FRAME_DIRECTORY;
            if (!input.objectKey().startsWith(ownerPrefix) || input.objectKey().contains("..")) {
                throw failure(FRAME_DOWNLOAD, "历史截图对象不在受控目录");
            }
            try (COSObject object = cosClient.getObject(cos.getBucketName(), input.objectKey());
                 InputStream stream = object.getObjectContent()) { return readBounded(stream, deadline); }
        }
        URI uri;
        try { uri = URI.create(input.url()); }
        catch (Exception exception) { throw failure(FRAME_DOWNLOAD, "截图地址格式非法"); }
        String host = uri.getHost();
        if (!HTTPS_PROTOCOL.equalsIgnoreCase(uri.getScheme()) || host == null
                || !host.toLowerCase(Locale.ROOT).endsWith(COS_HOST_SUFFIX)
                || !host.toLowerCase(Locale.ROOT).matches(COS_HOST_PATTERN)
                || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw failure(FRAME_DOWNLOAD, "截图地址不是受控腾讯 HTTPS 对象");
        }
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(callTimeout(deadline));
            connection.setReadTimeout(callTimeout(deadline));
            int status = connection.getResponseCode();
            if (status != 200 || connection.getContentLengthLong() > properties.getMaxDownloadBytes()) {
                throw failure(FRAME_DOWNLOAD, "截图 HTTP 状态异常或字节超限");
            }
            String type = connection.getContentType();
            if (type == null || !type.toLowerCase(Locale.ROOT).startsWith(IMAGE_TYPE_PREFIX)) {
                throw failure(FRAME_DOWNLOAD, "截图响应类型不是图片，拒绝读取媒体原文件");
            }
            try (InputStream stream = connection.getInputStream()) { return readBounded(stream, deadline); }
        } finally { connection.disconnect(); }
    }
    /** 增量有界读取，不采用 readAllBytes，不累计所有帧。 */
    private byte[] readBounded(InputStream stream, Instant deadline) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (true) {
            checkDeadline(deadline);
            int read = stream.read(buffer, 0, Math.min(buffer.length, properties.getMaxDownloadBytes() + 1 - output.size()));
            if (read < 0) { break; }
            output.write(buffer, 0, read);
            if (output.size() > properties.getMaxDownloadBytes()) { throw failure(FRAME_DOWNLOAD, "截图字节超过上限"); }
        }
        if (output.size() == 0) { throw failure(FRAME_DOWNLOAD, "截图响应为空"); }
        return output.toByteArray();
    }
    /** HEAD 验证私有持久对象尺寸和格式声明。 */
    private void verifyFrame(String objectKey, int bytes, Instant deadline) {
        checkDeadline(deadline);
        try {
            ObjectMetadata metadata = headWithRetry(objectKey, deadline);
            if (metadata.getContentLength() != bytes || !JPEG_TYPE.equalsIgnoreCase(metadata.getContentType())) {
                throw new VideoFrameAssetInvalidException("持久截图字节或类型不匹配");
            }
        } catch (VideoFrameProcessingException exception) { throw exception; }
        catch (CosServiceException exception) {
            if (exception.getStatusCode() == 404) { throw new VideoFrameAssetInvalidException("持久截图不存在"); }
            throw failure(FRAME_VERIFY, "持久截图 HEAD 服务异常，不重复取帧");
        }
        catch (Exception exception) { throw failure(FRAME_VERIFY, "持久截图 HEAD 失败，不重复取帧"); }
        checkDeadline(deadline);
    }
    /** 清单仅含永久对象身份及真实采样指标。 */
    private String buildManifest(VideoFrameSource source, Integer durationMs, List<JSONObject> frames, String prefix) {
        JSONObject manifest = new JSONObject();
        manifest.put(FIELD_SCHEMA_VERSION, VideoFramePolicy.SCHEMA_VERSION);
        manifest.put(FIELD_POLICY_VERSION, POLICY_VERSION);
        manifest.put(FIELD_SOURCE_VERSION, source.sourceVersion());
        manifest.put(FIELD_SOURCE_OBJECT_KEY, source.objectKey());
        manifest.put(FIELD_SOURCE_ETAG, source.etag());
        manifest.put(FIELD_SOURCE_BYTES, source.size());
        manifest.put(FIELD_OBJECT_PREFIX, prefix);
        manifest.put(FIELD_DURATION_MS, durationMs);
        manifest.put(FIELD_DURATION_SOURCE, DURATION_SOURCE);
        manifest.put(FIELD_COVERAGE, COVERAGE);
        manifest.put(FIELD_FRAME_COUNT, frames.size());
        manifest.put(FIELD_FRAMES, frames);
        long first = frames.getFirst().getLongValue(FIELD_TIME_MS);
        long last = frames.getLast().getLongValue(FIELD_TIME_MS);
        long maxGap = 0;
        for (int i = 1; i < frames.size(); i++) { maxGap = Math.max(maxGap,
                frames.get(i).getLongValue(FIELD_TIME_MS) - frames.get(i - 1).getLongValue(FIELD_TIME_MS)); }
        boolean missing = durationMs == null || durationMs <= 0;
        boolean mismatch = !missing && last > durationMs;
        manifest.put(FIELD_FIRST_FRAME_MS, first);
        manifest.put(FIELD_LAST_FRAME_MS, last);
        manifest.put(FIELD_MAX_GAP_MS, maxGap);
        manifest.put(FIELD_DURATION_MISSING, missing);
        manifest.put(FIELD_DURATION_MISMATCH, mismatch);
        manifest.put(FIELD_TAIL_BLIND_MS, missing || mismatch ? null : durationMs - last);
        if (mismatch) { log.warn("视频帧时长偏差: sourceVersion={}, durationMs={}, lastFrameMs={}", source.sourceVersion(), durationMs, last); }
        return JSON.toJSONString(manifest, JSONWriter.Feature.WriteMapNullValue);
    }
    /** 失败清理不能抛出覆盖原失败原因。 */
    private void cleanupObjects(List<String> keys) {
        for (String key : keys) {
            try { cosClient.deleteObject(cos.getBucketName(), key); }
            catch (Exception exception) { log.warn("视频帧半成品清理失败: objectKey={}", key); }
        }
    }
    /** 在每个远端步骤前预留完整调用时间，不能跨审核总期限。 */
    private void checkDeadline(Instant deadline) {
        if (Thread.currentThread().isInterrupted() || deadline == null
                || !Instant.now().plusSeconds(Math.max(1, properties.getRemoteTimeoutSeconds())).isBefore(deadline)) {
            throw failure(PROCESS_INTERRUPTED, "视频帧处理已中断或超过期限");
        }
    }
    /** 截图 HTTP 读取同时受单调用和总期限约束。 */
    private int callTimeout(Instant deadline) {
        checkDeadline(deadline);
        return (int) Math.max(1, Math.min(Math.max(1, properties.getRemoteTimeoutSeconds()) * 1000L,
                Duration.between(Instant.now(), deadline).toMillis()));
    }
    /** 源对象目录中的所有者不可由请求参数替代。 */
    private String sourceOwner(String key) {
        if (key == null || key.contains("..") || !key.matches(SOURCE_KEY_PATTERN)) {
            throw failure(SOURCE_VERIFY, "视频源对象键非法");
        }
        return key.substring(0, key.indexOf(VIDEO_DIRECTORY));
    }
    /** ETag 去引号并统一大小写，供两工程相同指纹计算。 */
    public static String normalizeEtag(String value) { return value == null ? "" : value.replace("\"", "").toLowerCase(Locale.ROOT).strip(); }
    /** 创建安全业务异常。 */
    private VideoFrameProcessingException failure(String stage, String reason) { return new VideoFrameProcessingException(stage, reason); }
}
