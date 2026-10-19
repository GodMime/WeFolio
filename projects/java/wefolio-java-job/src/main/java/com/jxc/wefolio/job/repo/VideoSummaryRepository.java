package com.jxc.wefolio.job.repo;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 原视频摘要专用快照与带权限、源和审核代次保护的短事务发布。 */
@Repository
@RequiredArgsConstructor
public class VideoSummaryRepository {
    /** 唯一受支持的作品及状态。 */
    private static final String VIDEO = "VIDEO", ACTIVE = "ACTIVE", PASSED = "PASSED";
    /** 原视频必须在作者目录内，禁止穿越及嵌套任意路径。 */
    private static final String SOURCE_PATTERN = "[A-Za-z0-9_-]+/work/video/[^/]+";
    /** 原视频标准目录后缀。 */
    private static final String VIDEO_DIRECTORY = "/work/video/";
    /** 现有名称列为 VARCHAR(30) utf8mb4，按最大字节数进行完整二进制比较。 */
    private static final int TITLE_MAX_BYTES = 30 * 4;
    /** 只在快照及 CAS 时占用数据库连接。 */
    private final JdbcTemplate jdbc;

    /** 原视频摘要完全不依赖帧和音频，避免后补操作使同源摘要失效。 */
    public Snapshot find(long workId) {
        List<Snapshot> rows = jdbc.query("""
                SELECT w.id,w.user_id,w.media_object_key,w.media_sha256,w.audit_round,w.version,
                  w.duration_ms,w.video_summary_json,u.unique_code
                FROM wf_work w JOIN wf_user u ON u.id=w.user_id AND u.deleted=0 AND u.status=?
                WHERE w.id=? AND w.media_type=? AND w.status=? AND w.audit_status=? AND w.deleted=0
                """, (rs, row) -> new Snapshot(rs.getLong("id"), rs.getLong("user_id"), rs.getString("media_object_key"),
                rs.getString("media_sha256"), rs.getInt("audit_round"), rs.getInt("version"),
                rs.getObject("duration_ms", Integer.class), rs.getString("video_summary_json"), rs.getString("unique_code")),
                ACTIVE, workId, VIDEO, ACTIVE, PASSED);
        if (rows.isEmpty()) { return null; }
        Snapshot row = rows.getFirst();
        return row.ownerCode() != null && row.objectKey() != null && row.objectKey().matches(SOURCE_PATTERN)
                && !row.objectKey().contains("..") && row.objectKey().startsWith(row.ownerCode() + VIDEO_DIRECTORY) ? row : null;
    }

    /** 同一次写入检查版本、作者、轮次、状态与源，晚到结果不得覆盖新的作品状态。 */
    @Transactional(rollbackFor = Exception.class, timeout = 5)
    public boolean save(Snapshot snapshot, String json) {
        return jdbc.update("""
                UPDATE wf_work SET video_summary_json=?,updated_at=?,version=version+1
                WHERE id=? AND user_id=? AND version=? AND audit_round=? AND deleted=0
                  AND media_type=? AND status=? AND audit_status=? AND media_object_key=?
                  AND (media_sha256=? OR (media_sha256 IS NULL AND ? IS NULL))
                  AND EXISTS (SELECT 1 FROM wf_user u WHERE u.id=wf_work.user_id
                    AND u.deleted=0 AND u.status=? AND u.unique_code=?)
                """, json, Timestamp.from(Instant.now()), snapshot.id(), snapshot.userId(), snapshot.version(),
                snapshot.auditRound(), VIDEO, ACTIVE, PASSED, snapshot.objectKey(), snapshot.sha256(), snapshot.sha256(),
                ACTIVE, snapshot.ownerCode()) == 1;
    }

    /** 摘要提交后重新读取名称及完整来源，不复用模型调用前的名称或版本。 */
    public TitleSnapshot findForTitle(long workId) {
        List<TitleSnapshot> rows = jdbc.query("""
                SELECT w.id,w.user_id,w.title,w.media_object_key,w.media_sha256,w.audit_round,w.version,
                  w.duration_ms,w.video_summary_json,u.unique_code
                FROM wf_work w JOIN wf_user u ON u.id=w.user_id AND u.deleted=0 AND u.status=?
                WHERE w.id=? AND w.media_type=? AND w.status=? AND w.audit_status=? AND w.deleted=0
                """, (rs, row) -> new TitleSnapshot(new Snapshot(rs.getLong("id"), rs.getLong("user_id"),
                rs.getString("media_object_key"), rs.getString("media_sha256"), rs.getInt("audit_round"),
                rs.getInt("version"), rs.getObject("duration_ms", Integer.class),
                rs.getString("video_summary_json"), rs.getString("unique_code")), rs.getString("title")),
                ACTIVE, workId, VIDEO, ACTIVE, PASSED);
        if (rows.isEmpty()) { return null; }
        TitleSnapshot row = rows.getFirst();
        Snapshot source = row.source();
        return source.ownerCode() != null && source.objectKey() != null && source.objectKey().matches(SOURCE_PATTERN)
                && !source.objectKey().contains("..")
                && source.objectKey().startsWith(source.ownerCode() + VIDEO_DIRECTORY) ? row : null;
    }

    /** 名称经服务判断可替换后独立提交，严格名称和版本条件保护并发编辑。 */
    @Transactional(rollbackFor = Exception.class, timeout = 5)
    public boolean renameDefaultTitle(TitleSnapshot latest, String suggestedTitle) {
        Snapshot source = latest.source();
        return jdbc.update("""
                UPDATE wf_work SET title=?,updated_at=?,version=version+1
                WHERE id=? AND user_id=? AND version=? AND audit_round=? AND deleted=0
                  AND media_type=? AND status=? AND audit_status=? AND media_object_key=?
                  AND (media_sha256=? OR (media_sha256 IS NULL AND ? IS NULL))
                  AND CAST(title AS BINARY(%d))=CAST(? AS BINARY(%d)) AND OCTET_LENGTH(title)=OCTET_LENGTH(?)
                  AND EXISTS (SELECT 1 FROM wf_user u WHERE u.id=wf_work.user_id
                    AND u.deleted=0 AND u.status=? AND u.unique_code=?)
                """.formatted(TITLE_MAX_BYTES, TITLE_MAX_BYTES), suggestedTitle, Timestamp.from(Instant.now()),
                source.id(), source.userId(), source.version(), source.auditRound(), VIDEO, ACTIVE, PASSED,
                source.objectKey(), source.sha256(), source.sha256(), latest.title(), latest.title(),
                ACTIVE, source.ownerCode()) == 1;
    }

    /** 名称阶段持有最新来源，避免向原摘要快照引入命名依赖。 */
    public record TitleSnapshot(Snapshot source, String title) {
        /** 避免作品名称及内容意外输出。 */
        @Override public String toString() { return "VideoTitleSnapshot[source=" + source + "]"; }
    }

    /** 快照日志不包含作品内容。 */
    public record Snapshot(long id, long userId, String objectKey, String sha256, int auditRound, int version,
                           Integer durationMs, String summary, String ownerCode) {
        /** 同源重建可以推进版本，但不可更换作者、原对象或审核代次。 */
        public boolean sameSource(Snapshot other) {
            return other != null && id == other.id && userId == other.userId && auditRound == other.auditRound
                    && Objects.equals(objectKey, other.objectKey) && Objects.equals(sha256, other.sha256)
                    && Objects.equals(ownerCode, other.ownerCode);
        }
        /** 避免摘要及媒体路径意外输出。 */
        @Override public String toString() { return "VideoSummarySnapshot[id=" + id + ",version=" + version + "]"; }
    }
}
