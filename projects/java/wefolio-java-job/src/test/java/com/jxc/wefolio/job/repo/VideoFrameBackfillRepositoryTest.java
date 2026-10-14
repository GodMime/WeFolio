package com.jxc.wefolio.job.repo;

import com.jxc.wefolio.job.model.VideoFrameBackfillModels.Work;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** H2 验证无任务表的固定水位、源及清单 CAS，不修改旧审核与客户端时长。 */
class VideoFrameBackfillRepositoryTest {
    /** 清空测试的既有清单、并发清单及派生摘要，只包含本地夹具内容。 */
    private static final String OLD_MANIFEST = "{\"oldFrames\":true}";
    private static final String NEW_MANIFEST = "{\"newFrames\":true}";
    private static final String OLD_SUMMARY = "{\"summary\":\"旧帧摘要\"}";
    private static final String BROKEN_MANIFEST = "invalid-old";
    /** 固定缓存期限只用于证明清单清空与缓存清空发生在同一更新中。 */
    private static final LocalDateTime CACHE_EXPIRY = LocalDateTime.of(2026, 10, 14, 12, 0);
    private JdbcTemplate jdbc;
    private VideoFrameBackfillRepository repository;
    private TransactionTemplate transaction;
    private Work work;

    @BeforeEach
    void setup() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:frames-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("video-frame-backfill-test-schema.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        // 专用旧夹具不含摘要缓存列，本测试补齐生产已存在的字段，不修改生产迁移链。
        jdbc.execute("ALTER TABLE wf_work ADD COLUMN IF NOT EXISTS ai_media_summary_json varchar(16000)");
        jdbc.execute("ALTER TABLE wf_work ADD COLUMN IF NOT EXISTS ai_media_summary_expires_at timestamp");
        repository = new VideoFrameBackfillRepository(jdbc);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.update("INSERT INTO wf_user(id,unique_code,status) VALUES(1,'OWNER','ACTIVE')");
        jdbc.update("""
                INSERT INTO wf_work(id,user_id,media_type,status,media_object_key,media_sha256,duration_ms,audit_status)
                VALUES(1,1,'VIDEO','ACTIVE','OWNER/work/video/a.mp4','sha',23000,'PASSED')
                """);
        work = repository.scan(0, repository.highWatermark(), 10).getFirst();
    }

    @Test
    void fixesInMemoryWatermarkAndScansByCursorWithoutTaskTables() {
        long watermark = repository.highWatermark();
        jdbc.update("INSERT INTO wf_work(id,user_id,media_type,status,media_object_key) VALUES(2,1,'VIDEO','ACTIVE','OWNER/work/video/later.mp4')");
        assertEquals(1, watermark);
        assertEquals(1, repository.scan(0, watermark, 20).size());
        assertTrue(repository.scan(1, watermark, 20).isEmpty());
        assertEquals(2, repository.highWatermark());
    }

    @Test
    void savesWholeManifestWithoutChangingAuditOrFrontendDuration() {
        String manifest = "{\"complete\":true}";
        jdbc.update("UPDATE wf_work SET video_audio_text='旧口播',video_summary_json='{\"summary\":\"原视频摘要\"}',ai_media_summary_json='旧摘要',ai_media_summary_expires_at=CURRENT_TIMESTAMP WHERE id=1");
        assertEquals(Boolean.TRUE, transaction.execute(status -> repository.saveManifest(work, manifest)));
        assertEquals(manifest, currentManifest());
        assertEquals("{\"summary\":\"原视频摘要\"}",
                jdbc.queryForObject("SELECT video_summary_json FROM wf_work WHERE id=1", String.class));
        assertNull(jdbc.queryForObject("SELECT video_audio_text FROM wf_work WHERE id=1", String.class));
        assertNull(jdbc.queryForObject("SELECT ai_media_summary_json FROM wf_work WHERE id=1", String.class));
        assertNull(jdbc.queryForObject("SELECT ai_media_summary_expires_at FROM wf_work WHERE id=1", LocalDateTime.class));
        assertEquals("PASSED", jdbc.queryForObject("SELECT audit_status FROM wf_work WHERE id=1", String.class));
        assertEquals(23000, jdbc.queryForObject("SELECT duration_ms FROM wf_work WHERE id=1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT version FROM wf_work WHERE id=1", Integer.class));
    }

