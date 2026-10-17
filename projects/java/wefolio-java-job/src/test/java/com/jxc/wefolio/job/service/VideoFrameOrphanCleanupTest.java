package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.CosProperties;
import com.jxc.wefolio.job.config.VideoFrameConfig;
import com.jxc.wefolio.job.config.VideoFrameProperties;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.COSObjectSummary;
import com.qcloud.cos.model.ListObjectsRequest;
import com.qcloud.cos.model.ObjectListing;
import com.qcloud.cos.model.ObjectMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 进程硬中断后通过新实例与持久数据库恢复回收，保留活任务及已发布引用。 */
class VideoFrameOrphanCleanupTest {
    /** 固定审核任务路径，不混入原始截图或其它用户目录。 */
    private static final String PREFIX = "OWNER/others/video-frames/7/" + "a".repeat(64) + "/v1/audit-42/";
    /** 测试表名直接消费当前生产迁移的 JSON 列定义，不允许夹带虚构清单列。 */
    private static final Path VIDEO_MIGRATION = Path.of("../wefolio-java-runtime/src/main/resources/db/migration/V62__persistent_video_frames_and_audit_mode.sql");
    private static final Path FULL_SCHEMA = Path.of("../wefolio-java-runtime/full-schema.sql");

    /** 上传后硬中断遗留的半清单对象，在任务恢复终结后自动删除。 */
    @Test
    void terminalInterruptedAuditReclaimsUnpublishedFrameAfterRestart() {
        try (var context = context("FAILED", null)) {
            COSClient cos = context.getBean(COSClient.class);
            String key = PREFIX + "0001.jpg";
            oldObject(cos, key);
            context.getBean(VideoFrameSnapshotClient.class).cleanupExpiredOutputs(mock(VideoFrameBackfillLock.ExecutionLock.class));
            verify(cos).deleteObject("bucket-1", key);
            verify(cos, never()).createMediaJobs(any());
        }
    }

    /** 当前审核仍持有帧目录，即使云端对象很旧也不得删除。 */
    @Test
    void activeAuditRetainsItsFrames() {
        try (var context = context("QUERYING", null)) {
            COSClient cos = context.getBean(COSClient.class);
            oldObject(cos, PREFIX + "0001.jpg");
            context.getBean(VideoFrameSnapshotClient.class).cleanupExpiredOutputs(mock(VideoFrameBackfillLock.ExecutionLock.class));
            verify(cos, never()).deleteObject(anyString(), anyString());
        }
    }

    /** 成功事务已发布的清单必须保留，失败清理再次扫描也不能删有效资产。 */
    @Test
    void publishedFramePrefixIsRetained() {
        try (var context = context("SUCCESS", manifest(PREFIX))) {
            COSClient cos = context.getBean(COSClient.class);
            oldObject(cos, PREFIX + "0001.jpg");
            context.getBean(VideoFrameSnapshotClient.class).cleanupExpiredOutputs(mock(VideoFrameBackfillLock.ExecutionLock.class));
            verify(cos, never()).deleteObject(anyString(), anyString());
        }
    }

    /** 逻辑删除不等于缺失任务，仍对应有效作品的活任务目录必须保留。 */
    @Test
    void softDeletedActiveAuditIsStillProtected() {
        try (var context = context("QUERYING", null)) {
            context.getBean(JdbcTemplate.class).update("UPDATE wf_work_audit_task SET deleted = 42");
            COSClient cos = context.getBean(COSClient.class);
            oldObject(cos, PREFIX + "0001.jpg");
            context.getBean(VideoFrameSnapshotClient.class).cleanupExpiredOutputs(mock(VideoFrameBackfillLock.ExecutionLock.class));
            verify(cos, never()).deleteObject(anyString(), anyString());
        }
    }

