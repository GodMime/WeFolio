package com.jxc.wefolio.job.repo;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.job.dict.MediaTypeDict;
import com.jxc.wefolio.job.dict.WorkAuditStatusDict;
import com.jxc.wefolio.job.model.VideoFrameBackfillModels.Work;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 历史视频扫描、重建清空与完整清单回写，不维护任务表。 */
@Repository
@RequiredArgsConstructor
public class VideoFrameBackfillRepository {
    /** 用户有效状态与作品有效状态属于独立值域。 */
    private static final String USER_STATUS_ACTIVE = "ACTIVE";
    private static final String WORK_STATUS_ACTIVE = "ACTIVE";
    /** 作者目录内的视频作品路径。 */
    private static final String VIDEO_DIRECTORY = "/work/video/";
    /** 只消费已有用户表和作品表。 */
    private final JdbcTemplate jdbc;

    /** 固定本次扫描水位；新上传作品由新审核链路处理。 */
    public long highWatermark() {
        Long watermark = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id),0) FROM wf_work WHERE media_type=? AND deleted=0",
                Long.class, MediaTypeDict.VIDEO.getCode());
        return watermark == null ? 0 : watermark;
    }

    /** 按作品 ID 分页，游标、水位和预算都仅保存在本次执行内存中。 */
    public List<Work> scan(long cursor, long highWatermark, int limit) {
        return jdbc.query("""
                SELECT w.id,w.media_object_key,w.media_sha256,w.duration_ms,w.video_frames_json,w.version,u.unique_code
                FROM wf_work w JOIN wf_user u ON u.id=w.user_id AND u.deleted=0 AND u.status=?
                WHERE w.media_type=? AND w.status=? AND w.audit_status=? AND w.deleted=0 AND w.id>? AND w.id<=?
                ORDER BY w.id LIMIT ?
                """, (rs, n) -> new Work(rs.getLong("id"), rs.getString("media_object_key"),
                        rs.getString("media_sha256"), rs.getObject("duration_ms", Integer.class),
                        rs.getString("video_frames_json"), rs.getInt("version"), rs.getString("unique_code")),
                USER_STATUS_ACTIVE, MediaTypeDict.VIDEO.getCode(), WORK_STATUS_ACTIVE,
                WorkAuditStatusDict.PASSED.getCode(), cursor, highWatermark, limit);
    }

    /**
     * 同一短事务核对源、版本及当前清单后写完整结果，不能覆盖新审核已发布的帧。
     * 事务内没有远端调用，发布新清单同步失效旧音频文本和帧摘要缓存；原视频摘要不依赖同源帧。
     */
    @Transactional
    public boolean saveManifest(Work work, String manifest) {
        List<String> current = jdbc.query("""
                SELECT video_frames_json FROM wf_work
                WHERE id=? AND media_type=? AND status=? AND audit_status=? AND media_object_key=? AND version=? AND deleted=0
                  AND (media_sha256=? OR (media_sha256 IS NULL AND ? IS NULL)) FOR UPDATE
                """, (rs, n) -> rs.getString(1), work.id(), MediaTypeDict.VIDEO.getCode(), WORK_STATUS_ACTIVE,
                WorkAuditStatusDict.PASSED.getCode(), work.objectKey(), work.version(), work.sha256(), work.sha256());
        if (current.isEmpty() || !sameManifest(current.getFirst(), work.manifest())) { return false; }
        return jdbc.update("""
                UPDATE wf_work SET video_frames_json=?,video_audio_text=NULL,ai_media_summary_json=NULL,
                  ai_media_summary_expires_at=NULL,updated_at=?,version=version+1
                WHERE id=? AND media_type=? AND status=? AND audit_status=? AND media_object_key=? AND version=? AND deleted=0
                  AND (media_sha256=? OR (media_sha256 IS NULL AND ? IS NULL))
                """, manifest, Timestamp.from(Instant.now()), work.id(), MediaTypeDict.VIDEO.getCode(),
                WORK_STATUS_ACTIVE, WorkAuditStatusDict.PASSED.getCode(), work.objectKey(), work.version(),
                work.sha256(), work.sha256()) == 1;
    }

    /**
     * 重建前在短事务内清空本次快照及派生摘要，成功返回后才允许删除旧 COS 帧。
     * 源、审核状态、版本或清单已变化时返回空；提交结果未知时异常向上传递，不能推断清空成功。
     */
    @Transactional(rollbackFor = Exception.class)
    public Work clearManifestForRebuild(Work work) {
        List<String> current = jdbc.query("""
                SELECT video_frames_json FROM wf_work
                WHERE id=? AND media_type=? AND status=? AND audit_status=? AND media_object_key=? AND version=? AND deleted=0
                  AND (media_sha256=? OR (media_sha256 IS NULL AND ? IS NULL)) FOR UPDATE
                """, (rs, n) -> rs.getString(1), work.id(), MediaTypeDict.VIDEO.getCode(), WORK_STATUS_ACTIVE,
                WorkAuditStatusDict.PASSED.getCode(), work.objectKey(), work.version(), work.sha256(), work.sha256());
        if (current.isEmpty() || !sameRebuildManifest(current.getFirst(), work.manifest())) { return null; }
        int changed = jdbc.update("""
                UPDATE wf_work SET video_frames_json=NULL,video_audio_text=NULL,video_summary_json=NULL,
                  ai_media_summary_json=NULL,ai_media_summary_expires_at=NULL,
                  updated_at=?,version=version+1
                WHERE id=? AND media_type=? AND status=? AND audit_status=? AND media_object_key=? AND version=? AND deleted=0
                  AND (media_sha256=? OR (media_sha256 IS NULL AND ? IS NULL))
                """, Timestamp.from(Instant.now()), work.id(), MediaTypeDict.VIDEO.getCode(), WORK_STATUS_ACTIVE,
                WorkAuditStatusDict.PASSED.getCode(), work.objectKey(), work.version(), work.sha256(), work.sha256());
        return changed == 1 ? new Work(work.id(), work.objectKey(), work.sha256(), work.durationMs(),
                null, work.version() + 1, work.ownerCode()) : null;
    }

    /** 提交响应未知时确认当前作品是否发布了本次完整清单；数据库异常交由调用方保留对象。 */
    public boolean manifestPublished(long workId, String manifest) {
        List<String> current = jdbc.query("SELECT video_frames_json FROM wf_work WHERE id=? AND deleted=0",
                (rs, n) -> rs.getString(1), workId);
        return !current.isEmpty() && current.getFirst() != null && sameManifest(current.getFirst(), manifest);
    }

    /** 作品目录必须属于真实作者，避免孤立键跨账号写入。 */
    public boolean ownedSource(Work work) {
        return work.ownerCode() != null && work.objectKey() != null
                && work.objectKey().startsWith(work.ownerCode() + VIDEO_DIRECTORY);
    }

    /** JSON 语义一致可忽略存储格式变化；异常旧内容只允许原字节一致。 */
    private boolean sameManifest(String left, String right) {
        if (Objects.equals(left, right)) { return true; }
        if (left == null || right == null) { return false; }
        try { return Objects.equals(JSON.parseObject(left), JSON.parseObject(right)); }
        catch (RuntimeException exception) { return false; }
    }

    /** 重建允许有效对象清单的格式差异；损坏或 JSON 空值清单只有原始文本完全相同才可清空。 */
    private boolean sameRebuildManifest(String left, String right) {
        if (Objects.equals(left, right)) { return true; }
        if (left == null || right == null) { return false; }
        try {
            JSONObject current = JSON.parseObject(left);
            return current != null && current.equals(JSON.parseObject(right));
        } catch (RuntimeException exception) { return false; }
    }
}
