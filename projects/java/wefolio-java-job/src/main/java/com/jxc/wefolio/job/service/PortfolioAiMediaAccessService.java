package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.job.dict.WorkAuditStatusDict;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.exception.CosServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import static com.jxc.wefolio.service.portfolioai.PortfolioAiMediaContract.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Comparator;
import java.util.Set;
import java.util.Locale;

/** 缓存命中及模型调用前实时检查当前账号、团队授权、作品状态及真实COS身份。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PortfolioAiMediaAccessService {
    /** 读取当前资料，不把冻结快照当授权。 */
    private final JdbcTemplate jdbc;
    /** SDK只发HEAD及签名，不下载原图片/视频。 */
    private final VideoFrameStorageService storage;
    /** 读取链接有效期，覆盖单次HTTP期限，不进入数据库。 */
    private static final int SIGN_SECONDS = 180;

    /** 每个来源始终验证当前授权和当前源；即使缓存命中也不跳过HEAD。 */
    public void verify(AiGenerationTaskEntity task, JsonNode entry) {
        verify(task, entry, true);
    }

    /** 同一次执行已验证真实对象后，阶段间只复核最新权限、源 digest 与帧清单。 */
    public void verifyCurrent(AiGenerationTaskEntity task, JsonNode entry) {
        verify(task, entry, false);
    }

    /** 每个阶段保持数据库授权检查；只有首次读取与返回前读取 COS 元数据。 */
    private void verify(AiGenerationTaskEntity task, JsonNode entry, boolean inspectObjects) {
        requireOwner(task);
        List<CurrentWork> rows = jdbc.query("""
                SELECT w.id,w.user_id,w.media_type,w.media_object_key,w.media_sha256,w.duration_ms,w.video_frames_json,u.unique_code
                FROM wf_work w JOIN wf_user u ON u.id=w.user_id
                WHERE w.id=? AND w.deleted=0 AND w.status=? AND w.audit_status=?
                AND u.deleted=0 AND u.status=?
                """, (rs, index) -> new CurrentWork(rs.getLong("id"), rs.getLong("user_id"), rs.getString("media_type"),
                rs.getString("media_object_key"), rs.getString("media_sha256"), rs.getObject("duration_ms", Integer.class),
                rs.getString("video_frames_json"), rs.getString("unique_code")), entry.path(KEY_WORK_ID).asLong(), WORK_ACTIVE,
                WorkAuditStatusDict.PASSED.getCode(), USER_ACTIVE);
        if (rows.size() != 1) { throw unavailable(); }
        CurrentWork work = rows.getFirst();
        if (work.userId() != entry.path(KEY_USER_ID).asLong() || !work.key().equals(entry.path(KEY_SOURCE_OBJECT_KEY).asText())
                || !work.key().startsWith(work.ownerCode() + WORK_DIRECTORY)) { throw unavailable(); }
        if (OWNER_USER.equals(task.getOwnerType())) {
            if (!task.getOwnerId().equals(work.userId())) { throw unavailable(); }
        } else {
            Integer allowed = jdbc.queryForObject("SELECT COUNT(*) FROM wf_team_member WHERE team_id=? AND user_id=? AND deleted=0 AND join_status=? AND allow_works=1", Integer.class, task.getOwnerId(), work.userId(), JOINED_STATUS);
            if (allowed == null || allowed != 1) { throw unavailable(); }
        }
        long bytes;
        String etag;
        if (inspectObjects) {
            ObjectMetadata metadata = head(work.key(), false);
            bytes = metadata.getContentLength(); etag = metadata.getETag();
        }
        else {
            if (!entry.path(KEY_SOURCE_BYTES).isIntegralNumber() || !entry.path(KEY_SOURCE_BYTES).canConvertToLong()
                    || !entry.path(KEY_SOURCE_ETAG).isTextual()) { throw unavailable(); }
            bytes = entry.path(KEY_SOURCE_BYTES).asLong(); etag = entry.path(KEY_SOURCE_ETAG).asText();
        }
        if (bytes <= 0 || etag == null
                || !fingerprint(work.key(), etag, bytes, work.sha()).equals(entry.path(KEY_SOURCE_VERSION).asText())) { throw unavailable(); }
        String route = entry.path(KEY_ROUTE).asText();
        if (ROUTE_FRAMES.equals(route)) { verifyFrames(work, entry, inspectObjects); }
        else if (ROUTE_VIDEO.equals(route)) {
            if (!ROUTE_VIDEO.equals(work.type()) || !withinDirectVideoLimits(bytes, work.duration())) { throw unavailable(); }
        } else if (!ROUTE_IMAGE.equals(route) || !ROUTE_IMAGE.equals(work.type()) || bytes > DIRECT_MEDIA_MAX_BYTES) { throw unavailable(); }
    }

    /** 在发最终文字生成前也检查任务归属和实际所有媒体权限。 */
    public void requireOwner(AiGenerationTaskEntity task) {
        Integer user = jdbc.queryForObject("SELECT COUNT(*) FROM wf_user WHERE id=? AND deleted=0 AND status=?", Integer.class, task.getRequestedBy(), USER_ACTIVE);
        if (user == null || user != 1) { throw unavailable(); }
        if (OWNER_USER.equals(task.getOwnerType())) {
            if (!task.getRequestedBy().equals(task.getOwnerId())) { throw unavailable(); }
        } else if (OWNER_TEAM.equals(task.getOwnerType())) {
            Integer team = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM wf_team t JOIN wf_team_member m ON m.team_id=t.id
                    WHERE t.id=? AND t.deleted=0 AND t.status=? AND m.user_id=? AND m.deleted=0
                    AND m.join_status=? AND m.role IN (?,?)
                    """, Integer.class, task.getOwnerId(), TEAM_ACTIVE, task.getRequestedBy(), JOINED_STATUS, OWNER_ROLE, MANAGER_ROLE);
            if (team == null || team != 1) { throw unavailable(); }
        } else { throw unavailable(); }
    }

    /** 同源完整资产必须仍是当前作品清单；全部对象HEAD验证后才能生成临时链接。 */
    private void verifyFrames(CurrentWork work, JsonNode entry, boolean inspectObjects) {
        if (work.frames() == null || !ROUTE_VIDEO.equals(work.type())) { throw unprepared(); }
        JsonNode manifest = StrictAiJson.parseObject(work.frames(), 128 * 1024, 12);
        int count = manifest.path(KEY_FRAME_COUNT).asInt();
        if (!FRAME_SCHEMA.equals(manifest.path(KEY_SCHEMA_VERSION).asText())
                || !entry.path(KEY_SOURCE_VERSION).asText().equals(manifest.path(KEY_SOURCE_VERSION).asText())
                || !hash(manifest.toString()).equals(entry.path(KEY_MANIFEST_HASH).asText())
                || !manifest.path(KEY_FRAME_COUNT).isIntegralNumber() || !manifest.path(KEY_FRAME_COUNT).canConvertToInt()
                || !isSupportedFrameCount(count) || !manifest.path(KEY_FRAMES).isArray() || !entry.path(KEY_FRAMES).isArray()
                || manifest.path(KEY_FRAMES).size() != count || entry.path(KEY_FRAMES).size() != count) { throw unprepared(); }
        Set<String> keys = new HashSet<>(); Set<Long> times = new HashSet<>();
        String prefix = work.ownerCode() + FRAME_DIRECTORY + work.id() + "/";
        List<JsonNode> currentFrames = new ArrayList<>(); manifest.path(KEY_FRAMES).forEach(currentFrames::add);
        List<JsonNode> frozenFrames = new ArrayList<>(); entry.path(KEY_FRAMES).forEach(frozenFrames::add);
        currentFrames.sort(Comparator.comparingLong(frame -> frame.path(KEY_TIME_MS).asLong(-1)));
        frozenFrames.sort(Comparator.comparingLong(frame -> frame.path(KEY_TIME_MS).asLong(-1)));
        for (int i = 0; i < count; i++) {
            JsonNode frame = currentFrames.get(i); JsonNode frozen = frozenFrames.get(i);
            String key = frame.path(KEY_OBJECT_KEY).asText(); long time = frame.path(KEY_TIME_MS).asLong(-1);
            if (!key.startsWith(prefix) || key.contains("..") || key.contains("%") || key.contains("\\") || !keys.add(key)
                    || !frame.path(KEY_TIME_MS).isIntegralNumber() || !frame.path(KEY_TIME_MS).canConvertToLong() || time < 0 || !times.add(time)
                    || !frame.path(KEY_BYTES).isIntegralNumber() || !frame.path(KEY_BYTES).canConvertToLong()
                    || frame.path(KEY_BYTES).asLong() <= 0 || frame.path(KEY_BYTES).asLong() > FRAME_MAX_BYTES
                    || !frame.path(KEY_WIDTH).isIntegralNumber() || !frame.path(KEY_WIDTH).canConvertToInt()
                    || !frame.path(KEY_HEIGHT).isIntegralNumber() || !frame.path(KEY_HEIGHT).canConvertToInt()
                    || frame.path(KEY_WIDTH).asInt() <= 0 || frame.path(KEY_HEIGHT).asInt() <= 0
                    || Math.max(frame.path(KEY_WIDTH).asInt(), frame.path(KEY_HEIGHT).asInt()) > FRAME_MAX_EDGE) { throw unprepared(); }
            if (!frozen.path(KEY_OBJECT_KEY).isTextual() || !key.equals(frozen.path(KEY_OBJECT_KEY).asText())
                    || !frozen.path(KEY_TIME_MS).isIntegralNumber() || !frozen.path(KEY_TIME_MS).canConvertToLong()
                    || frozen.path(KEY_TIME_MS).asLong() != time || !frozen.path(KEY_BYTES).isIntegralNumber()
                    || !frozen.path(KEY_BYTES).canConvertToLong() || frozen.path(KEY_BYTES).asLong() != frame.path(KEY_BYTES).asLong()) { throw unprepared(); }
            if (inspectObjects) {
                ObjectMetadata head = head(key, true);
                if (head.getContentLength() != frame.path(KEY_BYTES).asLong() || !JPEG_CONTENT_TYPE.equalsIgnoreCase(head.getContentType())) { throw unprepared(); }
            }
        }
    }

    /** 只签服务端被冻结且刚复核的本桶键，模型只接受此临时列表。 */
    public List<PortfolioAiModelClient.MediaInput> signed(AiGenerationTaskEntity task, JsonNode entry) {
        verify(task, entry);
        return signedInputs(entry);
    }

    /** 仅供同次生成首次完整 HEAD 后使用；签名前仍检查当前数据库授权和冻结源。 */
    public List<PortfolioAiModelClient.MediaInput> signedAfterVerification(AiGenerationTaskEntity task, JsonNode entry) {
        verifyCurrent(task, entry);
        return signedInputs(entry);
    }

    /** 从刚复核的受限对象列表构造临时媒体输入。 */
    private List<PortfolioAiModelClient.MediaInput> signedInputs(JsonNode entry) {
        List<PortfolioAiModelClient.MediaInput> result = new ArrayList<>();
        if (ROUTE_FRAMES.equals(entry.path(KEY_ROUTE).asText())) {
            List<JsonNode> frames = new ArrayList<>(); entry.path(KEY_FRAMES).forEach(frames::add);
            frames.sort(Comparator.comparingLong(frame -> frame.path(KEY_TIME_MS).asLong()));
            for (JsonNode frame : frames) { result.add(new PortfolioAiModelClient.MediaInput(TYPE_IMAGE_URL, sign(frame.path(KEY_OBJECT_KEY).asText()), frame.path(KEY_TIME_MS).asLong())); }
        } else {
            result.add(new PortfolioAiModelClient.MediaInput(ROUTE_VIDEO.equals(entry.path(KEY_ROUTE).asText()) ? TYPE_VIDEO_URL : TYPE_IMAGE_URL, sign(entry.path(KEY_SOURCE_OBJECT_KEY).asText()), null));
        }
        return result;
    }
    /** 官方COS签名默认主机，禁止业务自带URL。 */
    private String sign(String key) {
        try { return storage.signedGet(key, Duration.ofSeconds(SIGN_SECONDS)); }
        catch (RuntimeException exception) { throw temporary(); }
    }

    /** 区分不存在与远端临时异常；错误分类不保存或打印 COS 原文和临时链接。 */
    private ObjectMetadata head(String key, boolean frame) {
        try { return storage.head(key); }
        catch (CosServiceException exception) {
            int status = exception.getStatusCode();
            if (status == 404) { throw frame ? unprepared() : unavailable(); }
            if (status >= 400 && status < 500 && status != 408 && status != 429) { throw unavailable(); }
            log.warn("媒体 COS HEAD 暂时失败: status={}", status);
            throw temporary();
        } catch (RuntimeException exception) {
            log.warn("媒体 COS HEAD 暂时失败: exceptionType={}", exception.getClass().getSimpleName());
            throw temporary();
        }
    }
    /** 与帧保存源身份相同的算法。 */
    public static String fingerprint(String key, String etag, long bytes, String sha) { return hash(key + "\n" + (etag == null ? "" : etag.replace("\"", "").strip().toLowerCase(Locale.ROOT)) + "\n" + bytes + "\n" + (sha == null ? "" : sha)); }
    /** 不可逆身份及缓存摘要。 */
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance(SHA256_ALGORITHM).digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256不可用", exception); }
    }
    /** 当前作品数据库事实。 */
    private record CurrentWork(long id, long userId, String type, String key, String sha, Integer duration, String frames, String ownerCode) { }
    /** 当前访问无权或源变化。 */
    private static PortfolioAiCallException unavailable() { return new PortfolioAiCallException(ERROR_SOURCE_UNAVAILABLE, "所选作品或团队授权已失效", false); }
    /** 已有帧缺失或不合格。 */
    private static PortfolioAiCallException unprepared() { return new PortfolioAiCallException(ERROR_MEDIA_NOT_PREPARED, "所选视频持久帧尚未准备完成", false); }
    /** 暂时无法确认真实对象，保留已付费摘要并交给统一重试预算。 */
    private static PortfolioAiCallException temporary() { return new PortfolioAiCallException(ERROR_MEDIA_ACCESS_RETRY, "媒体对象服务暂时不可用，请稍后重试", true); }
}
