package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 意见反馈迁移测试，固定反馈单与附件上传任务的表结构契约。
 */
class FeedbackMigrationTest {

    /** 意见反馈表迁移路径。 */
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V53__create_feedback_tables.sql");

    /**
     * 验证迁移只创建已批准的反馈主表和上传任务表。
     *
     * @throws IOException 读取迁移文件失败时抛出异常
     */
    @Test
    void migrationCreatesOnlyApprovedFeedbackTables() throws IOException {
        assertThat(MIGRATION).exists();

        String sql = Files.readString(MIGRATION);
        List<String> createdTables = Pattern.compile("CREATE\\s+TABLE\\s+`([^`]+)`", Pattern.CASE_INSENSITIVE)
                .matcher(sql)
                .results()
                .map(result -> result.group(1))
                .toList();

        assertThat(createdTables).containsExactly("wf_feedback", "wf_feedback_upload_task");
        assertThat(sql)
                .containsOnlyOnce("CREATE TABLE `wf_feedback`")
                .containsOnlyOnce("CREATE TABLE `wf_feedback_upload_task`")
                .doesNotContain("CREATE TABLE `wf_feedback_round`")
                .doesNotContain("CREATE TABLE `wf_feedback_attachment`");
    }

    /**
     * 验证反馈单表完整字段、状态术语、约束和索引。
     *
     * @throws IOException 读取迁移文件失败时抛出异常
     */
    @Test
    void feedbackTableDeclaresExactColumnsConstraintsAndIndexes() throws IOException {
        String tableSql = normalizedTableDefinition(Files.readString(MIGRATION), "wf_feedback");

        assertExactColumnDefinitions(tableSql, Map.ofEntries(
                Map.entry("id", "BIGINT UNSIGNED NOT NULL AUTO_INCREMENT"),
                Map.entry("feedback_no", "VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL"),
                Map.entry("user_id", "BIGINT UNSIGNED NOT NULL"),
                Map.entry("status", "VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL"),
                Map.entry("feedback_result", "VARCHAR(200) NULL"),
                Map.entry("feedback_result_at", "DATETIME(3) NULL"),
                Map.entry("rounds_json", "JSON NOT NULL"),
                Map.entry("round_count", "TINYINT UNSIGNED NOT NULL"),
                Map.entry("attachment_count", "TINYINT UNSIGNED NOT NULL"),
                Map.entry("create_idempotency_key",
                        "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL"),
                Map.entry("created_at", "DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)"),
                Map.entry("updated_at", "DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) "
                        + "ON UPDATE CURRENT_TIMESTAMP(3)"),
                Map.entry("deleted", "BIGINT UNSIGNED NOT NULL DEFAULT 0"),
                Map.entry("version", "INT UNSIGNED NOT NULL DEFAULT 0")
        ));
        assertThat(tableSql)
                .contains("`status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL "
                        + "COMMENT 'PROCESSING 处理中 WAITING_FOLLOW_UP 待再次反馈 RESOLVED 已处理'")
                .contains("PRIMARY KEY (`id`)")
                .contains("UNIQUE KEY `uk_feedback_no` (`feedback_no`, `deleted`)")
                .contains("UNIQUE KEY `uk_feedback_create_idempotency` "
                        + "(`user_id`, `create_idempotency_key`, `deleted`)")
                .contains("KEY `idx_feedback_user_status_updated` (`user_id`, `status`, `updated_at`)")
                .contains("KEY `idx_feedback_user_updated` (`user_id`, `updated_at`)")
                .contains("CONSTRAINT `chk_feedback_status` CHECK "
                        + "(`status` IN ('PROCESSING', 'WAITING_FOLLOW_UP', 'RESOLVED'))")
                .contains("CONSTRAINT `chk_feedback_round_count` CHECK (`round_count` BETWEEN 1 AND 3)")
                .contains("CONSTRAINT `chk_feedback_attachment_count` CHECK (`attachment_count` <= 9)");
    }