    /** 旧租约已过期且作品不再允许该轮发布时，先持久终结旧任务再回收。 */
    @ParameterizedTest
    @ValueSource(strings = {"missing", "deleted", "round", "source", "manual"})
    void staleExpiredAuditIsRetiredWithoutOverwritingCurrentWork(String change) {
        try (var context = context("QUERYING", null)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            switch (change) {
                case "missing" -> jdbc.update("DELETE FROM wf_work WHERE id = 7");
                case "deleted" -> jdbc.update("UPDATE wf_work SET deleted = 7");
                case "round" -> jdbc.update("UPDATE wf_work SET audit_round = 2");
                case "source" -> jdbc.update("UPDATE wf_work SET media_object_key = 'OWNER/work/video/replacement.mp4'");
                case "manual" -> jdbc.update("UPDATE wf_work SET manual_audit_no = 'manual-review'");
                default -> throw new IllegalArgumentException("未知测试场景");
            }
            COSClient cos = context.getBean(COSClient.class);
            oldObject(cos, PREFIX + "0001.jpg");
            context.getBean(VideoFrameSnapshotClient.class).cleanupExpiredOutputs(mock(VideoFrameBackfillLock.ExecutionLock.class));
            verify(cos).deleteObject("bucket-1", PREFIX + "0001.jpg");
            assertEquals("FAILED", jdbc.queryForObject("SELECT task_status FROM wf_work_audit_task WHERE id = 42", String.class));
            if (!change.equals("missing")) {
                assertEquals("AUDITING", jdbc.queryForObject("SELECT audit_status FROM wf_work WHERE id = 7", String.class));
            }
        }
    }

    /** 旧任务仍持有未来租约时先保留，不能凭作品变化截断正在上传的处理者。 */
    @Test
    void staleAuditWithLiveLeaseRemainsProtected() {
        try (var context = context("QUERYING", null)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            jdbc.update("UPDATE wf_work SET audit_round = 2");
            jdbc.update("UPDATE wf_work_audit_task SET locked_until = DATEADD('HOUR', 1, CURRENT_TIMESTAMP)");
            COSClient cos = context.getBean(COSClient.class);
            oldObject(cos, PREFIX + "0001.jpg");
            context.getBean(VideoFrameSnapshotClient.class).cleanupExpiredOutputs(mock(VideoFrameBackfillLock.ExecutionLock.class));
            verify(cos, never()).deleteObject(anyString(), anyString());
        }
    }

    /** 清单损坏时无法证明安全，不以无法解析等价于没有引用。 */
    @ParameterizedTest
    @ValueSource(strings = {"scalar", "unknown-schema"})
    void unknownIdentityOrManifestIsRetained(String change) {
        try (var context = context("FAILED", null)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            jdbc.update("UPDATE wf_work SET video_frames_json = ?", change.equals("scalar") ? "\"broken\"" : "{}");
            COSClient cos = context.getBean(COSClient.class);
            oldObject(cos, PREFIX + "0001.jpg");
            context.getBean(VideoFrameSnapshotClient.class).cleanupExpiredOutputs(mock(VideoFrameBackfillLock.ExecutionLock.class));
            verify(cos, never()).deleteObject(anyString(), anyString());
        }
    }

    /** 数据库明确证明任务已不存在且没有作品引用时，任务历史删除后也不会永久残留。 */
    @Test
    void missingTaskWithoutPublishedReferencesIsReclaimed() {
        try (var context = context("FAILED", null)) {
            context.getBean(JdbcTemplate.class).update("DELETE FROM wf_work_audit_task");
            COSClient cos = context.getBean(COSClient.class);
            String key = PREFIX + "0001.jpg";
            oldObject(cos, key);
            context.getBean(VideoFrameSnapshotClient.class).cleanupExpiredOutputs(mock(VideoFrameBackfillLock.ExecutionLock.class));
            verify(cos).deleteObject("bucket-1", key);
        }
    }

