package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 访客高意向行为积分规则迁移测试。 */
class VisitorIntentPointRuleMigrationTest {

    /** V51 必须扩展窗口约束并插入两条 10 分规则。 */
    @Test
    void migrationAddsScheduleAndContactLeadVisitorRules() throws IOException {
        Path migration = Path.of("src/main/resources/db/migration/"
                + "V51__add_schedule_query_and_contact_lead_point_rules.sql");

        assertThat(migration).exists();
        String sql = Files.readString(migration).replaceAll("\\s+", " ");
        assertThat(sql)
                .contains("DROP CHECK `chk_point_billing_window_scene`")
                .contains("'VISIT_PERSONAL_PORTFOLIO'")
                .contains("'VIEW_PORTFOLIO_IMAGES'")
                .contains("'VIEW_PORTFOLIO_VIDEO'")
                .contains("'QUERY_PORTFOLIO_SCHEDULE'")
                .contains("'SUBMIT_CONTACT_LEAD'")
                .contains("'QUERY_PORTFOLIO_SCHEDULE', 1, '访客查询档期'")
                .contains("'SUBMIT_CONTACT_LEAD', 1, '访客预留联系信息'")
                .contains("'CONSUMPTION', 'QUERY_PORTFOLIO_SCHEDULE', 'VISITOR'")
                .contains("'CONSUMPTION', 'SUBMIT_CONTACT_LEAD', 'VISITOR'")
                .contains("'FIXED_PER_ACTION', 1, 10")
                .contains("'source', 'POINT_RULE_UPDATE_2026_08_13'")
                .contains("'dedupeWindowHours', 2")
                .contains("'dedupeScope', 'PORTFOLIO'")
                .doesNotContain("DELETE FROM")
                .doesNotContain("UPDATE `wf_point_transaction`");
        assertThat(countOccurrences(
                sql, "'QUERY_PORTFOLIO_SCHEDULE', 1, '访客查询档期'"))
                .isEqualTo(1);
        assertThat(countOccurrences(
                sql, "'SUBMIT_CONTACT_LEAD', 1, '访客预留联系信息'"))
                .isEqualTo(1);
        assertThat(countOccurrences(sql, "'FIXED_PER_ACTION', 1, 10"))
                .isEqualTo(2);
    }

    /** 统计固定 SQL 片段出现次数。 */
    private int countOccurrences(String source, String target) {
        return (source.length() - source.replace(target, "").length())
                / target.length();
    }
}