    /**
     * 验证反馈附件上传任务表完整字段、约束和索引。
     *
     * @throws IOException 读取迁移文件失败时抛出异常
     */
    @Test
    void feedbackUploadTaskTableDeclaresExactColumnsConstraintsAndIndexes() throws IOException {
        String tableSql = normalizedTableDefinition(Files.readString(MIGRATION), "wf_feedback_upload_task");

        assertExactColumnDefinitions(tableSql, Map.ofEntries(
                Map.entry("id", "BIGINT UNSIGNED NOT NULL AUTO_INCREMENT"),
                Map.entry("user_id", "BIGINT UNSIGNED NOT NULL"),
                Map.entry("client_id", "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL"),
                Map.entry("object_key", "VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL"),
                Map.entry("media_type", "VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL"),
                Map.entry("mime_type", "VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL"),
                Map.entry("file_size", "BIGINT UNSIGNED NOT NULL"),
                Map.entry("duration_ms", "BIGINT UNSIGNED NOT NULL DEFAULT 0"),
                Map.entry("status", "VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING'"),
                Map.entry("expires_at", "DATETIME(3) NOT NULL"),
                Map.entry("feedback_id", "BIGINT UNSIGNED NULL"),
                Map.entry("round_no", "TINYINT UNSIGNED NULL"),
                Map.entry("created_at", "DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)"),
                Map.entry("updated_at", "DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) "
                        + "ON UPDATE CURRENT_TIMESTAMP(3)"),
                Map.entry("deleted", "BIGINT UNSIGNED NOT NULL DEFAULT 0"),
                Map.entry("version", "INT UNSIGNED NOT NULL DEFAULT 0")
        ));
        assertThat(tableSql)
                .contains("PRIMARY KEY (`id`)")
                .contains("UNIQUE KEY `uk_feedback_upload_task_client` "
                        + "(`user_id`, `client_id`, `deleted`)")
                .contains("UNIQUE KEY `uk_feedback_upload_task_object` (`object_key`, `deleted`)")
                .contains("KEY `idx_feedback_upload_task_status_expires` (`status`, `expires_at`)")
                .contains("KEY `idx_feedback_upload_task_feedback` (`user_id`, `feedback_id`)")
                .contains("CONSTRAINT `chk_feedback_upload_task_media_type` CHECK "
                        + "(`media_type` IN ('IMAGE', 'VIDEO'))")
                .contains("CONSTRAINT `chk_feedback_upload_task_status` CHECK "
                        + "(`status` IN ('PENDING', 'CONFIRMED', 'EXPIRED'))")
                .contains("CONSTRAINT `chk_feedback_upload_task_round_no` CHECK "
                        + "(`round_no` IS NULL OR `round_no` BETWEEN 1 AND 3)");
    }

    /**
     * 验证迁移不包含外键、独立轮次表、独立附件表或数据破坏语句。
     *
     * @throws IOException 读取迁移文件失败时抛出异常
     */
    @Test
    void migrationAvoidsForeignKeysDestructiveStatementsAndRemovedStructures() throws IOException {
        String sql = Files.readString(MIGRATION);
        String upperSql = sql.toUpperCase(Locale.ROOT);

        assertThat(upperSql)
                .doesNotContain("FOREIGN KEY")
                .doesNotContain("REFERENCES")
                .doesNotContain("DELETE FROM")
                .doesNotContain("TRUNCATE")
                .doesNotContain("DROP TABLE");
        assertThat(Pattern.compile("(?im)^\\s*UPDATE\\s+").matcher(sql).find()).isFalse();
        assertThat(sql)
                .doesNotContain("wf_feedback_round")
                .doesNotContain("wf_feedback_attachment")
                .doesNotContain("latest_activity_at");
    }

    /**
     * 提取指定表的建表 SQL，并将连续空白规范为单个空格。
     *
     * @param sql 完整迁移 SQL
     * @param tableName 表名
     * @return 规范化后的建表 SQL
     */
    private String normalizedTableDefinition(String sql, String tableName) {
        String marker = "CREATE TABLE `" + tableName + "`";
        int start = sql.indexOf(marker);
        int end = sql.indexOf(") ENGINE=InnoDB", start);

        assertThat(start).as("迁移应创建表 %s", tableName).isGreaterThanOrEqualTo(0);
        assertThat(end).as("表 %s 应声明 InnoDB 引擎", tableName).isGreaterThan(start);

        return sql.substring(start, end)
                .replaceAll("\\s+", " ")
                .replaceAll("\\(\\s+", "(")
                .replaceAll("\\s+\\)", ")");
    }

    /**
     * 精确比较建表 SQL 中全部列的类型、字符集、空值和默认值定义。
     *
     * @param tableSql 已规范化空白的建表 SQL
     * @param expectedDefinitions 预期列定义
     */
    private void assertExactColumnDefinitions(String tableSql, Map<String, String> expectedDefinitions) {
        Pattern columnPattern = Pattern.compile(
                "(?:\\(|,)\\s*`([^`]+)`\\s+(.+?)\\s+COMMENT\\s+'[^']*'");
        Map<String, String> actualDefinitions = columnPattern.matcher(tableSql)
                .results()
                .collect(Collectors.toMap(result -> result.group(1), result -> result.group(2)));

        assertThat(actualDefinitions).containsExactlyInAnyOrderEntriesOf(expectedDefinitions);
    }
}