    /** 等待供应商的旧任务没有领取租约，也需在作品换轮后安全持久终结。 */
    @Test
    void staleWaitingAuditWithoutWorkerLeaseIsRetired() {
        try (var context = context("RUNNING", null)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            jdbc.update("UPDATE wf_work SET audit_round = 2");
            jdbc.update("UPDATE wf_work_audit_task SET locked_until = NULL");
            COSClient cos = context.getBean(COSClient.class);
            String key = PREFIX + "0001.jpg";
            oldObject(cos, key);
            context.getBean(VideoFrameSnapshotClient.class).cleanupExpiredOutputs(mock(VideoFrameBackfillLock.ExecutionLock.class));
            verify(cos).deleteObject("bucket-1", key);
            assertEquals("FAILED", jdbc.queryForObject("SELECT task_status FROM wf_work_audit_task WHERE id = 42", String.class));
        }
    }

    /** 当前新版清单有独立目录时只回收旧目录，帧数组里的旧引用同样受保护。 */
    @Test
    void newerManifestAllowsOldPrefixCleanupButFrameReferencesProtectIt() {
        String replacement = PREFIX.replace("audit-42", "audit-43");
        try (var context = context("SUCCESS", manifest(replacement))) {
            COSClient cos = context.getBean(COSClient.class);
            String key = PREFIX + "0001.jpg";
            oldObject(cos, key);
            var snapshots = context.getBean(VideoFrameSnapshotClient.class);
            snapshots.cleanupExpiredOutputs(mock(VideoFrameBackfillLock.ExecutionLock.class));
            verify(cos).deleteObject("bucket-1", key);
            clearInvocations(cos);
            context.getBean(JdbcTemplate.class).update("UPDATE wf_work SET video_frames_json = ?",
                    "{\"objectPrefix\":\"" + replacement + "\",\"frames\":[{\"objectKey\":\"" + key + "\"}]}");
            snapshots.cleanupExpiredOutputs(mock(VideoFrameBackfillLock.ExecutionLock.class));
            verify(cos, never()).deleteObject(anyString(), anyString());
        }
    }

    /** JSON 中只有不完整空帧结构时保留，不能用缺字段结构证明没有发布引用。 */
    @Test
    void incompleteManifestRetainsAuditFrames() {
        String replacement = PREFIX.replace("audit-42", "audit-43");
        try (var context = context("SUCCESS", "{\"objectPrefix\":\"" + replacement + "\",\"frames\":[]}")) {
            COSClient cos = context.getBean(COSClient.class);
            oldObject(cos, PREFIX + "0001.jpg");
            context.getBean(VideoFrameSnapshotClient.class).cleanupExpiredOutputs(mock(VideoFrameBackfillLock.ExecutionLock.class));
            verify(cos, never()).deleteObject(anyString(), anyString());
        }
    }

    /** 完整生产 DDL、增量 migration 和实际仓储查询必须使用同一清单列。 */
    @Test
    void cleanupUsesProductionVideoFramesJsonColumn() throws Exception {
        String column = productionVideoColumn();
        String schema = Files.readString(FULL_SCHEMA);
        Matcher work = Pattern.compile("CREATE TABLE `wf_work` \\([\\s\\S]*?\\n\\) ENGINE").matcher(schema);
        assertTrue(work.find());
        assertTrue(work.group().contains("`" + column + "` json DEFAULT NULL"));
        assertFalse(work.group().contains("snapshot_frames"));
        try (var context = context("FAILED", manifest(PREFIX))) {
            COSClient cos = context.getBean(COSClient.class);
            oldObject(cos, PREFIX + "0001.jpg");
            context.getBean(VideoFrameSnapshotClient.class).cleanupExpiredOutputs(mock(VideoFrameBackfillLock.ExecutionLock.class));
            verify(cos, never()).deleteObject(anyString(), anyString());
        }
    }