    @Test
    void staleVersionCannotOverwriteNewAuditManifest() {
        jdbc.update("UPDATE wf_work SET version=version+1,video_frames_json='{\"newAudit\":true}' WHERE id=1");
        assertEquals(Boolean.FALSE, transaction.execute(status -> repository.saveManifest(work, "{\"history\":true}")));
        assertEquals("{\"newAudit\":true}", currentManifest());
    }

    @Test
    void changedManifestEvenWithoutVersionIncrementRejectsHistoryResult() {
        jdbc.update("UPDATE wf_work SET video_frames_json='{\"other\":true}' WHERE id=1");
        assertEquals(Boolean.FALSE, transaction.execute(status -> repository.saveManifest(work, "{\"history\":true}")));
        assertEquals("{\"other\":true}", currentManifest());
    }

    @Test
    void changedSourceOrDeletionCannotPublishOldFrames() {
        jdbc.update("UPDATE wf_work SET media_object_key='OWNER/work/video/replaced.mp4' WHERE id=1");
        assertEquals(Boolean.FALSE, transaction.execute(status -> repository.saveManifest(work, "{}")));
        assertNull(currentManifest());
        jdbc.update("UPDATE wf_work SET media_object_key=?,deleted=1 WHERE id=1", work.objectKey());
        assertEquals(Boolean.FALSE, transaction.execute(status -> repository.saveManifest(work, "{}")));
        assertNull(currentManifest());
    }

    @Test
    void nullSourceDigestKeepsExistingHistoricalCompatibility() {
        jdbc.update("UPDATE wf_work SET media_sha256=NULL WHERE id=1");
        Work legacy = repository.scan(0, 1, 10).getFirst();
        assertEquals(Boolean.TRUE, transaction.execute(status -> repository.saveManifest(legacy, "{}")));
    }

    @Test
    void oldInvalidManifestCanBeReplacedOnlyWhenStillCurrent() {
        jdbc.update("UPDATE wf_work SET video_frames_json='invalid-old' WHERE id=1");
        Work previous = repository.scan(0, 1, 10).getFirst();
        assertEquals(Boolean.TRUE, transaction.execute(status -> repository.saveManifest(previous, "{\"complete\":true}")));
        assertEquals("{\"complete\":true}", currentManifest());
    }

    @Test
    void publicationChecksActualCurrentManifestAndIgnoresJsonFormatting() {
        jdbc.update("UPDATE wf_work SET video_frames_json='{\"other\":true}' WHERE id=1");
        assertFalse(repository.manifestPublished(1, "{\"this\":true}"));
        assertTrue(repository.manifestPublished(1, "{ \"other\" : true }"));
        assertFalse(repository.manifestPublished(2, "{\"other\":true}"));
    }

    @Test
    void sourceDirectoryMustBelongToActualOwner() {
        assertTrue(repository.ownedSource(work));
        Work foreign = new Work(1, "OTHER/work/video/a.mp4", "sha", 23000, null, 0, "OWNER");
        assertFalse(repository.ownedSource(foreign));
    }

    @Test
    void inactiveOwnerOrWorkIsExcludedFromScan() {
        jdbc.update("UPDATE wf_user SET status='DISABLED' WHERE id=1");
        assertTrue(repository.scan(0, 1, 10).isEmpty());
        jdbc.update("UPDATE wf_user SET status='ACTIVE' WHERE id=1");
        jdbc.update("UPDATE wf_work SET status='DISABLED' WHERE id=1");
        assertTrue(repository.scan(0, 1, 10).isEmpty());
    }

