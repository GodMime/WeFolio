package com.jxc.wefolio.job.repo;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 音频全文专用快照与短事务写入；不让普通实体更新携带长文本。 */
@Repository
@RequiredArgsConstructor
public class VideoAudioTextRepository {
    /** 状态与目录来自受控业务值域。 */
    private static final String ACTIVE = "ACTIVE";
    /** 只有审核通过的作品允许补充转写。 */
    private static final String PASSED = "PASSED";
    /** 全文仅属于视频作品，不能写入音频等其它类型。 */
    private static final String VIDEO = "VIDEO";
    /** 所有者目录下的视频子路径。 */
    private static final String VIDEO_DIRECTORY = "/work/video/";
    /** 原视频必须是受控目录内的单个对象。 */
    private static final String SOURCE_KEY_PATTERN = "[A-Za-z0-9_-]+/work/video/[^/]+";
    /** 仅在快照与写入时访问数据库，不在远端调用期间持有行锁。 */
    private final JdbcTemplate jdbc;

    /** 同一查询读取权限、审核轮次、源、帧和文本，避免拼接不同版本。 */
    public Snapshot find(long workId) {
        List<Snapshot> found = jdbc.query("""
                SELECT w.id,w.user_id,w.media_object_key,w.media_sha256,w.audit_round,w.version,
                  w.video_frames_json,w.video_audio_text,u.unique_code
                FROM wf_work w JOIN wf_user u ON u.id=w.user_id AND u.deleted=0 AND u.status=?
                WHERE w.id=? AND w.media_type=? AND w.status=? AND w.audit_status=? AND w.deleted=0
                """, (rs, row) -> new Snapshot(rs.getLong("id"), rs.getLong("user_id"),
                rs.getString("media_object_key"), rs.getString("media_sha256"), rs.getInt("audit_round"),
                rs.getInt("version"), rs.getString("video_frames_json"), rs.getString("video_audio_text"),
                rs.getString("unique_code")), ACTIVE, workId, VIDEO, ACTIVE, PASSED);
        if (found.isEmpty()) { return null; }
        Snapshot snapshot = found.getFirst();
        return snapshot.ownerCode() != null && snapshot.objectKey() != null
                && snapshot.objectKey().matches(SOURCE_KEY_PATTERN) && !snapshot.objectKey().contains("..")
                && snapshot.objectKey().startsWith(snapshot.ownerCode() + VIDEO_DIRECTORY) ? snapshot : null;
    }

    /** 版本、作者、审核、源和清单共同通过后，同事务发布全文并使摘要失效。 */
    @Transactional(rollbackFor = Exception.class, timeout = 5)
    public boolean save(Snapshot snapshot, String text) {
        List<String> current = jdbc.query("""
                SELECT video_frames_json FROM wf_work
                WHERE id=? AND user_id=? AND version=? AND audit_round=? AND deleted=0
                  AND media_type=? AND status=? AND audit_status=? AND media_object_key=?
                  AND (media_sha256=? OR (media_sha256 IS NULL AND ? IS NULL)) FOR UPDATE
                """, (rs, row) -> rs.getString(1), snapshot.id(), snapshot.userId(), snapshot.version(),
                snapshot.auditRound(), VIDEO, ACTIVE, PASSED, snapshot.objectKey(), snapshot.sha256(), snapshot.sha256());
        if (current.isEmpty() || !sameManifest(current.getFirst(), snapshot.manifest())) { return false; }
        return jdbc.update("""
                UPDATE wf_work SET video_audio_text=?,ai_media_summary_json=NULL,ai_media_summary_expires_at=NULL,
                  updated_at=?,version=version+1
                WHERE id=? AND user_id=? AND version=? AND audit_round=? AND deleted=0
                  AND media_type=? AND status=? AND audit_status=? AND media_object_key=?
                  AND (media_sha256=? OR (media_sha256 IS NULL AND ? IS NULL))
                  AND EXISTS (SELECT 1 FROM wf_user u WHERE u.id=wf_work.user_id
                    AND u.deleted=0 AND u.status=? AND u.unique_code=?)
                """, text, Timestamp.from(Instant.now()), snapshot.id(), snapshot.userId(), snapshot.version(),
                snapshot.auditRound(), VIDEO, ACTIVE, PASSED, snapshot.objectKey(), snapshot.sha256(),
                snapshot.sha256(), ACTIVE, snapshot.ownerCode()) == 1;
    }

    /** 清单比较接受数据库 JSON 的空白规范化，不放松内容一致性。 */
    private static boolean sameManifest(String left, String right) {
        if (Objects.equals(left, right)) { return true; }
        if (left == null || right == null) { return false; }
        try { return Objects.equals(JSON.parse(left), JSON.parse(right)); }
        catch (RuntimeException ignored) { return false; }
    }

    /** 不自动生成包含全文的 toString，防止调用方意外输出识别内容。 */
    public record Snapshot(long id, long userId, String objectKey, String sha256, int auditRound, int version,
                           String manifest, String text, String ownerCode) {
        /** 重建允许版本和帧变化，但不能接续其它作品、审核轮次或源。 */
        public boolean sameSource(Snapshot other) {
            return other != null && id == other.id && userId == other.userId && auditRound == other.auditRound
                    && Objects.equals(objectKey, other.objectKey) && Objects.equals(sha256, other.sha256)
                    && Objects.equals(ownerCode, other.ownerCode);
        }
        /** 调试表示仅保留作品与版本，不携带全文或媒体清单。 */
        @Override public String toString() { return "VideoAudioSnapshot[id=" + id + ",version=" + version + "]"; }
    }
}
