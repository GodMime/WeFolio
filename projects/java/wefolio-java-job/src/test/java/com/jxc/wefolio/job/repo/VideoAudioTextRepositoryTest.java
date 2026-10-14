package com.jxc.wefolio.job.repo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** H2 校验转写专用快照、来源/版本/权限 CAS 和缓存失效的原子性。 */
class VideoAudioTextRepositoryTest {
    /** 在本次测试专属 H2 数据库中建立和检查夹具。 */
    private JdbcTemplate jdbc;
    /** 验证完整生产 SQL 的转写专用仓储。 */
    private VideoAudioTextRepository repository;
    /** 用真实本地事务验证失败回滚和短事务条件写入。 */
    private TransactionTemplate transaction;

    /** 只创建本地最小真实查询结构，不访问任何线上数据库。 */
    @BeforeEach void setup() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:audio-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE wf_user(id bigint primary key,unique_code varchar(32),status varchar(32),deleted int default 0)");
        jdbc.execute("""
                CREATE TABLE wf_work(id bigint primary key,user_id bigint,media_type varchar(32),status varchar(32),
                  media_object_key varchar(255),media_sha256 varchar(64),audit_round int,version int default 0,
                  audit_status varchar(32),deleted int default 0,video_frames_json varchar(4000),
                  video_audio_text clob,ai_media_summary_json varchar(4000),ai_media_summary_expires_at timestamp,updated_at timestamp)
                """);
        jdbc.update("INSERT INTO wf_user(id,unique_code,status) VALUES(1,'OWNER','ACTIVE')");
        jdbc.update("""
                INSERT INTO wf_work(id,user_id,media_type,status,media_object_key,media_sha256,audit_round,audit_status,
                  video_frames_json,ai_media_summary_json,ai_media_summary_expires_at)
                VALUES(1,1,'VIDEO','ACTIVE','OWNER/work/video/a.mp4','sha',2,'PASSED','{"frames":1}','旧摘要',CURRENT_TIMESTAMP)
                """);
        repository = new VideoAudioTextRepository(jdbc);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /** 成功同事务清摘要并递增版本，空文本作为已完成状态保留。 */
    @Test void writesCompleteTextAndClearsSummaryAtomically() {
        var snapshot = repository.find(1);
        assertNull(snapshot.text());
        assertTrue(Boolean.TRUE.equals(transaction.execute(status -> repository.save(snapshot, "完整口播\n第二句"))));
        assertEquals("完整口播\n第二句", repository.find(1).text());
        assertEquals(1, repository.find(1).version());
        assertNull(jdbc.queryForObject("SELECT ai_media_summary_json FROM wf_work WHERE id=1", String.class));
        assertNull(jdbc.queryForObject("SELECT ai_media_summary_expires_at FROM wf_work WHERE id=1", Object.class));
        assertTrue(Boolean.TRUE.equals(transaction.execute(status -> repository.save(repository.find(1), ""))));
        assertEquals("", repository.find(1).text());
    }

    /** 普通编辑、源替换、审核轮次变化、帧变化与删除都阻止旧结果覆盖。 */
    @Test void eachStaleDimensionIndependentlyRejectsWrite() {
        for (String change : new String[]{"version=version+1", "media_sha256='new-sha'", "audit_round=3",
                "video_frames_json='{}'", "media_object_key='OWNER/work/video/b.mp4'", "deleted=1", "audit_status='REVIEW'"}) {
            var snapshot = repository.find(1);
            transaction.executeWithoutResult(status -> {
                jdbc.update("UPDATE wf_work SET " + change + " WHERE id=1");
                assertFalse(repository.save(snapshot, "旧转写")); status.setRollbackOnly();
            });
        }
        assertNull(repository.find(1).text());
    }

    /** 用户停用与目录归属是读写双方的权限条件，不只在最初预检一次。 */
    @Test void ownerStatusAndDirectoryCannotBeBypassed() {
        var snapshot = repository.find(1);
        jdbc.update("UPDATE wf_user SET status='DISABLED' WHERE id=1");
        assertNull(repository.find(1)); assertFalse(repository.save(snapshot, "不能写入"));
        jdbc.update("UPDATE wf_user SET status='ACTIVE' WHERE id=1");
        jdbc.update("UPDATE wf_work SET media_object_key='OTHER/work/video/a.mp4' WHERE id=1");
        assertNull(repository.find(1));
    }

    /** JSON 空白格式变化不误判源变化；快照日志不含文字。 */
    @Test void equivalentManifestFormattingIsAcceptedAndSnapshotToStringIsSafe() {
        var snapshot = repository.find(1);
        jdbc.update("UPDATE wf_work SET video_frames_json='{ \"frames\" : 1 }' WHERE id=1");
        assertTrue(repository.save(snapshot, "不要出现在日志中的全文"));
        assertFalse(repository.find(1).toString().contains("不要出现在日志中的全文"));
    }
}
