package com.jxc.wefolio.job.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jxc.wefolio.job.dict.AuditResultDict;
import com.jxc.wefolio.job.dict.WorkAuditStatusDict;
import com.jxc.wefolio.job.entity.WorkAuditTaskEntity;
import com.jxc.wefolio.job.entity.WorkAuditWorkEntity;
import com.jxc.wefolio.job.mapper.WorkAuditTaskMapper;
import com.jxc.wefolio.job.mapper.WorkAuditWorkMapper;
import com.jxc.wefolio.job.repo.WorkAuditTaskRepository;
import com.jxc.wefolio.job.repo.WorkAuditWorkRepository;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.time.LocalDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** H2 等价 JSON 校验、生产媒体 CHECK 和真实 Spring 事务证明审核结果的原子写回。 */
class WorkAuditPersistentTransactionTest {
    /** 当前生产增量定义，测试直接消费 CHECK 表达式，避免另写约束后发生漂移。 */
    private static final Path VIDEO_MIGRATION = Path.of(
            "../wefolio-java-runtime/src/main/resources/db/migration/V62__persistent_video_frames_and_audit_mode.sql");
    /** 合法的最小 JSON 清单，不能再用生产 JSON 列无法接受的普通文本模拟成功。 */
    private static final String MANIFEST_JSON = "{\"schemaVersion\":\"video-frames-v1\",\"frames\":[]}";
    /** 待测真实 JDBC 查询器。 */
    private JdbcTemplate jdbc;
    /** 实际 MyBatis 作品仓储，仅在 CAS 与异常注入测试中使用 spy。 */
    private WorkAuditWorkRepository works;
    /** 带真实 Spring 事务拦截器的应用入口。 */
    private WorkAuditClaimTransactionService service;
    /** 当前领取任务，用于两行原子写回。 */
    private WorkAuditTaskEntity task;
    /** 安全的固定审核成功响应。 */
    private final TencentCiAuditResult result = new TencentCiAuditResult("ci-job", "Success",
            AuditResultDict.PASS, 0, "Normal", 0, true, false, "{}");