    /** 历史扫描只为审核通过的展示作品付费补帧。 */
    @Test
    void unapprovedVideoIsExcludedEvenWhenWorkAndOwnerRemainActive() {
        for (String auditStatus : new String[] {"PENDING", "AUDITING", "REJECTED", "REVIEW_REQUIRED", "FAILED"}) {
            jdbc.update("UPDATE wf_work SET audit_status=? WHERE id=1", auditStatus);
            assertTrue(repository.scan(0, 1, 10).isEmpty(), auditStatus);
        }
        jdbc.update("UPDATE wf_work SET audit_status=NULL WHERE id=1");
        assertTrue(repository.scan(0, 1, 10).isEmpty());
    }

    /** 扫描后审核状态变化时仍须拒绝发布，不能只依赖第一次扫描。 */
    @Test
    void auditRevokedWithoutVersionIncrementCannotPublishFrames() {
        jdbc.update("UPDATE wf_work SET audit_status='REJECTED' WHERE id=1");
        assertEquals(Boolean.FALSE, transaction.execute(status -> repository.saveManifest(work, "{}")));
        assertNull(currentManifest());
    }

    /** 已保存清单与派生缓存一起清空，返回的新快照可用于后续重建发布。 */
    @Test
    void rebuildClearsManifestAndSummaryCacheWithoutChangingSourceOrAudit() {
        Work snapshot = savedWork(OLD_MANIFEST);
        jdbc.update("UPDATE wf_work SET video_audio_text='旧口播',video_summary_json='{\"summary\":\"原视频摘要\"}' WHERE id=1");

        Work cleared = transaction.execute(status -> repository.clearManifestForRebuild(snapshot));

        assertEquals(new Work(snapshot.id(), snapshot.objectKey(), snapshot.sha256(), snapshot.durationMs(),
                null, snapshot.version() + 1, snapshot.ownerCode()), cleared);
        assertNull(currentManifest());
        assertNull(jdbc.queryForObject("SELECT video_summary_json FROM wf_work WHERE id=1", String.class));
        assertNull(jdbc.queryForObject("SELECT video_audio_text FROM wf_work WHERE id=1", String.class));
        assertNull(jdbc.queryForObject("SELECT ai_media_summary_json FROM wf_work WHERE id=1", String.class));
        assertNull(jdbc.queryForObject("SELECT ai_media_summary_expires_at FROM wf_work WHERE id=1", LocalDateTime.class));
        assertEquals(1, jdbc.queryForObject("SELECT version FROM wf_work WHERE id=1", Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT updated_at FROM wf_work WHERE id=1", LocalDateTime.class));
        assertEquals(snapshot.objectKey(), jdbc.queryForObject("SELECT media_object_key FROM wf_work WHERE id=1", String.class));
        assertEquals("PASSED", jdbc.queryForObject("SELECT audit_status FROM wf_work WHERE id=1", String.class));
        assertEquals(snapshot.durationMs(), jdbc.queryForObject("SELECT duration_ms FROM wf_work WHERE id=1", Integer.class));
    }

    /** 原来没有帧清单也返回版本递增快照，不能复用清空前的旧版本发布。 */
    @Test
    void rebuildOfNullManifestReturnsANewSnapshotAndClearsStaleSummary() {
        Work snapshot = savedWork(null);
        Work cleared = transaction.execute(status -> repository.clearManifestForRebuild(snapshot));
        assertNotNull(cleared);
        assertNull(cleared.manifest());
        assertEquals(snapshot.version() + 1, cleared.version());
        assertNull(jdbc.queryForObject("SELECT ai_media_summary_json FROM wf_work WHERE id=1", String.class));
        assertEquals(Boolean.FALSE, transaction.execute(status -> repository.saveManifest(snapshot, NEW_MANIFEST)));
        assertEquals(Boolean.TRUE, transaction.execute(status -> repository.saveManifest(cleared, NEW_MANIFEST)));
        assertEquals(NEW_MANIFEST, currentManifest());
        assertEquals(2, jdbc.queryForObject("SELECT version FROM wf_work WHERE id=1", Integer.class));
    }

    /** 重建清空使用与保存一致的作品状态、源及版本守卫，任意冲突均保留帧与缓存。 */
    @Test
    void rebuildRefusesStatusSourceVersionAndDeletionConflicts() {
        for (String mutation : new String[]{
                "UPDATE wf_work SET status='DISABLED' WHERE id=1",
                "UPDATE wf_work SET audit_status='AUDITING' WHERE id=1",
                "UPDATE wf_work SET audit_status='REJECTED' WHERE id=1",
                "UPDATE wf_work SET media_type='IMAGE' WHERE id=1",
                "UPDATE wf_work SET media_object_key='OWNER/work/video/replaced.mp4' WHERE id=1",
                "UPDATE wf_work SET media_sha256='changed' WHERE id=1",
                "UPDATE wf_work SET media_sha256=NULL WHERE id=1",
                "UPDATE wf_work SET version=version+1 WHERE id=1",
                "UPDATE wf_work SET deleted=1 WHERE id=1"}) {
            jdbc.update("""
                    UPDATE wf_work SET status='ACTIVE',audit_status='PASSED',media_type='VIDEO',
                      media_object_key='OWNER/work/video/a.mp4',media_sha256='sha',version=0,deleted=0 WHERE id=1
                    """);
            Work snapshot = savedWork(OLD_MANIFEST);
            jdbc.update(mutation);
            assertNull(transaction.execute(status -> repository.clearManifestForRebuild(snapshot)), mutation);
            assertEquals(OLD_MANIFEST, currentManifest(), mutation);
            assertSummaryUnchanged();
        }
    }

    /** 即使并发方没有增加版本，只要清单改变仍拒绝清空。 */
    @Test
    void rebuildRefusesAChangedManifestWithoutVersionIncrement() {
        Work snapshot = savedWork(OLD_MANIFEST);
        jdbc.update("UPDATE wf_work SET video_frames_json=? WHERE id=1", NEW_MANIFEST);
        assertNull(transaction.execute(status -> repository.clearManifestForRebuild(snapshot)));
        assertEquals(NEW_MANIFEST, currentManifest());
        assertSummaryUnchanged();
        assertEquals(0, jdbc.queryForObject("SELECT version FROM wf_work WHERE id=1", Integer.class));
    }

    /** 数据库 JSON 格式化不影响对象语义，清空仍必须匹配同一份快照。 */
    @Test
    void rebuildAllowsEquivalentObjectManifestFormatting() {
        Work snapshot = savedWork(OLD_MANIFEST);
        jdbc.update("UPDATE wf_work SET video_frames_json=? WHERE id=1", "{ \"oldFrames\" : true }");
        assertNotNull(transaction.execute(status -> repository.clearManifestForRebuild(snapshot)));
        assertNull(currentManifest());
    }

    /** 损坏文本只允许完全相同的旧快照清空，不能将解析失败或 JSON 空值当成同一清单。 */
    @Test
    void rebuildRequiresExactTextForMalformedAndJsonNullManifests() {
        Work broken = savedWork(BROKEN_MANIFEST);
        jdbc.update("UPDATE wf_work SET video_frames_json=? WHERE id=1", BROKEN_MANIFEST + " ");
        assertNull(transaction.execute(status -> repository.clearManifestForRebuild(broken)));
        assertSummaryUnchanged();
        jdbc.update("UPDATE wf_work SET video_frames_json=? WHERE id=1", BROKEN_MANIFEST);
        assertNotNull(transaction.execute(status -> repository.clearManifestForRebuild(broken)));
        Work nullText = savedWork("null");
        jdbc.update("UPDATE wf_work SET video_frames_json=? WHERE id=1", " null ");
        assertNull(transaction.execute(status -> repository.clearManifestForRebuild(nullText)));
        assertEquals(" null ", currentManifest());
        assertSummaryUnchanged();
    }

    /** 空源摘要保持历史兼容，但扫描后变为非空摘要时清空守卫仍应拒绝。 */
    @Test
    void rebuildSupportsNullSourceDigestWithoutAcceptingALaterChangedDigest() {
        jdbc.update("UPDATE wf_work SET media_sha256=NULL WHERE id=1");
        Work snapshot = savedWork(OLD_MANIFEST);
        jdbc.update("UPDATE wf_work SET media_sha256='changed' WHERE id=1");
        assertNull(transaction.execute(status -> repository.clearManifestForRebuild(snapshot)));
        assertEquals(OLD_MANIFEST, currentManifest());
        jdbc.update("UPDATE wf_work SET media_sha256=NULL WHERE id=1");
        assertNotNull(transaction.execute(status -> repository.clearManifestForRebuild(snapshot)));
        assertNull(currentManifest());
    }

    /** 清空后新的审核结果先发布时，重建不能覆盖该并发结果。 */
    @Test
    void rebuiltManifestCannotOverwriteConcurrentPublicationAfterClear() {
        Work snapshot = savedWork(OLD_MANIFEST);
        Work cleared = transaction.execute(status -> repository.clearManifestForRebuild(snapshot));
        assertNotNull(cleared);
        jdbc.update("UPDATE wf_work SET video_frames_json=?,version=version+1 WHERE id=1", NEW_MANIFEST);
        assertEquals(Boolean.FALSE, transaction.execute(status -> repository.saveManifest(cleared, OLD_MANIFEST)));
        assertEquals(NEW_MANIFEST, currentManifest());
    }

    /** 清空后即使并发方忘记增加版本，原 NULL 清单守卫也会阻止覆盖。 */
    @Test
    void rebuiltManifestCannotOverwriteConcurrentManifestWithoutVersionIncrement() {
        Work snapshot = savedWork(OLD_MANIFEST);
        Work cleared = transaction.execute(status -> repository.clearManifestForRebuild(snapshot));
        assertNotNull(cleared);
        jdbc.update("UPDATE wf_work SET video_frames_json=? WHERE id=1", NEW_MANIFEST);
        assertEquals(Boolean.FALSE, transaction.execute(status -> repository.saveManifest(cleared, OLD_MANIFEST)));
        assertEquals(NEW_MANIFEST, currentManifest());
    }

    /** 数据库更新失败向上传递，不能伪造已清空快照并诱发后续 COS 删除。 */
    @Test
    void rebuildDatabaseFailureIsPropagatedWithoutClearingTheManifest() {
        Work snapshot = savedWork(OLD_MANIFEST);
        jdbc.execute("ALTER TABLE wf_work DROP COLUMN ai_media_summary_expires_at");
        assertThrows(DataAccessException.class,
                () -> transaction.execute(status -> repository.clearManifestForRebuild(snapshot)));
        assertEquals(OLD_MANIFEST, currentManifest());
        assertEquals(0, jdbc.queryForObject("SELECT version FROM wf_work WHERE id=1", Integer.class));
        assertEquals(OLD_SUMMARY, jdbc.queryForObject("SELECT ai_media_summary_json FROM wf_work WHERE id=1", String.class));
    }

    /** 构造当前清单及缓存，返回后续事务必须匹配的真实作品快照。 */
    private Work savedWork(String manifest) {
        jdbc.update("UPDATE wf_work SET video_frames_json=?,ai_media_summary_json=?,ai_media_summary_expires_at=? WHERE id=1",
                manifest, OLD_SUMMARY, CACHE_EXPIRY);
        return repository.scan(0, 1, 10).getFirst();
    }

    /** CAS 冲突不能清掉其它执行者仍使用的摘要与期限。 */
    private void assertSummaryUnchanged() {
        assertEquals(OLD_SUMMARY, jdbc.queryForObject("SELECT ai_media_summary_json FROM wf_work WHERE id=1", String.class));
        assertEquals(CACHE_EXPIRY, jdbc.queryForObject("SELECT ai_media_summary_expires_at FROM wf_work WHERE id=1", LocalDateTime.class));
    }

    /** 只读取作品上的当前完整清单。 */
    private String currentManifest() {
        return jdbc.queryForObject("SELECT video_frames_json FROM wf_work WHERE id=1", String.class);
    }
}
