package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 逻辑删除值迁移测试 — 确认所有基础表的 deleted 可承载主键并回填历史删除行。
 */
class LogicDeleteValueMigrationTest {

    private static final String MIGRATION_PATH =
            "src/main/resources/db/migration/V16__use_id_as_logic_delete_value.sql";

    private static final List<String> TABLE_NAMES = List.of(
            "wf_ai_generation_task",
            "wf_contact_lead",
            "wf_point_account",
            "wf_point_meter",
            "wf_point_rule",
            "wf_point_transaction",
            "wf_portfolio",
            "wf_portfolio_history",
            "wf_portfolio_reference",
            "wf_portfolio_share_record",
            "wf_recharge_order",
            "wf_recharge_package",
            "wf_referral_relation",
            "wf_schedule",
            "wf_slot_definition",
            "wf_system_message",
            "wf_tag",
            "wf_team",
            "wf_team_member",
            "wf_team_member_change_request",
            "wf_user",
            "wf_user_auth",
            "wf_visit_event",
            "wf_visit_record",
            "wf_work",
            "wf_work_tag",
            "wf_work_upload_task"
    );

    @Test
    void migrationChangesDeletedColumnToBigintAndBackfillsDeletedRows() throws IOException {
        String sql = Files.readString(Path.of(MIGRATION_PATH));

        TABLE_NAMES.forEach(tableName -> {
            assertThat(sql).contains("ALTER TABLE `" + tableName + "`");
            assertThat(sql).contains("UPDATE `" + tableName + "` SET `deleted` = `id` WHERE `deleted` <> 0;");
        });
        assertThat(sql).contains("MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0");
    }
}