    /** 为每个用例构造独立数据库及实际事务代理，完全不访问外部数据库。 */
    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:video_atomic_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        // H2 原生 JSON 把 JDBC String 当作 JSON 字符串，并不解析其内容；CLOB + IS JSON
        // 保留生产 JDBC 绑定方式并验证 JSON 语法，也避免 VARCHAR(4096) 的人为上限。
        // 此替代只证明 JSON 接受/拒绝及事务语义，不模拟 MySQL 的二进制 JSON 存储与全部解析细节。
        jdbc.execute("""
                CREATE TABLE wf_work (
                  id BIGINT PRIMARY KEY, audit_round INT, audit_status VARCHAR(32),
                  media_type VARCHAR(16) NOT NULL,
                  media_object_key VARCHAR(512), media_sha256 VARCHAR(64), manual_audit_no VARCHAR(34),
                  audit_reason_code VARCHAR(32), audit_reason_codes CLOB CHECK(audit_reason_codes IS NULL OR audit_reason_codes IS JSON),
                  audit_reject_reason VARCHAR(512), video_frames_json CLOB, video_audio_text CLOB,
                  ai_media_summary_json CLOB, ai_media_summary_expires_at TIMESTAMP,
                  title VARCHAR(128), deleted BIGINT, version INT, updated_at TIMESTAMP,
                  CONSTRAINT chk_work_video_frames_json CHECK(video_frames_json IS NULL OR video_frames_json IS JSON)
                )
                """);
        jdbc.execute("""
                CREATE TABLE wf_work_audit_task (
                  id BIGINT PRIMARY KEY, work_id BIGINT, audit_round INT, media_type VARCHAR(32),
                  media_object_key VARCHAR(512), media_sha256 VARCHAR(64), snapshot_mode VARCHAR(32),
                  task_status VARCHAR(32), audit_result VARCHAR(32), locked_by VARCHAR(128), locked_until TIMESTAMP,
                  ci_job_id VARCHAR(128), ci_state VARCHAR(32), ci_result INT, ci_label VARCHAR(128), ci_score INT,
                  last_error_message VARCHAR(512), response_payload CLOB CHECK(response_payload IS NULL OR response_payload IS JSON),
                  finished_at TIMESTAMP,
                  deleted BIGINT, version INT, updated_at TIMESTAMP
                )
                """);
        String migration = Files.readString(VIDEO_MIGRATION);
        assertThat(migration).contains("`video_frames_json` JSON DEFAULT NULL");
        jdbc.execute("ALTER TABLE wf_work ADD " + productionCheck(migration, "chk_work_video_frames_media"));
        jdbc.execute("ALTER TABLE wf_work_audit_task ADD " + productionCheck(migration, "chk_work_audit_snapshot_mode"));
        jdbc.update("""
                INSERT INTO wf_work(id,audit_round,audit_status,media_type,media_object_key,title,deleted,version)
                VALUES (11,1,'AUDITING','VIDEO','OWNER/work/video/demo.mp4','用户中途编辑标题',0,0)
                """);
        jdbc.update("""
                INSERT INTO wf_work_audit_task(id,work_id,audit_round,media_type,media_object_key,snapshot_mode,
                  task_status,audit_result,locked_by,locked_until,deleted,version)
                VALUES (101,11,1,'VIDEO','OWNER/work/video/demo.mp4','AVERAGE','QUERYING','UNKNOWN','owner',?,0,0)
                """, LocalDateTime.now().plusMinutes(5));
        TableInfoHelper.remove(WorkAuditTaskEntity.class);
        TableInfoHelper.remove(WorkAuditWorkEntity.class);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), source));
        configuration.addMapper(WorkAuditTaskMapper.class);
        configuration.addMapper(WorkAuditWorkMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate session = new SqlSessionTemplate(factory);
        WorkAuditTaskRepository tasks = new WorkAuditTaskRepository(session.getMapper(WorkAuditTaskMapper.class));
        works = spy(new WorkAuditWorkRepository(session.getMapper(WorkAuditWorkMapper.class)));
        WorkAuditRiskTypeResolver risk = new WorkAuditRiskTypeResolver();
        WorkAuditClaimTransactionService target = new WorkAuditClaimTransactionService(works, tasks,
                risk, new WorkAuditRiskCollectionResolver(risk));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        service = (WorkAuditClaimTransactionService) proxy.getProxy();
        task = new WorkAuditTaskEntity(); task.setId(101L); task.setWorkId(11L); task.setAuditRound(1);
        task.setLockedBy("owner"); task.setCiJobId("ci-job"); task.setMediaObjectKey("OWNER/work/video/demo.mp4");
    }

    /** 三份结果一次提交，标题等普通作品字段不被旧快照覆盖。 */
    @Test
    void completedFramesAndAuditShouldCommitTogether() {
        jdbc.update("UPDATE wf_work SET video_audio_text='旧口播',ai_media_summary_json='旧摘要',ai_media_summary_expires_at=CURRENT_TIMESTAMP WHERE id=11");
        assertThat(service.completePersistentVideo(task, result, WorkAuditStatusDict.PASSED, null, MANIFEST_JSON))
                .isTrue();
        assertThat(value("wf_work", "audit_status", 11)).isEqualTo("PASSED");
        assertThat(value("wf_work", "video_frames_json", 11)).isEqualTo(MANIFEST_JSON);
        assertThat(value("wf_work", "video_audio_text", 11)).isNull();
        assertThat(value("wf_work", "ai_media_summary_json", 11)).isNull();
        assertThat(value("wf_work", "ai_media_summary_expires_at", 11)).isNull();
        assertThat(value("wf_work", "title", 11)).isEqualTo("用户中途编辑标题");
        assertThat(value("wf_work_audit_task", "task_status", 101)).isEqualTo("SUCCESS");
    }

    /** 工作行保存异常时，已经写出的任务 SUCCESS 也回滚，不残留半成功。 */
    @Test
    void databaseSaveFailureMustRollbackTaskSuccess() {
        doThrow(new DataIntegrityViolationException("注入数据库保存失败")).when(works)
                .updatePersistentVideoForRound(any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> service.completePersistentVideo(task, result, WorkAuditStatusDict.PASSED,
                null, MANIFEST_JSON)).isInstanceOf(DataIntegrityViolationException.class);
        assertUnchanged();
        reset(works);
        assertThat(service.failPersistentVideo(task, "MANIFEST_SAVE: 保存失败", "{}")).isTrue();
        assertThat(value("wf_work", "audit_status", 11)).isEqualTo("FAILED");
        assertThat(value("wf_work", "audit_reject_reason", 11)).contains("MANIFEST_SAVE");
        assertThat(value("wf_work_audit_task", "task_status", 101)).isEqualTo("FAILED");
    }

    /** 非法清单通过真实 JDBC 写回触发数据库约束，任务 SUCCESS 必须同步回滚。 */
    @Test
    void invalidManifestJsonMustRollbackTaskSuccess() {
        assertThatThrownBy(() -> service.completePersistentVideo(task, result, WorkAuditStatusDict.PASSED,
                null, "full-manifest")).isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("CHK_WORK_VIDEO_FRAMES_JSON");
        assertUnchanged();
    }

    /** 非视频作品禁止带持久帧，真实 CHECK 失败必须回滚已写出的任务终态。 */
    @Test
    void nonVideoManifestMustRollbackTaskSuccess() {
        jdbc.update("UPDATE wf_work SET media_type = 'IMAGE' WHERE id = 11");
        assertThatThrownBy(() -> service.completePersistentVideo(task, result, WorkAuditStatusDict.PASSED,
                null, MANIFEST_JSON)).isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("CHK_WORK_VIDEO_FRAMES_MEDIA");
        assertUnchanged();
    }

    /** 空清单保持兼容：非视频作品可以沿既有审核链提交，不触发媒体 CHECK。 */
    @Test
    void nonVideoWithoutManifestShouldCommit() {
        jdbc.update("UPDATE wf_work SET media_type = 'IMAGE' WHERE id = 11");
        assertThat(service.completePersistentVideo(task, result, WorkAuditStatusDict.PASSED, null, null)).isTrue();
        assertThat(value("wf_work", "video_frames_json", 11)).isNull();
        assertThat(value("wf_work", "audit_status", 11)).isEqualTo("PASSED");
        assertThat(value("wf_work_audit_task", "task_status", 101)).isEqualTo("SUCCESS");
    }

    /** JSON 列没有旧测试 VARCHAR(4096) 的任意长度限制，较大合法 JSON 清单应正常提交。 */
    @Test
    void largeValidManifestShouldCommitWithoutArtificialLengthLimit() {
        String manifest = "{\"frames\":[],\"sourceObjectKey\":\"" + "a".repeat(4096) + "\"}";
        assertThat(service.completePersistentVideo(task, result, WorkAuditStatusDict.PASSED, null, manifest)).isTrue();
        assertThat(value("wf_work", "video_frames_json", 11)).isEqualTo(manifest);
        assertThat(value("wf_work_audit_task", "task_status", 101)).isEqualTo("SUCCESS");
    }

    /** 第二个 CAS 失去写回权也必须回滚任务 SUCCESS；不得落失败覆盖新轮次。 */
    @Test
    void workCasLossMustRollbackTaskWrite() {
        doReturn(false).when(works).updatePersistentVideoForRound(any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> service.completePersistentVideo(task, result, WorkAuditStatusDict.PASSED,
                null, MANIFEST_JSON)).isInstanceOf(WorkAuditStaleClaimException.class);
        assertUnchanged();
    }

    /** 失败结果同样原子写回，不能出现任务失败而作品仍被错误推进。 */
    @Test
    void failureWorkCasLossMustRollbackTaskFailure() {
        doReturn(false).when(works).updatePersistentVideoForRound(any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> service.failPersistentVideo(task, "FRAME_UPLOAD: 上传失败", "{}"))
                .isInstanceOf(WorkAuditStaleClaimException.class);
        assertUnchanged();
    }

    /** 数据库拒绝清单或 CAS 失效后，两行状态、领取权和清单必须全部回滚。 */
    private void assertUnchanged() {
        assertThat(value("wf_work", "audit_status", 11)).isEqualTo("AUDITING");
        assertThat(value("wf_work", "video_frames_json", 11)).isNull();
        assertThat(value("wf_work", "version", 11)).isEqualTo("0");
        assertThat(value("wf_work_audit_task", "task_status", 101)).isEqualTo("QUERYING");
        assertThat(value("wf_work_audit_task", "locked_by", 101)).isEqualTo("owner");
        assertThat(value("wf_work_audit_task", "response_payload", 101)).isNull();
        assertThat(value("wf_work_audit_task", "version", 101)).isEqualTo("0");
    }

    /** 提取当前生产约束，只移除标识符引号；表达式本身由真实迁移提供。 */
    private String productionCheck(String migration, String constraint) {
        Matcher check = Pattern.compile("ADD (CONSTRAINT `" + Pattern.quote(constraint)
                + "` CHECK \\([^;]+\\))").matcher(migration);
        assertThat(check.find()).as("生产迁移应包含 %s", constraint).isTrue();
        return check.group(1).replace("`", "");
    }

    /** 表和字段名由测试常量调用，真实业务不使用动态 SQL。 */
    private String value(String table, String column, long id) {
        return jdbc.queryForObject("SELECT " + column + " FROM " + table + " WHERE id = ?", String.class, id);
    }
}
