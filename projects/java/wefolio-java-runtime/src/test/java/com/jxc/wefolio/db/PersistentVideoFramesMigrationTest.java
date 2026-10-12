package com.jxc.wefolio.db;

import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.entity.AiGenerationTaskEntity;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 视频持久帧结构与部署窗口兼容约束，不执行真实数据库迁移。 */
class PersistentVideoFramesMigrationTest {
    /** 比较时仅归一 SQL 关键字，字符串、带引号标识符和字符集名称仍逐字检查。 */
    private static final Set<String> SQL_KEYWORDS = Set.of(
            "create", "table", "bigint", "unsigned", "not", "null", "auto_increment", "comment",
            "datetime", "default", "current_timestamp", "on", "update", "int", "varchar", "character",
            "set", "collate", "tinyint", "primary", "key", "unique", "constraint", "check", "in", "is",
            "or", "and", "between", "engine", "charset", "row_format", "json", "char", "date");
    /** 先完整识别带引号内容，避免把状态值、注释或标识符误当成 SQL 关键字。 */
    private static final Pattern SQL_TOKENS = Pattern.compile(
            "'(?:''|\\\\.|[^'\\\\])*'|\"(?:\"\"|\\\\.|[^\"\\\\])*\"|`(?:``|[^`])*`|[A-Za-z_][A-Za-z0-9_]*");

