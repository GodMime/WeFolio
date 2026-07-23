package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 作品审核独立历史修复 SQL 结构测试。 */
class WorkAuditRepairSqlTest {

    private static final Path BEFORE_SCHEMA_SQL =
            Path.of("../../../docs/operations/sql/work_audit_repair_before_schema.sql");

    private static final Path AFTER_SCHEMA_SQL =
            Path.of("../../../docs/operations/sql/work_audit_repair_after_schema.sql");

    @Test
    void beforeSchemaSqlShouldBackupRepairReliableShaAndAbortOnUnresolvedRows() throws IOException {
        assertThat(BEFORE_SCHEMA_SQL).exists();

        String sql = Files.readString(BEFORE_SCHEMA_SQL);

        assertThat(sql)
                .contains("wf_ops_backup_work_audit_task_before_v45")
                .contains("START TRANSACTION")
                .contains("t.work_id = w.id")
                .contains("t.media_object_key = w.media_object_key")
                .contains("t.media_sha256 IS NULL")
                .contains("SIGNAL SQLSTATE '45000'")
                .contains("ROLLBACK")
                .contains("COMMIT");
    }

    @Test
    void afterSchemaSqlShouldRepairRoundsResultsStatusesAndRiskTypesIdempotently() throws IOException {
        assertThat(AFTER_SCHEMA_SQL).exists();

        String sql = Files.readString(AFTER_SCHEMA_SQL);

        assertThat(sql)
                .contains("wf_ops_backup_work_audit_task_after_v45")
                .contains("wf_ops_backup_work_after_v45")
                .contains("CREATE TEMPORARY TABLE tmp_work_audit_task_round AS")
                .contains("ROW_NUMBER() OVER (PARTITION BY work_id ORDER BY id)")
                .contains("COUNT(*)")
                .contains("WHEN w.audit_status = 'PENDING' AND")
                .contains("WHEN t.ci_result = 1 THEN 'BLOCK'")
                .contains("WHEN t.ci_result = 2 THEN 'REVIEW'")
                .contains("'PORN_CONTENT'", "'ADVERTISING_CONTENT'", "'LOW_QUALITY_CONTENT'")
                .contains("'POLITICAL_CONTENT'", "'TERRORISM_CONTENT'")
                .contains("'OTHER_UNSAFE_CONTENT'", "'AUDIT_SERVICE_ERROR'")
                .contains("w.audit_reason_codes = CASE")
                .contains("JSON_ARRAY('AUDIT_SERVICE_ERROR')")
                .contains("JSON_ARRAY(")
                .contains("w.audit_reason_codes = b.audit_reason_codes")
                .contains("START TRANSACTION")
                .contains("ROLLBACK")
                .contains("COMMIT");
    }

    @Test
    void flywayMigrationShouldNotReferenceIndependentRepairSql() throws IOException {
        String migration = Files.readString(
                Path.of("src/main/resources/db/migration/V45__add_work_audit_round.sql"));

        assertThat(migration)
                .doesNotContain("work_audit_repair_before_schema")
                .doesNotContain("work_audit_repair_after_schema")
                .doesNotContain("wf_ops_backup_");
    }
}
