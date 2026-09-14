package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

/** 活动迁移结构契约；不以文本测试代替真实 MySQL 迁移。 */
class VisitActivityMigrationTest {
    /** 历史毫秒保持 NULL，并具备双键和设备查询所需索引及公共列。 */
    @Test
    void preservesHistoricalUnknownAndIndexes() throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/migration/V57__add_visit_activity_sessions.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains("foreground_duration_ms BIGINT NULL DEFAULT NULL")
                    .contains("visitor_id, portfolio_type, portfolio_id, client_session_key, deleted")
                    .contains("visit_record_id, deleted, created_at, id")
                    .contains("last_reported_at DATETIME(3) NULL DEFAULT NULL")
                    .doesNotContain("activity_session_count", "UPDATE wf_visit_record", "DELETE FROM");
        }
    }
}
