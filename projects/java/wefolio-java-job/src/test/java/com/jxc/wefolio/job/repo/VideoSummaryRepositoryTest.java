package com.jxc.wefolio.job.repo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 本地真实 SQL 验证原视频摘要 CAS，不访问线上数据库。 */
class VideoSummaryRepositoryTest {
    /** 上传端生成的明确默认名称。 */
    private static final String DEFAULT_TITLE = "视频 2026-10-19 12:00:00 01";
    /** 每次独立数据库及真实短事务。 */
    private JdbcTemplate jdbc;
    private VideoSummaryRepository repository;
    private TransactionTemplate transaction;

    /** 最小结构包含全部保护字段与其它派生内容。 */
    @BeforeEach void setup() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:video-summary-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE wf_user(id bigint primary key,unique_code varchar(32),status varchar(32),deleted int default 0)");
        jdbc.execute("""
                CREATE TABLE wf_work(id bigint primary key,user_id bigint,media_type varchar(32),status varchar(32),
                  title varchar(30),media_object_key varchar(255),media_sha256 varchar(64),audit_round int,version int default 0,
                  duration_ms int,audit_status varchar(32),deleted int default 0,video_summary_json varchar(4000),
                  video_frames_json varchar(4000),video_audio_text clob,ai_media_summary_json varchar(4000),updated_at timestamp)
                """);
        jdbc.update("INSERT INTO wf_user(id,unique_code,status) VALUES(1,'OWNER','ACTIVE')");
        jdbc.update("""
                INSERT INTO wf_work(id,user_id,media_type,status,media_object_key,media_sha256,audit_round,audit_status,
                  duration_ms,video_frames_json,video_audio_text,ai_media_summary_json)
                VALUES(1,1,'VIDEO','ACTIVE','OWNER/work/video/a.mp4','sha',2,'PASSED',84613,'旧帧','口播','旧帧摘要')
                """);
        jdbc.update("UPDATE wf_work SET title=? WHERE id=1", DEFAULT_TITLE);
        var manager = new DataSourceTransactionManager(dataSource);
        var proxy = new ProxyFactory(new VideoSummaryRepository(jdbc));
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        repository = (VideoSummaryRepository) proxy.getProxy();
        transaction = new TransactionTemplate(manager);
    }

    /** 只发布新摘要，不清帧、音频或帧摘要缓存，防止额外重复付费。 */
    @Test void writesOnlyDirectSummaryAndVersion() {
        var snapshot = repository.find(1);
        assertNull(snapshot.summary()); assertEquals(84613, snapshot.durationMs());
        assertTrue(Boolean.TRUE.equals(transaction.execute(status -> repository.save(snapshot, "{\"content\":\"摘要\"}"))));
        assertEquals(1, repository.find(1).version());
        assertEquals("{\"content\":\"摘要\"}", repository.find(1).summary());
        assertEquals("旧帧", jdbc.queryForObject("SELECT video_frames_json FROM wf_work WHERE id=1", String.class));
        assertEquals("口播", jdbc.queryForObject("SELECT video_audio_text FROM wf_work WHERE id=1", String.class));
        assertEquals("旧帧摘要", jdbc.queryForObject("SELECT ai_media_summary_json FROM wf_work WHERE id=1", String.class));
        assertFalse(repository.find(1).toString().contains("摘要"));
    }

    /** MySQL 的 INT UNSIGNED 默认以 Long 返回，快照仍应读取合法的视频毫秒数。 */
    @Test void readsDurationWhenJdbcReturnsLong() {
        // H2 的普通 INT 返回 Integer，改用 BIGINT 复现生产驱动的 Long 返回值。
        jdbc.execute("ALTER TABLE wf_work ALTER COLUMN duration_ms BIGINT");
        Object raw = jdbc.queryForObject("SELECT duration_ms FROM wf_work WHERE id=1", (rs, row) -> rs.getObject(1));
        assertInstanceOf(Long.class, raw);
        assertEquals(84613, repository.find(1).durationMs());
    }

    /** 指定类型读取仍保留缺失时长，交由摘要准入拒绝，不能隐式改为零。 */
    @Test void preservesNullDuration() {
        jdbc.update("UPDATE wf_work SET duration_ms=NULL WHERE id=1");
        assertNull(repository.find(1).durationMs());
    }

    /** 超出应用整数范围时明确拒绝，不能将无符号时长静默截断成可收费输入。 */
    @Test void rejectsDurationOutsideIntegerRange() {
        jdbc.execute("ALTER TABLE wf_work ALTER COLUMN duration_ms BIGINT");
        jdbc.update("UPDATE wf_work SET duration_ms=? WHERE id=1", (long) Integer.MAX_VALUE + 1L);
        assertThrows(DataAccessException.class, () -> repository.find(1));
    }

    /** 任何身份或审核状态变化均拒绝晚到写入。 */
    @Test void eachStaleDimensionRejectsSave() {
        for (String change : new String[]{"version=1", "audit_round=3", "media_sha256='changed'",
                "media_object_key='OWNER/work/video/b.mp4'", "user_id=2", "deleted=1", "status='DISABLED'",
                "audit_status='REVIEW'", "media_type='IMAGE'"}) {
            var snapshot = repository.find(1);
            transaction.executeWithoutResult(status -> {
                jdbc.update("UPDATE wf_work SET " + change + " WHERE id=1");
                assertFalse(repository.save(snapshot, "不能写入"), change); status.setRollbackOnly();
            });
        }
        assertNull(repository.find(1).summary());
    }

    /** 所有者权限和路径归属在读写两侧均检查。 */
    @Test void revokedOwnerCannotBeConsumedOrWritten() {
        var snapshot = repository.find(1);
        jdbc.update("UPDATE wf_user SET status='DISABLED' WHERE id=1");
        assertNull(repository.find(1)); assertFalse(repository.save(snapshot, "拒绝"));
        jdbc.update("UPDATE wf_user SET status='ACTIVE',unique_code='OTHER' WHERE id=1");
        assertNull(repository.find(1)); assertFalse(repository.save(snapshot, "拒绝"));
        jdbc.update("UPDATE wf_user SET unique_code='OWNER' WHERE id=1");
        jdbc.update("UPDATE wf_work SET media_object_key='OWNER/work/video/../secret.mp4' WHERE id=1");
        assertNull(repository.find(1));
    }

    /** 源 SHA 缺失沿既有空值语义安全匹配，不假定无SHA即可忽略其它身份。 */
    @Test void nullShaIsSupportedAndFrameChangesDoNotChangeSourceIdentity() {
        jdbc.update("UPDATE wf_work SET media_sha256=NULL WHERE id=1");
        var snapshot = repository.find(1);
        jdbc.update("UPDATE wf_work SET video_frames_json='新帧',video_audio_text='新转写' WHERE id=1");
        assertTrue(repository.save(snapshot, "同源摘要"));
    }

    /** 名称查询必须使用已提交摘要后的最新版本，改名仅触及名称和更新时间、版本。 */
    @Test void readsLatestTitleSnapshotAndRenamesWithoutChangingSummary() {
        var beforeSummary = repository.find(1);
        assertTrue(repository.save(beforeSummary, "已提交摘要"));
        var latest = repository.findForTitle(1);
        assertEquals(DEFAULT_TITLE, latest.title());
        assertEquals(1, latest.source().version());
        assertEquals("已提交摘要", latest.source().summary());
        assertTrue(beforeSummary.sameSource(latest.source()));

        assertTrue(repository.renameDefaultTitle(latest, "花间有约"));

        var renamed = repository.findForTitle(1);
        assertEquals("花间有约", renamed.title());
        assertEquals(2, renamed.source().version());
        assertEquals("已提交摘要", renamed.source().summary());
        assertTrue(latest.source().sameSource(renamed.source()));
        assertNotNull(jdbc.queryForObject("SELECT updated_at FROM wf_work WHERE id=1", java.sql.Timestamp.class));
        assertEquals("旧帧", jdbc.queryForObject("SELECT video_frames_json FROM wf_work WHERE id=1", String.class));
        assertEquals("口播", jdbc.queryForObject("SELECT video_audio_text FROM wf_work WHERE id=1", String.class));
        assertEquals("旧帧摘要", jdbc.queryForObject("SELECT ai_media_summary_json FROM wf_work WHERE id=1", String.class));
        assertFalse(renamed.toString().contains("花间有约"));
        assertFalse(renamed.toString().contains("已提交摘要"));
    }

    /** 即使其它写入漏加版本，完整名称二进制比较也不能覆盖不同尾字或尾随空格。 */
    @Test void strictTitleComparisonRejectsSamePrefixAndTrailingSpaceChanges() {
        for (String changedTitle : new String[]{"视频 2026-10-19 12:00:00 02", DEFAULT_TITLE + " ",
                "视频 2026-10-19 12:00:00 01\u0000"}) {
            var latest = repository.findForTitle(1);
            transaction.executeWithoutResult(status -> {
                jdbc.update("UPDATE wf_work SET title=? WHERE id=1", changedTitle);
                assertFalse(repository.renameDefaultTitle(latest, "花间有约"), changedTitle);
                assertEquals(changedTitle, repository.findForTitle(1).title());
                status.setRollbackOnly();
            });
        }
    }

    /** 修改版本、来源、审核或可见状态后，晚到的名称都不能覆盖最新作品。 */
    @Test void eachStaleDimensionRejectsRename() {
        assertTrue(repository.save(repository.find(1), "已提交摘要"));
        for (String change : new String[]{"version=2", "audit_round=3", "media_sha256='changed'",
                "media_object_key='OWNER/work/video/b.mp4'", "user_id=2", "deleted=1", "status='DISABLED'",
                "audit_status='REVIEW'", "media_type='IMAGE'"}) {
            var latest = repository.findForTitle(1);
            transaction.executeWithoutResult(status -> {
                jdbc.update("UPDATE wf_work SET " + change + " WHERE id=1");
                assertFalse(repository.renameDefaultTitle(latest, "不能写入"), change);
                status.setRollbackOnly();
            });
        }
        assertEquals(DEFAULT_TITLE, repository.findForTitle(1).title());
        assertEquals("已提交摘要", repository.find(1).summary());
    }

    /** 名称读取和写入均检查作者资格，读取也拒绝越权源路径。 */
    @Test void titleSnapshotAndRenameRespectOwnerAndSourceAccess() {
        var latest = repository.findForTitle(1);
        for (String change : new String[]{"status='DISABLED'", "deleted=1", "unique_code='OTHER'"}) {
            transaction.executeWithoutResult(status -> {
                jdbc.update("UPDATE wf_user SET " + change + " WHERE id=1");
                assertNull(repository.findForTitle(1));
                assertFalse(repository.renameDefaultTitle(latest, "拒绝"), change);
                status.setRollbackOnly();
            });
        }
        for (String objectKey : new String[]{"OTHER/work/video/a.mp4", "OWNER/work/video/../secret.mp4",
                "OWNER/work/video/sub/a.mp4"}) {
            jdbc.update("UPDATE wf_work SET media_object_key=? WHERE id=1", objectKey);
            assertNull(repository.findForTitle(1));
        }
    }

    /** 名称读取只消费仍有效且审核通过的视频作品。 */
    @Test void titleSnapshotRejectsInvalidWorkStates() {
        for (String change : new String[]{"deleted=1", "status='DISABLED'", "audit_status='REVIEW'", "media_type='IMAGE'"}) {
            transaction.executeWithoutResult(status -> {
                jdbc.update("UPDATE wf_work SET " + change + " WHERE id=1");
                assertNull(repository.findForTitle(1), change);
                status.setRollbackOnly();
            });
        }
        assertNull(repository.findForTitle(999));
    }

    /** 名称更新保持源 SHA 的空值语义。 */
    @Test void renamesWhenSourceShaIsNull() {
        jdbc.update("UPDATE wf_work SET media_sha256=NULL WHERE id=1");
        assertTrue(repository.renameDefaultTitle(repository.findForTitle(1), "灯火为序"));
        assertEquals("灯火为序", repository.findForTitle(1).title());
    }

    /** 真实事务代理先提交摘要；随后名称 SQL 异常不能回滚已经提交的摘要。 */
    @Test void renameTransactionFailureLeavesCommittedSummaryUntouched() {
        assertTrue(repository.save(repository.find(1), "已提交摘要"));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        var latest = repository.findForTitle(1);
        jdbc.execute("ALTER TABLE wf_work ADD CONSTRAINT reject_suggested_title CHECK (title <> '花间有约')");

        assertThrows(DataAccessException.class, () -> repository.renameDefaultTitle(latest, "花间有约"));

        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals("已提交摘要", repository.find(1).summary());
        assertEquals(DEFAULT_TITLE, repository.findForTitle(1).title());
        assertEquals(1, repository.find(1).version());
    }
}
