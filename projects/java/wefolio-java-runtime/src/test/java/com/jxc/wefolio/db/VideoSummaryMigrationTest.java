package com.jxc.wefolio.db;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 原视频摘要只新增一列，默认空值且数据库禁止非视频使用。 */
class VideoSummaryMigrationTest {
    /** 只读取本次增量，已提交的迁移保持不可变。 */
    private static final Path MIGRATION = Path.of("src/main/resources/db/migration/V65__add_work_video_summary.sql");

    /** 增量与完整建表精确一致，不加入状态列、有效期或清理操作。 */
    @Test void nullableSingleColumnMatchesFullSchemaWithoutDestructiveOperations() throws Exception {
        String sql = Files.readString(MIGRATION);
        String full = Files.readString(Path.of("full-schema.sql"));
        String work = full.substring(full.indexOf("CREATE TABLE `wf_work` ("));
        work = work.substring(0, work.indexOf(") ENGINE="));
        var column = Pattern.compile("ADD COLUMN\\s+(.+?)\\s+AFTER", Pattern.DOTALL).matcher(sql);
        assertThat(column.find()).isTrue();
        String definition = work.lines().map(String::strip).filter(line -> line.startsWith("`video_summary_json` "))
                .findFirst().orElseThrow();
        assertThat(normalize(definition.substring(0, definition.length() - 1))).isEqualTo(normalize(column.group(1)));
        assertThat(sql.split("ADD COLUMN", -1)).hasSize(2);
        assertThat(sql).contains("CHECK (`video_summary_json` IS NULL OR `media_type` = 'VIDEO')");
        assertThat(work).contains("CONSTRAINT `chk_work_video_summary_media` CHECK ((`video_summary_json` is null or `media_type` = _ascii'VIDEO'))");
        assertThat(sql).doesNotContainPattern("(?im)^\\s*(CREATE|DELETE|UPDATE|DROP)\\s+");
    }

    /** 旧视频和非视频默认 NULL，数据库仅允许视频保存非空 JSON。 */
    @Test void defaultsNullAndRejectsSummaryOnNonVideo() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:summary_column_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE wf_work (id BIGINT PRIMARY KEY, media_type VARCHAR(16), video_audio_text CLOB)");
        jdbc.update("INSERT INTO wf_work VALUES (1,'VIDEO',NULL),(2,'IMAGE',NULL)");
        String sql = Files.readString(MIGRATION);
        // H2 仅拆开 MySQL 的多个 ALTER 子句，类型、默认值与 CHECK 保持迁移原文。
        jdbc.execute("ALTER TABLE wf_work " + sql.substring(sql.indexOf("ADD COLUMN"), sql.indexOf(",\n    ADD CONSTRAINT")));
        jdbc.execute("ALTER TABLE wf_work " + sql.substring(sql.indexOf("ADD CONSTRAINT")));
        assertThat(jdbc.queryForObject("SELECT video_summary_json FROM wf_work WHERE id=1", String.class)).isNull();
        jdbc.update("UPDATE wf_work SET video_summary_json='{}' WHERE id=1");
        assertThat(jdbc.queryForObject("SELECT video_summary_json IS NOT NULL FROM wf_work WHERE id=1", Boolean.class)).isTrue();
        assertThatThrownBy(() -> jdbc.update("UPDATE wf_work SET video_summary_json='{}' WHERE id=2"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 统一大小写与空白后比较列定义，中文注释仍完整保留。 */
    private String normalize(String value) { return value.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT); }
}