    /** 对象删除瞬态失败时不推进该候选游标，下轮仍能恢复清理。 */
    @Test
    void failedCosDeleteRetriesCandidateOnNextPass() {
        try (var context = context("FAILED", null)) {
            COSClient cos = context.getBean(COSClient.class);
            String key = PREFIX + "0001.jpg";
            oldObject(cos, key);
            doThrow(new com.qcloud.cos.exception.CosClientException("timeout")).doNothing().when(cos).deleteObject("bucket-1", key);
            var snapshots = context.getBean(VideoFrameSnapshotClient.class);
            var lock = mock(VideoFrameBackfillLock.ExecutionLock.class);
            assertThrows(RuntimeException.class, () -> snapshots.cleanupExpiredOutputs(lock));
            snapshots.cleanupExpiredOutputs(lock);
            verify(cos, times(2)).deleteObject("bucket-1", key);
            ArgumentCaptor<ListObjectsRequest> pages = ArgumentCaptor.forClass(ListObjectsRequest.class);
            verify(cos, times(2)).listObjects(pages.capture());
            assertTrue(pages.getAllValues().stream().allMatch(page -> page.getMarker() == null));
        }
    }

    /** 数据库不可读时保留对象并中断本页，恢复数据库后从未完成候选继续。 */
    @Test
    void unreadableDatabaseKeepsFramesUntilNextPass() {
        try (var context = context("FAILED", null)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            jdbc.execute("ALTER TABLE wf_work RENAME TO saved_work");
            COSClient cos = context.getBean(COSClient.class);
            String key = PREFIX + "0001.jpg";
            oldObject(cos, key);
            var snapshots = context.getBean(VideoFrameSnapshotClient.class);
            var lock = mock(VideoFrameBackfillLock.ExecutionLock.class);
            assertThrows(RuntimeException.class, () -> snapshots.cleanupExpiredOutputs(lock));
            verify(cos, never()).deleteObject(anyString(), anyString());
            jdbc.execute("ALTER TABLE saved_work RENAME TO wf_work");
            snapshots.cleanupExpiredOutputs(lock);
            verify(cos).deleteObject("bucket-1", key);
        }
    }

    /** 历史规范化过程中硬中断的对象也经同一年龄与引用保护恢复回收。 */
    @Test
    void unpublishedHistoryNormalizedFramesAreReclaimed() {
        try (var context = context("FAILED", null)) {
            COSClient cos = context.getBean(COSClient.class);
            String key = PREFIX.replace("audit-42", "history-00000000-0000-0000-0000-000000000001") + "0001.jpg";
            oldObject(cos, key);
            context.getBean(VideoFrameSnapshotClient.class).cleanupExpiredOutputs(mock(VideoFrameBackfillLock.ExecutionLock.class));
            verify(cos).deleteObject("bucket-1", key);
        }
    }

    /** 仅加载清扫及回收仓储，真实 H2 在不同上下文间隔离。 */
    private AnnotationConfigApplicationContext context(String state, String manifest) {
        var context = new AnnotationConfigApplicationContext(TestConfiguration.class);
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        jdbc.execute("CREATE TABLE wf_work_audit_task (id BIGINT PRIMARY KEY, work_id BIGINT, task_status VARCHAR(32), media_object_key VARCHAR(512), snapshot_source_version VARCHAR(64), snapshot_policy_version VARCHAR(96), audit_round INT DEFAULT 1, media_sha256 VARCHAR(64), locked_until TIMESTAMP DEFAULT DATEADD('HOUR', -1, CURRENT_TIMESTAMP), locked_by VARCHAR(64), finished_at TIMESTAMP, updated_at TIMESTAMP, version INT DEFAULT 0, deleted BIGINT DEFAULT 0, last_error_message VARCHAR(512))");
        // H2 的原生 JSON 会把 JDBC String 编码为 JSON 字符串；CLOB + IS JSON 保持生产绑定与语法约束。
        String column = productionVideoColumn();
        jdbc.execute("CREATE TABLE wf_work (id BIGINT PRIMARY KEY, " + column + " CLOB CHECK(" + column + " IS NULL OR " + column + " IS JSON), deleted BIGINT DEFAULT 0, audit_round INT DEFAULT 1, audit_status VARCHAR(32) DEFAULT 'AUDITING', manual_audit_no VARCHAR(64), media_object_key VARCHAR(512) DEFAULT 'OWNER/work/video/a.mp4', media_sha256 VARCHAR(64))");
        jdbc.update("INSERT INTO wf_work_audit_task (id, work_id, task_status, media_object_key, snapshot_source_version, snapshot_policy_version) VALUES (42, 7, ?, 'OWNER/work/video/a.mp4', ?, 'v1')", state, "a".repeat(64));
        jdbc.update("INSERT INTO wf_work (id, video_frames_json) VALUES (7, ?)", manifest);
        return context;
    }

