package com.jxc.wefolio.job.repo;

import com.jxc.wefolio.job.model.VideoFrameBackfillModels.Work;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** H2 验证无任务表的固定水位、源及清单 CAS，不修改旧审核与客户端时长。 */
class VideoFrameBackfillRepositoryTest {
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
        assertEquals(Boolean.TRUE, transaction.execute(status -> repository.saveManifest(work, manifest)));
        assertEquals(manifest, currentManifest());
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

    /** 只读取作品上的当前完整清单。 */
    private String currentManifest() {
        return jdbc.queryForObject("SELECT video_frames_json FROM wf_work WHERE id=1", String.class);
    }
}