    /** 原任务采用默认 INTERVAL，不改已落库间隔、次数或审核轮次。 */
    @Test void retainsOldTaskSnapshotParametersAndOnlyAddsNullableAssets() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V62__persistent_video_frames_and_audit_mode.sql"));
        assertThat(sql).contains("DEFAULT 'INTERVAL'", "`snapshot_duration_ms` INT UNSIGNED DEFAULT NULL",
                "`video_frames_json` JSON DEFAULT NULL", "'INTERVAL', 'AVERAGE'");
        assertThat(sql).doesNotContain("UPDATE ", "DELETE ", "DROP ", "MODIFY ", "video_frames_status", "wf_video_frame_task");
    }

    /** 两个既有表只增加必要字段，并将全量定义同步到增量迁移后的目标结构。 */
    @Test void matchesFullSchemaAndRuntimeWorkMapping() throws Exception {
        String schema = Files.readString(Path.of("full-schema.sql"));
        String work = table(schema, "wf_work");
        String audit = table(schema, "wf_work_audit_task");
        assertThat(work).contains("`video_frames_json` json DEFAULT NULL", "chk_work_video_frames_media");
        assertThat(audit).contains("`snapshot_mode` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'INTERVAL'",
                "`snapshot_interval_seconds`", "`snapshot_count`", "`snapshot_duration_ms`", "`snapshot_policy_version`",
                "`snapshot_source_version`", "chk_work_audit_snapshot_mode");
        assertThat(WorkEntity.class.getDeclaredField("videoFramesJson").getType()).isEqualTo(String.class);
    }

    /** 历史补帧仅执行一次性任务并记录日志，不新增批次表、明细表或作品处理状态字段。 */
    @Test void doesNotCreateHistoricalBackfillTaskTables() throws Exception {
        String schema = Files.readString(Path.of("full-schema.sql"));
        assertThat(schema).doesNotContain("wf_video_frame_backfill_", "wf_video_frame_task", "video_frames_status",
                "`high_watermark_id`", "`charged_frames`");
        Path migrationDirectory = Path.of("src/main/resources/db/migration");
        assertThat(Files.exists(migrationDirectory.resolve("V63__one_time_video_frame_backfill.sql"))).isFalse();
        try (var files = Files.list(migrationDirectory)) {
            for (Path migration : files.filter(path -> path.toString().endsWith(".sql")).toList()) {
                assertThat(Files.readString(migration)).as("迁移 %s 不应创建历史补帧任务结构", migration.getFileName())
                        .doesNotContain("wf_video_frame_backfill_", "wf_video_frame_task", "video_frames_status");
            }
        }
    }

    /** 摘要和预算分别复用作品、任务表，可空缓存与零默认值兼容旧文字任务，不新建表。 */
    @Test void matchesMediaBudgetAndCacheSchema() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V63__portfolio_media_fields_on_existing_tables.sql"));
        String schema = Files.readString(Path.of("full-schema.sql"));
        String work = table(schema, "wf_work");
        String task = table(schema, "wf_ai_generation_task");
        assertAddedColumnMatches(work, sql, "ai_media_summary_json");
        assertAddedColumnMatches(work, sql, "ai_media_summary_expires_at");
        assertAddedColumnMatches(task, sql, "media_budget_json");
        assertAddedColumnMatches(task, sql, "media_budget_unknown_count");
        assertThat(sql).contains("ALTER TABLE `wf_work`", "ALTER TABLE `wf_ai_generation_task`",
                "`ai_media_summary_json` JSON DEFAULT NULL", "`ai_media_summary_expires_at` DATETIME(3) DEFAULT NULL",
                "`media_budget_json` JSON DEFAULT NULL", "`media_budget_unknown_count` INT UNSIGNED NOT NULL DEFAULT 0",
                "ADD KEY `idx_work_ai_media_summary_expiry` (`ai_media_summary_expires_at`,`id`)");
        assertThat(work).contains("KEY `idx_work_ai_media_summary_expiry` (`ai_media_summary_expires_at`,`id`)");
        assertThat(schema).doesNotContain("wf_ai_media_summary_cache", "wf_ai_media_budget_reservation");
        assertThat(Files.exists(Path.of("src/main/resources/db/migration/V64__portfolio_multimodal_budget_and_cache.sql"))).isFalse();
        assertThat(Files.exists(Path.of("src/main/resources/db/migration/V65__portfolio_media_fields_on_existing_tables.sql"))).isFalse();
        assertThat(sql).doesNotContain("micros", "FLOAT", "DOUBLE");
        assertThat(sql).doesNotContainPattern("(?m)^\\s*(CREATE|UPDATE|DELETE|DROP)\\s+");
        assertThat(WorkEntity.class.getDeclaredField("aiMediaSummaryJson").getType()).isEqualTo(String.class);
        assertThat(WorkEntity.class.getDeclaredField("aiMediaSummaryExpiresAt").getType()).isEqualTo(LocalDateTime.class);
        assertThat(AiGenerationTaskEntity.class.getDeclaredField("mediaBudgetJson").getType()).isEqualTo(String.class);
        assertThat(AiGenerationTaskEntity.class.getDeclaredField("mediaBudgetUnknownCount").getType()).isEqualTo(Integer.class);
    }

    /** 普通业务实体更新不得带回旧缓存或预算；这些字段由 Job 显式 SQL 维护。 */
    @Test void runtimeGenericUpdatesExcludeMediaOwnedFields() throws Exception {
        assertThat(WorkEntity.class.getDeclaredField("aiMediaSummaryJson").getAnnotation(TableField.class).updateStrategy())
                .isEqualTo(FieldStrategy.NEVER);
        assertThat(WorkEntity.class.getDeclaredField("aiMediaSummaryExpiresAt").getAnnotation(TableField.class).updateStrategy())
                .isEqualTo(FieldStrategy.NEVER);
        assertThat(AiGenerationTaskEntity.class.getDeclaredField("mediaBudgetJson").getAnnotation(TableField.class).updateStrategy())
                .isEqualTo(FieldStrategy.NEVER);
        assertThat(AiGenerationTaskEntity.class.getDeclaredField("mediaBudgetUnknownCount").getAnnotation(TableField.class).updateStrategy())
                .isEqualTo(FieldStrategy.NEVER);
        WorkEntity work = new WorkEntity(); work.setAiMediaSummaryJson("隐藏媒体理解摘要");
        assertThat(work.toString()).doesNotContain("隐藏媒体理解摘要", "aiMediaSummaryJson");
    }

    /** 全量 DDL 可沿用 dump 的关键字大小写，但不能忽略字面量或标识符的差异。 */
    @Test void normalizesOnlySqlKeywordCase() {
        String upper = "CREATE TABLE `WorkId` (`status` VARCHAR(32) DEFAULT 'NULL' COMMENT 'IS NULL')";
        String lower = "create table `WorkId` (`status` varchar(32) default 'NULL' comment 'IS NULL')";
        assertThat(normalizeSqlKeywords(upper)).isEqualTo(normalizeSqlKeywords(lower));
        assertThat(normalizeSqlKeywords(upper)).isNotEqualTo(normalizeSqlKeywords(lower.replace("'NULL'", "'null'")));
        assertThat(normalizeSqlKeywords(upper)).isNotEqualTo(normalizeSqlKeywords(lower.replace("`WorkId`", "`workid`")));
    }

    /** 比较 ALTER 新增列与全量定义，类型、默认值、注释逐字保留，仅归一 SQL 关键字。 */
    private void assertAddedColumnMatches(String schemaTable, String migration, String name) {
        Pattern addedColumn = Pattern.compile("(?m)^\\s*ADD COLUMN\\s+(`" + Pattern.quote(name)
                + "`.*?)(?:\\s+AFTER\\s+`[^`]+`)?[,;]?$");
        Matcher matcher = addedColumn.matcher(migration);
        assertThat(matcher.find()).as("迁移应增加字段 %s", name).isTrue();
        String schemaDefinition = schemaTable.lines().map(String::strip)
                .filter(line -> line.startsWith("`" + name + "` ")).findFirst().orElseThrow();
        if (schemaDefinition.endsWith(",")) {
            schemaDefinition = schemaDefinition.substring(0, schemaDefinition.length() - 1);
        }
        assertThat(normalizeSqlKeywords(schemaDefinition)).as("字段 %s 的全量定义应与增量迁移一致", name)
                .isEqualTo(normalizeSqlKeywords(matcher.group(1)));
    }

    /** 仅折叠已知 SQL 关键字，不改写引号中的字节或其它名称。 */
    private String normalizeSqlKeywords(String sql) {
        Matcher matcher = SQL_TOKENS.matcher(sql);
        StringBuilder normalized = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group();
            String lower = token.toLowerCase(Locale.ROOT);
            matcher.appendReplacement(normalized, Matcher.quoteReplacement(SQL_KEYWORDS.contains(lower) ? lower : token));
        }
        matcher.appendTail(normalized);
        return normalized.toString();
    }

    /** 截取单张表，避免其它表同名字段造成假通过。 */
    private String table(String schema, String name) {
        String suffix = schema.substring(schema.indexOf("CREATE TABLE `" + name + "` ("));
        return suffix.substring(0, suffix.indexOf(';') + 1);
    }
}