    /** 从真实迁移中提取新增视频清单列，夹具不自行重命名生产字段。 */
    private String productionVideoColumn() {
        try {
            Matcher column = Pattern.compile("ADD COLUMN `([^`]+)` JSON DEFAULT NULL").matcher(Files.readString(VIDEO_MIGRATION));
            assertTrue(column.find());
            return column.group(1);
        } catch (java.io.IOException exception) { throw new java.io.UncheckedIOException(exception); }
    }

    /** 构造有真实数量和各帧对象键的当前清单，避免不完整样例产生虚假回收证明。 */
    private String manifest(String prefix) {
        JSONObject manifest = new JSONObject();
        manifest.put("schemaVersion", VideoFramePolicy.SCHEMA_VERSION);
        manifest.put("objectPrefix", prefix);
        manifest.put("frameCount", 8);
        JSONArray frames = new JSONArray();
        for (int index = 1; index <= 8; index++) {
            JSONObject frame = new JSONObject();
            frame.put("objectKey", prefix + String.format(java.util.Locale.ROOT, "%04d.jpg", index));
            frames.add(frame);
        }
        manifest.put("frames", frames);
        return JSON.toJSONString(manifest);
    }

    /** 远端对象和二次 HEAD 均使用确定的过期修改时间。 */
    private void oldObject(COSClient cos, String key) {
        Date modified = Date.from(Instant.now().minus(Duration.ofHours(25)));
        COSObjectSummary summary = new COSObjectSummary(); summary.setKey(key); summary.setLastModified(modified);
        ObjectListing listing = new ObjectListing(); listing.getObjectSummaries().add(summary);
        when(cos.listObjects(any(ListObjectsRequest.class))).thenReturn(listing);
        ObjectMetadata metadata = new ObjectMetadata(); metadata.setLastModified(modified);
        when(cos.getObjectMetadata("bucket-1", key)).thenReturn(metadata);
    }

    /** 候选仓储存在后自动注册，旧实现仍可运行同一个真实回收路径反馈循环。 */
    @Configuration
    @ComponentScan(basePackages = "com.jxc.wefolio.job", useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = ".*\\.(VideoFrameSnapshotClient|VideoFrameOrphanRepository)"))
    static class TestConfiguration {
        /** 无真实网络访问的专用存储客户端。 */
        @Bean(name = VideoFrameConfig.COS_CLIENT_NAME)
        COSClient cos() { return mock(COSClient.class); }
        /** 测试桶不含凭证。 */
        @Bean
        CosProperties cosProperties() { var cos = new CosProperties(); cos.setBucketName("bucket-1"); return cos; }
        /** 保留生产单调用预算。 */
        @Bean
        VideoFrameProperties frames() { return new VideoFrameProperties(); }
        /** 每次模拟重启都创建独立数据库。 */
        @Bean(destroyMethod = "shutdown")
        DataSource database() { return new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build(); }
        /** 使用真实查询验证当前任务状态和作品清单。 */
        @Bean
        JdbcTemplate jdbc(DataSource database) { return new JdbcTemplate(database); }
    }
}
