package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** V49 访问汇总去重与逻辑删除唯一索引修复测试。 */
class LogicalDeleteUniqueIndexRepairMigrationTest {

    /** V49 migration 路径。 */
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V49__repair_logical_delete_unique_indexes.sql");

    @Test
    void migrationShouldMergeVisitsAndRestoreAllSelectedUniqueIndexes() throws IOException {
        assertThat(MIGRATION).exists();

        String sql = Files.readString(MIGRATION).replaceAll("\\s+", " ").trim();

        assertThat(sql).containsIgnoringCase("CREATE TEMPORARY TABLE `tmp_visit_record_merge`");
        assertThat(sql).containsIgnoringCase("SET `event`.`visit_record_id` = `merged`.`keeper_id`");
        assertThat(sql).containsIgnoringCase(
                "ADD UNIQUE KEY `uk_visit_visitor_portfolio` (`visitor_id`, `portfolio_id`, `deleted`)");
        assertThat(sql).containsIgnoringCase(
                "ADD UNIQUE KEY `uk_auth_type_identifier` (`auth_type`, `identifier_hash`, `deleted`)");
        assertThat(sql).containsIgnoringCase(
                "ADD UNIQUE KEY `uk_auth_union_identifier` (`union_identifier_hash`, `deleted`)");
        assertThat(sql).containsIgnoringCase(
                "ADD UNIQUE KEY `uk_team_member` (`team_id`, `user_id`, `deleted`)");
        assertThat(sql).containsIgnoringCase(
                "ADD UNIQUE KEY `uk_team_active_owner` (`team_id`, `active_owner_marker`, `deleted`)");
    }
}
