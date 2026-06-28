package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 团队成员变更请求迁移测试 — 确认待确认变更和消息动作具备数据库约束。
 */
class TeamMemberChangeRequestMigrationTest {

    @Test
    void migrationCreatesChangeRequestTableAndExtendsMessageActionCheck() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V14__add_team_member_change_request.sql"));

        assertThat(sql).contains("CREATE TABLE `wf_team_member_change_request`");
        assertThat(sql).contains("`member_version_before`");
        assertThat(sql).contains("`pending_marker` TINYINT");
        assertThat(sql).contains("UNIQUE KEY `uk_member_pending_change` (`member_id`, `pending_marker`, `deleted`)");
        assertThat(sql).contains("CONSTRAINT `chk_tmcr_status` CHECK");
        assertThat(sql).contains("'PENDING_CONFIRMATION'", "'ACCEPTED'", "'REJECTED'", "'INVALIDATED'");
        assertThat(sql).contains("DROP CHECK `chk_msg_action_type`");
        assertThat(sql).contains("'TEAM_MEMBER_CHANGE'");
    }
}
